from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps


ROOT = Path(__file__).parents[1]
ANDROID_DIRECTORY = ROOT / "android"
FIXTURE_DIRECTORY = Path(__file__).parent / "fixtures" / "palette"
sys.path.insert(0, str(ANDROID_DIRECTORY))
MODULE_PATH = ANDROID_DIRECTORY / "extract_palette.py"
SPEC = importlib.util.spec_from_file_location("extract_palette", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"could not load {MODULE_PATH}")
extract_palette = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(extract_palette)


class ExtractPaletteTests(unittest.TestCase):
    def test_lighting_fixtures_produce_api_compatible_palettes(self) -> None:
        for scenario in ("daylight", "sunset", "overcast", "night"):
            with self.subTest(scenario=scenario):
                image_path = FIXTURE_DIRECTORY / f"{scenario}.ppm"
                first = extract_palette.extract(
                    image_path, mask_config_path=FIXTURE_DIRECTORY / "mask.json"
                )
                second = extract_palette.extract(
                    image_path, mask_config_path=FIXTURE_DIRECTORY / "mask.json"
                )

                self.assertEqual(first, second)
                self.assertEqual(6, len(first))
                self.assertAlmostEqual(
                    1.0, sum(float(entry["weight"]) for entry in first), places=6
                )
                self.assertEqual(
                    sorted(
                        (float(entry["weight"]) for entry in first), reverse=True
                    ),
                    [float(entry["weight"]) for entry in first],
                )
                for entry in first:
                    self.assertRegex(str(entry["hex"]), r"^#[0-9A-F]{6}$")
                    self.assertGreater(float(entry["weight"]), 0)
                    self.assertNotEqual("#FF00FF", entry["hex"])

    def test_applies_exif_orientation_before_masking(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            final = Image.new("RGB", (60, 90))
            draw_colors = [
                (20, 40, 80),
                (40, 70, 120),
                (70, 110, 160),
                (110, 150, 190),
                (160, 190, 215),
                (210, 225, 235),
            ]
            for y in range(final.height):
                color = draw_colors[min(5, y // 15)]
                for x in range(final.width):
                    final.putpixel((x, y), color)

            normalized_path = directory / "normalized.png"
            final.save(normalized_path, "PNG")
            raw = final.rotate(90, expand=True)
            exif = raw.getexif()
            exif[274] = 6
            raw_path = directory / "raw.tiff"
            raw.save(raw_path, "TIFF", exif=exif)

            with Image.open(raw_path) as saved_raw:
                transposed = ImageOps.exif_transpose(saved_raw).convert("RGB")
                self.assertEqual(final.tobytes(), transposed.tobytes())

            config = {
                "schema_version": 1,
                "coordinate_space": "normalized",
                "minimum_included_fraction": 0.1,
                "minimum_included_pixels": 1,
                "include_polygon": [[0, 0], [1, 0], [1, 1], [0, 1]],
                "exclude_polygons": [],
                "exclude_rectangles": [],
            }
            config_path = directory / "mask.json"
            config_path.write_text(json.dumps(config), encoding="utf-8")

            expected = extract_palette.extract(
                normalized_path, mask_config_path=config_path
            )
            actual = extract_palette.extract(raw_path, mask_config_path=config_path)
            self.assertEqual(expected, actual)

    def test_downsamples_before_quantization(self) -> None:
        config = extract_palette.load_mask_config(FIXTURE_DIRECTORY / "mask.json")
        source = Image.new("RGB", (1200, 800))
        draw = ImageDraw.Draw(source)
        colors = [
            (20, 50, 90),
            (50, 90, 130),
            (80, 120, 160),
            (110, 150, 185),
            (150, 185, 210),
            (200, 220, 235),
        ]
        for index, color in enumerate(colors):
            left = index * source.width // len(colors)
            right = (index + 1) * source.width // len(colors) - 1
            draw.rectangle((left, 0, right, source.height - 1), fill=color)

        _, stats = extract_palette.extract_from_image(
            source, config, analysis_dimension=180
        )
        self.assertEqual(180, stats["analysis_width"])
        self.assertEqual(120, stats["analysis_height"])
        self.assertLess(int(stats["included_pixels"]), 180 * 120)

    def test_fails_clearly_when_fewer_than_three_colors_exist(self) -> None:
        config = extract_palette.load_mask_config(FIXTURE_DIRECTORY / "mask.json")
        source = Image.new("RGB", (100, 100), (40, 80, 120))
        with self.assertRaisesRegex(ValueError, "ingest API requires 3-10"):
            extract_palette.extract_from_image(source, config)

    def test_palette_preview_is_decodable(self) -> None:
        config = extract_palette.load_mask_config(FIXTURE_DIRECTORY / "mask.json")
        with Image.open(FIXTURE_DIRECTORY / "sunset.ppm") as source:
            source.load()
            palette, _ = extract_palette.extract_from_image(source, config)
            preview = extract_palette.render_palette_preview(source, config, palette)

        self.assertEqual(6, preview.width)
        self.assertGreater(preview.height, 4)
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "preview.jpg"
            preview.save(output, "JPEG")
            with Image.open(output) as verified:
                verified.load()
                self.assertEqual(preview.size, verified.size)

    def test_validate_palette_rejects_lowercase_hex(self) -> None:
        with self.assertRaisesRegex(ValueError, "uppercase"):
            extract_palette.validate_palette(
                [
                    {"hex": "#aabbcc", "weight": 0.4},
                    {"hex": "#DDEEFF", "weight": 0.3},
                    {"hex": "#112233", "weight": 0.3},
                ]
            )


if __name__ == "__main__":
    unittest.main()
