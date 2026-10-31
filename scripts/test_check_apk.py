import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


class DistributionPayloadTests(unittest.TestCase):
    def inspect(self, extra, manifest_bytes=7):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('assets/runtime.cjs', b'fixture')
                archive.writestr('assets/runtime-manifest.json', json.dumps({'bytes': manifest_bytes}))
                for name in extra:
                    archive.writestr(name, b'fixture')
            return subprocess.run([sys.executable, str(Path(__file__).with_name('check_apk.py')), str(path)], capture_output=True, text=True)

    def test_production_arm64_tunnel_is_an_allowed_payload(self):
        result = self.inspect(['lib/arm64-v8a/libngrok_java.so'])
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report['nativeUncompressedBytes']['lib/arm64-v8a/libngrok_java.so'], 7)
        self.assertTrue(report['packagingOnly'])

    def test_tunnel_exception_does_not_allow_unexpected_sdk_files(self):
        for name in ['assets/ngrok-token', 'lib/x86_64/libngrok_java.so', 'assets/ngrok/config.yml']:
            with self.subTest(name=name):
                result = self.inspect([name])
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Unexpected packaged files', result.stderr)

    def test_credentials_remain_forbidden_with_production_tunnel(self):
        for name in ['assets/auth.json', 'assets/signing.jks', 'assets/relay.token', 'assets/node_modules/index.js']:
            with self.subTest(name=name):
                self.assertNotEqual(self.inspect(['lib/arm64-v8a/libngrok_java.so', name]).returncode, 0)

    def test_runtime_manifest_must_match_packaged_bundle(self):
        result = self.inspect([], manifest_bytes=99)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Bundle bytes disagree', result.stderr)


if __name__ == '__main__':
    unittest.main()
