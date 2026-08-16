# Android sky camera

The lightweight capture client uses Termux rather than a custom Android app.
It takes a photo, extracts six weighted colors on the phone, durably queues the
JPEG and metadata, and uploads with safe retries. Palette processing on-device
keeps the web endpoint fast and inside the small CPU allowance of free
serverless hosting.

## First: prove unattended camera access

Before installing the full client, run the camera probe on the target phone.
It preserves its test photos and logs so Android background-camera restrictions
can be diagnosed separately from palette and network problems.

Transfer `scripts/termux-camera-probe.sh` to the phone with Quick Share. Quick
Share normally places it in Downloads. In Termux, run:

```sh
termux-setup-storage
bash ~/storage/downloads/termux-camera-probe.sh setup
```

Allow file and camera access if Android asks. The probe takes one photo
immediately and registers an inexact 15-minute Android job. Leave the Galaxy
S9+ plugged in with Termux visible for the first test, wait at least 20 minutes,
then check for a second photo:

```sh
~/.local/lib/colors/termux-camera-probe.sh status
```

Once that works, repeat with the screen off. Stop the probe without deleting
its evidence with:

```sh
~/.local/lib/colors/termux-camera-probe.sh cancel
```

The probe schedules with `--network any` as a compatibility workaround for a
Termux:API v0.53 bug on Android 9. A `--network none` job is registered, but the
API then throws a `NullPointerException` whenever it formats that job for setup,
status, or cancellation. The permanently mounted phone should therefore remain
connected to Wi-Fi when captures are expected.

For the dedicated Galaxy S9+, also make these one-time changes before the
screen-off test (Samsung menu wording can vary slightly by software build):

- Keep the phone connected to a reliable charger.
- In Developer options, enable **Stay awake** while charging for the first test.
- In **Settings > Apps > Special access > Optimize battery usage**, show all
  apps and turn optimization off for Termux and Termux:API.
- In **Device care > Battery**, make sure neither app is in Sleeping apps and
  turn off any setting that automatically sleeps unused apps.
- In the Termux:API app permissions, allow Camera access.

Use Termux's dark theme and the lowest usable brightness while the screen stays
on; the S9+'s OLED display can retain a bright static image over long periods.

## Capture and normalize one image

The next stage captures one full, unmasked photograph and converts it into a
consistently oriented, storage-efficient JPEG. It does not mask, extract a
palette, or upload yet, which keeps those later steps independently testable.

Transfer `android/capture_image.sh` and `android/normalize_capture.py` into the
same folder on the phone, then install Pillow:

```sh
pkg install python python-pillow termux-api -y
mkdir -p ~/colors
cp ~/storage/downloads/capture_image.sh ~/colors/
cp ~/storage/downloads/normalize_capture.py ~/colors/
chmod 700 ~/colors/capture_image.sh
```

Capture and normalize one photograph:

```sh
~/colors/capture_image.sh
```

The command prints JSON describing the completed capture. It writes a JPEG and
matching JSON sidecar under `~/.local/share/colors/captures`. Raw and incomplete
files use `~/.local/share/colors/tmp` and are removed automatically. The
existing `~/.config/colors/camera-id` file selects the camera. Progress messages
appear while the camera and Pillow are working; the final standard output stays
as one JSON object so later automation can consume it safely.

Each completed capture receives a UUIDv4 `capture_id`. That ID belongs to the
photograph, not to an upload attempt. The JPEG and sidecar retain it so every
retry can send the same value.

Defaults are a 1920-pixel longest edge and JPEG quality 85. Override them with
configuration files when needed:

```sh
printf '%s\n' '1600' > ~/.config/colors/max-dimension
printf '%s\n' '82' > ~/.config/colors/jpeg-quality
```

The normalizer corrects EXIF orientation, strips the rotation metadata,
re-encodes as JPEG, verifies that Pillow can decode the result, and rejects any
file above the ingest API's 12 MB limit. The durable JPEG remains unmasked so
the complete source view can be uploaded later; the fixed sky mask below defines
which pixels are sampled for its palette.

## Calibrate the fixed sky mask

`sky-mask.json` describes the part of the normalized photograph that may be
sampled for color. Its coordinates are fractions from 0 to 1, so the same mask
stays aligned if the capture resolution changes. `include_polygon` traces the
usable sky. Optional exclusion polygons and rectangles can remove another fixed
obstruction without changing that outline.

The checked-in mask is calibrated for the permanently mounted Galaxy S9+ view.
The archived and uploaded JPEG remains complete and unmasked; only palette
sampling will use the mask.

Transfer `sky_mask.py` and `sky-mask.json` to `~/colors` on the phone. To inspect
the mask against a completed capture, run:

```sh
python ~/colors/sky_mask.py preview \
  --config ~/colors/sky-mask.json \
  --image ~/.local/share/colors/captures/CAPTURE_ID.jpg \
  --output ~/storage/shared/colors-mask-preview.jpg
```

Open `colors-mask-preview.jpg` in Gallery. Cyan is included in palette sampling;
red is excluded. The boundary should remain just above every roof and tree. A
small safety margin is intentional because branches can move in the wind.

Recalibrate after the phone or mount moves. Take a representative capture,
adjust the normalized points in `include_polygon`, generate another preview,
and inspect it at full size. Validate both the normal capture resolution and a
second size before deploying the change:

```sh
python ~/colors/sky_mask.py validate \
  --config ~/colors/sky-mask.json --width 1440 --height 1920
python ~/colors/sky_mask.py validate \
  --config ~/colors/sky-mask.json --width 720 --height 960
```

The tool rejects malformed normalized coordinates and masks that leave fewer
than the configured minimum number or fraction of pixels. This prevents a bad
calibration from silently producing a palette from an empty or tiny region.
For the complete point-editing, backup, preview, and recovery procedure, see
[`MASK_CALIBRATION.md`](MASK_CALIBRATION.md).

## Extract the masked sky palette

`extract_palette.py` corrects EXIF orientation, downsamples the normalized view
to a 180-pixel longest edge, and quantizes only pixels included by
`sky-mask.json`. It emits six colors by default, sorted by descending weight, in
the exact JSON shape accepted by the ingest API. The number of colors can be set
from 3 to 10, and invalid or undersized palettes fail before upload.

Run it against a completed capture:

```sh
python ~/colors/extract_palette.py \
  ~/.local/share/colors/captures/CAPTURE_ID.jpg
```

For the one-command phone smoke test, benchmark, weighted swatch preview,
optional tuning, and expected output, see
[`PALETTE_EXTRACTION.md`](PALETTE_EXTRACTION.md).

## Install

Install Termux and its matching Termux:API add-on from the same source. The
complete scheduled client now consists of the capture, mask, palette, outbox,
and single-cycle job files. See [`OUTBOX.md`](OUTBOX.md) for the Quick Share
file list and exact installation commands.

The production ingest URL is built in. The shared secret remains only in
`~/.config/colors/ingest-token` with mode `600`; it is never placed in a script,
command-line argument, or log.

## Retry-safe ingest contract

Every multipart upload must include the capture's UUIDv4 as `capture_id`. Keep
the capture ID, image bytes, `captured_at`, palette, and `device_id` unchanged
across retries. The server responds as follows:

- `201` and `idempotentReplay: false` for the first accepted upload.
- `200` and `idempotentReplay: true` for an exact retry.
- `409` if the same capture ID is reused with different content or metadata.

The server uses the capture ID for the D1 primary key and combines it with the
image digest for a deterministic R2 key. Concurrent exact retries converge on
one row and object. A durable outbox may therefore retry after a timeout without
trying to determine whether the prior response was lost before or after commit.

After the idempotent endpoint is deployed, Quick Share
`scripts/termux-idempotency-smoke-test.sh` to the phone and run:

```sh
bash ~/storage/downloads/termux-idempotency-smoke-test.sh
```

The smoke test creates one tiny test capture, repeats it exactly, then sends one
conflicting request. It passes only when the statuses are `201`, `200`, and
`409`, both successful responses contain the same capture ID, and the second is
marked as a replay.

## Schedule

For a 15-minute cadence, install the production JobScheduler registration:

```sh
~/colors/schedule_capture_job.sh install
```

Android treats background schedules as inexact. Disable battery optimization
for Termux and Termux:API, keep the phone powered, and mount it where its camera
has a fixed unobstructed view. If your phone restricts background camera use,
use a Tasker Time profile plus the Termux:Tasker plug-in to run the same script;
Tasker may be more reliable on heavily customized Android builds.

Use the rear camera (`-c 0`) unless `termux-camera-info` reports another ID for
the lens you mounted. Capture only property and views you are entitled to
record.

The job retries oldest captures before creating new work, preserves pending
files through reboots and network failures, prevents overlapping runs, applies
bounded exponential backoff, and retains delivered local copies according to a
configurable policy. Installation, storage layout, recovery testing,
configuration, logs, and notifications are documented in
[`OUTBOX.md`](OUTBOX.md).

The stable job ID, exact constraints, interval configuration, timing-drift
report, reboot acceptance procedure, and inspect/replace/cancel commands are in
[`SCHEDULING.md`](SCHEDULING.md).
