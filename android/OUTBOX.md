# Durable Android outbox

`capture_and_upload.sh` is the single scheduled phone job. Each cycle:

1. takes a non-blocking lock so two jobs cannot overlap;
2. retries due captures from oldest to newest;
3. recovers any photograph that was captured but not yet palette-processed;
4. captures and queues at most one new photograph when the pending limit allows;
5. uses any remaining upload budget on the oldest due work; and
6. removes delivered local files that exceed the configured retention policy.

The phone may reboot, lose Wi-Fi, time out after a server commit, or be killed
between scheduled runs. Capture state is stored on disk before upload, and every
retry uses the same UUID, JPEG bytes, timestamp, palette, and device ID.

## Install or update the phone files

Quick Share these files to the Galaxy S9+:

- `capture_and_upload.sh`
- `capture_image.sh`
- `normalize_capture.py`
- `outbox.py`
- `extract_palette.py`
- `sky_mask.py`
- `sky-mask.json`
- `schedule_capture_job.sh`
- `schedule_timing.py`

Then run in Termux:

```sh
pkg install python python-pillow termux-api util-linux -y
mkdir -p ~/colors ~/.config/colors
cp ~/storage/downloads/capture_and_upload.sh ~/colors/
cp ~/storage/downloads/capture_image.sh ~/colors/
cp ~/storage/downloads/normalize_capture.py ~/colors/
cp ~/storage/downloads/outbox.py ~/colors/
cp ~/storage/downloads/extract_palette.py ~/colors/
cp ~/storage/downloads/sky_mask.py ~/colors/
cp ~/storage/downloads/sky-mask.json ~/colors/
cp ~/storage/downloads/schedule_capture_job.sh ~/colors/
cp ~/storage/downloads/schedule_timing.py ~/colors/
chmod 700 ~/colors/capture_and_upload.sh ~/colors/capture_image.sh ~/colors/outbox.py
chmod 700 ~/colors/schedule_capture_job.sh ~/colors/schedule_timing.py
chmod 600 ~/.config/colors/ingest-token
```

The production endpoint is already the default. Optionally give this fixed
camera a recognizable identifier:

```sh
printf '%s\n' 'galaxy-s9-window' > ~/.config/colors/device-id
chmod 600 ~/.config/colors/device-id
```

Run one cycle by hand:

```sh
~/colors/capture_and_upload.sh
```

Progress is printed without the bearer token and appended to:

```text
~/.local/share/colors/logs/job.log
```

## On-disk states

A normalized capture is first committed under `captures/` as a JPEG and JSON
sidecar. Palette extraction then creates an atomic pending directory:

```text
~/.local/share/colors/
├── captures/                  # only captures awaiting palette processing
├── outbox/
│   ├── pending/
│   │   └── CAPTURE_UUID/
│   │       ├── capture.jpg
│   │       └── capture.json
│   └── delivered/
│       └── CAPTURE_UUID/
│           ├── capture.jpg
│           └── capture.json
└── logs/job.log
```

The pending sidecar contains the immutable capture UUID, timestamp, device ID,
palette, image metadata, attempt count, last error, and next eligible retry
time. A capture moves to `delivered/` only after the Worker returns valid JSON
for the same capture ID with either:

- `201` and `idempotentReplay: false`; or
- `200` and `idempotentReplay: true`.

Network errors, timeouts, malformed responses, and server errors update the
pending sidecar but never remove the JPEG. Retry delay doubles from one minute
to a maximum of one hour. Each scheduled cycle attempts at most four items.

The Python uploader reads `~/.config/colors/ingest-token` directly and places
the bearer value in the HTTPS request. The token is never a shell argument,
standard output value, or log field. The uploader refuses a token file that is
group- or world-readable.

## Configuration

Defaults are suitable for a 15-minute schedule. Override them with one-line
files under `~/.config/colors`:

| File | Default | Meaning |
| --- | ---: | --- |
| `device-id` | `android-sky-camera` | Stable source name sent to the Worker |
| `max-pending` | `192` | Pause new captures above this pending count |
| `max-uploads-per-run` | `4` | Bounded attempts during one cycle |
| `request-timeout-seconds` | `120` | Timeout for one HTTPS request |
| `initial-backoff-seconds` | `60` | Delay after the first failure |
| `maximum-backoff-seconds` | `3600` | Retry-delay ceiling |
| `notify-after-attempts` | `3` | Failure count that triggers a notification |
| `retention-days` | `7` | Maximum age of delivered local copies |
| `retention-count` | `672` | Maximum delivered captures retained locally |
| `log-max-bytes` | `1048576` | Rotate `job.log` after approximately 1 MiB |

Environment variables with the same purpose are also available for temporary
tests: `COLORS_DEVICE_ID`, `COLORS_MAX_PENDING`,
`COLORS_MAX_UPLOADS_PER_RUN`, `COLORS_REQUEST_TIMEOUT`,
`COLORS_INITIAL_BACKOFF`, `COLORS_MAXIMUM_BACKOFF`,
`COLORS_NOTIFY_AFTER_ATTEMPTS`, `COLORS_RETENTION_DAYS`,
`COLORS_RETENTION_COUNT`, and `COLORS_LOG_MAX_BYTES`.

## Prove offline recovery

Run one cycle against an unreachable local endpoint. It should capture a photo,
retain it under `outbox/pending`, and exit without deleting it:

```sh
COLORS_INGEST_ENDPOINT=http://127.0.0.1:9/api/ingest \
  ~/colors/capture_and_upload.sh
```

Inspect the pending count and recent log:

```sh
python ~/colors/outbox.py count \
  --outbox-directory ~/.local/share/colors/outbox
tail -n 30 ~/.local/share/colors/logs/job.log
```

Wait at least the configured initial backoff, then run normally:

```sh
~/colors/capture_and_upload.sh
```

The oldest pending capture should move to `outbox/delivered`. The cycle may also
capture and deliver one new photograph. Repeating the job never creates another
server capture for the same queued UUID.

## Schedule

Use the production scheduler controller to register the same script as an
inexact 15-minute Android job:

```sh
~/colors/schedule_capture_job.sh install
```

The file lock makes an overlapping invocation exit successfully without doing
work. After three failed upload attempts for a capture, Termux:API posts a
generic notification with the pending count and log location; it never includes
the secret or server response body.

Stable job ID, constraints, battery settings, timing reports, reboot testing,
and inspect/replace/cancel commands are documented in
[`SCHEDULING.md`](SCHEDULING.md).
