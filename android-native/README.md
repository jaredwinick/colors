# Colors Camera for Android

This directory contains the native Android camera station. It builds on the
CameraX scheduling proof of concept from Issue #27 and is being productionized
through Issues #30-#38. The app targets the dedicated Samsung Galaxy S9+ running
Android 10 (API 29).

The native app currently provides the production foundation, proven capture
scheduler, production JPEG normalization, fixed sky-mask calibration,
deterministic weighted palette extraction, a transactional durable capture
outbox, and secure idempotent delivery to the Cloudflare Worker. The Termux
client in `android/` remains the rollback path until the native pipeline passes
its production soak test.

## Production identity and architecture

- Application name: **Colors Camera**
- Application ID and namespace: `com.jaredwinick.colors.camera`
- Version: `0.7.1` (`versionCode` 11)
- Capture files: app-private `files/durable-captures`
- Diagnostics and configuration: app-private storage
- Ingest token: encrypted with a non-exportable Android Keystore AES-GCM key
- Notification channel ID: `camera_station_v1`, low-priority **Camera station**
  status

The production application ID differs from the old
`com.jaredwinick.colors.poc` ID. Android therefore installs the two apps beside
one another. This is intentional for a safe transition, but never run both
stations at the same time because they will compete for the camera. POC data
and settings are not migrated; configure the production app and enter its
ingest token once.

Human-readable `versionName` values follow semantic versioning. Android
`versionCode` increases monotonically for every APK that may be installed as an
upgrade.

Code is split by responsibility:

| Package | Responsibility |
| --- | --- |
| `camera` | CameraX capture and power state |
| `config` | Versioned settings, validation, endpoint policy, and secrets |
| `diagnostics` | Safe capture records and timing reports |
| `persistence` | App-private runtime and diagnostic stores |
| `schedule` | UTC cadence, exact-alarm fallback, reboot restore |
| `ui` | Station controls and production settings |
| `processing` | JPEG normalization |
| `mask` | Schema-v1 validation, rasterization, durable calibration, and previews |
| `palette` | Masked-sky sampling, deterministic weighted median cut, and previews |
| `outbox` | SQLite capture state machine, immutable files, reconciliation, and retention |
| `network` | Strict multipart transport, response validation, retry policy, and delivery cycles |

The scheduler preserves the behavior proven in Issue #27: a foreground
service holds a partial wake lock in precision mode, an in-process timer owns
each UTC slot, and a uniquely identified exact alarm remains five seconds
behind it as a recovery fallback. Each next capture is calculated from a UTC
wall-clock boundary rather than the previous completion time.

## Configuration

Open **Production settings** in the app to edit the device, camera, image,
palette, outbox, retry, notification, retention, and log limits. The interval
and precision-mode controls remain on the station screen. Saving either screen
updates the same versioned configuration.

Current defaults match the working Termux pipeline:

| Setting | Default |
| --- | ---: |
| UTC interval | 15 minutes |
| Precision mode | enabled |
| Device ID | `android-sky-camera` |
| Camera lens | back |
| Focus | infinity; continuous autofocus fallback |
| White balance | fixed daylight; automatic fallback |
| Exposure | automatic, −0.3 EV compensation |
| Flash / night extension | off / not enabled |
| Maximum image dimension | 1920 px |
| JPEG quality | 85 |
| Palette colors | 8 |
| Palette analysis dimension | 180 px |
| Maximum pending captures | 192 |
| Maximum uploads per cycle | 4 |
| Request timeout | 120 seconds |
| Retry range | 60-3600 seconds |
| Notify after failures | 3 attempts |
| Delivered-file retention | 7 days / 672 captures |
| Log size | 1 MiB |

Configuration schema version 4 is stored in app-private Android preferences.
Earlier settings receive current defaults for new fields during migration. A
schema-3 installation still using the former six-color default is migrated to
eight colors, while a deliberately customized count is preserved. An unknown
future schema is never interpreted as current configuration. All values are
validated before saving.

## Production camera and JPEG behavior

The defaults are tuned for a permanently mounted sky camera:

- CameraX uses the rear lens, explicitly disables flash, and does not enable a
  night, HDR, or other CameraX extension. Normal darkness at night is retained.
- Infinity focus is requested at `0.0` diopters only when Camera2 reports a
  variable-focus manual sensor. A fixed-focus camera is accepted as infinity;
  otherwise the app falls back to continuous picture autofocus.
- Fixed daylight white balance is preferred so sunrise, sunset, and twilight
  color shifts are not continually neutralized. Automatic white balance is the
  supported fallback and remains configurable.
- Automatic exposure remains active across daylight and twilight. The default
  −0.3 EV compensation mildly protects bright sky highlights without forcing a
  dark manual exposure. The value is translated to the nearest supported camera
  step and clamped to the device range.
- Every applied mode and fallback is written to safe capture metadata and the
  timing CSV. Camera controls that the selected lens does not advertise are
  never forced.

Each camera callback receives one UUIDv4 and an actual UTC success timestamp.
The complete source JPEG is orientation-corrected, converted through an RGB
bitmap, reduced to a longest edge of at most 1920 pixels, and encoded at quality
85. The app then verifies that Android can decode the result, verifies normal
EXIF orientation and JPEG markers, and rejects files over the Worker's 12 MB
limit.

The normalized JPEG remains a complete, unmasked view. Raw and normalized work,
pending packages, delivered copies, and quarantined evidence are stored under
the internal durable-capture root. A synced sidecar and JPEG package is the file
completion marker; the transactional outbox row is committed only after that
package is atomically installed.

## Fixed sky mask and recalibration

Open **Sky mask calibration** from the station screen. The bundled reset mask
is byte-for-byte identical to `android/sky-mask.json`, the known-good Galaxy
S9+ skyline calibration. Its normalized coordinates apply at every image size.
The original JPEG is never cropped, painted, or masked; the mask only determines
which pixels a later palette stage may sample.

The calibration preview uses the latest completed production JPEG:

- cyan is included sky;
- red is excluded from palette sampling;
- yellow lines mark exclusions; and
- the optional 0.1-step grid labels the include-boundary point indices.

To make a small mount adjustment without Termux:

1. Tap **Export active mask JSON** and edit the normalized coordinates with any
   Android document editor.
2. Tap **Import mask JSON and preview** and select the changed file.
3. Inspect or share the full-resolution cyan/red preview. The app also
   rasterizes the same draft at the configured palette-analysis resolution.
4. Check the confirmation only after every roof, tree, and other fixed object
   is red, then tap **Activate confirmed mask**.

Importing never changes the active mask. A draft must pass schema, coordinate,
polygon, rectangle, included-pixel, and included-fraction validation at both
resolutions; it must also have a successfully generated preview. Activation is
atomic and saves the previous active mask as a backup. **Preview previous active
backup** stages recovery without activating it, and **Preview bundled Galaxy
S9+ default** provides a safe reset through the same preview-and-confirm path.
An invalid active file is preserved for diagnosis and automatically recovered
from the backup or bundled default at startup.

Palette code must obtain its Boolean sampling map through
`validatedAnalysisMask`. A malformed or undersized mask throws before a palette
can be built and does not mutate or delete the complete source capture.

## Weighted palette extraction

After JPEG normalization, the app reduces a working bitmap to the configured
longest analysis edge (180 pixels by default) and samples only pixels admitted
by the validated active sky mask. A deterministic median-cut implementation
builds 3-10 colors without dithering, merges duplicate representatives, and
emits uppercase `#RRGGBB` colors in descending-weight order with a stable hex
tie-break. Weights are positive and normalized to exactly one at six-decimal
precision, matching the Worker's existing ingest shape.

The complete JPEG is never altered by palette processing. Capture metadata
stores the palette plus source/analysis dimensions, included-pixel count,
requested and resulting color counts, elapsed time, and peak process memory. If
the mask is invalid, fewer than three colors remain, or extraction otherwise
fails, the app retains the complete normalized image and records a safe error
instead of constructing an uploadable palette.

Changing **Palette colors** or **Palette analysis dimension** in Production
settings requires a preview from the latest committed image. The preview shows
the cyan/red mask overlay and weighted color swatches; the exact settings cannot
be saved until that visible result is explicitly confirmed. The default is
eight colors, although a low-color scene can validly return fewer after
duplicate representatives are merged, provided at least three remain.

## Durable capture store and outbox

Every successful CameraX callback is registered in an app-private SQLite
database before image processing begins. Captures move through explicit
`STAGED`, `PROCESSING`, `PENDING_UPLOAD`, `DELIVERED`, and
`ATTENTION_REQUIRED` states. The database stores the immutable UUID, actual UTC
capture time, device ID, JPEG size/type/SHA-256, palette, processing metadata,
retry fields, delivery confirmation, and local paths. Pending JPEGs and
sidecars live under app-private `files/durable-captures`; they are never exposed
as general shared-storage files.

The pending sidecar is written and synced beside a copied JPEG in a temporary
directory, then the directory is atomically renamed before the database state
is committed. Startup reconciliation handles either side of that boundary:
complete orphan packages are recovered into SQLite, interrupted processing is
returned to a recoverable staged state, and missing, changed, conflicting, or
otherwise inconsistent evidence is marked for attention or moved to quarantine
without silent deletion. Existing version-0.5 capture/metadata pairs are
migrated into the durable store on upgrade; originals are removed only after a
safe internal copy is committed.

Capture and upload requests received while startup reconciliation is running
wait for its final result. A successful reconciliation releases each request
exactly once; a real startup failure records `OUTBOX_INITIALIZATION_FAILED`.
This prevents manual-only service startup from racing queue inspection.

Pending records are ordered by capture time and UUID. When the configurable
pending limit (192 by default) is reached, new manual and scheduled captures
pause with `OUTBOX_BACKPRESSURE`; queued work is retained indefinitely. The
station status displays staged, processing, pending, delivered, and attention
counts, oldest pending age, and local storage use. The same queue snapshot is
included in timing CSV diagnostics.

After every successful local commit, the app consumes the oldest eligible
pending records up to the configured per-cycle budget. **Upload pending captures
now** runs the same bounded pass without taking a photograph. This is useful for
recovery and production smoke tests. If the queue is already at its limit, the
app attempts delivery before recording `OUTBOX_BACKPRESSURE`, preventing a full
queue from becoming permanently stuck.

Retries preserve the exact UUID, JPEG bytes, capture time, device ID, and
palette. Network failures, timeouts, retryable HTTP responses, and malformed
success responses receive exponential backoff from 60 seconds to one hour by
default. Only a validated `201` with `idempotentReplay: false` or `200` with
`idempotentReplay: true` moves evidence to `DELIVERED`. Authentication,
validation, redirect, and `409` idempotency-conflict responses move the complete
immutable package to `ATTENTION_REQUIRED`; they are never silently deleted.
Delivered retention applies only to server-confirmed records.

The release endpoint is compiled into the app:

```text
https://colors-sky-archive.jaredwinick.workers.dev/api/ingest
```

Release builds always use that endpoint. Debug builds expose an override for
development; it must use HTTPS, except that plain HTTP is accepted for
`localhost`, `127.0.0.1`, or `::1`. Credentials in URLs are rejected.

The Bearer token is entered under **Cloudflare ingest credential**. The app
never redisplays or logs it; the screen reports only `configured` or
`not configured`. Clearing application data or uninstalling the app removes
the encrypted value. Reinstalling on a different device requires entering the
token again.

The uploader sends the credential only in the `Authorization: Bearer` header,
rejects redirects, caps JPEGs at 12 MiB and successful response bodies at
64 KiB, and never stores response bodies or sensitive headers. Release builds
require the compiled HTTPS endpoint. Debug HTTP overrides remain restricted to
loopback hosts.

### Production upload smoke test

1. In **Production settings**, confirm the effective endpoint and save the
   Cloudflare ingest token. The token field clears and status becomes
   `configured`.
2. Note the current pending count. To create one item if necessary, use
   **Capture test now** and wait for local processing.
3. Set **Maximum uploads per cycle** to `1`, then tap **Upload pending captures
   now**. The oldest pending count should fall by one and delivered should rise
   by one.
4. Confirm exactly one matching D1 row and R2 object, then load the response's
   `/api/images/...` route. Restore the upload budget afterward.

## Build and CI

The pinned build uses Android Gradle Plugin 8.9.2, Gradle 8.11.1, JDK 17,
compile SDK 36, Kotlin 2.1.20, and CameraX 1.6.1.

From `android-native`:

```text
./gradlew testDebugUnitTest assembleDebug lintDebug
```

On Windows:

```text
gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

The local debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
The `android-native.yml` GitHub Actions workflow runs the tests and lint against
a release build, then publishes the permanently signed APK as the
`colors-camera-release` artifact. This is the reproducible deployment path when
Android Studio and the Android SDK are not installed locally.

## Signing and upgrade-safe sideloads

Android permits an in-place upgrade only when the application ID and signing
certificate match the installed app. GitHub-hosted runners do not retain their
generated debug keys, so their ordinary debug APKs cannot safely serve as
upgradable deployments.

The Actions workflow instead decodes one encrypted repository-secret keystore
into the runner's temporary directory and supplies its passwords to Gradle only
through environment variables. The build fails if any signing input is missing
or if the resulting certificate does not have this expected SHA-256 fingerprint:

```text
41:91:06:39:0C:E9:82:8F:BC:F9:F4:70:2B:85:9C:FD:0E:CD:04:8C:0E:CD:4F:B5:6D:81:32:1B:AB:1F:B7:F8
```

The private recovery copy is stored locally under the ignored `work/signing`
directory and must be backed up securely. GitHub stores the build copy as these
write-only repository secrets:

- `ANDROID_SIGNING_KEYSTORE_BASE64`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

Never commit the keystore or its passwords. Before upgrading:

1. Stop station mode and confirm there is no capture in progress.
2. Export any timing diagnostics needed for troubleshooting.
3. Download `colors-camera-release` from the successful Actions run.
4. Transfer its APK with Quick Share and open it; Android should offer
   **Update**.
5. Open Colors Camera, confirm the saved settings and credential status, then
   start the station and run one test capture.

The first transition from an older CI debug APK requires one uninstall because
that runner's temporary signing key is unrecoverable. After installing the
stable release APK, stop if any later build offers only uninstall/reinstall or
reports a signature mismatch; verify its certificate before deleting app data.

## Galaxy S9+ setup

Keep the mounted phone powered and retain the same exemptions used during the
successful timing trial:

1. Open **Settings > Apps > Colors Camera > Battery** and allow background
   activity if Samsung shows that control.
2. Open **Settings > Apps > Special access > Optimize battery usage**, show all
   apps, and turn optimization off for **Colors Camera**.
3. Open **Device care > Battery > App power management** and add the app to
   **Apps that won't be put to sleep**.
4. Keep notifications enabled. The persistent **Colors camera station**
   notification is evidence that station mode is active.
5. Keep the phone on reliable external power when precision mode is enabled.

Menu wording varies by the S9+'s One UI build. Do not remove the Termux or
Termux:API exemptions while Termux remains the rollback path.

## Start, test, and stop

1. Open **Production settings**, review the defaults, and save the Cloudflare
   ingest token. The token field clears and its status becomes `configured`.
2. Return to the station screen, enter `15`, and keep precision mode selected.
3. Tap **Capture test now** and grant camera access. Confirm **Last capture**
   changes and a JPEG is saved.
4. Tap **Start station**. Confirm the status says `RUNNING` and the next capture
   is on a `00`, `15`, `30`, or `45` UTC boundary.
5. Turn the display off. Camera preview is not required.

Tap **Stop station** to cancel the fallback alarm, stop the foreground service,
and release the continuous wake lock. Captures and diagnostics are retained.
Starting again creates a fresh timing session. Manual test captures are excluded
from scheduled timing statistics.

Before running the native station, cancel the Termux scheduler so both systems
cannot request the camera:

```text
~/colors/schedule_capture_job.sh cancel
```

Restore the known working Termux job if native development pauses:

```text
~/colors/schedule_capture_job.sh install
```

## Diagnostics

**Share timing report** exports `colors-camera-timing.csv`. Each row contains
the intended UTC slot, trigger source, service receipt, capture start and
completion, timing deltas, screen/power state, safe error code, and image path.
The report also includes palette JSON, size, analysis dimensions, sampled-pixel
count, elapsed time, peak process memory, and the upload pass counts and error
code associated with each completed capture. The ingest token and configuration
secrets are never included.

**Share latest production image** opens Android's share sheet for the newest
fully committed JPEG. This makes daylight, sunset, night, overcast, orientation,
and file-size checks possible without moving the mounted phone.

Normalized JPEGs are written with upright pixels and an explicit EXIF
orientation of `normal`. Android devices that return the equivalent
`undefined` value for an orientation-free JPEG are also accepted.

Common error codes include `CAMERA_PERMISSION_MISSING`, `CAMERA_BIND_FAILED`,
`CAMERA_CAPTURE_*`, `CAPTURE_TIMEOUT`, `OVERLAP_PREVENTED`, `DUPLICATE_SLOT`,
`PROCESS_INTERRUPTED`, `ALARM_SCHEDULE_FAILED`,
`PRECISION_REQUIRES_EXTERNAL_POWER`, and
`PRECISION_REQUIRES_BATTERY_EXEMPTION`. Palette failures use safe codes such as
`PALETTE_IMAGE_DECODE_FAILED`, `PALETTE_MASK_INVALID`,
`PALETTE_QUANTIZATION_INVALID`, and `PALETTE_EXTRACTION_FAILED`. Durable-store
codes include `OUTBOX_INITIALIZATION_FAILED`, `OUTBOX_BACKPRESSURE`,
`OUTBOX_INSPECTION_FAILED`,
`PROCESS_INTERRUPTED_RECOVERABLE`, and `IMMUTABLE_EVIDENCE_INCONSISTENT`.
Delivery codes include `INGEST_TOKEN_UNAVAILABLE`, `REQUEST_TIMEOUT`,
`NETWORK_REQUEST_FAILED`, `SERVER_RETRYABLE`, `AUTHORIZATION_REJECTED`,
`REQUEST_REJECTED`, `REDIRECT_REJECTED`, `IDEMPOTENCY_CONFLICT`,
`UPLOAD_RETRY_THRESHOLD`, and `UPLOAD_ATTENTION_REQUIRED`.

## Stop or uninstall

Stop station mode before uninstalling. Then use **Settings > Apps > Colors
Camera > Uninstall**. Uninstalling removes the app's configuration, Keystore
credential, JPEGs, and diagnostics. It does not affect Termux or Cloudflare.
