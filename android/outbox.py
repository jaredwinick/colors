#!/data/data/com.termux/files/usr/bin/python
"""Manage the durable Colors capture outbox and authenticated uploads."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


UUID4_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    re.IGNORECASE,
)
HEX_COLOR_PATTERN = re.compile(r"^#[0-9A-F]{6}$")
MAX_RESPONSE_BYTES = 64 * 1024
SIDECAR_NAME = "capture.json"
IMAGE_NAME = "capture.jpg"


class OutboxError(Exception):
    """A safe, operator-facing outbox error."""


class RejectRedirects(urllib.request.HTTPRedirectHandler):
    """Keep the bearer token on the configured origin."""

    def redirect_request(
        self,
        request: urllib.request.Request,
        file_pointer: Any,
        code: int,
        message: str,
        headers: Any,
        new_url: str,
    ) -> None:
        return None


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def isoformat_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace(
        "+00:00", "Z"
    )


def parse_datetime(value: object, field: str) -> datetime:
    if not isinstance(value, str):
        raise OutboxError(f"{field} must be an ISO-8601 datetime")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise OutboxError(f"{field} must be an ISO-8601 datetime") from error
    if parsed.tzinfo is None:
        raise OutboxError(f"{field} must include a timezone")
    return parsed.astimezone(timezone.utc)


def validate_capture_id(value: object) -> str:
    if not isinstance(value, str) or not UUID4_PATTERN.fullmatch(value):
        raise OutboxError("capture_id must be a UUIDv4")
    return value.lower()


def validate_palette(value: object) -> list[dict[str, float | str]]:
    if not isinstance(value, list) or not 3 <= len(value) <= 10:
        raise OutboxError("palette must contain 3-10 colors")

    validated: list[dict[str, float | str]] = []
    total = 0.0
    for index, entry in enumerate(value):
        if not isinstance(entry, dict):
            raise OutboxError(f"palette[{index}] must be an object")
        hex_color = entry.get("hex")
        weight = entry.get("weight")
        if not isinstance(hex_color, str) or not HEX_COLOR_PATTERN.fullmatch(hex_color):
            raise OutboxError(f"palette[{index}].hex must be uppercase #RRGGBB")
        if (
            isinstance(weight, bool)
            or not isinstance(weight, (int, float))
            or float(weight) <= 0
        ):
            raise OutboxError(f"palette[{index}].weight must be positive")
        total += float(weight)
        validated.append({"hex": hex_color, "weight": float(weight)})

    if not 0.999 <= total <= 1.001:
        raise OutboxError("palette weights must total 1.0")
    return validated


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise OutboxError(f"could not read JSON file {path}: {error}") from error


def atomic_write_json(path: Path, value: object) -> None:
    temporary = path.with_name(f".{path.name}.{os.getpid()}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_text(
            json.dumps(value, separators=(",", ":"), sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.chmod(temporary, stat.S_IRUSR | stat.S_IWUSR)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def outbox_directories(outbox_directory: Path) -> tuple[Path, Path]:
    pending = outbox_directory / "pending"
    delivered = outbox_directory / "delivered"
    pending.mkdir(parents=True, exist_ok=True)
    delivered.mkdir(parents=True, exist_ok=True)
    return pending, delivered


def load_item(directory: Path) -> dict[str, Any]:
    capture_id = validate_capture_id(directory.name)
    sidecar_path = directory / SIDECAR_NAME
    image_path = directory / IMAGE_NAME
    metadata = read_json(sidecar_path)
    if not isinstance(metadata, dict):
        raise OutboxError(f"sidecar is not a JSON object: {sidecar_path}")
    if validate_capture_id(metadata.get("capture_id")) != capture_id:
        raise OutboxError(f"sidecar capture ID does not match {directory.name}")
    parse_datetime(metadata.get("captured_at"), "captured_at")
    validate_palette(metadata.get("palette"))
    if not image_path.is_file() or image_path.stat().st_size <= 0:
        raise OutboxError(f"queued image is missing or empty: {image_path}")
    if metadata.get("bytes") != image_path.stat().st_size:
        raise OutboxError(f"queued image size does not match sidecar: {capture_id}")
    if metadata.get("mime_type") != "image/jpeg":
        raise OutboxError(f"queued image must be image/jpeg: {capture_id}")
    device_id = metadata.get("device_id")
    if not isinstance(device_id, str) or not device_id or len(device_id) > 100:
        raise OutboxError(f"invalid device_id in sidecar: {capture_id}")
    return metadata


def enqueue_capture(
    *,
    outbox_directory: Path,
    image_path: Path,
    metadata_path: Path,
    palette_path: Path,
    device_id: str,
    now: datetime | None = None,
) -> dict[str, Any]:
    pending, delivered = outbox_directories(outbox_directory)
    source_metadata = read_json(metadata_path)
    if not isinstance(source_metadata, dict):
        raise OutboxError("capture metadata must be a JSON object")
    capture_id = validate_capture_id(source_metadata.get("capture_id"))
    captured_at = source_metadata.get("captured_at")
    parse_datetime(captured_at, "captured_at")
    palette = validate_palette(read_json(palette_path))
    device_id = device_id.strip()
    if not device_id or len(device_id) > 100:
        raise OutboxError("device_id must contain 1-100 characters")
    if not image_path.is_file() or image_path.stat().st_size <= 0:
        raise OutboxError(f"capture image is missing or empty: {image_path}")
    if source_metadata.get("bytes") != image_path.stat().st_size:
        raise OutboxError("capture image size does not match its metadata")
    if source_metadata.get("mime_type") != "image/jpeg":
        raise OutboxError("capture image must be image/jpeg")

    final_directory = pending / capture_id
    delivered_directory = delivered / capture_id
    if delivered_directory.exists():
        raise OutboxError(f"capture is already delivered: {capture_id}")
    if final_directory.exists():
        existing = load_item(final_directory)
        if (
            existing.get("captured_at") == captured_at
            and existing.get("device_id") == device_id
            and existing.get("palette") == palette
            and existing.get("bytes") == image_path.stat().st_size
        ):
            metadata_path.unlink(missing_ok=True)
            image_path.unlink(missing_ok=True)
            return existing
        raise OutboxError(f"pending capture ID already exists with different data: {capture_id}")

    queued_at = isoformat_utc(now or utc_now())
    sidecar: dict[str, Any] = {
        **source_metadata,
        "schema_version": 2,
        "capture_id": capture_id,
        "captured_at": captured_at,
        "device_id": device_id,
        "image_path": IMAGE_NAME,
        "palette": palette,
        "state": "pending",
        "queued_at": queued_at,
        "attempt_count": 0,
        "last_attempt_at": None,
        "last_error": None,
        "next_attempt_at": None,
        "delivered_at": None,
        "delivery": None,
    }

    staging = pending / f".{capture_id}.{os.getpid()}.{uuid.uuid4().hex}.tmp"
    try:
        staging.mkdir(mode=0o700)
        staged_image = staging / IMAGE_NAME
        shutil.copy2(image_path, staged_image)
        os.chmod(staged_image, stat.S_IRUSR | stat.S_IWUSR)
        atomic_write_json(staging / SIDECAR_NAME, sidecar)
        staging.replace(final_directory)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise

    # The sidecar is the staged-capture commit marker. Remove it first so a
    # process kill during cleanup can leave only an inert orphan JPEG, never a
    # sidecar that blocks every later cycle while pointing at a missing image.
    metadata_path.unlink(missing_ok=True)
    image_path.unlink(missing_ok=True)
    return sidecar


def pending_items(outbox_directory: Path) -> list[tuple[Path, dict[str, Any]]]:
    pending, _ = outbox_directories(outbox_directory)
    items: list[tuple[Path, dict[str, Any]]] = []
    for directory in pending.iterdir():
        if not directory.is_dir() or directory.name.startswith("."):
            continue
        metadata = load_item(directory)
        items.append((directory, metadata))
    items.sort(
        key=lambda item: (
            parse_datetime(item[1].get("captured_at"), "captured_at"),
            item[0].name,
        )
    )
    return items


def read_token(token_file: Path) -> str:
    if not token_file.is_file():
        raise OutboxError(f"ingest token file is missing: {token_file}")
    if os.name == "posix" and stat.S_IMODE(token_file.stat().st_mode) & 0o077:
        raise OutboxError(f"ingest token file permissions must be 600: {token_file}")
    try:
        token = token_file.read_text(encoding="utf-8").strip()
    except OSError as error:
        raise OutboxError(f"could not read ingest token file: {error}") from error
    if len(token) < 32 or len(token) > 4096 or "\n" in token or "\r" in token:
        raise OutboxError("ingest token file does not contain one valid secret")
    return token


def validate_endpoint(endpoint: str) -> str:
    parsed = urllib.parse.urlsplit(endpoint)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise OutboxError("ingest endpoint must be an http or https URL")
    if parsed.username or parsed.password:
        raise OutboxError("ingest endpoint must not contain credentials")
    if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise OutboxError("plain HTTP ingest is allowed only for a local recovery test")
    return endpoint


def open_request(
    request: urllib.request.Request, timeout_seconds: int
) -> Any:
    opener = urllib.request.build_opener(RejectRedirects())
    return opener.open(request, timeout=timeout_seconds)


def build_multipart(metadata: dict[str, Any], image: bytes) -> tuple[bytes, str]:
    boundary = f"colors-{uuid.uuid4().hex}"
    body = bytearray()

    def add_field(name: str, value: str) -> None:
        body.extend(f"--{boundary}\r\n".encode())
        body.extend(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
        body.extend(value.encode("utf-8"))
        body.extend(b"\r\n")

    add_field("capture_id", str(metadata["capture_id"]))
    add_field("captured_at", str(metadata["captured_at"]))
    add_field("device_id", str(metadata["device_id"]))
    add_field(
        "palette",
        json.dumps(metadata["palette"], separators=(",", ":"), sort_keys=True),
    )
    body.extend(f"--{boundary}\r\n".encode())
    body.extend(
        b'Content-Disposition: form-data; name="image"; filename="capture.jpg"\r\n'
    )
    body.extend(b"Content-Type: image/jpeg\r\n\r\n")
    body.extend(image)
    body.extend(b"\r\n")
    body.extend(f"--{boundary}--\r\n".encode())
    return bytes(body), boundary


def upload_item(
    *,
    directory: Path,
    metadata: dict[str, Any],
    endpoint: str,
    token: str,
    timeout_seconds: int,
) -> tuple[int, dict[str, Any]]:
    image = (directory / IMAGE_NAME).read_bytes()
    body, boundary = build_multipart(metadata, image)
    request = urllib.request.Request(
        validate_endpoint(endpoint),
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Content-Length": str(len(body)),
            "User-Agent": "colors-android-outbox/1",
        },
    )
    try:
        with open_request(request, timeout_seconds) as response:
            status = response.status
            response_body = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as error:
        response_body = error.read(MAX_RESPONSE_BYTES + 1)
        detail = response_body[:MAX_RESPONSE_BYTES].decode("utf-8", errors="replace")
        raise OutboxError(f"server returned HTTP {error.code}: {detail[:300]}") from error
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise OutboxError(f"network request failed: {error}") from error

    if len(response_body) > MAX_RESPONSE_BYTES:
        raise OutboxError("server response exceeded 64 KiB")
    try:
        payload = json.loads(response_body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise OutboxError("server success response was not valid JSON") from error
    if status not in {200, 201} or not isinstance(payload, dict):
        raise OutboxError(f"server returned unconfirmed success status {status}")
    capture = payload.get("capture")
    if not isinstance(capture, dict) or capture.get("id") != metadata["capture_id"]:
        raise OutboxError("server response capture ID did not match the queued capture")
    replay = payload.get("idempotentReplay")
    if not isinstance(replay, bool):
        raise OutboxError("server response did not identify idempotent replay state")
    if (status == 200 and not replay) or (status == 201 and replay):
        raise OutboxError("server status and idempotent replay state were inconsistent")
    return status, payload


def safe_error(error: Exception) -> str:
    return " ".join(str(error).split())[:500] or error.__class__.__name__


def record_failure(
    directory: Path,
    metadata: dict[str, Any],
    error: Exception,
    *,
    initial_backoff_seconds: int,
    maximum_backoff_seconds: int,
    now: datetime,
) -> dict[str, Any]:
    attempt_count = int(metadata.get("attempt_count", 0)) + 1
    exponent = min(attempt_count - 1, 20)
    delay = min(initial_backoff_seconds * (2**exponent), maximum_backoff_seconds)
    updated = {
        **metadata,
        "attempt_count": attempt_count,
        "last_attempt_at": isoformat_utc(now),
        "last_error": safe_error(error),
        "next_attempt_at": isoformat_utc(now + timedelta(seconds=delay)),
    }
    atomic_write_json(directory / SIDECAR_NAME, updated)
    return updated


def record_delivery(
    outbox_directory: Path,
    directory: Path,
    metadata: dict[str, Any],
    status: int,
    response: dict[str, Any],
    *,
    now: datetime,
) -> dict[str, Any]:
    _, delivered = outbox_directories(outbox_directory)
    destination = delivered / directory.name
    if destination.exists():
        raise OutboxError(f"delivered destination already exists: {directory.name}")
    updated = {
        **metadata,
        "state": "delivered",
        "attempt_count": int(metadata.get("attempt_count", 0)) + 1,
        "last_attempt_at": isoformat_utc(now),
        "last_error": None,
        "next_attempt_at": None,
        "delivered_at": isoformat_utc(now),
        "delivery": {
            "http_status": status,
            "idempotent_replay": bool(response["idempotentReplay"]),
            "image_url": response["capture"].get("imageUrl"),
        },
    }
    atomic_write_json(directory / SIDECAR_NAME, updated)
    directory.replace(destination)
    return updated


def drain_outbox(
    *,
    outbox_directory: Path,
    endpoint: str,
    token_file: Path,
    max_items: int,
    timeout_seconds: int,
    initial_backoff_seconds: int,
    maximum_backoff_seconds: int,
    notify_after_attempts: int,
    now: datetime | None = None,
) -> dict[str, Any]:
    current_time = now or utc_now()
    items = pending_items(outbox_directory)
    ready = [
        item
        for item in items
        if item[1].get("next_attempt_at") is None
        or parse_datetime(item[1]["next_attempt_at"], "next_attempt_at")
        <= current_time
    ]
    summary: dict[str, Any] = {
        "attempted": 0,
        "delivered": 0,
        "failed": 0,
        "deferred": len(items) - len(ready),
        "pending": len(items),
        "notification_required": False,
        "failures": [],
    }
    if max_items <= 0 or not ready:
        return summary

    token = read_token(token_file)
    for directory, metadata in ready[:max_items]:
        summary["attempted"] += 1
        attempt_time = now or utc_now()
        try:
            status, response = upload_item(
                directory=directory,
                metadata=metadata,
                endpoint=endpoint,
                token=token,
                timeout_seconds=timeout_seconds,
            )
            record_delivery(
                outbox_directory,
                directory,
                metadata,
                status,
                response,
                now=attempt_time,
            )
            summary["delivered"] += 1
        except Exception as error:
            updated = record_failure(
                directory,
                metadata,
                error,
                initial_backoff_seconds=initial_backoff_seconds,
                maximum_backoff_seconds=maximum_backoff_seconds,
                now=attempt_time,
            )
            summary["failed"] += 1
            summary["failures"].append(
                {
                    "capture_id": metadata["capture_id"],
                    "attempt_count": updated["attempt_count"],
                    "error": updated["last_error"],
                }
            )
            if updated["attempt_count"] >= notify_after_attempts:
                summary["notification_required"] = True

    summary["pending"] = len(pending_items(outbox_directory))
    return summary


def apply_retention(
    *,
    outbox_directory: Path,
    retention_days: int,
    retention_count: int,
    now: datetime | None = None,
) -> dict[str, int]:
    _, delivered = outbox_directories(outbox_directory)
    current_time = now or utc_now()
    captures: list[tuple[Path, datetime]] = []
    for directory in delivered.iterdir():
        if not directory.is_dir() or not UUID4_PATTERN.fullmatch(directory.name):
            continue
        metadata = load_item(directory)
        timestamp = parse_datetime(
            metadata.get("delivered_at") or metadata.get("captured_at"),
            "delivered_at",
        )
        captures.append((directory, timestamp))
    captures.sort(key=lambda item: (item[1], item[0].name), reverse=True)

    cutoff = current_time - timedelta(days=retention_days)
    removed = 0
    for index, (directory, timestamp) in enumerate(captures):
        if index >= retention_count or timestamp < cutoff:
            shutil.rmtree(directory)
            removed += 1
    return {"removed": removed, "remaining": len(captures) - removed}


def positive_integer(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def nonnegative_integer(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be a non-negative integer")
    return parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    enqueue = subparsers.add_parser("enqueue", help="queue one processed capture")
    enqueue.add_argument("--outbox-directory", required=True, type=Path)
    enqueue.add_argument("--image", required=True, type=Path)
    enqueue.add_argument("--metadata", required=True, type=Path)
    enqueue.add_argument("--palette", required=True, type=Path)
    enqueue.add_argument("--device-id", required=True)

    drain = subparsers.add_parser("drain", help="upload due captures oldest first")
    drain.add_argument("--outbox-directory", required=True, type=Path)
    drain.add_argument("--endpoint", required=True)
    drain.add_argument("--token-file", required=True, type=Path)
    drain.add_argument("--max-items", type=positive_integer, default=4)
    drain.add_argument("--timeout-seconds", type=positive_integer, default=120)
    drain.add_argument("--initial-backoff-seconds", type=positive_integer, default=60)
    drain.add_argument("--maximum-backoff-seconds", type=positive_integer, default=3600)
    drain.add_argument("--notify-after-attempts", type=positive_integer, default=3)

    count = subparsers.add_parser("count", help="print the pending capture count")
    count.add_argument("--outbox-directory", required=True, type=Path)

    retain = subparsers.add_parser("retain", help="apply delivered-file retention")
    retain.add_argument("--outbox-directory", required=True, type=Path)
    retain.add_argument("--retention-days", type=nonnegative_integer, default=7)
    retain.add_argument("--retention-count", type=nonnegative_integer, default=672)

    return parser.parse_args()


def main() -> None:
    args = parse_args()
    try:
        if args.command == "enqueue":
            result = enqueue_capture(
                outbox_directory=args.outbox_directory,
                image_path=args.image,
                metadata_path=args.metadata,
                palette_path=args.palette,
                device_id=args.device_id,
            )
            print(json.dumps({"capture_id": result["capture_id"], "state": "pending"}))
        elif args.command == "drain":
            result = drain_outbox(
                outbox_directory=args.outbox_directory,
                endpoint=args.endpoint,
                token_file=args.token_file,
                max_items=args.max_items,
                timeout_seconds=args.timeout_seconds,
                initial_backoff_seconds=args.initial_backoff_seconds,
                maximum_backoff_seconds=args.maximum_backoff_seconds,
                notify_after_attempts=args.notify_after_attempts,
            )
            print(json.dumps(result, separators=(",", ":"), sort_keys=True))
        elif args.command == "count":
            print(len(pending_items(args.outbox_directory)))
        elif args.command == "retain":
            result = apply_retention(
                outbox_directory=args.outbox_directory,
                retention_days=args.retention_days,
                retention_count=args.retention_count,
            )
            print(json.dumps(result, separators=(",", ":"), sort_keys=True))
        else:
            raise OutboxError(f"unsupported command: {args.command}")
    except Exception as error:
        print(f"outbox error: {safe_error(error)}", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
