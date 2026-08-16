from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

from PIL import Image


MODULE_PATH = Path(__file__).parents[1] / "android" / "sky_mask.py"
SPEC = importlib.util.spec_from_file_location("sky_mask", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"could not load {MODULE_PATH}")
sky_mask = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sky_mask)


def config(**overrides: object) -> dict[str, object]:
    value: dict[str, object] = {
        "schema_version": 1,
        "coordinate_space": "normalized",
        "minimum_included_fraction": 0.1,
        "minimum_included_pixels": 1,
        "include_polygon": [[0, 0], [1, 0], [1, 0.8], [0, 0.8]],
        "exclude_polygons": [],
        "exclude_rectangles": [],
    }
    value.update(overrides)
    return value


class SkyMaskTests(unittest.TestCase):
    def test_normalized_mask_stays_aligned_when_resized(self) -> None:
        validated = sky_mask.validate_mask_config(config())
        small = sky_mask.build_sky_mask((100, 200), validated)
        large = sky_mask.build_sky_mask((400, 800), validated)

        small_fraction = sky_mask.mask_statistics(small)["included_fraction"]
        large_fraction = sky_mask.mask_statistics(large)["included_fraction"]
        self.assertAlmostEqual(small_fraction, large_fraction, places=2)
        self.assertEqual(255, small.getpixel((50, 100)))
        self.assertEqual(0, small.getpixel((50, 190)))
        self.assertEqual(255, large.getpixel((200, 400)))
        self.assertEqual(0, large.getpixel((200, 760)))

    def test_exclusions_are_removed_from_inclusion_polygon(self) -> None:
        validated = sky_mask.validate_mask_config(
            config(
                exclude_polygons=[[[0.1, 0.1], [0.3, 0.1], [0.3, 0.3], [0.1, 0.3]]],
                exclude_rectangles=[[0.6, 0.1, 0.8, 0.3]],
            )
        )
        mask = sky_mask.build_sky_mask((100, 100), validated)
        self.assertEqual(0, mask.getpixel((20, 20)))
        self.assertEqual(0, mask.getpixel((70, 20)))
        self.assertEqual(255, mask.getpixel((50, 20)))

    def test_rejects_too_few_included_pixels(self) -> None:
        validated = sky_mask.validate_mask_config(
            config(minimum_included_pixels=10_000)
        )
        with self.assertRaisesRegex(ValueError, "minimum is 10000"):
            sky_mask.build_sky_mask((20, 20), validated)

    def test_rejects_out_of_range_coordinates(self) -> None:
        with self.assertRaisesRegex(ValueError, "between 0 and 1"):
            sky_mask.validate_mask_config(
                config(include_polygon=[[0, 0], [1.1, 0], [0, 1]])
            )

    def test_preview_is_decodable_and_preserves_dimensions(self) -> None:
        validated = sky_mask.validate_mask_config(config())
        source = Image.new("RGB", (120, 160), (100, 150, 200))
        mask = sky_mask.build_sky_mask(source.size, validated)
        preview = sky_mask.render_mask_preview(
            source, mask, validated, calibration_grid=True
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "preview.jpg"
            preview.save(output, "JPEG")
            with Image.open(output) as verified:
                verified.load()
                self.assertEqual((120, 160), verified.size)


if __name__ == "__main__":
    unittest.main()
