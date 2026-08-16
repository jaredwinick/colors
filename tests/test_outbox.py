from __future__ import annotations

import importlib.util
import json
import os
import re
import tempfile
import threading
import unittest
import urllib.error
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

from PIL import Image


MODULE_PATH = Path(__file__).parents[1] / "android" / "outbox.py"
SPEC = importlib.util.spec_from_file_location("outbox", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"could not load {MODULE_PATH}")
outbox = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(outbox)

PALETTE = [
    {"hex": "#335577", "weight": 0.5},
    {"hex": "#7799BB", "weight": 0.3},
    {"hex": "#DDEEFF", "weight": 0.2},
]


class RecordingHandler(BaseHTTPRequestHandler):
    requests: list[dict[str, object]] = []
    statuses: list[int] = []
    mismatched_response = False

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        length = int(self.headers["Content-Length"])
        body = self.rfile.read(length)
        match = re.search(br'name="capture_id"\r\n\r\n([^\r\n]+)', body)
        capture_id = match.group(1).decode() if match else "missing"
        self.__class__.requests.append(
            {
                "authorization": self.headers.get("Authorization"),
                "content_type": self.headers.get("Content-Type"),
                "body": body,
                "capture_id": capture_id,
            }
        )
        status = self.__class__.statuses.pop(0) if self.__class__.statuses else 201
        if status not in {200, 201}:
            payload = {"error": "temporary server failure"}
        else:
            response_id = (
                "6fa459ea-ee8a-4ca4-894e-db77e160355e"
                if self.__class__.mismatched_response
                else capture_id
            )
            payload = {
                "capture": {
                    "id": response_id,
                    "imageUrl": f"/api/images/test/{response_id}.jpg",
                },
                "idempotentReplay": status == 200,
            }
        encoded = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format: str, *args: object) -> None:
        return


class OutboxTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.outbox_directory = self.root / "outbox"
        self.token_file = self.root / "ingest-token"
        self.secret = "a" * 64
        self.token_file.write_text(self.secret, encoding="utf-8")
        os.chmod(self.token_file, 0o600)
        RecordingHandler.requests = []
        RecordingHandler.statuses = []
        RecordingHandler.mismatched_response = False
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), RecordingHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.endpoint = f"http://127.0.0.1:{self.server.server_port}/api/ingest"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temporary.cleanup()

    def enqueue(
        self,
        capture_id: str,
        captured_at: str,
        *,
        now: datetime | None = None,
    ) -> dict[str, object]:
        source = self.root / f"source-{capture_id}"
        source.mkdir()
        image_path = source / "capture.jpg"
        metadata_path = source / "capture.json"
        palette_path = source / "palette.json"
        Image.new("RGB", (20, 20), (90, 150, 210)).save(image_path, "JPEG")
        metadata = {
            "schema_version": 1,
            "capture_id": capture_id,
            "captured_at": captured_at,
            "camera_id": 0,
            "image_path": str(image_path),
            "mime_type": "image/jpeg",
            "bytes": image_path.stat().st_size,
            "width": 20,
            "height": 20,
        }
        metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
        palette_path.write_text(json.dumps(PALETTE), encoding="utf-8")
        result = outbox.enqueue_capture(
            outbox_directory=self.outbox_directory,
            image_path=image_path,
            metadata_path=metadata_path,
            palette_path=palette_path,
            device_id="galaxy-s9-window",
            now=now,
        )
        self.assertFalse(image_path.exists())
        self.assertFalse(metadata_path.exists())
        return result

    def drain(
        self,
        *,
        max_items: int = 4,
        now: datetime | None = None,
    ) -> dict[str, object]:
        return outbox.drain_outbox(
            outbox_directory=self.outbox_directory,
            endpoint=self.endpoint,
            token_file=self.token_file,
            max_items=max_items,
            timeout_seconds=5,
            initial_backoff_seconds=60,
            maximum_backoff_seconds=3600,
            notify_after_attempts=3,
            now=now,
        )

    def test_enqueue_creates_private_pending_pair_with_required_metadata(self) -> None:
        capture_id = "c5f3db98-4f4d-4c26-a013-750868f57c91"
        result = self.enqueue(capture_id, "2026-08-16T20:00:00Z")

        directory = self.outbox_directory / "pending" / capture_id
        self.assertTrue((directory / "capture.jpg").is_file())
        self.assertTrue((directory / "capture.json").is_file())
        self.assertEqual("pending", result["state"])
        self.assertEqual("galaxy-s9-window", result["device_id"])
        self.assertEqual(PALETTE, result["palette"])
        self.assertEqual(0, result["attempt_count"])

    def test_confirmed_first_upload_moves_capture_to_delivered(self) -> None:
        capture_id = "a34a289e-634a-4b0f-865b-9d989918c4e8"
        self.enqueue(capture_id, "2026-08-16T20:00:00Z")
        RecordingHandler.statuses = [201]

        summary = self.drain()

        self.assertEqual(1, summary["delivered"])
        self.assertEqual(0, summary["pending"])
        self.assertFalse((self.outbox_directory / "pending" / capture_id).exists())
        delivered = self.outbox_directory / "delivered" / capture_id
        self.assertTrue((delivered / "capture.jpg").is_file())
        metadata = json.loads((delivered / "capture.json").read_text("utf-8"))
        self.assertEqual(201, metadata["delivery"]["http_status"])
        request = RecordingHandler.requests[0]
        self.assertEqual(f"Bearer {self.secret}", request["authorization"])
        body = request["body"]
        assert isinstance(body, bytes)
        for field in ("image", "capture_id", "captured_at", "device_id", "palette"):
            self.assertIn(f'name="{field}"'.encode(), body)
        self.assertNotIn(self.secret, json.dumps(summary))

    def test_confirmed_idempotent_response_is_delivered(self) -> None:
        capture_id = "fc8cc145-0d41-4824-9c44-d12be54c07ef"
        self.enqueue(capture_id, "2026-08-16T20:00:00Z")
        RecordingHandler.statuses = [200]

        summary = self.drain()

        self.assertEqual(1, summary["delivered"])
        metadata = json.loads(
            (
                self.outbox_directory
                / "delivered"
                / capture_id
                / "capture.json"
            ).read_text("utf-8")
        )
        self.assertTrue(metadata["delivery"]["idempotent_replay"])

    def test_response_loss_then_idempotent_retry_delivers_same_queued_capture(self) -> None:
        now = datetime(2026, 8, 16, 20, 0, tzinfo=timezone.utc)
        capture_id = "c3e74923-6f12-42a8-bfc8-8e6a7c971afa"
        self.enqueue(capture_id, "2026-08-16T19:00:00Z", now=now)
        with mock.patch.object(
            outbox,
            "open_request",
            side_effect=urllib.error.URLError("response lost after commit"),
        ):
            first = self.drain(now=now)
        RecordingHandler.statuses = [200]

        retry = self.drain(now=now + timedelta(seconds=60))

        self.assertEqual(1, first["failed"])
        self.assertEqual(1, retry["delivered"])
        self.assertEqual(capture_id, RecordingHandler.requests[0]["capture_id"])
        self.assertTrue((self.outbox_directory / "delivered" / capture_id).exists())

    def test_server_failure_retains_capture_and_applies_persisted_backoff(self) -> None:
        now = datetime(2026, 8, 16, 20, 0, tzinfo=timezone.utc)
        capture_id = "a651a285-e7d0-40ad-aabe-323d2f88ce98"
        self.enqueue(capture_id, "2026-08-16T19:00:00Z", now=now)
        RecordingHandler.statuses = [500]

        first = self.drain(now=now)
        second = self.drain(now=now + timedelta(seconds=30))

        self.assertEqual(1, first["failed"])
        self.assertEqual(1, first["pending"])
        self.assertEqual(0, second["attempted"])
        self.assertEqual(1, second["deferred"])
        metadata = json.loads(
            (
                self.outbox_directory / "pending" / capture_id / "capture.json"
            ).read_text("utf-8")
        )
        self.assertEqual(1, metadata["attempt_count"])
        self.assertEqual("2026-08-16T20:01:00Z", metadata["next_attempt_at"])

    def test_network_loss_retains_capture_without_leaking_token(self) -> None:
        capture_id = "083b97dc-1527-45aa-bc43-815225531355"
        self.enqueue(capture_id, "2026-08-16T20:00:00Z")
        with mock.patch.object(
            outbox,
            "open_request",
            side_effect=urllib.error.URLError("offline"),
        ):
            summary = self.drain()

        self.assertEqual(1, summary["failed"])
        self.assertTrue((self.outbox_directory / "pending" / capture_id).exists())
        self.assertNotIn(self.secret, json.dumps(summary))

    def test_repeated_failures_request_notification_at_threshold(self) -> None:
        now = datetime(2026, 8, 16, 20, 0, tzinfo=timezone.utc)
        capture_id = "42b63090-c41c-477e-a11c-dbc75e3081ab"
        self.enqueue(capture_id, "2026-08-16T19:00:00Z", now=now)
        RecordingHandler.statuses = [500, 500, 500]

        first = self.drain(now=now)
        second = self.drain(now=now + timedelta(seconds=60))
        third = self.drain(now=now + timedelta(seconds=180))

        self.assertFalse(first["notification_required"])
        self.assertFalse(second["notification_required"])
        self.assertTrue(third["notification_required"])

    def test_oldest_ready_capture_is_attempted_first_with_bounded_work(self) -> None:
        newer = "b455c9c6-da60-4716-b80c-20e23059e68b"
        older = "0e9f6da5-dcb0-4f1a-931c-da8fd68a1858"
        self.enqueue(newer, "2026-08-16T20:15:00Z")
        self.enqueue(older, "2026-08-16T20:00:00Z")

        summary = self.drain(max_items=1)

        self.assertEqual(1, summary["attempted"])
        self.assertEqual(older, RecordingHandler.requests[0]["capture_id"])
        self.assertTrue((self.outbox_directory / "pending" / newer).exists())

    def test_mismatched_success_response_is_not_marked_delivered(self) -> None:
        capture_id = "9607c350-a6b4-41b8-a9c9-48a0d4218477"
        self.enqueue(capture_id, "2026-08-16T20:00:00Z")
        RecordingHandler.mismatched_response = True

        summary = self.drain()

        self.assertEqual(1, summary["failed"])
        self.assertTrue((self.outbox_directory / "pending" / capture_id).exists())

    def test_retention_keeps_only_the_configured_newest_deliveries(self) -> None:
        first = "f45bbd0f-1b72-43f9-a469-afb0d90b0894"
        second = "4abcc0c6-af9b-4b76-9ad5-4cce7ebd1f7e"
        start = datetime(2026, 8, 16, 20, 0, tzinfo=timezone.utc)
        self.enqueue(first, "2026-08-16T19:00:00Z", now=start)
        self.enqueue(second, "2026-08-16T19:15:00Z", now=start)
        self.drain(max_items=1, now=start)
        self.drain(max_items=1, now=start + timedelta(minutes=1))

        result = outbox.apply_retention(
            outbox_directory=self.outbox_directory,
            retention_days=7,
            retention_count=1,
            now=start + timedelta(minutes=2),
        )

        self.assertEqual({"removed": 1, "remaining": 1}, result)
        self.assertFalse((self.outbox_directory / "delivered" / first).exists())
        self.assertTrue((self.outbox_directory / "delivered" / second).exists())


class JobScriptContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.script = (
            Path(__file__).parents[1] / "android" / "capture_and_upload.sh"
        ).read_text(encoding="utf-8")

    def test_shell_never_places_authorization_header_or_token_value_in_arguments(self) -> None:
        self.assertNotIn("Authorization:", self.script)
        self.assertNotRegex(self.script, r"token=\"?\$\(")
        self.assertIn("--token-file \"$TOKEN_FILE\"", self.script)

    def test_job_uses_nonblocking_lock(self) -> None:
        self.assertIn("flock -n 9", self.script)
        self.assertIn("Another Colors job is already running", self.script)

    def test_job_drains_oldest_work_before_new_capture(self) -> None:
        first_drain = self.script.index('drain_queue "$max_uploads"')
        new_capture = self.script.index('log "Capturing one new photograph."')
        self.assertLess(first_drain, new_capture)


if __name__ == "__main__":
    unittest.main()
