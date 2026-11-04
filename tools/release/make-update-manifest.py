#!/usr/bin/env python3
"""Builds and signs update.json for PeerCraft's in-game update notice and self-update.

    make-update-manifest.py keygen --key ~/.config/peercraft/release-key.pem
    make-update-manifest.py build releases/3.5.0 --key ~/.config/peercraft/release-key.pem
    make-update-manifest.py verify --pub <base64>

build reads releases/<dir>/manifest.json and curseforge-uploads.json, checks the local jars,
derives CurseForge CDN links, confirms every link is live (files are only on the CDN once
moderation approved them), then rewrites releases/update.json and releases/update.json.sig.
The signature is ECDSA P-256 / SHA-256 in DER, base64, over the exact bytes of update.json; Java 8
verifies it with Signature.getInstance("SHA256withECDSA").
"""
import argparse
import base64
import hashlib
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parents[2]
PAGE = "https://www.curseforge.com/minecraft/mc-mods/peercraft/files/all"
CDN = "https://mediafilez.forgecdn.net/files/{hi}/{lo}/{name}"
NAME = re.compile(r"^peercraft-(fabric|neoforge|forge)-([0-9][0-9.]*)-([0-9]+\.[0-9]+\.[0-9]+(?:-(?:beta|rc)(?:\.[0-9]+)?)?)\.jar$")
VERSION = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-(beta|rc)(?:\.(\d+))?)?$")


def version_key(text):
    m = VERSION.match(text)
    if not m:
        raise SystemExit(f"bad version {text!r}")
    stage = {"beta": 1, "rc": 2, None: 3}[m.group(4)]
    return int(m.group(1)), int(m.group(2)), int(m.group(3)), stage, int(m.group(5) or 0)


def cdn_url(file_id, name):
    return CDN.format(hi=file_id // 1000, lo=file_id % 1000, name=name)


def public_b64(private_key):
    der = private_key.public_key().public_bytes(serialization.Encoding.DER,
                                                serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(der).decode()


def load_key(path):
    data = Path(os.path.expanduser(path)).read_bytes()
    return serialization.load_pem_private_key(data, password=None)


def head_ok(url, size):
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "PeerCraft release tool"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            length = response.headers.get("Content-Length")
            return response.status == 200 and (length is None or int(length) == size)
    except Exception:
        return False


def covered_versions(loader, minecraft):
    """The Minecraft versions one Stonecutter node's jar is released for (its minecraft_versions)."""
    props = ROOT / "mod" / "versions" / f"{minecraft}-{loader}" / "gradle.properties"
    if props.is_file():
        for line in props.read_text().splitlines():
            if line.startswith("minecraft_versions="):
                return [v.strip() for v in line.split("=", 1)[1].split(",") if v.strip()]
    return [minecraft]


def cmd_keygen(args):
    path = Path(os.path.expanduser(args.key))
    if path.exists():
        raise SystemExit(f"{path} already exists; refusing to overwrite a release key")
    path.parent.mkdir(parents=True, exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                            serialization.NoEncryption())
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as out:
        out.write(pem)
    print(f"private key: {path}  (back it up; losing it means clients reject future updates)")
    print("public key for UpdateSignature.PUBLIC_KEYS:")
    print(public_b64(key))


def cmd_build(args):
    release = (ROOT / args.release).resolve() if not Path(args.release).is_absolute() else Path(args.release)
    manifest = json.loads((release / "manifest.json").read_text())
    uploads = {u["file"]: u for u in json.loads((release / "curseforge-uploads.json").read_text())}

    files, versions, channels = {}, set(), set()
    for item in manifest:
        name = item["file"]
        m = NAME.match(name)
        if not m:
            raise SystemExit(f"unexpected jar name {name}")
        loader, minecraft, version = m.groups()
        upload = uploads.get(name)
        if upload is None:
            raise SystemExit(f"{name} is missing from curseforge-uploads.json")
        jar = release / name
        data = jar.read_bytes()
        if hashlib.sha256(data).hexdigest() != item["sha256"] or upload["sha256"] != item["sha256"]:
            raise SystemExit(f"sha256 mismatch for {name}")
        if not upload.get("id"):
            # That build keeps the plain notice; players can still update from the page.
            print(f"warning: {name} has no CurseForge file id, no self-update for it", file=sys.stderr)
            versions.add(version)
            continue
        url = cdn_url(int(upload["id"]), name)
        if not args.skip_cdn_check and not head_ok(url, len(data)):
            raise SystemExit(f"{url} is not live yet (still in CurseForge moderation?)")
        entry = {
            "file": name, "cf": int(upload["id"]), "url": url,
            "sha512": hashlib.sha512(data).hexdigest(), "size": len(data),
        }
        # One jar can serve a run of Minecraft versions; clients look it up by their own.
        for covered in covered_versions(loader, minecraft):
            files[f"{loader}-{covered}"] = entry
        versions.add(version)
        channels.add(item.get("metadata", {}).get("releaseType", "release"))
    if len(versions) != 1:
        raise SystemExit(f"release directory mixes versions: {sorted(versions)}")
    version = versions.pop()
    beta = version_key(version)[3] != 3 or channels & {"beta", "alpha"}

    out_path = ROOT / "releases" / "update.json"
    current = json.loads(out_path.read_text()) if out_path.exists() else {}
    doc = {
        "format": 2,
        "stable": current.get("stable", version),
        "beta": current.get("beta", version),
        "url": PAGE,
        "files": dict(current.get("files", {})),
    }
    doc["beta"] = version
    if not beta:
        doc["stable"] = version
    if version_key(doc["beta"]) < version_key(doc["stable"]):
        doc["beta"] = doc["stable"]
    doc["files"][version] = dict(sorted(files.items()))
    doc["files"] = {v: doc["files"][v] for v in {doc["stable"], doc["beta"]} if v in doc["files"]}

    body = (json.dumps(doc, indent=2, ensure_ascii=False) + "\n").encode()
    key = load_key(args.key)
    signature = key.sign(body, ec.ECDSA(hashes.SHA256()))
    if args.dry_run:
        sys.stdout.write(body.decode())
        print(f"signature: {base64.b64encode(signature).decode()}", file=sys.stderr)
        return
    out_path.write_bytes(body)
    (ROOT / "releases" / "update.json.sig").write_text(base64.b64encode(signature).decode() + "\n")
    print(f"update.json: stable={doc['stable']} beta={doc['beta']}, {len(files)} builds for {version}")
    print("commit and push releases/update.json and releases/update.json.sig to main")


def cmd_verify(args):
    from cryptography.exceptions import InvalidSignature
    public = serialization.load_der_public_key(base64.b64decode(args.pub))
    body = (ROOT / "releases" / "update.json").read_bytes()
    signature = base64.b64decode((ROOT / "releases" / "update.json.sig").read_text().strip())
    try:
        public.verify(signature, body, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature:
        raise SystemExit("signature INVALID")
    print("signature OK")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    keygen = sub.add_parser("keygen")
    keygen.add_argument("--key", required=True)
    build = sub.add_parser("build")
    build.add_argument("release", help="releases/<version> directory")
    build.add_argument("--key", required=True)
    build.add_argument("--dry-run", action="store_true", help="print instead of writing update.json")
    build.add_argument("--skip-cdn-check", action="store_true", help="for local tests only")
    verify = sub.add_parser("verify")
    verify.add_argument("--pub", required=True)
    args = parser.parse_args()
    {"keygen": cmd_keygen, "build": cmd_build, "verify": cmd_verify}[args.command](args)


if __name__ == "__main__":
    main()
