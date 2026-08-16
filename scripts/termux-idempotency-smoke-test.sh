#!/data/data/com.termux/files/usr/bin/bash

set -euo pipefail

BASE_URL="${COLORS_BASE_URL:-https://colors-sky-archive.jaredwinick.workers.dev}"
CONFIG_DIR="$HOME/.config/colors"
TOKEN_FILE="$CONFIG_DIR/ingest-token"
TEMP_DIR=""
TOKEN=""

cleanup() {
  unset TOKEN
  if [[ -n "${TEMP_DIR:-}" && -d "$TEMP_DIR" ]]; then
    rm -rf -- "$TEMP_DIR"
  fi
}

trap cleanup EXIT

for command_name in curl base64 mktemp date tr python; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$command_name" >&2
    printf 'Install dependencies with: pkg install curl coreutils python -y\n' >&2
    exit 1
  fi
done

if [[ ! -s "$TOKEN_FILE" ]]; then
  printf 'Token file not found or empty: %s\n' "$TOKEN_FILE" >&2
  exit 1
fi
if [[ ! -r /proc/sys/kernel/random/uuid ]]; then
  printf 'Android UUID source is unavailable: /proc/sys/kernel/random/uuid\n' >&2
  exit 1
fi

umask 077
TEMP_DIR="$(mktemp -d "$CONFIG_DIR/idempotency-test.XXXXXX")"
image_path="$TEMP_DIR/test.png"
first_body="$TEMP_DIR/first.json"
retry_body="$TEMP_DIR/retry.json"
conflict_body="$TEMP_DIR/conflict.json"

printf '%s' \
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=' \
  | base64 -d > "$image_path"

TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE")"
capture_id="$(tr -d '\r\n' < /proc/sys/kernel/random/uuid)"
captured_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
palette='[{"hex":"#87CEEB","weight":0.5},{"hex":"#FFFFFF","weight":0.3},{"hex":"#F4A261","weight":0.2}]'

upload() {
  local output_file="$1"
  local device_id="$2"
  curl --silent --show-error \
    --output "$output_file" \
    --write-out '%{http_code}' \
    --header "Authorization: Bearer $TOKEN" \
    --form "image=@$image_path;type=image/png" \
    --form-string "capture_id=$capture_id" \
    --form-string "palette=$palette" \
    --form-string "captured_at=$captured_at" \
    --form-string "device_id=$device_id" \
    "$BASE_URL/api/ingest"
}

printf 'Sending first upload...\n' >&2
first_status="$(upload "$first_body" 'android-idempotency-smoke-test')"
printf 'Repeating the exact request...\n' >&2
retry_status="$(upload "$retry_body" 'android-idempotency-smoke-test')"
printf 'Reusing the ID with conflicting metadata...\n' >&2
conflict_status="$(upload "$conflict_body" 'android-idempotency-conflict')"

if [[ "$first_status" != "201" || "$retry_status" != "200" || "$conflict_status" != "409" ]]; then
  printf 'Unexpected statuses: first=%s retry=%s conflict=%s\n' \
    "$first_status" "$retry_status" "$conflict_status" >&2
  printf 'First: '; cat "$first_body"; printf '\n'
  printf 'Retry: '; cat "$retry_body"; printf '\n'
  printf 'Conflict: '; cat "$conflict_body"; printf '\n'
  exit 1
fi

python -c '
import json, sys
first = json.load(open(sys.argv[1], encoding="utf-8"))
retry = json.load(open(sys.argv[2], encoding="utf-8"))
conflict = json.load(open(sys.argv[3], encoding="utf-8"))
assert first["capture"]["id"] == retry["capture"]["id"]
assert first["idempotentReplay"] is False
assert retry["idempotentReplay"] is True
assert "already used" in conflict["error"]
' "$first_body" "$retry_body" "$conflict_body"

printf 'Idempotency smoke test passed.\n'
printf 'capture_id=%s first=%s retry=%s conflict=%s\n' \
  "$capture_id" "$first_status" "$retry_status" "$conflict_status"
