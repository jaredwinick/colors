#!/data/data/com.termux/files/usr/bin/python
"""Normalize one camera JPEG and emit durable capture metadata."""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path
from typing import Any

from PIL import Image, ImageOps


API_MAX_IMAGE_BYTES = 12 * 1024 * 1024
EXIF_ORIENTATION_TAG = 274


def positive_integer(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def jpeg_quality(value: str) -> int:
    parsed = int(value)
    if not 1 <= parsed <= 95:
        raise argparse.ArgumentTypeError("must be between 1 and 95")
    return parsed


def iso8601_datetime(value: str) -> str:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise argparse.ArgumentTypeError("must include a timezone")
    return value


def normalize_capture(
    *,
    input_path: Path,
    output_path: Path,
    metadata_output_path: Path,
    capture_id: str,
    captured_at: str,
    camera_id: int,
    final_image_path: Path,
    max_dimension: int = 1920,
    quality: int = 85,
    max_bytes: int = API_MAX_IMAGE_BYTES,
) -> dict[str, Any]:
    """Write a validated, oriented JPEG and its metadata sidecar."""

    if not input_path.is_file() or input_path.stat().st_size <= 0:
        raise ValueError(f"input image is missing or empty: {input_path}")
    if input_path.resolve() == output_path.resolve():
        raise ValueError("input and output paths must be different")
    if not capture_id or any(character.isspace() for character in capture_id):
        raise ValueError("capture ID must be non-empty and contain no whitespace")
    if camera_id < 0:
        raise ValueError("camera ID must be non-negative")
    if max_dimension < 1:
        raise ValueError("maximum dimension must be positive")
    if not 1 <= quality <= 95:
        raise ValueError("JPEG quality must be between 1 and 95")
    if max_bytes < 1:
        raise ValueError("maximum bytes must be positive")

    iso8601_datetime(captured_at)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_output_path.parent.mkdir(parents=True, exist_ok=True)

    try:
        with Image.open(input_path) as source:
            source.load()
            source_width, source_height = source.size
            normalized = ImageOps.exif_transpose(source).convert("RGB")
            normalized.thumbnail(
                (max_dimension, max_dimension),
                Image.Resampling.LANCZOS,
            )
            normalized.save(
                output_path,
                format="JPEG",
                quality=quality,
                optimize=True,
                progressive=True,
            )

        image_bytes = output_path.stat().st_size
        if image_bytes <= 0:
            raise ValueError("normalized JPEG is empty")
        if image_bytes > max_bytes:
            raise ValueError(
                f"normalized JPEG is {image_bytes} bytes; limit is {max_bytes} bytes"
            )

        with Image.open(output_path) as verified:
            verified.load()
            if verified.format != "JPEG":
                raise ValueError(f"normalized image is not JPEG: {verified.format}")
            width, height = verified.size
            if max(width, height) > max_dimension:
                raise ValueError("normalized dimensions exceed the configured maximum")
            orientation = verified.getexif().get(EXIF_ORIENTATION_TAG)
            if orientation not in (None, 1):
                raise ValueError("normalized JPEG still contains an EXIF rotation")

        metadata: dict[str, Any] = {
            "schema_version": 1,
            "capture_id": capture_id,
            "captured_at": captured_at,
            "camera_id": camera_id,
            "image_path": str(final_image_path),
            "mime_type": "image/jpeg",
            "source_width": source_width,
            "source_height": source_height,
            "width": width,
            "height": height,
            "bytes": image_bytes,
            "jpeg_quality": quality,
            "max_dimension": max_dimension,
        }
        metadata_output_path.write_text(
            json.dumps(metadata, separators=(",", ":"), sort_keys=True) + "\n",
            encoding="utf-8",
        )
        return metadata
    except Exception:
        output_path.unlink(missing_ok=True)
        metadata_output_path.unlink(missing_ok=True)
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--metadata-output", required=True, type=Path)
    parser.add_argument("--capture-id", required=True)
    parser.add_argument("--captured-at", required=True, type=iso8601_datetime)
    parser.add_argument("--camera-id", required=True, type=int)
    parser.add_argument("--final-image-path", required=True, type=Path)
    parser.add_argument("--max-dimension", type=positive_integer, default=1920)
    parser.add_argument("--jpeg-quality", type=jpeg_quality, default=85)
    parser.add_argument(
        "--max-bytes",
        type=positive_integer,
        default=API_MAX_IMAGE_BYTES,
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    metadata = normalize_capture(
        input_path=args.input,
        output_path=args.output,
        metadata_output_path=args.metadata_output,
        capture_id=args.capture_id,
        captured_at=args.captured_at,
        camera_id=args.camera_id,
        final_image_path=args.final_image_path,
        max_dimension=args.max_dimension,
        quality=args.jpeg_quality,
        max_bytes=args.max_bytes,
    )
    print(json.dumps(metadata, separators=(",", ":"), sort_keys=True))


if __name__ == "__main__":
    main()
