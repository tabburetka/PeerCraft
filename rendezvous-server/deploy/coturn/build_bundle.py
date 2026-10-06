#!/usr/bin/env python3
"""Package the prepared server and public instructions; never include generated secrets."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import zipfile


def build(jar, output):
    jar = Path(jar)
    with zipfile.ZipFile(jar) as archive:
        for name in ("CoturnTurnProvider", "TurnProviders", "RelayBroker"):
            if f"net/peercraft/rendezvous/relay/{name}.class" not in archive.namelist():
                raise ValueError("JAR не содержит подготовленную реализацию coturn")
    base = Path(__file__).resolve().parent
    files = {
        "rendezvous-server.jar": jar,
        "prepare.py": base / "prepare.py",
        "README.ru.md": base / "README.ru.md",
        "coturn-fallback-plan.md": base.parents[2] / "docs" / "coturn-fallback-plan.md",
    }
    contents = {name: path.read_bytes() for name, path in files.items()}
    contents["README.ru.md"] = contents["README.ru.md"].replace(
        b"../../../docs/coturn-fallback-plan.md", b"coturn-fallback-plan.md")
    manifest = {
        "format": 1,
        "relayEnabled": False,
        "installationPerformed": False,
        "files": {name: {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
                  for name, data in contents.items()},
    }
    contents["manifest.json"] = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode()
    descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream, tarfile.open(fileobj=stream, mode="w:gz") as archive:
            for name, data in contents.items():
                entry = tarfile.TarInfo("peercraft-coturn/" + name)
                entry.size = len(data)
                entry.mode = 0o644
                archive.addfile(entry, io.BytesIO(data))
    except BaseException:
        Path(output).unlink(missing_ok=True)
        raise
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        build(args.jar, args.output)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Пакет не создан: {error}\n")
    print(f"Пакет создан: {args.output}. Секреты не включены; установка не выполнялась.")


if __name__ == "__main__":
    main()
