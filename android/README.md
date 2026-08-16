# Android sky camera

The lightweight capture client uses Termux rather than a custom Android app.
It takes a photo, extracts seven weighted colors on the phone, and uploads one
multipart request. Palette processing on-device keeps the web endpoint fast and
inside the small CPU allowance of free serverless hosting.

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

## Install

Install Termux and its matching Termux:API add-on from the same source. Then:

```sh
pkg update
pkg install python python-pillow termux-api curl
mkdir -p ~/.config/colors ~/colors
cp capture_and_upload.sh extract_palette.py ~/colors/
chmod +x ~/colors/capture_and_upload.sh
printf '%s' 'YOUR_LONG_RANDOM_INGEST_TOKEN' > ~/.config/colors/ingest-token
chmod 600 ~/.config/colors/ingest-token
```

Edit `SITE_URL` in `capture_and_upload.sh`, test the camera permission with
`termux-camera-photo`, then run the script once by hand.

## Schedule

For a simple 15-minute cadence, Termux:API can register the script with Android
JobScheduler:

```sh
termux-job-scheduler \
  --script "$HOME/colors/capture_and_upload.sh" \
  --period-ms 900000 \
  --network any \
  --persisted true
```

Android treats background schedules as inexact. Disable battery optimization
for Termux and Termux:API, keep the phone powered, and mount it where its camera
has a fixed unobstructed view. If your phone restricts background camera use,
use a Tasker Time profile plus the Termux:Tasker plug-in to run the same script;
Tasker may be more reliable on heavily customized Android builds.

Use the rear camera (`-c 0`) unless `termux-camera-info` reports another ID for
the lens you mounted. Capture only property and views you are entitled to
record.
