#!/data/data/com.termux/files/usr/bin/python
"""Extract an API-compatible weighted palette from the masked sky."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import time
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageOps


SCRIPT_DIRECTORY = Path(__file__).resolve().parent
if str(SCRIPT_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIRECTORY))

from sky_mask import (  # noqa: E402
    MaskConfig,
    build_sky_mask,
    load_mask_config,
    render_mask_preview,
)


DEFAULT_COLORS = 6
DEFAULT_ANALYSIS_DIMENSION = 180
MIN_COLORS = 3
MAX_COLORS = 10
HEX_COLOR = re.compile(r"^#[0-9A-F]{6}$")


def palette_color_count(value: str) -> int:
    parsed = int(value)
    if not MIN_COLORS <= parsed <= MAX_COLORS:
        raise argparse.ArgumentTypeError(
            f"must be between {MIN_COLORS} and {MAX_COLORS}"
        )
    return parsed


def positive_integer(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def _normalize_weights(
    weighted_colors: list[tuple[int, str]], total: int
) -> list[dict[str, float | str]]:
    palette: list[dict[str, float | str]] = []
    for count, hex_color in weighted_colors:
        palette.append({"hex": hex_color, "weight": round(count / total, 6)})

    rounding_difference = round(
        1.0 - sum(float(entry["weight"]) for entry in palette), 6
    )
    palette[0]["weight"] = round(
        float(palette[0]["weight"]) + rounding_difference, 6
    )
    return palette


def validate_palette(value: Any) -> list[dict[str, float | str]]:
    """Validate the ingest API constraints and return normalized weights."""

    if not isinstance(value, list) or not MIN_COLORS <= len(value) <= MAX_COLORS:
        raise ValueError(f"palette must contain {MIN_COLORS}-{MAX_COLORS} colors")

    validated: list[tuple[float, str]] = []
    for index, entry in enumerate(value):
        if not isinstance(entry, dict):
            raise ValueError(f"palette[{index}] must be an object")
        hex_color = entry.get("hex")
        weight = entry.get("weight")
        if not isinstance(hex_color, str) or not HEX_COLOR.fullmatch(hex_color):
            raise ValueError(f"palette[{index}].hex must be uppercase #RRGGBB")
        if (
            isinstance(weight, bool)
            or not isinstance(weight, (int, float))
            or not math.isfinite(float(weight))
            or float(weight) <= 0
        ):
            raise ValueError(f"palette[{index}].weight must be positive and finite")
        validated.append((float(weight), hex_color))

    total = sum(weight for weight, _ in validated)
    normalized = [
        {"hex": hex_color, "weight": round(weight / total, 6)}
        for weight, hex_color in validated
    ]
    rounding_difference = round(
        1.0 - sum(float(entry["weight"]) for entry in normalized), 6
    )
    normalized[0]["weight"] = round(
        float(normalized[0]["weight"]) + rounding_difference, 6
    )
    normalized.sort(key=lambda entry: (-float(entry["weight"]), str(entry["hex"])))
    return normalized


def extract_from_image(
    image: Image.Image,
    mask_config: MaskConfig,
    *,
    colors: int = DEFAULT_COLORS,
    analysis_dimension: int = DEFAULT_ANALYSIS_DIMENSION,
) -> tuple[list[dict[str, float | str]], dict[str, int | float]]:
    """Extract a deterministic palette and analysis statistics from one image."""

    if not MIN_COLORS <= colors <= MAX_COLORS:
        raise ValueError(f"colors must be between {MIN_COLORS} and {MAX_COLORS}")
    if analysis_dimension < 1:
        raise ValueError("analysis_dimension must be positive")

    normalized = ImageOps.exif_transpose(image).convert("RGB")
    source_width, source_height = normalized.size
    analysis = normalized.copy()
    analysis.thumbnail(
        (analysis_dimension, analysis_dimension), Image.Resampling.LANCZOS
    )
    mask = build_sky_mask(analysis.size, mask_config)

    analysis_bytes = analysis.tobytes()
    included_bytes = bytearray()
    for pixel_index, included in enumerate(mask.tobytes()):
        if included == 255:
            offset = pixel_index * 3
            included_bytes.extend(analysis_bytes[offset : offset + 3])

    included_pixel_count = len(included_bytes) // 3
    if included_pixel_count == 0:
        raise ValueError("sky mask did not include any analysis pixels")

    samples = Image.frombytes("RGB", (included_pixel_count, 1), bytes(included_bytes))
    quantized = samples.quantize(
        colors=colors,
        method=Image.Quantize.MEDIANCUT,
        dither=Image.Dither.NONE,
    )
    raw_colors = quantized.getcolors(maxcolors=colors) or []
    color_table = quantized.getpalette() or []

    merged_counts: dict[str, int] = {}
    for count, color_index in raw_colors:
        offset = color_index * 3
        red, green, blue = color_table[offset : offset + 3]
        hex_color = f"#{red:02X}{green:02X}{blue:02X}"
        merged_counts[hex_color] = merged_counts.get(hex_color, 0) + count

    weighted_colors = sorted(
        ((count, hex_color) for hex_color, count in merged_counts.items()),
        key=lambda entry: (-entry[0], entry[1]),
    )
    if not MIN_COLORS <= len(weighted_colors) <= MAX_COLORS:
        raise ValueError(
            f"quantization produced {len(weighted_colors)} colors; "
            f"the ingest API requires {MIN_COLORS}-{MAX_COLORS}"
        )

    palette = validate_palette(
        _normalize_weights(weighted_colors, included_pixel_count)
    )
    stats: dict[str, int | float] = {
        "source_width": source_width,
        "source_height": source_height,
        "analysis_width": analysis.width,
        "analysis_height": analysis.height,
        "included_pixels": included_pixel_count,
        "palette_colors": len(palette),
    }
    return palette, stats


def extract_with_stats(
    path: Path,
    *,
    mask_config_path: Path | None = None,
    colors: int = DEFAULT_COLORS,
    analysis_dimension: int = DEFAULT_ANALYSIS_DIMENSION,
) -> tuple[list[dict[str, float | str]], dict[str, int | float]]:
    config_path = mask_config_path or SCRIPT_DIRECTORY / "sky-mask.json"
    mask_config = load_mask_config(config_path)
    with Image.open(path) as source:
        source.load()
        return extract_from_image(
            source,
            mask_config,
            colors=colors,
            analysis_dimension=analysis_dimension,
        )


def extract(
    path: Path,
    colors: int = DEFAULT_COLORS,
    *,
    mask_config_path: Path | None = None,
    analysis_dimension: int = DEFAULT_ANALYSIS_DIMENSION,
) -> list[dict[str, float | str]]:
    """Compatibility wrapper returning only the API palette array."""

    palette, _ = extract_with_stats(
        path,
        mask_config_path=mask_config_path,
        colors=colors,
        analysis_dimension=analysis_dimension,
    )
    return palette


def render_palette_preview(
    image: Image.Image,
    mask_config: MaskConfig,
    palette: list[dict[str, float | str]],
) -> Image.Image:
    normalized = ImageOps.exif_transpose(image).convert("RGB")
    mask = build_sky_mask(normalized.size, mask_config)
    overlay = render_mask_preview(normalized, mask, mask_config)

    footer_height = max(72, round(normalized.height * 0.08))
    preview = Image.new("RGB", (normalized.width, normalized.height + footer_height), "black")
    preview.paste(overlay, (0, 0))
    draw = ImageDraw.Draw(preview)

    left = 0
    for index, entry in enumerate(palette):
        if index == len(palette) - 1:
            right = normalized.width
        else:
            right = round(left + normalized.width * float(entry["weight"]))
        right = max(left + 1, min(normalized.width, right))
        draw.rectangle(
            (left, normalized.height, right - 1, preview.height - 1),
            fill=str(entry["hex"]),
        )
        if right - left >= 100:
            label = f"{entry['hex']} {float(entry['weight']) * 100:.1f}%"
            draw.text(
                (left + 8, normalized.height + 8),
                label,
                fill=(255, 255, 255),
                stroke_width=2,
                stroke_fill=(0, 0, 0),
            )
        left = right
    return preview


def _maximum_resident_memory_kib() -> int | None:
    try:
        import resource
    except ImportError:
        return None
    return int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument(
        "--mask-config", type=Path, default=SCRIPT_DIRECTORY / "sky-mask.json"
    )
    parser.add_argument("--colors", type=palette_color_count, default=DEFAULT_COLORS)
    parser.add_argument(
        "--analysis-dimension",
        type=positive_integer,
        default=DEFAULT_ANALYSIS_DIMENSION,
    )
    parser.add_argument("--preview", type=Path)
    parser.add_argument("--benchmark", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    started_at = time.perf_counter()
    palette, stats = extract_with_stats(
        args.image,
        mask_config_path=args.mask_config,
        colors=args.colors,
        analysis_dimension=args.analysis_dimension,
    )
    elapsed_seconds = time.perf_counter() - started_at
    metrics: dict[str, int | float | None] | None = None
    if args.benchmark:
        metrics = {
            **stats,
            "elapsed_seconds": round(elapsed_seconds, 4),
            "max_rss_kib": _maximum_resident_memory_kib(),
        }

    if args.preview:
        config = load_mask_config(args.mask_config)
        with Image.open(args.image) as source:
            source.load()
            preview = render_palette_preview(source, config, palette)
        args.preview.parent.mkdir(parents=True, exist_ok=True)
        preview.save(args.preview, format="JPEG", quality=90, optimize=True)

    if metrics is not None:
        print(
            json.dumps(metrics, separators=(",", ":"), sort_keys=True),
            file=sys.stderr,
        )

    print(json.dumps(palette, separators=(",", ":")))


if __name__ == "__main__":
    main()
