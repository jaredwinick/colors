from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image


MODULE_PATH = Path(__file__).parents[1] / "android" / "normalize_capture.py"
SPEC = importlib.util.spec_from_file_location("normalize_capture", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"could not load {MODULE_PATH}")
normalize_capture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(normalize_capture)


class NormalizeCaptureTests(unittest.TestCase):
    def test_corrects_exif_orientation_resizes_and_writes_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            source_path = directory / "source.jpg"
            output_path = directory / "normalized.jpg"
            metadata_path = directory / "capture.json"

            source = Image.new("RGB", (80, 40), (110, 170, 220))
            exif = source.getexif()
            exif[274] = 6
            source.save(source_path, format="JPEG", exif=exif)

            metadata = normalize_capture.normalize_capture(
                input_path=source_path,
                output_path=output_path,
                metadata_output_path=metadata_path,
                capture_id="test-capture",
                captured_at="2026-08-10T12:34:56Z",
                camera_id=0,
                final_image_path=Path("/captures/test-capture.jpg"),
                max_dimension=30,
                quality=82,
            )

            with Image.open(output_path) as normalized:
                normalized.load()
                self.assertEqual("JPEG", normalized.format)
                self.assertEqual((15, 30), normalized.size)
                self.assertNotIn(274, normalized.getexif())

            self.assertEqual(80, metadata["source_width"])
            self.assertEqual(40, metadata["source_height"])
            self.assertEqual(15, metadata["width"])
            self.assertEqual(30, metadata["height"])
            self.assertEqual(82, metadata["jpeg_quality"])
            self.assertLess(metadata["bytes"], normalize_capture.API_MAX_IMAGE_BYTES)
            self.assertEqual(metadata, json.loads(metadata_path.read_text("utf-8")))

    def test_removes_partial_outputs_when_size_limit_is_exceeded(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            source_path = directory / "source.jpg"
            output_path = directory / "normalized.jpg"
            metadata_path = directory / "capture.json"
            Image.new("RGB", (100, 100), (80, 120, 200)).save(source_path, "JPEG")

            with self.assertRaisesRegex(ValueError, "limit is 1 byte"):
                normalize_capture.normalize_capture(
                    input_path=source_path,
                    output_path=output_path,
                    metadata_output_path=metadata_path,
                    capture_id="too-large",
                    captured_at="2026-08-10T12:34:56Z",
                    camera_id=0,
                    final_image_path=Path("/captures/too-large.jpg"),
                    max_bytes=1,
                )

            self.assertFalse(output_path.exists())
            self.assertFalse(metadata_path.exists())


if __name__ == "__main__":
    unittest.main()
