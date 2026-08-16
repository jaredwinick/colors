#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

# Manage the one recurring Android JobScheduler registration used by Colors.
# Reusing the stable job ID replaces the prior production registration.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JOB_ID=1701
MINIMUM_PERIOD_MS=900000
MAXIMUM_PERIOD_MS=2147483647
DEFAULT_PERIOD_MS=900000
CONFIG_DIR="${COLORS_CONFIG_DIR:-$HOME/.config/colors}"
DATA_DIR="${COLORS_DATA_DIR:-$HOME/.local/share/colors}"
PERIOD_FILE="$CONFIG_DIR/schedule-period-ms"
REGISTERED_AT_FILE="$CONFIG_DIR/schedule-registered-at"
CAPTURE_SCRIPT="${COLORS_CAPTURE_JOB_SCRIPT:-$SCRIPT_DIR/capture_and_upload.sh}"
TIMING_SCRIPT="${COLORS_SCHEDULE_TIMING_SCRIPT:-$SCRIPT_DIR/schedule_timing.py}"
LOG_FILE="${COLORS_LOG_FILE:-$DATA_DIR/logs/job.log}"
PYTHON_BIN="${COLORS_PYTHON:-python}"

usage() {
  cat <<'EOF'
Colors recurring capture scheduler

Usage:
  schedule_capture_job.sh install [period-ms]
  schedule_capture_job.sh replace [period-ms]
  schedule_capture_job.sh status
  schedule_capture_job.sh timing
  schedule_capture_job.sh cancel

The default and minimum period is 900000 ms (15 minutes). Android schedules
periodic jobs inexactly. Job ID 1701 is reserved for the production Colors job.
EOF
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

read_period() {
  local requested="${1:-}"
  if [[ -n "$requested" ]]; then
    printf '%s' "$requested"
  elif [[ -n "${COLORS_SCHEDULE_PERIOD_MS:-}" ]]; then
    printf '%s' "$COLORS_SCHEDULE_PERIOD_MS"
  elif [[ -s "$PERIOD_FILE" ]]; then
    tr -d '\r\n' < "$PERIOD_FILE"
  else
    printf '%s' "$DEFAULT_PERIOD_MS"
  fi
}

validate_period() {
  local period_ms="$1"
  if [[ ! "$period_ms" =~ ^[0-9]+$ ]]; then
    printf 'Schedule period must be an integer number of milliseconds: %s\n' \
      "$period_ms" >&2
    exit 1
  fi
  if (( period_ms < MINIMUM_PERIOD_MS || period_ms > MAXIMUM_PERIOD_MS )); then
    printf 'Schedule period must be between %d and %d milliseconds: %s\n' \
      "$MINIMUM_PERIOD_MS" "$MAXIMUM_PERIOD_MS" "$period_ms" >&2
    exit 1
  fi
}

show_timing() {
  local period_ms registered_at
  local timing_arguments=()
  period_ms="$(read_period)"
  validate_period "$period_ms"
  require_command "$PYTHON_BIN" 'pkg install python -y'
  if [[ ! -r "$TIMING_SCRIPT" ]]; then
    printf 'Timing report script is missing: %s\n' "$TIMING_SCRIPT" >&2
    exit 1
  fi
  timing_arguments=(
    --log "$LOG_FILE"
    --period-ms "$period_ms"
  )
  if [[ -s "$REGISTERED_AT_FILE" ]]; then
    registered_at="$(tr -d '\r\n' < "$REGISTERED_AT_FILE")"
    timing_arguments+=(--since "$registered_at")
  else
    printf 'No scheduler registration timestamp exists; including the full log.\n' >&2
  fi
  "$PYTHON_BIN" "$TIMING_SCRIPT" "${timing_arguments[@]}"
}

install_job() {
  local requested_period="${1:-}"
  local period_ms scheduler_output registration_started_at
  period_ms="$(read_period "$requested_period")"
  validate_period "$period_ms"
  require_command termux-job-scheduler 'pkg install termux-api -y'

  if [[ ! -f "$CAPTURE_SCRIPT" ]]; then
    printf 'Capture job is missing: %s\n' "$CAPTURE_SCRIPT" >&2
    exit 1
  fi
  if [[ ! -x "$CAPTURE_SCRIPT" ]]; then
    printf 'Capture job is not executable: %s\nRun: chmod 700 %s\n' \
      "$CAPTURE_SCRIPT" "$CAPTURE_SCRIPT" >&2
    exit 1
  fi

  registration_started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  if ! scheduler_output="$(termux-job-scheduler \
    --script "$CAPTURE_SCRIPT" \
    --job-id "$JOB_ID" \
    --period-ms "$period_ms" \
    --network any \
    --battery-not-low false \
    --storage-not-low true \
    --charging false \
    --persisted true)"; then
    printf 'Android rejected the Colors job registration.\n' >&2
    exit 1
  fi
  printf '%s\n' "$scheduler_output"
  if [[ "$scheduler_output" != *"response 1"* ]]; then
    printf 'Android did not report JobScheduler RESULT_SUCCESS (response 1).\n' >&2
    exit 1
  fi

  umask 077
  mkdir -p "$CONFIG_DIR"
  printf '%s\n' "$period_ms" > "$PERIOD_FILE"
  printf '%s\n' "$registration_started_at" > "$REGISTERED_AT_FILE"
  chmod 600 "$PERIOD_FILE" "$REGISTERED_AT_FILE"

  cat <<EOF

Colors job $JOB_ID is registered.
Period: $period_ms ms (inexact)
Timing sample begins: $registration_started_at
Network: any
Charging required: false
Battery-not-low required: false
Storage-not-low required: true
Persists after reboot: true

Inspect it with:
  $SCRIPT_DIR/schedule_capture_job.sh status
EOF
}

show_status() {
  local period_ms pending_output
  period_ms="$(read_period)"
  validate_period "$period_ms"
  require_command termux-job-scheduler 'pkg install termux-api -y'

  printf 'Expected Colors job:\n'
  printf '  job-id=%d period-ms=%s script=%s\n\n' \
    "$JOB_ID" "$period_ms" "$CAPTURE_SCRIPT"
  printf 'Pending Android jobs:\n'
  if ! pending_output="$(termux-job-scheduler --pending)"; then
    printf 'Could not inspect Android jobs. Check the Termux:API notification or log.\n' >&2
    exit 1
  fi
  printf '%s\n' "$pending_output"
  if [[ "$pending_output" != *"Job $JOB_ID:"* ]]; then
    printf '\nWARNING: production job %d is not registered.\n' "$JOB_ID" >&2
  fi

  printf '\nTiming report:\n'
  show_timing

  printf '\nRecent job log:\n'
  if [[ -s "$LOG_FILE" ]]; then
    tail -n 30 "$LOG_FILE"
  else
    printf 'No job log exists yet at %s\n' "$LOG_FILE"
  fi
}

cancel_job() {
  require_command termux-job-scheduler 'pkg install termux-api -y'
  termux-job-scheduler --cancel --job-id "$JOB_ID"
  printf 'Cancelled Colors job %d. Captures, queued uploads, and logs were kept.\n' \
    "$JOB_ID"
}

command_name="${1:-status}"
case "$command_name" in
  install|replace)
    if (( $# > 2 )); then
      usage >&2
      exit 1
    fi
    install_job "${2:-}"
    ;;
  status)
    if (( $# > 1 )); then
      usage >&2
      exit 1
    fi
    show_status
    ;;
  timing)
    if (( $# != 1 )); then
      usage >&2
      exit 1
    fi
    show_timing
    ;;
  cancel)
    if (( $# != 1 )); then
      usage >&2
      exit 1
    fi
    cancel_job
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    printf 'Unknown command: %s\n\n' "$command_name" >&2
    usage >&2
    exit 1
    ;;
esac
