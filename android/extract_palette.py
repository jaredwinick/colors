#!/data/data/com.termux/files/usr/bin/python
"""Extract a compact, weighted palette from a sky photograph."""

from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageOps


def extract(path: Path, colors: int = 7) -> list[dict[str, float | str]]:
    with Image.open(path) as source:
        image = ImageOps.exif_transpose(source).convert("RGB")
        # The upper 82% avoids a thin mount/window edge in typical installations.
        image = image.crop((0, 0, image.width, max(1, int(image.height * 0.82))))
        image.thumbnail((180, 180))
        quantized = image.quantize(
            colors=colors,
            method=Image.Quantize.MEDIANCUT,
            dither=Image.Dither.NONE,
        )
        raw = quantized.getcolors(maxcolors=colors) or []
        palette = quantized.getpalette() or []

    total = sum(count for count, _ in raw) or 1
    weighted: list[tuple[int, str, float]] = []
    for count, index in raw:
        red, green, blue = palette[index * 3 : index * 3 + 3]
        hex_color = f"#{red:02X}{green:02X}{blue:02X}"
        luminance = round(0.2126 * red + 0.7152 * green + 0.0722 * blue)
        weighted.append((luminance, hex_color, count / total))

    # Light-to-dark order makes adjacent captures easier to compare visually.
    weighted.sort(reverse=True)
    return [
        {"hex": hex_color, "weight": round(weight, 4)}
        for _, hex_color, weight in weighted
    ]


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: extract_palette.py IMAGE")
    print(json.dumps(extract(Path(sys.argv[1])), separators=(",", ":")))
