#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

# Capture and normalize one unmasked photograph. The resulting JPEG and JSON
# sidecar are the durable handoff to masking, palette extraction, and upload.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_DIR="${COLORS_CONFIG_DIR:-$HOME/.config/colors}"
DATA_DIR="${COLORS_DATA_DIR:-$HOME/.local/share/colors}"
CAPTURE_DIR="${COLORS_CAPTURE_DIR:-$DATA_DIR/captures}"
TEMP_DIR="${COLORS_TEMP_DIR:-$DATA_DIR/tmp}"
NORMALIZER="${COLORS_NORMALIZER:-$SCRIPT_DIR/normalize_capture.py}"
PYTHON_BIN="${COLORS_PYTHON:-python}"
MAX_IMAGE_BYTES="${COLORS_MAX_IMAGE_BYTES:-12582912}"

raw_temp=""
normalized_temp=""
metadata_temp=""
final_image=""
final_metadata=""
committed="false"
SECONDS=0

usage() {
  cat <<'EOF'
Usage: capture_image.sh

Capture one photograph, correct its orientation, resize it, and write a
validated JPEG plus JSON metadata under ~/.local/share/colors/captures.

Configuration files under ~/.config/colors:
  camera-id       Camera ID reported by termux-camera-info (default: 0)
  max-dimension   Longest output edge in pixels (default: 1920)
  jpeg-quality    Pillow JPEG quality from 1 to 95 (default: 85)

The same values can be overridden with COLORS_CAMERA_ID,
COLORS_MAX_DIMENSION, and COLORS_JPEG_QUALITY.
EOF
}

cleanup() {
  [[ -z "$raw_temp" || ! -e "$raw_temp" ]] || rm -f -- "$raw_temp"
  [[ -z "$normalized_temp" || ! -e "$normalized_temp" ]] || rm -f -- "$normalized_temp"
  [[ -z "$metadata_temp" || ! -e "$metadata_temp" ]] || rm -f -- "$metadata_temp"

  # The JSON sidecar is the commit marker. Do not leave a final JPEG behind if
  # the second atomic move fails.
  if [[ "$committed" != "true" && -n "$final_image" && -e "$final_image" && ! -e "$final_metadata" ]]; then
    rm -f -- "$final_image"
  fi
}

trap cleanup EXIT

read_setting() {
  local environment_value="$1"
  local config_file="$2"
  local default_value="$3"
  local value

  if [[ -n "$environment_value" ]]; then
    value="$environment_value"
  elif [[ -s "$config_file" ]]; then
    value="$(tr -d '[:space:]' < "$config_file")"
  else
    value="$default_value"
  fi

  printf '%s' "$value"
}

require_command() {
  local command_name="$1"
  local install_hint="$2"

  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf 'Missing required command: %s\nInstall it with: %s\n' \
      "$command_name" "$install_hint" >&2
    exit 1
  fi
}

progress() {
  printf '[colors +%ss] %s\n' "$SECONDS" "$1" >&2
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || "${1:-}" == "help" ]]; then
  usage
  exit 0
elif (( $# != 0 )); then
  printf 'This command does not accept arguments.\n\n' >&2
  usage >&2
  exit 2
fi

require_command termux-camera-photo "pkg install termux-api -y"
require_command "$PYTHON_BIN" "pkg install python python-pillow -y"

if [[ ! -f "$NORMALIZER" ]]; then
  printf 'Normalizer not found: %s\n' "$NORMALIZER" >&2
  exit 1
fi

camera_id="$(read_setting "${COLORS_CAMERA_ID:-}" "$CONFIG_DIR/camera-id" "0")"
max_dimension="$(read_setting "${COLORS_MAX_DIMENSION:-}" "$CONFIG_DIR/max-dimension" "1920")"
jpeg_quality="$(read_setting "${COLORS_JPEG_QUALITY:-}" "$CONFIG_DIR/jpeg-quality" "85")"

if [[ ! "$camera_id" =~ ^[0-9]+$ ]]; then
  printf 'Camera ID must be a non-negative integer: %s\n' "$camera_id" >&2
  exit 1
fi
if [[ ! "$max_dimension" =~ ^[0-9]+$ ]] || (( max_dimension < 1 )); then
  printf 'Maximum dimension must be a positive integer: %s\n' "$max_dimension" >&2
  exit 1
fi
if [[ ! "$jpeg_quality" =~ ^[0-9]+$ ]] || (( jpeg_quality < 1 || jpeg_quality > 95 )); then
  printf 'JPEG quality must be between 1 and 95: %s\n' "$jpeg_quality" >&2
  exit 1
fi
if [[ ! "$MAX_IMAGE_BYTES" =~ ^[0-9]+$ ]] || (( MAX_IMAGE_BYTES < 1 )); then
  printf 'Maximum image bytes must be a positive integer: %s\n' "$MAX_IMAGE_BYTES" >&2
  exit 1
fi

umask 077
mkdir -p "$CONFIG_DIR" "$CAPTURE_DIR" "$TEMP_DIR"

attempt_stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
attempt_id="${attempt_stamp}-$$-${RANDOM}"
raw_temp="$TEMP_DIR/${attempt_id}.raw.jpg"

progress "Capturing camera $camera_id..."
if ! termux-camera-photo -c "$camera_id" "$raw_temp"; then
  printf 'Camera command failed for camera ID %s.\n' "$camera_id" >&2
  exit 1
fi
if [[ ! -s "$raw_temp" ]]; then
  printf 'Camera command produced an empty file: %s\n' "$raw_temp" >&2
  exit 1
fi

# Record this immediately after a successful, non-empty camera result.
captured_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
capture_stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
capture_id="${capture_stamp}-${RANDOM}${RANDOM}"
raw_bytes="$(wc -c < "$raw_temp")"
progress "Captured $raw_bytes bytes; normalizing to a ${max_dimension}px JPEG..."

final_image="$CAPTURE_DIR/$capture_id.jpg"
final_metadata="$CAPTURE_DIR/$capture_id.json"
normalized_temp="$TEMP_DIR/$capture_id.normalized.jpg"
metadata_temp="$TEMP_DIR/$capture_id.json"

if [[ -e "$final_image" || -e "$final_metadata" ]]; then
  printf 'Capture destination already exists for ID %s.\n' "$capture_id" >&2
  exit 1
fi

"$PYTHON_BIN" "$NORMALIZER" \
  --input "$raw_temp" \
  --output "$normalized_temp" \
  --metadata-output "$metadata_temp" \
  --capture-id "$capture_id" \
  --captured-at "$captured_at" \
  --camera-id "$camera_id" \
  --final-image-path "$final_image" \
  --max-dimension "$max_dimension" \
  --jpeg-quality "$jpeg_quality" \
  --max-bytes "$MAX_IMAGE_BYTES" \
  >/dev/null

if [[ ! -s "$normalized_temp" || ! -s "$metadata_temp" ]]; then
  printf 'Normalizer did not produce both required output files.\n' >&2
  exit 1
fi

normalized_bytes="$(wc -c < "$normalized_temp")"
if (( normalized_bytes > MAX_IMAGE_BYTES )); then
  printf 'Normalized JPEG exceeds the %s-byte API limit.\n' "$MAX_IMAGE_BYTES" >&2
  exit 1
fi
progress "Validated normalized JPEG ($normalized_bytes bytes); committing capture..."

mv -- "$normalized_temp" "$final_image"
mv -- "$metadata_temp" "$final_metadata"
committed="true"
progress "Capture $capture_id is ready."

cat "$final_metadata"
