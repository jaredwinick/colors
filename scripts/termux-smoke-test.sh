#!/data/data/com.termux/files/usr/bin/bash

set -euo pipefail

# Quick Share normally saves this file in Android's Downloads directory.
# In Termux, run `termux-setup-storage` once, then run:
#   bash ~/storage/downloads/termux-smoke-test.sh

BASE_URL="${COLORS_BASE_URL:-https://colors-sky-archive.jaredwinick.workers.dev}"
CONFIG_DIR="$HOME/.config/colors"
TOKEN_FILE="$CONFIG_DIR/ingest-token"
TEST_IMAGE=""
TOKEN=""

cleanup() {
  unset TOKEN
  if [[ -n "${TEST_IMAGE:-}" && -f "$TEST_IMAGE" ]]; then
    rm -f -- "$TEST_IMAGE"
  fi
}

trap cleanup EXIT

missing_commands=()
for command_name in curl base64 mktemp date tr; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    missing_commands+=("$command_name")
  fi
done

if (( ${#missing_commands[@]} > 0 )); then
  printf 'Missing required commands: %s\n' "${missing_commands[*]}" >&2
  printf 'Install them with: pkg install curl coreutils -y\n' >&2
  exit 1
fi

if [[ ! -s "$TOKEN_FILE" ]]; then
  printf 'Token file not found or empty: %s\n' "$TOKEN_FILE" >&2
  exit 1
fi

umask 077
mkdir -p "$CONFIG_DIR"
TEST_IMAGE="$(mktemp "$CONFIG_DIR/smoke-test.XXXXXX.png")"

printf '%s' \
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=' \
  | base64 -d > "$TEST_IMAGE"

TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE")"
if [[ -z "$TOKEN" ]]; then
  printf 'Token file contains no usable value: %s\n' "$TOKEN_FILE" >&2
  exit 1
fi

captured_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

response="$(
  curl --silent --show-error --fail-with-body \
    --header "Authorization: Bearer $TOKEN" \
    --form "image=@$TEST_IMAGE;type=image/png" \
    --form-string 'palette=[{"hex":"#87CEEB","weight":0.5},{"hex":"#FFFFFF","weight":0.3},{"hex":"#F4A261","weight":0.2}]' \
    --form-string "captured_at=$captured_at" \
    --form-string 'device_id=android-termux-smoke-test' \
    "$BASE_URL/api/ingest"
)"

printf 'Upload succeeded. Safe response from Colors:\n%s\n' "$response"
