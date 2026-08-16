#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Copy this file to ~/.shortcuts/tasks/ if launching it through Termux:Tasker.
# Keep the token in ~/.config/colors/ingest-token, readable only by you.
SITE_URL="${COLORS_SITE_URL:-https://YOUR-SITE.example}"
TOKEN_FILE="${COLORS_TOKEN_FILE:-$HOME/.config/colors/ingest-token}"
DEVICE_ID="${COLORS_DEVICE_ID:-android-sky-camera}"
WORK_DIR="$HOME/.cache/colors"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "$WORK_DIR"
captured_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
capture_id="$(python -c 'import uuid; print(uuid.uuid4())')"
image_path="$WORK_DIR/sky-$stamp.jpg"

termux-camera-photo -c 0 "$image_path"
palette="$(python "$SCRIPT_DIR/extract_palette.py" "$image_path")"
token="$(tr -d '\r\n' < "$TOKEN_FILE")"

curl --fail-with-body --silent --show-error \
  --retry 4 \
  --retry-all-errors \
  --connect-timeout 20 \
  --max-time 120 \
  -H "Authorization: Bearer $token" \
  -F "image=@$image_path;type=image/jpeg" \
  -F "capture_id=$capture_id" \
  -F "captured_at=$captured_at" \
  -F "device_id=$DEVICE_ID" \
  -F "palette=$palette" \
  "$SITE_URL/api/ingest"

rm -f "$image_path"
