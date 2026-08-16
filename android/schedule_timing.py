#!/data/data/com.termux/files/usr/bin/python
"""Summarize recurring Colors job start intervals from the durable job log."""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from statistics import mean, median
from typing import Any


START_PATTERN = re.compile(
    r"^(?P<timestamp>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z) "
    r"\[colors \+\d+s\] Starting durable capture cycle\.$"
)


def parse_start_times(log_text: str) -> list[datetime]:
    starts: list[datetime] = []
    for line in log_text.splitlines():
        match = START_PATTERN.fullmatch(line.strip())
        if match is None:
            continue
        starts.append(
            datetime.fromisoformat(
                match.group("timestamp").replace("Z", "+00:00")
            ).astimezone(timezone.utc)
        )
    return starts


def parse_utc_datetime(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be an ISO-8601 datetime") from error
    if parsed.tzinfo is None:
        raise argparse.ArgumentTypeError("must include a timezone")
    return parsed.astimezone(timezone.utc)


def starts_at_or_after(starts: list[datetime], since: datetime) -> list[datetime]:
    return [started_at for started_at in starts if started_at >= since]


def summarize(starts: list[datetime], period_ms: int) -> dict[str, Any]:
    if period_ms < 1:
        raise ValueError("period_ms must be positive")
    target_seconds = period_ms / 1000
    intervals = [
        (current - previous).total_seconds()
        for previous, current in zip(starts, starts[1:])
    ]
    report: dict[str, Any] = {
        "cycle_count": len(starts),
        "first_started_at": starts[0].isoformat().replace("+00:00", "Z")
        if starts
        else None,
        "last_started_at": starts[-1].isoformat().replace("+00:00", "Z")
        if starts
        else None,
        "interval_count": len(intervals),
        "target_interval_seconds": target_seconds,
    }
    if not intervals:
        report.update(
            {
                "average_interval_seconds": None,
                "median_interval_seconds": None,
                "minimum_interval_seconds": None,
                "maximum_interval_seconds": None,
                "average_drift_seconds": None,
                "maximum_absolute_drift_seconds": None,
            }
        )
        return report

    drifts = [interval - target_seconds for interval in intervals]
    report.update(
        {
            "average_interval_seconds": round(mean(intervals), 1),
            "median_interval_seconds": round(median(intervals), 1),
            "minimum_interval_seconds": round(min(intervals), 1),
            "maximum_interval_seconds": round(max(intervals), 1),
            "average_drift_seconds": round(mean(drifts), 1),
            "maximum_absolute_drift_seconds": round(
                max(abs(drift) for drift in drifts), 1
            ),
        }
    )
    return report


def positive_integer(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--period-ms", required=True, type=positive_integer)
    parser.add_argument("--since", type=parse_utc_datetime)
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def format_report(report: dict[str, Any]) -> str:
    lines = [
        f"Cycles recorded: {report['cycle_count']}",
        f"Target interval: {report['target_interval_seconds']:.0f}s",
    ]
    if report.get("sample_since") is not None:
        lines.append(f"Sample since: {report['sample_since']}")
    if report["interval_count"] == 0:
        lines.append("At least two completed cycle starts are needed for drift statistics.")
        return "\n".join(lines)
    lines.extend(
        [
            f"First start: {report['first_started_at']}",
            f"Last start: {report['last_started_at']}",
            f"Observed intervals: {report['interval_count']}",
            f"Average / median: {report['average_interval_seconds']:.1f}s / "
            f"{report['median_interval_seconds']:.1f}s",
            f"Minimum / maximum: {report['minimum_interval_seconds']:.1f}s / "
            f"{report['maximum_interval_seconds']:.1f}s",
            f"Average drift: {report['average_drift_seconds']:+.1f}s",
            "Maximum absolute drift: "
            f"{report['maximum_absolute_drift_seconds']:.1f}s",
        ]
    )
    return "\n".join(lines)


def main() -> None:
    args = parse_args()
    log_text = args.log.read_text(encoding="utf-8") if args.log.is_file() else ""
    starts = parse_start_times(log_text)
    if args.since is not None:
        starts = starts_at_or_after(starts, args.since)
    report = summarize(starts, args.period_ms)
    report["sample_since"] = (
        args.since.isoformat().replace("+00:00", "Z")
        if args.since is not None
        else None
    )
    if args.json:
        print(json.dumps(report, separators=(",", ":"), sort_keys=True))
    else:
        print(format_report(report))


if __name__ == "__main__":
    main()
