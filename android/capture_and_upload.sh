#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

# Run one durable Colors cycle: retry queued work, capture at most one new
# photograph, extract its masked palette, queue it, and retry within a bounded
# upload budget. The bearer token is read only inside outbox.py and is never a
# command-line argument.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_DIR="${COLORS_CONFIG_DIR:-$HOME/.config/colors}"
DATA_DIR="${COLORS_DATA_DIR:-$HOME/.local/share/colors}"
CAPTURE_DIR="${COLORS_CAPTURE_DIR:-$DATA_DIR/captures}"
OUTBOX_DIR="${COLORS_OUTBOX_DIR:-$DATA_DIR/outbox}"
TEMP_DIR="${COLORS_TEMP_DIR:-$DATA_DIR/tmp}"
LOG_DIR="${COLORS_LOG_DIR:-$DATA_DIR/logs}"
LOG_FILE="${COLORS_LOG_FILE:-$LOG_DIR/job.log}"
LOCK_FILE="${COLORS_LOCK_FILE:-$DATA_DIR/job.lock}"
TOKEN_FILE="${COLORS_TOKEN_FILE:-$CONFIG_DIR/ingest-token}"
CAPTURE_SCRIPT="${COLORS_CAPTURE_SCRIPT:-$SCRIPT_DIR/capture_image.sh}"
PALETTE_SCRIPT="${COLORS_PALETTE_SCRIPT:-$SCRIPT_DIR/extract_palette.py}"
OUTBOX_SCRIPT="${COLORS_OUTBOX_SCRIPT:-$SCRIPT_DIR/outbox.py}"
MASK_CONFIG="${COLORS_MASK_CONFIG:-$SCRIPT_DIR/sky-mask.json}"
PYTHON_BIN="${COLORS_PYTHON:-python}"
INGEST_ENDPOINT="${COLORS_INGEST_ENDPOINT:-https://colors-sky-archive.jaredwinick.workers.dev/api/ingest}"
SECONDS=0

temporary_files=()

cleanup() {
  local temporary
  for temporary in "${temporary_files[@]:-}"; do
    [[ -z "$temporary" || ! -e "$temporary" ]] || rm -f -- "$temporary"
  done
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
    value="$(tr -d '\r\n' < "$config_file")"
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

require_positive_integer() {
  local label="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[0-9]+$ ]] || (( value < 1 )); then
    printf '%s must be a positive integer: %s\n' "$label" "$value" >&2
    exit 1
  fi
}

require_nonnegative_integer() {
  local label="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[0-9]+$ ]]; then
    printf '%s must be a non-negative integer: %s\n' "$label" "$value" >&2
    exit 1
  fi
}

log() {
  local timestamp line
  timestamp="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  line="$timestamp [colors +${SECONDS}s] $*"
  printf '%s\n' "$line" >&2
  printf '%s\n' "$line" >> "$LOG_FILE"
}

notify_failure() {
  local message="$1"
  if command -v termux-notification >/dev/null 2>&1; then
    termux-notification \
      --id colors-outbox \
      --title 'Colors upload needs attention' \
      --content "$message" \
      --priority high \
      >/dev/null 2>&1 || true
  fi
}

oldest_staged_metadata() {
  local candidate oldest=""
  shopt -s nullglob
  local sidecars=("$CAPTURE_DIR"/*.json)
  shopt -u nullglob
  for candidate in "${sidecars[@]:-}"; do
    if [[ -z "$oldest" || "$candidate" -ot "$oldest" ]]; then
      oldest="$candidate"
    fi
  done
  printf '%s' "$oldest"
}

process_capture() {
  local metadata_path="$1"
  local capture_values capture_id image_path palette_path enqueue_result
  if ! capture_values="$("$PYTHON_BIN" -c '
import json, sys
metadata = json.load(open(sys.argv[1], encoding="utf-8"))
print(metadata["capture_id"])
print(metadata["image_path"])
' "$metadata_path" 2>> "$LOG_FILE")"; then
    log "Could not parse staged capture metadata: $metadata_path"
    return 1
  fi
  capture_id="$(printf '%s\n' "$capture_values" | sed -n '1p')"
  image_path="$(printf '%s\n' "$capture_values" | sed -n '2p')"
  if [[ -z "$capture_id" || ! -s "$image_path" ]]; then
    log "Staged capture is incomplete: $metadata_path"
    return 1
  fi

  palette_path="$(mktemp "$TEMP_DIR/$capture_id.palette.XXXXXX.json")"
  temporary_files+=("$palette_path")
  log "Extracting the masked palette for capture $capture_id..."
  if ! "$PYTHON_BIN" "$PALETTE_SCRIPT" "$image_path" \
    --mask-config "$MASK_CONFIG" \
    > "$palette_path" 2>> "$LOG_FILE"; then
    log "Palette extraction failed; capture $capture_id remains staged for retry."
    return 1
  fi

  if ! enqueue_result="$("$PYTHON_BIN" "$OUTBOX_SCRIPT" enqueue \
    --outbox-directory "$OUTBOX_DIR" \
    --image "$image_path" \
    --metadata "$metadata_path" \
    --palette "$palette_path" \
    --device-id "$device_id" \
    2>> "$LOG_FILE")"; then
    log "Outbox enqueue failed; capture $capture_id remains staged for retry."
    return 1
  fi
  log "Queued capture $capture_id: $enqueue_result"
  return 0
}

DRAIN_OK="true"
DRAIN_ATTEMPTED=0
DRAIN_DELIVERED=0
DRAIN_FAILED=0
DRAIN_PENDING=0
DRAIN_NOTIFY="false"

drain_queue() {
  local limit="$1"
  local summary parsed
  if (( limit < 1 )); then
    return 0
  fi
  if ! summary="$("$PYTHON_BIN" "$OUTBOX_SCRIPT" drain \
    --outbox-directory "$OUTBOX_DIR" \
    --endpoint "$INGEST_ENDPOINT" \
    --token-file "$TOKEN_FILE" \
    --max-items "$limit" \
    --timeout-seconds "$request_timeout" \
    --initial-backoff-seconds "$initial_backoff" \
    --maximum-backoff-seconds "$maximum_backoff" \
    --notify-after-attempts "$notify_after_attempts" \
    2>> "$LOG_FILE")"; then
    DRAIN_OK="false"
    log "Outbox upload pass could not start; queued files were retained."
    notify_failure "The Colors uploader could not start. Check $LOG_FILE"
    return 1
  fi
  parsed="$("$PYTHON_BIN" -c '
import json, sys
value = json.loads(sys.argv[1])
print(value["attempted"], value["delivered"], value["failed"], value["pending"], str(value["notification_required"]).lower())
' "$summary")"
  read -r DRAIN_ATTEMPTED DRAIN_DELIVERED DRAIN_FAILED DRAIN_PENDING DRAIN_NOTIFY <<< "$parsed"
  log "Outbox upload pass: $summary"
  if [[ "$DRAIN_NOTIFY" == "true" ]]; then
    notify_failure "$DRAIN_PENDING capture(s) remain queued after repeated upload failures."
  fi
  return 0
}

require_command "$PYTHON_BIN" "pkg install python python-pillow -y"
require_command flock "pkg install util-linux -y"
require_command sed "pkg install sed -y"

for required_file in "$CAPTURE_SCRIPT" "$PALETTE_SCRIPT" "$OUTBOX_SCRIPT" "$MASK_CONFIG"; do
  if [[ ! -f "$required_file" ]]; then
    printf 'Required Colors file is missing: %s\n' "$required_file" >&2
    exit 1
  fi
done

device_id="$(read_setting "${COLORS_DEVICE_ID:-}" "$CONFIG_DIR/device-id" "android-sky-camera")"
max_pending="$(read_setting "${COLORS_MAX_PENDING:-}" "$CONFIG_DIR/max-pending" "192")"
max_uploads="$(read_setting "${COLORS_MAX_UPLOADS_PER_RUN:-}" "$CONFIG_DIR/max-uploads-per-run" "4")"
request_timeout="$(read_setting "${COLORS_REQUEST_TIMEOUT:-}" "$CONFIG_DIR/request-timeout-seconds" "120")"
initial_backoff="$(read_setting "${COLORS_INITIAL_BACKOFF:-}" "$CONFIG_DIR/initial-backoff-seconds" "60")"
maximum_backoff="$(read_setting "${COLORS_MAXIMUM_BACKOFF:-}" "$CONFIG_DIR/maximum-backoff-seconds" "3600")"
notify_after_attempts="$(read_setting "${COLORS_NOTIFY_AFTER_ATTEMPTS:-}" "$CONFIG_DIR/notify-after-attempts" "3")"
retention_days="$(read_setting "${COLORS_RETENTION_DAYS:-}" "$CONFIG_DIR/retention-days" "7")"
retention_count="$(read_setting "${COLORS_RETENTION_COUNT:-}" "$CONFIG_DIR/retention-count" "672")"
log_max_bytes="$(read_setting "${COLORS_LOG_MAX_BYTES:-}" "$CONFIG_DIR/log-max-bytes" "1048576")"

require_positive_integer "Maximum pending captures" "$max_pending"
require_positive_integer "Maximum uploads per run" "$max_uploads"
require_positive_integer "Request timeout" "$request_timeout"
require_positive_integer "Initial backoff" "$initial_backoff"
require_positive_integer "Maximum backoff" "$maximum_backoff"
require_positive_integer "Notification attempt threshold" "$notify_after_attempts"
require_nonnegative_integer "Retention days" "$retention_days"
require_nonnegative_integer "Retention count" "$retention_count"
require_positive_integer "Maximum log bytes" "$log_max_bytes"
if (( maximum_backoff < initial_backoff )); then
  printf 'Maximum backoff must be greater than or equal to initial backoff.\n' >&2
  exit 1
fi

umask 077
mkdir -p "$CONFIG_DIR" "$CAPTURE_DIR" "$OUTBOX_DIR" "$TEMP_DIR" "$LOG_DIR"
if [[ -f "$LOG_FILE" ]] && (( $(wc -c < "$LOG_FILE") > log_max_bytes )); then
  mv -f -- "$LOG_FILE" "$LOG_FILE.1"
fi
touch "$LOG_FILE"

exec 9> "$LOCK_FILE"
if ! flock -n 9; then
  log "Another Colors job is already running; this cycle will exit without overlap."
  exit 0
fi

log "Starting durable capture cycle."
total_attempted=0
job_failed=0

if drain_queue "$max_uploads"; then
  total_attempted=$((total_attempted + DRAIN_ATTEMPTED))
fi

staged_metadata="$(oldest_staged_metadata)"
if [[ -n "$staged_metadata" ]]; then
  log "Recovering the oldest staged capture before taking a new photograph."
  if ! process_capture "$staged_metadata"; then
    notify_failure "Colors could not process its staged capture. Check $LOG_FILE"
    job_failed=1
  fi
fi

if ! pending_count="$("$PYTHON_BIN" "$OUTBOX_SCRIPT" count --outbox-directory "$OUTBOX_DIR" 2>> "$LOG_FILE")"; then
  log "Could not inspect the outbox; no new capture will be created."
  notify_failure "Colors could not inspect its outbox. Check $LOG_FILE"
  exit 1
fi
require_nonnegative_integer "Pending capture count" "$pending_count"

if (( job_failed == 0 && pending_count < max_pending )); then
  log "Capturing one new photograph."
  if capture_json="$("$CAPTURE_SCRIPT" 2>> "$LOG_FILE")"; then
    capture_id="$("$PYTHON_BIN" -c 'import json,sys; print(json.loads(sys.argv[1])["capture_id"])' "$capture_json")"
    new_metadata="$CAPTURE_DIR/$capture_id.json"
    if ! process_capture "$new_metadata"; then
      notify_failure "Colors captured a photo but could not queue it. Check $LOG_FILE"
      job_failed=1
    fi
  else
    log "Camera capture failed; no new outbox item was created."
    notify_failure "Colors could not capture a photograph. Check $LOG_FILE"
    job_failed=1
  fi
elif (( pending_count >= max_pending )); then
  log "Pending limit $max_pending reached; skipping a new capture until uploads recover."
  notify_failure "$pending_count captures are queued; new captures are paused until delivery recovers."
fi

remaining_uploads=$((max_uploads - total_attempted))
if [[ "$DRAIN_OK" == "true" ]] && (( remaining_uploads > 0 )); then
  if drain_queue "$remaining_uploads"; then
    total_attempted=$((total_attempted + DRAIN_ATTEMPTED))
  fi
fi

if retention_summary="$("$PYTHON_BIN" "$OUTBOX_SCRIPT" retain \
  --outbox-directory "$OUTBOX_DIR" \
  --retention-days "$retention_days" \
  --retention-count "$retention_count" \
  2>> "$LOG_FILE")"; then
  log "Applied delivered-file retention: $retention_summary"
else
  log "Delivered-file retention failed; no queued captures were removed."
  job_failed=1
fi

if ! final_pending="$("$PYTHON_BIN" "$OUTBOX_SCRIPT" count --outbox-directory "$OUTBOX_DIR" 2>> "$LOG_FILE")"; then
  log "Could not read the final outbox count."
  exit 1
fi
log "Cycle complete: attempted=$total_attempted pending=$final_pending."
exit "$job_failed"
