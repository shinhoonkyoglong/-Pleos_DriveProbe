"""Offline transport/cleanup regressions; no emulator, vehicle, or SDK writes.

Run: python -m unittest discover -s scripts/tests -p test_driving_tools.py -v
"""
import json
import os
from pathlib import Path
import subprocess
import threading
import tempfile
import sys
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit


SCRIPTS = Path(__file__).resolve().parents[1]
POWERSHELL = os.environ.get("DRIVING_TEST_POWERSHELL", "powershell.exe")


class Console:
    def __init__(self, failure=None):
        self.requests = []
        self.gear = "Park"
        self.speed = 0
        self.steering = 0
        self.failure = failure
        self.failed = False
        console = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                self.handle_request()

            def do_GET(self):
                body = json.dumps({'gear': {'actual': {'value': console.gear}},
                                   'controls': {'speed_kph': console.speed,
                                                'steering_deg': console.steering}}).encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_PUT(self):
                self.handle_request()

            def handle_request(self):
                parts = urlsplit(self.path)
                query = {key: values[0] for key, values in parse_qs(parts.query).items()}
                console.requests.append((self.command, parts.path, query))
                error = None
                status = 200
                if console.failure == "setup" and parts.path == "/vehicle/turn-signal":
                    error = "injected setup rejection"
                elif (console.failure == "steering" and console.gear == "Drive"
                      and "steering" in query and not console.failed):
                    status, error = 500, "injected drive failure"
                elif (console.failure == "cleanup" and console.gear == "Drive"
                      and query.get("speed") == "0"):
                    status, error = 503, "injected stop failure"
                if error:
                    console.failed = True
                else:
                    console.gear = query.get("gear", console.gear)
                    console.speed = int(query.get("speed", console.speed))
                    console.steering = int(query.get("steering", console.steering))
                body = json.dumps({"error": error} if error else {"ok": True}).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)

    def run(self, normal="0.02", drowsy="0", severe="0"):
        return subprocess.run(
            [POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
             str(SCRIPTS / "drive-sim.ps1"), "-Normal", normal, "-Drowsy", drowsy,
             "-Severe", severe, "-ConsoleUri", f"http://127.0.0.1:{self.server.server_port}/"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20,
        )


class DrivingToolsTest(unittest.TestCase):
    def test_check_only_uses_current_user_and_releases_its_port(self):
        with Console() as console, tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            port = str(console.server.server_port)
            (root / 'emulator-5554').write_text(
                "import sys\nfrom pathlib import Path\na=sys.argv[1:]\n"
                "if a == ['get-state']: print('device')\n"
                "elif a == ['shell','am','get-current-user']: print('10')\n"
                "elif a[:3] == ['shell','pm','path']: print('package:/debug.apk')\n"
                "elif a[:2] == ['shell','run-as']: print('uid=1010175')\n"
                f"elif a == ['forward','tcp:0','tcp:8088']: print({port!r})\n"
                f"elif a == ['forward','--remove','tcp:{port}']: Path('port-released').touch()\n"
                "else: raise SystemExit(4)\n", encoding='utf-8',
            )
            result = subprocess.run(
                [POWERSHELL, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                 str(SCRIPTS / 'Test-Driving.ps1'), '-CheckOnly', '-AdbPath', sys.executable],
                cwd=directory, capture_output=True, timeout=12,
            )
            self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))
            self.assertTrue((root / 'port-released').exists())
            self.assertEqual(console.requests, [])  # GET-only; no driving inputs.

    def test_unresponsive_adb_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            # Python accepts '-s emulator-5554 get-state' as a local script
            # invocation, giving this test a real hung child without an emulator.
            root = Path(directory)
            (root / 'emulator-5554').write_text(
                "from pathlib import Path\nimport time\nPath('adb-started').touch()\ntime.sleep(30)\n",
                encoding='utf-8',
            )
            result = subprocess.run(
                [POWERSHELL, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                 str(SCRIPTS / 'Test-Driving.ps1'), '-CheckOnly', '-AdbPath', sys.executable,
                 '-AdbTimeoutSeconds', '1'], cwd=directory, capture_output=True, timeout=8,
            )
            self.assertTrue((root / 'adb-started').exists())
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(b'adb timed out', result.stdout + result.stderr)

    def test_zero_warmup_plan_still_needs_no_device(self):
        result = subprocess.run(
            [POWERSHELL, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
             str(SCRIPTS / 'Test-Driving.ps1'), '-Scenario', 'Fatigue', '-NormalMinutes', '0',
             '-RequireInterventions', '-DryRun', '-AdbPath', 'missing-adb.exe'],
            capture_output=True, timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))

    def test_dry_run_needs_no_device_and_creates_no_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'no artifacts'
            result = subprocess.run(
                [POWERSHELL, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                 str(SCRIPTS / 'Test-Driving.ps1'), '-DryRun', '-AdbPath', 'missing-adb.exe',
                 '-OutputDirectory', str(target)], capture_output=True, timeout=10,
            )
            self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))
            self.assertFalse(target.exists())

    def test_preflight_failure_writes_failed_json_and_readable_markdown(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [POWERSHELL, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                 str(SCRIPTS / 'Test-Driving.ps1'), '-AdbPath', 'missing-adb.exe',
                 '-OutputDirectory', directory], capture_output=True, timeout=10,
            )
            self.assertNotEqual(result.returncode, 0)
            folder, = Path(directory).iterdir()
            report = json.loads((folder / 'result.json').read_text(encoding='utf-8'))
            self.assertFalse(report['passed'])
            self.assertIsNone(report['tripId'])
            markdown = (folder / 'result.md').read_text(encoding='utf-8')
            self.assertIn('\n\nResult: FAIL\n\n| Check |', markdown)

    def assert_stop_attempted(self, console):
        paths = [(path, query) for _, path, query in console.requests]
        self.assertEqual(paths[-1], ("/vehicle/gear", {"gear": "Park"}))
        self.assertIn(("/vehicle/controls", {"speed": "0"}), paths)
        self.assertIn(("/vehicle/controls", {"steering": "0"}), paths)

    def test_normal_and_both_drowsy_generators_stop_cleanly(self):
        with Console() as console:
            result = console.run(drowsy="0.02", severe="0.02")
            self.assertEqual(result.returncode, 0, result.stdout.decode(errors="replace"))
            self.assert_stop_attempted(console)

    def test_successful_normal_run(self):
        with Console() as console:
            result = console.run()
            self.assertEqual(result.returncode, 0, result.stdout.decode(errors="replace"))
            self.assertEqual((console.gear, console.speed, console.steering), ("Park", 0, 0))
            self.assert_stop_attempted(console)

    def test_http_failure_aborts_and_still_parks(self):
        with Console("steering") as console:
            result = console.run()
            self.assertNotEqual(result.returncode, 0)
            self.assertTrue(console.failed)
            self.assert_stop_attempted(console)
            self.assertEqual(console.speed, 0)

    def test_json_error_during_setup_prevents_drive_and_still_parks(self):
        with Console("setup") as console:
            result = console.run()
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn(("PUT", "/vehicle/gear", {"gear": "Drive"}), console.requests)
            self.assert_stop_attempted(console)

    def test_failed_speed_cleanup_does_not_skip_park_or_report_success(self):
        with Console("cleanup") as console:
            result = console.run()
            self.assertNotEqual(result.returncode, 0)
            self.assertTrue(console.failed)
            self.assert_stop_attempted(console)


if __name__ == "__main__":
    unittest.main()
