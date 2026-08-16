#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${COLORS_DATA_DIR:-$HOME/.local/share/colors}"
OUTPUT_DIR="${COLORS_PALETTE_OUTPUT_DIR:-$HOME/storage/shared}"

usage() {
  cat <<'EOF'
Usage: palette_smoke_test.sh [CAPTURE.jpg]

With no argument, the newest normalized JPEG under the Colors capture directory
is used. Results and a preview are written to shared storage.
EOF
}

if (( $# > 1 )); then
  usage >&2
  exit 2
fi

if (( $# == 1 )); then
  image_path="$1"
else
  shopt -s nullglob
  captures=("$DATA_DIR"/captures/*.jpg)
  if (( ${#captures[@]} == 0 )); then
    printf 'No normalized captures found under %s/captures\n' "$DATA_DIR" >&2
    printf 'Run ~/colors/capture_image.sh first.\n' >&2
    exit 1
  fi
  image_path="${captures[0]}"
  for candidate in "${captures[@]:1}"; do
    if [[ "$candidate" -nt "$image_path" ]]; then
      image_path="$candidate"
    fi
  done
fi

if [[ ! -s "$image_path" ]]; then
  printf 'Capture is missing or empty: %s\n' "$image_path" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
palette_path="$OUTPUT_DIR/colors-palette-$stamp.json"
benchmark_path="$OUTPUT_DIR/colors-palette-benchmark-$stamp.json"
preview_path="$OUTPUT_DIR/colors-palette-preview-$stamp.jpg"

printf 'Analyzing %s\n' "$image_path" >&2
python "$SCRIPT_DIR/extract_palette.py" "$image_path" \
  --mask-config "$SCRIPT_DIR/sky-mask.json" \
  --preview "$preview_path" \
  --benchmark \
  > "$palette_path" \
  2> "$benchmark_path"

printf '\nPalette:\n'
cat "$palette_path"
printf '\nBenchmark:\n'
cat "$benchmark_path"
printf '\nPreview: %s\n' "$preview_path"
printf 'Open the preview in Gallery and verify the swatches represent the cyan sky.\n'
