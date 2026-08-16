from __future__ import annotations

import importlib.util
import unittest
from datetime import datetime, timezone
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "android" / "schedule_timing.py"
SPEC = importlib.util.spec_from_file_location("schedule_timing", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"could not load {MODULE_PATH}")
schedule_timing = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(schedule_timing)


class ScheduleTimingTests(unittest.TestCase):
    def test_parses_only_cycle_start_lines(self) -> None:
        log_text = """\
2026-08-16T20:00:00Z [colors +0s] Starting durable capture cycle.
2026-08-16T20:00:01Z [colors +1s] Capturing one new photograph.
not a Colors log line
2026-08-16T20:15:30Z [colors +0s] Starting durable capture cycle.
"""

        self.assertEqual(
            [
                datetime(2026, 8, 16, 20, 0, tzinfo=timezone.utc),
                datetime(2026, 8, 16, 20, 15, 30, tzinfo=timezone.utc),
            ],
            schedule_timing.parse_start_times(log_text),
        )

    def test_summarizes_interval_and_drift_statistics(self) -> None:
        starts = [
            datetime(2026, 8, 16, 20, 0, tzinfo=timezone.utc),
            datetime(2026, 8, 16, 20, 15, tzinfo=timezone.utc),
            datetime(2026, 8, 16, 20, 32, tzinfo=timezone.utc),
            datetime(2026, 8, 16, 20, 46, tzinfo=timezone.utc),
        ]

        report = schedule_timing.summarize(starts, 900000)

        self.assertEqual(4, report["cycle_count"])
        self.assertEqual(3, report["interval_count"])
        self.assertEqual(920.0, report["average_interval_seconds"])
        self.assertEqual(900.0, report["median_interval_seconds"])
        self.assertEqual(840.0, report["minimum_interval_seconds"])
        self.assertEqual(1020.0, report["maximum_interval_seconds"])
        self.assertEqual(20.0, report["average_drift_seconds"])
        self.assertEqual(120.0, report["maximum_absolute_drift_seconds"])

    def test_reports_insufficient_sample_without_failing(self) -> None:
        starts = [datetime(2026, 8, 16, 20, 0, tzinfo=timezone.utc)]

        report = schedule_timing.summarize(starts, 900000)

        self.assertEqual(1, report["cycle_count"])
        self.assertEqual(0, report["interval_count"])
        self.assertIsNone(report["average_interval_seconds"])
        self.assertIn("At least two", schedule_timing.format_report(report))

    def test_filters_manual_cycles_before_scheduler_registration(self) -> None:
        starts = [
            datetime(2026, 8, 16, 20, 0, tzinfo=timezone.utc),
            datetime(2026, 8, 16, 20, 15, tzinfo=timezone.utc),
            datetime(2026, 8, 16, 21, 2, 24, tzinfo=timezone.utc),
            datetime(2026, 8, 16, 21, 17, 42, tzinfo=timezone.utc),
        ]
        registered_at = datetime(2026, 8, 16, 21, 0, tzinfo=timezone.utc)

        filtered = schedule_timing.starts_at_or_after(starts, registered_at)

        self.assertEqual(starts[2:], filtered)

    def test_empty_log_is_supported(self) -> None:
        report = schedule_timing.summarize([], 900000)

        self.assertEqual(0, report["cycle_count"])
        self.assertIsNone(report["first_started_at"])
        self.assertIsNone(report["last_started_at"])


if __name__ == "__main__":
    unittest.main()
