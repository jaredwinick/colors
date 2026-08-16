# Extracting and checking a sky palette

The palette extractor uses only Python and Pillow. It corrects EXIF orientation,
shrinks the image to a predictable analysis size, applies `sky-mask.json`, and
quantizes only the included sky pixels. The archived source JPEG is never
changed.

The default output contains six colors sorted from greatest to least weight:

```json
[
  {"hex":"#596068","weight":0.24},
  {"hex":"#747C84","weight":0.2},
  {"hex":"#393B3F","weight":0.18},
  {"hex":"#878D92","weight":0.15},
  {"hex":"#989DA1","weight":0.13},
  {"hex":"#B6BAC0","weight":0.1}
]
```

Hex values are uppercase and weights are positive, total 1.0, and are accepted
directly by the ingest API. If an image cannot produce at least three distinct
colors, the extractor stops with a clear error rather than uploading an invalid
palette.

## Install or update the phone files

Quick Share these four files to the phone:

- `extract_palette.py`
- `sky_mask.py`
- `sky-mask.json`
- `palette_smoke_test.sh`

Then copy them into the existing Colors directory:

```sh
mkdir -p ~/colors
cp ~/storage/downloads/extract_palette.py ~/colors/
cp ~/storage/downloads/sky_mask.py ~/colors/
cp ~/storage/downloads/sky-mask.json ~/colors/
cp ~/storage/downloads/palette_smoke_test.sh ~/colors/
chmod 700 ~/colors/palette_smoke_test.sh
```

Pillow should already be installed from the capture setup. If needed, install
it with `pkg install python python-pillow -y`.

## Run the device acceptance check

First create a current normalized capture, then run the smoke test:

```sh
~/colors/capture_image.sh
~/colors/palette_smoke_test.sh
```

The smoke test automatically selects the newest completed JPEG and prints its
palette, benchmark, and preview path. You can also pass a specific capture path
as its only argument.

Open the reported preview in Gallery. The image uses the same cyan/red mask
overlay as calibration, with weighted palette swatches along the bottom. Confirm
that the swatches look representative of the included sky and that no roof or
tree color is conspicuous.

The benchmark JSON reports elapsed extraction time, peak process memory in KiB,
source dimensions, analysis dimensions, included pixel count, and palette size.
`max_rss_kib` is available on Android/Linux; it is `null` on platforms that do
not expose that measurement.

## Optional tuning

The defaults are six colors and a 180-pixel longest analysis edge. Both are
configurable for experiments:

```sh
python ~/colors/extract_palette.py CAPTURE_IMAGE_PATH \
  --colors 8 \
  --analysis-dimension 240
```

`--colors` must be between 3 and 10. A larger analysis dimension retains more
fine detail but uses more time and memory. Keep the defaults unless comparisons
across several daylight, sunset, overcast, and night captures show a meaningful
benefit.

## Galaxy S9+ baseline

The default settings were accepted on the dedicated Galaxy S9+ running Android
9 on 2026-08-16. The generated overlay and palette swatches were visually
approved. The device reported:

- Normalized source: 1440 x 1920 pixels
- Analysis image: 135 x 180 pixels
- Included sky samples: 21,573 pixels
- Palette size: 6 colors
- Extraction time: 0.116 seconds
- Peak process memory: 58,824 KiB (about 57.4 MiB)

The measured memory is the peak for the complete Python/Pillow process, not only
the palette data. These numbers are a diagnostic baseline: a future result does
not need to match exactly, but a large sustained increase is worth investigating.
