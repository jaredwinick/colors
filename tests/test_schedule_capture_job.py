from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "android" / "schedule_capture_job.sh"
TIMING_SCRIPT = ROOT / "android" / "schedule_timing.py"


class ScheduleCaptureJobTests(unittest.TestCase):
    def setUp(self) -> None:
        if os.name == "nt":
            git_bash = (
                Path(os.environ.get("ProgramFiles", r"C:\Program Files"))
                / "Git"
                / "bin"
                / "bash.exe"
            )
            self.bash = str(git_bash) if git_bash.is_file() else None
        else:
            self.bash = shutil.which("bash")
        if self.bash is None:
            self.skipTest("bash is required for scheduler controller tests")

        def shell_path(path: Path) -> str:
            resolved = path.resolve()
            if os.name != "nt":
                return str(resolved)
            posix = resolved.as_posix()
            return f"/{posix[0].lower()}{posix[2:]}"

        self.shell_path = shell_path
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT)
        self.root = Path(self.temporary.name)
        self.bin_directory = self.root / "bin"
        self.bin_directory.mkdir()
        self.capture_script = self.root / "capture_and_upload.sh"
        self.capture_script.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        os.chmod(self.capture_script, 0o700)
        self.scheduler_log = self.root / "scheduler-arguments.txt"
        scheduler = self.bin_directory / "termux-job-scheduler"
        scheduler.write_text(
            """#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$SCHEDULER_ARGUMENTS_LOG"
if [[ "${1:-}" == "--pending" ]]; then
  printf 'Pending Job 1701: test (periodic: 900000ms) (persisted) (network: any)\\n'
elif [[ "${1:-}" == "--cancel" ]]; then
  printf 'Cancelling Job 1701: test\\n'
else
  printf 'Scheduling Job 1701: test - response 1\\n'
fi
""",
            encoding="utf-8",
        )
        os.chmod(scheduler, 0o700)
        if os.name == "nt":
            test_path = "bin:/usr/bin:/bin"
            home = "."
            config_directory = "config"
            data_directory = "data"
            capture_script = "capture_and_upload.sh"
            scheduler_log = "scheduler-arguments.txt"
        else:
            test_path_parts = [self.shell_path(self.bin_directory)]
            test_path_parts.append(os.environ.get("PATH", ""))
            test_path = os.pathsep.join(test_path_parts)
            home = self.shell_path(self.root)
            config_directory = self.shell_path(self.root / "config")
            data_directory = self.shell_path(self.root / "data")
            capture_script = self.shell_path(self.capture_script)
            scheduler_log = self.shell_path(self.scheduler_log)
        self.environment = {
            **os.environ,
            "HOME": home,
            "PATH": test_path,
            "COLORS_CONFIG_DIR": config_directory,
            "COLORS_DATA_DIR": data_directory,
            "COLORS_CAPTURE_JOB_SCRIPT": capture_script,
            "COLORS_SCHEDULE_TIMING_SCRIPT": self.shell_path(TIMING_SCRIPT),
            "COLORS_PYTHON": self.shell_path(Path(sys.executable)),
            "SCHEDULER_ARGUMENTS_LOG": scheduler_log,
        }

    def tearDown(self) -> None:
        if hasattr(self, "temporary"):
            self.temporary.cleanup()

    def run_script(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [self.bash, "--login", self.shell_path(SCRIPT), *arguments],
            check=False,
            capture_output=True,
            text=True,
            env=self.environment,
            cwd=self.root,
        )

    def test_install_registers_stable_job_and_constraints(self) -> None:
        result = self.run_script("install")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Colors job 1701 is registered", result.stdout)
        self.assertEqual(
            "900000\n",
            (self.root / "config" / "schedule-period-ms").read_text(
                encoding="utf-8"
            ),
        )
        registered_at = (
            self.root / "config" / "schedule-registered-at"
        ).read_text(encoding="utf-8").strip()
        parsed_registration = datetime.fromisoformat(
            registered_at.replace("Z", "+00:00")
        )
        self.assertIsNotNone(parsed_registration.tzinfo)
        arguments = self.scheduler_log.read_text(encoding="utf-8")
        self.assertIn("--job-id 1701", arguments)
        self.assertIn("--period-ms 900000", arguments)
        self.assertIn("--network any", arguments)
        self.assertIn("--battery-not-low false", arguments)
        self.assertIn("--storage-not-low true", arguments)
        self.assertIn("--charging false", arguments)
        self.assertIn("--persisted true", arguments)

    def test_rejects_period_below_android_minimum(self) -> None:
        result = self.run_script("install", "899999")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("between 900000", result.stderr)
        self.assertFalse(self.scheduler_log.exists())
        self.assertFalse(
            (self.root / "config" / "schedule-registered-at").exists()
        )

    def test_status_is_the_default_command(self) -> None:
        result = self.run_script()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Pending Job 1701", result.stdout)
        self.assertIn("Cycles recorded: 0", result.stdout)

    def test_cancel_targets_only_production_job(self) -> None:
        result = self.run_script("cancel")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Cancelled Colors job 1701", result.stdout)
        self.assertEqual(
            "--cancel --job-id 1701\n",
            self.scheduler_log.read_text(encoding="utf-8"),
        )


if __name__ == "__main__":
    unittest.main()
