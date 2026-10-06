import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("prepare", Path(__file__).with_name("prepare.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PrepareTest(unittest.TestCase):
    def test_private_matching_secrets_and_disabled_broker(self):
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / "generated"
            module.prepare("8.8.8.8", "192.168.68.110", "https://relay.example.org", output)
            secret = (output / "relay.env").read_text().strip().split("=", 1)[1]
            self.assertEqual(64, len(secret))
            self.assertIn("static-auth-secret=" + secret, (output / "turnserver.conf").read_text())
            self.assertIn("enabled=false", (output / "relay.properties").read_text())
            self.assertIn("allowed-peer-ip=8.8.8.8", (output / "turnserver.conf").read_text())
            self.assertEqual(0o700, output.stat().st_mode & 0o777)
            for file in output.iterdir():
                self.assertEqual(0o600, file.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                module.prepare("8.8.8.8", "192.168.68.110", "https://relay.example.org", output)

    def test_invalid_addresses_never_write_files(self):
        with tempfile.TemporaryDirectory() as root:
            for ip, url in [("192.168.1.1", "https://relay.example.org"),
                            ("8.8.8.8", "http://relay.example.org"),
                            ("8.8.8.8", "https://user@relay.example.org"),
                            ("8.8.8.8", "https://relay.example.org\nenabled=true")]:
                output = Path(root) / "generated"
                with self.assertRaises(ValueError):
                    module.prepare(ip, "192.168.68.110", url, output)
                self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
