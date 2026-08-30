# Colors Camera for Android

This directory contains the native Android camera station. It builds on the
CameraX scheduling proof of concept from Issue #27 and is being productionized
through Issues #30-#38. The app targets the dedicated Samsung Galaxy S9+ running
Android 10 (API 29).

The native app currently provides the production foundation, proven capture
scheduler, production JPEG normalization, and fixed sky-mask calibration.
Palette extraction, durable upload, and full operations screens are delivered
by the subsequent roadmap issues. The Termux client in `android/` remains the
rollback path until the native pipeline passes its production soak test.

## Production identity and architecture

- Application name: **Colors Camera**
- Application ID and namespace: `com.jaredwinick.colors.camera`
- Version: `0.4.0` (`versionCode` 6)
- Capture files: app-specific external `Pictures/captures`
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
| `processing` | JPEG normalization and later mask/palette work |
| `mask` | Schema-v1 validation, rasterization, durable calibration, and previews |
| `network` | Reserved for durable Worker upload work in Issues #33-#34 |

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
| Palette colors | 6 |
| Palette analysis dimension | 180 px |
| Maximum pending captures | 192 |
| Maximum uploads per cycle | 4 |
| Request timeout | 120 seconds |
| Retry range | 60-3600 seconds |
| Notify after failures | 3 attempts |
| Delivered-file retention | 7 days / 672 captures |
| Log size | 1 MiB |

Configuration schema version 3 is stored in app-private Android preferences.
Schema-1 and schema-2 settings receive current defaults for new fields during
migration; an unknown future schema is never interpreted as current
configuration. All values are validated before saving.

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

The normalized JPEG remains a complete, unmasked view. It is staged under
`Pictures/capture-work`, then committed to `Pictures/captures`; its versioned
metadata is committed last under `Pictures/capture-metadata`. A metadata file is
therefore the completion marker and can never point to a partial JPEG. Startup
removes temporary work and new-format orphan images while leaving legacy POC
captures alone.

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

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The
`android-native.yml` GitHub Actions workflow runs the same command and publishes
the APK as the `colors-camera-debug` artifact. This is the reproducible build
path when Android Studio and the Android SDK are not installed locally.

## Signing and upgrade-safe sideloads

Android permits an in-place upgrade only when the application ID and signing
certificate match the installed app. A local debug build normally keeps a
stable key in the builder's Gradle home, but GitHub-hosted runners do not retain
their generated debug key between workflow runs. An APK from a different CI run
may therefore require uninstalling the prior CI APK, which deletes local app
data.

For upgrade-safe production sideloads, keep one private release keystore outside
the repository and sign every APK with that same key. Never commit the keystore
or its passwords. A maintainer can either configure Android Studio's signed APK
wizard or add a local Gradle signing configuration sourced from environment
variables. Before upgrading:

1. Stop station mode and confirm there is no capture in progress.
2. Export any timing diagnostics needed for troubleshooting.
3. Build and sign the new APK with the same private release key.
4. Transfer it with Quick Share and open it; Android should offer **Update**.
5. Open Colors Camera, confirm the saved settings and credential status, then
   start the station and run one test capture.

If Android offers only uninstall/reinstall or reports a signature mismatch,
stop. Obtain an APK signed with the original key unless deleting the app's
configuration, token, diagnostics, and captures is acceptable.

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
   ingest token. Upload is implemented in later issues, but provisioning the
   credential now verifies secure persistence.
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
The ingest token and configuration secrets are never included.

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
`PRECISION_REQUIRES_BATTERY_EXEMPTION`.

## Stop or uninstall

Stop station mode before uninstalling. Then use **Settings > Apps > Colors
Camera > Uninstall**. Uninstalling removes the app's configuration, Keystore
credential, JPEGs, and diagnostics. It does not affect Termux or Cloudflare.
