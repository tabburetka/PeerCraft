import hashlib
import importlib.util
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("bundle", Path(__file__).with_name("build_bundle.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BundleTest(unittest.TestCase):
    def test_old_jar_rejected_before_creating_output(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "old.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("placeholder", b"old")
            output = Path(directory) / "bundle.tar.gz"
            with self.assertRaises(ValueError):
                module.build(jar, output)
            self.assertFalse(output.exists())

    def test_whitelist_and_manifest_match_all_archive_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "server.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                for name in ("CoturnTurnProvider", "TurnProviders", "RelayBroker"):
                    archive.writestr(f"net/peercraft/rendezvous/relay/{name}.class", b"fixture")
            output = Path(directory) / "bundle.tar.gz"
            module.build(jar, output)
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
            with tarfile.open(output) as archive:
                manifest = json.load(archive.extractfile("peercraft-coturn/manifest.json"))
                self.assertFalse(manifest["relayEnabled"])
                self.assertFalse(manifest["installationPerformed"])
                self.assertEqual({"rendezvous-server.jar", "prepare.py", "README.ru.md", "coturn-fallback-plan.md"}, set(manifest["files"]))
                self.assertEqual(5, len(archive.getnames()))
                for name, details in manifest["files"].items():
                    data = archive.extractfile("peercraft-coturn/" + name).read()
                    self.assertEqual(hashlib.sha256(data).hexdigest(), details["sha256"])
                    self.assertEqual(len(data), details["bytes"])
            with self.assertRaises(FileExistsError):
                module.build(jar, output)


if __name__ == "__main__":
    unittest.main()
