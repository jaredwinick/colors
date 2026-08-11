#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

# Quick Share normally saves this file in Android's Downloads directory.
# In Termux, run `termux-setup-storage` once, then start the probe with:
#   bash ~/storage/downloads/termux-camera-probe.sh setup

JOB_ID="${COLORS_CAMERA_JOB_ID:-1101}"
PERIOD_MS="${COLORS_CAMERA_PERIOD_MS:-900000}"
CONFIG_DIR="$HOME/.config/colors"
INSTALL_DIR="$HOME/.local/lib/colors"
STATE_DIR="$HOME/.local/share/colors/camera-probe"
INSTALLED_SCRIPT="$INSTALL_DIR/termux-camera-probe.sh"
CAMERA_ID_FILE="$CONFIG_DIR/camera-id"
LOG_FILE="$STATE_DIR/camera-probe.log"
DEVICE_INFO_FILE="$STATE_DIR/device-info.txt"
CAMERA_INFO_FILE="$STATE_DIR/camera-info.json"
INSTALLER_INFO_FILE="$STATE_DIR/installer-info.txt"

usage() {
  cat <<'EOF'
Colors camera probe

Usage:
  termux-camera-probe.sh setup [camera-id]
  termux-camera-probe.sh capture [label]
  termux-camera-probe.sh status
  termux-camera-probe.sh cancel

The setup command takes one test photo and registers an inexact recurring
Android job with a 15-minute minimum period. Camera 0 is used by default.
EOF
}

log() {
  local message="$1"
  local timestamp
  timestamp="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  mkdir -p "$STATE_DIR"
  printf '%s %s\n' "$timestamp" "$message" | tee -a "$LOG_FILE"
}

print_missing_help() {
  cat >&2 <<'EOF'

Install the Termux command package with:
  pkg update
  pkg install termux-api -y

The Termux:API Android add-on must also be installed from the same source as
Termux (for example, both from F-Droid). Some newer Termux distributions bundle
the Android-side API support. Re-run setup after the commands are available.
EOF
}

require_commands() {
  local missing=()
  local command_name

  for command_name in date tee termux-camera-info termux-camera-photo termux-info termux-job-scheduler; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
      missing+=("$command_name")
    fi
  done

  if (( ${#missing[@]} > 0 )); then
    printf 'Missing required commands: %s\n' "${missing[*]}" >&2
    print_missing_help
    exit 1
  fi
}

read_camera_id() {
  local camera_id="0"

  if [[ -s "$CAMERA_ID_FILE" ]]; then
    camera_id="$(tr -d '[:space:]' < "$CAMERA_ID_FILE")"
  fi

  if [[ ! "$camera_id" =~ ^[0-9]+$ ]]; then
    printf 'Invalid camera ID in %s: %s\n' "$CAMERA_ID_FILE" "$camera_id" >&2
    exit 1
  fi

  printf '%s' "$camera_id"
}

capture_photo() {
  local label="${1:-manual}"
  local camera_id
  local stamp
  local image_path

  require_commands
  umask 077
  mkdir -p "$STATE_DIR"
  label="${label//[^a-zA-Z0-9_-]/_}"
  camera_id="$(read_camera_id)"
  stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  image_path="$STATE_DIR/${stamp}-${label}.jpg"

  log "capture-start camera=$camera_id label=$label"
  if ! termux-camera-photo -c "$camera_id" "$image_path"; then
    log "capture-failed camera=$camera_id label=$label reason=command-error"
    return 1
  fi

  if [[ ! -s "$image_path" ]]; then
    log "capture-failed camera=$camera_id label=$label reason=empty-file"
    return 1
  fi

  log "capture-succeeded camera=$camera_id label=$label bytes=$(wc -c < "$image_path") file=$image_path"
  printf '\nPhoto saved at:\n%s\n' "$image_path"
}

setup_probe() {
  local camera_id="${1:-0}"
  local source_script

  if [[ ! "$camera_id" =~ ^[0-9]+$ ]]; then
    printf 'Camera ID must be a non-negative integer.\n' >&2
    exit 1
  fi

  require_commands
  umask 077
  mkdir -p "$CONFIG_DIR" "$INSTALL_DIR" "$STATE_DIR"
  printf '%s\n' "$camera_id" > "$CAMERA_ID_FILE"

  source_script="$(realpath "${BASH_SOURCE[0]}")"
  cp -- "$source_script" "$INSTALLED_SCRIPT"
  chmod 700 "$INSTALLED_SCRIPT"

  {
    printf 'Recorded at: %s\n\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    termux-info
  } > "$DEVICE_INFO_FILE" 2>&1

  {
    printf 'Recorded at: %s\n\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    if [[ -x /system/bin/cmd ]]; then
      /system/bin/cmd package list packages -i com.termux || true
      /system/bin/cmd package list packages -i com.termux.api || true
    else
      printf 'Android package command is unavailable.\n'
    fi
  } > "$INSTALLER_INFO_FILE" 2>&1

  if ! termux-camera-info > "$CAMERA_INFO_FILE" 2>> "$LOG_FILE"; then
    log "camera-info-failed"
    printf 'Could not read camera information. Check the Termux:API installation and permissions.\n' >&2
    exit 1
  fi

  printf 'Saved phone details to %s\n' "$DEVICE_INFO_FILE"
  printf 'Saved installer details to %s\n' "$INSTALLER_INFO_FILE"
  printf 'Saved camera details to %s\n' "$CAMERA_INFO_FILE"
  printf '\nAndroid may now ask for camera permission. Choose Allow.\n\n'
  capture_photo "setup"

  termux-job-scheduler --cancel --job-id "$JOB_ID" >/dev/null 2>&1 || true
  # Termux:API v0.53 crashes while formatting network=none jobs on Android 9.
  # The mounted phone normally has Wi-Fi, so requiring any network is safe.
  termux-job-scheduler \
    --script "$INSTALLED_SCRIPT" \
    --job-id "$JOB_ID" \
    --period-ms "$PERIOD_MS" \
    --network any \
    --battery-not-low false \
    --storage-not-low true \
    --charging false \
    --persisted true

  cat <<EOF

Camera probe installed.

Leave the phone plugged in with Termux visible for the first scheduled test.
Android scheduling is inexact; wait at least 20 minutes, then run:
  $INSTALLED_SCRIPT status

The result we want is a second JPG whose name ends in -scheduled.jpg.
EOF
}

show_status() {
  require_commands

  printf 'Pending Android jobs:\n'
  termux-job-scheduler --pending || true

  printf '\nRecent probe log:\n'
  if [[ -s "$LOG_FILE" ]]; then
    tail -n 25 "$LOG_FILE"
  else
    printf 'No probe log exists yet.\n'
  fi

  printf '\nCaptured photos:\n'
  if compgen -G "$STATE_DIR/*.jpg" >/dev/null; then
    ls -lh "$STATE_DIR"/*.jpg
  else
    printf 'No photos exist yet.\n'
  fi

  printf '\nInstaller source:\n'
  if [[ -s "$INSTALLER_INFO_FILE" ]]; then
    cat "$INSTALLER_INFO_FILE"
  else
    printf 'Installer details have not been recorded.\n'
  fi

  printf '\nDiagnostic files:\n%s\n%s\n%s\n' \
    "$DEVICE_INFO_FILE" "$INSTALLER_INFO_FILE" "$CAMERA_INFO_FILE"
}

cancel_probe() {
  require_commands
  termux-job-scheduler --cancel --job-id "$JOB_ID"
  log "job-cancelled job-id=$JOB_ID"
  printf 'Cancelled camera probe job %s. Existing photos and logs were kept.\n' "$JOB_ID"
}

command_name="${1:-capture}"
default_capture_label="manual"
if (( $# == 0 )); then
  default_capture_label="scheduled"
fi
case "$command_name" in
  setup)
    setup_probe "${2:-0}"
    ;;
  capture)
    capture_photo "${2:-$default_capture_label}"
    ;;
  status)
    show_status
    ;;
  cancel)
    cancel_probe
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    printf 'Unknown command: %s\n\n' "$command_name" >&2
    usage >&2
    exit 2
    ;;
esac
