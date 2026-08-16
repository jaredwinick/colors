#!/data/data/com.termux/files/usr/bin/python
"""Validate, rasterize, and preview the fixed sky sampling mask."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Sequence

from PIL import Image, ImageDraw


Point = tuple[float, float]
MaskConfig = dict[str, Any]


def _normalized_number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{label} must be a number")
    number = float(value)
    if not 0.0 <= number <= 1.0:
        raise ValueError(f"{label} must be between 0 and 1")
    return number


def _polygon(value: Any, label: str) -> list[Point]:
    if not isinstance(value, list) or len(value) < 3:
        raise ValueError(f"{label} must contain at least three points")
    points: list[Point] = []
    for index, point in enumerate(value):
        if not isinstance(point, list) or len(point) != 2:
            raise ValueError(f"{label}[{index}] must be an [x, y] pair")
        points.append(
            (
                _normalized_number(point[0], f"{label}[{index}][0]"),
                _normalized_number(point[1], f"{label}[{index}][1]"),
            )
        )
    return points


def validate_mask_config(value: Any) -> MaskConfig:
    """Return a normalized mask configuration or raise a useful error."""

    if not isinstance(value, dict):
        raise ValueError("mask config must be a JSON object")
    if value.get("schema_version") != 1:
        raise ValueError("schema_version must be 1")
    if value.get("coordinate_space") != "normalized":
        raise ValueError("coordinate_space must be normalized")

    minimum_fraction = _normalized_number(
        value.get("minimum_included_fraction"), "minimum_included_fraction"
    )
    minimum_pixels = value.get("minimum_included_pixels")
    if isinstance(minimum_pixels, bool) or not isinstance(minimum_pixels, int):
        raise ValueError("minimum_included_pixels must be an integer")
    if minimum_pixels < 1:
        raise ValueError("minimum_included_pixels must be positive")

    include_polygon = _polygon(value.get("include_polygon"), "include_polygon")

    raw_exclude_polygons = value.get("exclude_polygons", [])
    if not isinstance(raw_exclude_polygons, list):
        raise ValueError("exclude_polygons must be an array")
    exclude_polygons = [
        _polygon(polygon, f"exclude_polygons[{index}]")
        for index, polygon in enumerate(raw_exclude_polygons)
    ]

    raw_rectangles = value.get("exclude_rectangles", [])
    if not isinstance(raw_rectangles, list):
        raise ValueError("exclude_rectangles must be an array")
    exclude_rectangles: list[tuple[float, float, float, float]] = []
    for index, rectangle in enumerate(raw_rectangles):
        label = f"exclude_rectangles[{index}]"
        if not isinstance(rectangle, list) or len(rectangle) != 4:
            raise ValueError(f"{label} must be [left, top, right, bottom]")
        left, top, right, bottom = (
            _normalized_number(component, f"{label}[{component_index}]")
            for component_index, component in enumerate(rectangle)
        )
        if left >= right or top >= bottom:
            raise ValueError(f"{label} must have positive width and height")
        exclude_rectangles.append((left, top, right, bottom))

    return {
        "schema_version": 1,
        "coordinate_space": "normalized",
        "minimum_included_fraction": minimum_fraction,
        "minimum_included_pixels": minimum_pixels,
        "include_polygon": include_polygon,
        "exclude_polygons": exclude_polygons,
        "exclude_rectangles": exclude_rectangles,
    }


def load_mask_config(path: Path) -> MaskConfig:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid JSON in {path}: {error}") from error
    return validate_mask_config(value)


def _pixel_point(point: Point, size: tuple[int, int]) -> tuple[int, int]:
    width, height = size
    return (round(point[0] * (width - 1)), round(point[1] * (height - 1)))


def _pixel_polygon(
    polygon: Sequence[Point], size: tuple[int, int]
) -> list[tuple[int, int]]:
    return [_pixel_point(point, size) for point in polygon]


def build_sky_mask(size: tuple[int, int], config: MaskConfig) -> Image.Image:
    """Rasterize a validated config into an L image: 255 sky, 0 excluded."""

    width, height = size
    if width < 1 or height < 1:
        raise ValueError("image dimensions must be positive")

    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    draw.polygon(_pixel_polygon(config["include_polygon"], size), fill=255)
    for polygon in config["exclude_polygons"]:
        draw.polygon(_pixel_polygon(polygon, size), fill=0)
    for left, top, right, bottom in config["exclude_rectangles"]:
        draw.rectangle(
            [
                _pixel_point((left, top), size),
                _pixel_point((right, bottom), size),
            ],
            fill=0,
        )

    included_pixels = mask.histogram()[255]
    total_pixels = width * height
    included_fraction = included_pixels / total_pixels
    if included_pixels < config["minimum_included_pixels"]:
        raise ValueError(
            f"mask includes {included_pixels} pixels; minimum is "
            f"{config['minimum_included_pixels']}"
        )
    if included_fraction < config["minimum_included_fraction"]:
        raise ValueError(
            f"mask includes {included_fraction:.4f} of the image; minimum is "
            f"{config['minimum_included_fraction']:.4f}"
        )
    return mask


def mask_statistics(mask: Image.Image) -> dict[str, int | float]:
    included_pixels = mask.histogram()[255]
    total_pixels = mask.width * mask.height
    return {
        "width": mask.width,
        "height": mask.height,
        "included_pixels": included_pixels,
        "excluded_pixels": total_pixels - included_pixels,
        "included_fraction": round(included_pixels / total_pixels, 6),
    }


def render_mask_preview(
    image: Image.Image,
    mask: Image.Image,
    config: MaskConfig,
    *,
    calibration_grid: bool = False,
) -> Image.Image:
    """Overlay sampled sky in cyan and excluded pixels in red."""

    source = image.convert("RGB")
    sampled_tint = Image.blend(source, Image.new("RGB", source.size, (0, 210, 225)), 0.16)
    excluded_tint = Image.blend(source, Image.new("RGB", source.size, (210, 25, 40)), 0.58)
    preview = Image.composite(sampled_tint, excluded_tint, mask)

    draw = ImageDraw.Draw(preview)
    line_width = max(2, round(max(source.size) / 480))
    include_points = _pixel_polygon(config["include_polygon"], source.size)

    if calibration_grid:
        grid_color = (235, 235, 235)
        for step in range(1, 10):
            position = step / 10
            x, _ = _pixel_point((position, 0), source.size)
            _, y = _pixel_point((0, position), source.size)
            draw.line((x, 0, x, source.height - 1), fill=grid_color, width=1)
            draw.line((0, y, source.width - 1, y), fill=grid_color, width=1)
            draw.text((x + 3, 3), f"x={position:.1f}", fill=grid_color)
            draw.text((3, y + 3), f"y={position:.1f}", fill=grid_color)

    draw.line(include_points + [include_points[0]], fill=(0, 255, 255), width=line_width)
    if calibration_grid:
        point_radius = max(4, line_width + 2)
        for index, (x, y) in enumerate(include_points[2:], start=2):
            draw.ellipse(
                (
                    x - point_radius,
                    y - point_radius,
                    x + point_radius,
                    y + point_radius,
                ),
                fill=(255, 230, 0),
                outline=(0, 0, 0),
                width=1,
            )
            draw.text((x + point_radius + 2, y - 12), str(index), fill=(0, 0, 0))
    for polygon in config["exclude_polygons"]:
        points = _pixel_polygon(polygon, source.size)
        draw.line(points + [points[0]], fill=(255, 230, 0), width=line_width)
    for left, top, right, bottom in config["exclude_rectangles"]:
        draw.rectangle(
            [
                _pixel_point((left, top), source.size),
                _pixel_point((right, bottom), source.size),
            ],
            outline=(255, 230, 0),
            width=line_width,
        )

    padding = max(8, round(max(source.size) / 160))
    legend_height = 90 if calibration_grid else 64
    draw.rectangle(
        (padding, padding, padding + 320, padding + legend_height), fill=(0, 0, 0)
    )
    draw.text((padding + 10, padding + 8), "CYAN: sampled sky", fill=(0, 255, 255))
    draw.text((padding + 10, padding + 34), "RED: excluded", fill=(255, 120, 125))
    if calibration_grid:
        draw.text(
            (padding + 10, padding + 60),
            "YELLOW: include_polygon point",
            fill=(255, 230, 0),
        )
    return preview


def _validate_command(args: argparse.Namespace) -> None:
    config = load_mask_config(args.config)
    mask = build_sky_mask((args.width, args.height), config)
    print(json.dumps(mask_statistics(mask), separators=(",", ":"), sort_keys=True))


def _preview_command(args: argparse.Namespace) -> None:
    config = load_mask_config(args.config)
    with Image.open(args.image) as source:
        source.load()
        image = source.convert("RGB")
    mask = build_sky_mask(image.size, config)
    preview = render_mask_preview(
        image, mask, config, calibration_grid=args.calibration_grid
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    preview.save(args.output, format="JPEG", quality=90, optimize=True)
    result = {"output": str(args.output), **mask_statistics(mask)}
    print(json.dumps(result, separators=(",", ":"), sort_keys=True))


def positive_integer(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(required=True)

    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--config", required=True, type=Path)
    validate_parser.add_argument("--width", required=True, type=positive_integer)
    validate_parser.add_argument("--height", required=True, type=positive_integer)
    validate_parser.set_defaults(run=_validate_command)

    preview_parser = subparsers.add_parser("preview")
    preview_parser.add_argument("--config", required=True, type=Path)
    preview_parser.add_argument("--image", required=True, type=Path)
    preview_parser.add_argument("--output", required=True, type=Path)
    preview_parser.add_argument("--calibration-grid", action="store_true")
    preview_parser.set_defaults(run=_preview_command)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
