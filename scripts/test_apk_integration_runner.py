import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

RUNNER = Path(__file__).resolve().parents[1] / '.github/scripts/run-apk-integration-tests.sh'


class AndroidSuiteFailureTests(unittest.TestCase):
    def simulate(self, failed_suite):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ['app/build/outputs/apk/release/app-x86_64-release.apk',
                         'app/build/outputs/apk/androidTest/release/tests.apk',
                         'fixture/build/outputs/apk/debug/fixture.apk']:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('inert fixture, not an APK')
            (root / 'gradlew').write_text('#!' + sys.executable + '\n' + '''
import os, pathlib, sys
args=' '.join(sys.argv[1:])
with open('calls.txt','a') as log: log.write(args+'\\n')
variant='debug' if 'connectedDebugAndroidTest' in args else 'release'
folder=pathlib.Path('app/build/outputs/androidTest-results/connected')/variant
folder.mkdir(parents=True,exist_ok=True)
(folder/'receipt.txt').write_text(args)
sys.exit(17 if os.environ['FAILED_SUITE'] in args else 0)
''')
            (root / 'gradlew').chmod(0o755)
            tools = root / 'tools'
            tools.mkdir()
            for name in ['adb', 'python3']:
                path = tools / name
                path.write_text('#!/bin/sh\nexit 0\n')
                path.chmod(0o755)
            result = subprocess.run(['bash', str(RUNNER)], cwd=root, env={**os.environ, 'FAILED_SUITE': failed_suite, 'PATH': str(tools) + os.pathsep + os.environ['PATH']}, capture_output=True, text=True)
            receipts = [str(p.relative_to(root)) for p in (root / 'emulator-diagnostics').rglob('receipt.txt')]
            return result, (root / 'calls.txt').read_text(), receipts

    def test_provider_failure_runs_codex_then_fails_with_both_reports(self):
        result, calls, receipts = self.simulate('ProviderProfilesDeviceTest')
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('CodexAuthDeviceTest', calls)
        self.assertNotIn('ChatGptMcpDeviceTest', calls)
        self.assertIn('emulator-diagnostics/provider-results/release/receipt.txt', receipts)
        self.assertIn('emulator-diagnostics/codex-provider-results/release/receipt.txt', receipts)

    def test_mcp_failure_retains_report_and_stops_later_suites(self):
        result, calls, receipts = self.simulate('ChatGptMcpDeviceTest')
        self.assertEqual(result.returncode, 17, result.stdout + result.stderr)
        self.assertIn('emulator-diagnostics/chatgpt-mcp-results/debug/receipt.txt', receipts)
        self.assertNotIn('PhoneTargetDeviceTest', calls)

    def test_success_runs_through_development_bridge(self):
        result, calls, _ = self.simulate('no-failure-fixture')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('DevBridgeDeviceTest', calls)


if __name__ == '__main__':
    unittest.main()
