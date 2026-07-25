# Android sky camera

The lightweight capture client uses Termux rather than a custom Android app.
It takes a photo, extracts seven weighted colors on the phone, and uploads one
multipart request. Palette processing on-device keeps the web endpoint fast and
inside the small CPU allowance of free serverless hosting.

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
