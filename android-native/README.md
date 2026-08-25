# Native Android camera timing proof of concept

This project implements the experiment in GitHub Issue #27. It tests whether a
mounted Samsung Galaxy S9+ on Android 10 can take screen-off photographs near
15-minute UTC boundaries more consistently than the existing Termux
`JobScheduler` client.

It is deliberately separate from `android/`, which remains the working Termux
capture and upload system. The proof of concept saves local JPEGs and timing
diagnostics only. It does not mask images, extract palettes, contact the ingest
API, or change Cloudflare data.

## What the app does

- Runs a persistent camera foreground service with a visible notification.
- Schedules one `setExactAndAllowWhileIdle()` alarm at a time.
- Calculates every next time from a UTC wall-clock boundary, not from the prior
  capture's completion time.
- Acquires a partial wake lock for at most two minutes around CameraX work.
- Captures through the rear camera with no preview and closes the camera after
  every JPEG.
- Prevents concurrent work and persistently claims each scheduled slot.
- Restores an enabled station after reboot once the user has unlocked Android.
- Preserves an in-progress record so a killed process becomes a visible
  `PROCESS_INTERRUPTED` result instead of a silent loss.
- Shows a live timing summary and shares the complete CSV report through the
  Android share sheet.
- Offers an opt-in precision experiment that holds a partial wake lock while
  station mode is active and uses an in-process UTC timer. The exact alarm stays
  registered as a recovery fallback if Android removes the process.

The APK targets Android 10 (API 29). The manifest includes forward-compatible
camera foreground-service and exact-alarm declarations, but newer Android
versions impose additional foreground-service and exact-alarm restrictions.
Issue #27 is evaluated only on the Android 10 Galaxy S9+.

## Build

The pinned build uses Android Gradle Plugin 8.9.2, Gradle 8.11.1, JDK 17,
compile SDK 36, Kotlin 2.1.20, and CameraX 1.6.1.

From `android-native`:

```text
./gradlew testDebugUnitTest assembleDebug
```

On Windows:

```text
gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The repository's `android-native.yml` GitHub Actions workflow runs the same
tests and build and publishes `colors-camera-poc-debug` as a downloadable
artifact. This is an alternative when Android Studio and the Android SDK are
not installed locally.

## Sideload on the mounted phone

1. Download `app-debug.apk` from a successful GitHub Actions run or build it
   locally.
2. Move the APK to the Galaxy S9+ with Quick Share.
3. Open the transferred APK on the phone. If Android asks, allow that file
   manager to install unknown apps, then install **Colors Camera POC**.
4. Open the app and tap **Capture test now**. Grant Camera access when asked.
5. Confirm a low-priority **Colors camera station** notification appears and
   the app's **Last capture** time changes. A successful test JPEG is retained
   in the app's external files area.

If reinstalling a new debug APK over the prior build fails because its signing
key changed, uninstall the old proof-of-concept app first. Uninstalling deletes
its local JPEGs and timing diagnostics, so share the CSV report before doing so.

## Samsung Android 10 setup

Keep the phone powered and retain the same exemptions used by Termux:

1. Open **Settings > Apps > Colors Camera POC > Battery** and allow background
   activity if Samsung shows that control.
2. Open **Settings > Apps > Special access > Optimize battery usage**, show all
   apps, and turn optimization off for **Colors Camera POC**.
3. Open **Device care > Battery > App power management** and add the app to
   **Apps that won't be put to sleep**. Remove it from sleeping or deep sleeping
   lists if necessary.
4. Keep notifications enabled. The persistent notification is evidence that
   station mode is active.

Menu wording varies by the S9+'s One UI build. Do not remove the existing
Termux or Termux:API exemptions.

## Start and stop station mode

The interval must divide evenly into a 1,440-minute UTC day. Use `15` for the
acceptance trial.

1. Enter `15` under **UTC interval (minutes)**.
2. Tap **Start station**.
3. Confirm the screen says `RUNNING` and shows the next `00`, `15`, `30`, or
   `45` UTC boundary.
4. Turn the display off. A camera preview is never required.

Tap **Stop station** to cancel the next alarm and remove the foreground
service. Stopping retains JPEGs and diagnostic records. Starting again creates
a new timing session so older results do not contaminate the live summary.

`Capture test now` is deliberately excluded from scheduled timing statistics.

## Precision wake-lock experiment

This second trial is for the permanently mounted, externally powered phone. It
tests whether keeping the CPU awake removes the roughly 170-second Doze delay
seen in the first alarm-only CSV. It is deliberately optional because a
continuous partial wake lock consumes more energy than alarm-only mode.

Before replacing an earlier debug build, share its CSV. GitHub Actions debug
APKs may use different signing keys, so Android may require uninstalling the old
build before installing the new one; uninstalling also removes the app's local
JPEGs and diagnostics.

1. Keep the Galaxy S9+ connected to reliable external power.
2. Confirm battery optimization is off for **Colors Camera POC**. The app's
   **Open battery optimization settings** button opens the relevant system list.
3. Leave the interval at `15` and select **Precision experiment (powered phone)**.
4. Tap **Start station**. The app refuses to start precision mode if power is
   disconnected or Android still reports battery optimization as enabled.
5. Turn the screen off and leave the station running for at least six hours.
   Twenty-four hours gives a better comparison with the alarm-only trial.
6. Share the new CSV and attach it to Issue #27.

The status display should say `PRECISION EXPERIMENT`. In the new CSV:

- `trigger_source` should normally be `TIMER`; `ALARM` means the recovery
  fallback was needed.
- `station_wake_lock_held`, `plugged`, and `battery_optimization_exempt` should
  be `true` for precision captures.
- `trigger_lateness_ms` measures timer or alarm delivery against the intended
  UTC boundary, while `capture_lateness_ms` includes CameraX work.
- `device_idle_mode` shows whether Android considered the phone to be in Doze,
  which lets us test the wake lock rather than infer its effect.

If the phone becomes noticeably warm, loses charge while plugged in, or must be
removed from dedicated station use, stop the experiment. Stopping station mode
releases the continuous wake lock immediately. If external power is removed
after startup, the next timer check releases the wake lock and leaves the exact
alarm as the lower-power fallback.

## Avoid camera contention with Termux

Development and a one-off native camera test can leave the production Termux
job installed. Before the actual timing trial, cancel only its scheduler job so
the two systems cannot request the rear camera at the same moment:

```text
~/colors/schedule_capture_job.sh cancel
```

Do not delete Termux, its scripts, captures, configuration, or ingest secret.
After stopping the native proof of concept, restore the known working job with:

```text
~/colors/schedule_capture_job.sh install
```

## Six-hour screen-off acceptance trial

1. Stop the Termux scheduler as described above.
2. In the native app, start a new 15-minute station session shortly before any
   UTC quarter-hour boundary.
3. Leave the mounted phone powered with its screen off for at least six hours,
   producing at least 24 elapsed slots.
4. Inspect the persistent notification or reopen the app without stopping it.
   The report must show no missing slots and no duplicate records.
5. Reboot once during a follow-up run. Unlock the phone but do not manually open
   the app. Within one slot, confirm the foreground notification returns and a
   later capture succeeds.
6. Tap **Share timing report** and send `colors-camera-timing.csv` with Quick
   Share, email, or another installed share target. The phone can remain in its
   mount.
7. Attach the CSV and the displayed summary to Issue #27.

The Issue #27 success gate is at least 24 slots, no concurrent or duplicate
captures, no silent missing slots, at least 95% of successful captures within
60 seconds, a worst successful lateness within 180 seconds, and recovery after
reboot. A narrowly worse result should still be compared explicitly with the
Issue #17 baseline.

## Diagnostic meanings

Each CSV row contains the intended UTC slot, trigger source and receipt,
foreground-service receipt, capture start, successful capture, completion,
trigger/capture lateness, screen and power state, result, and a safe error code.
`captured_at` is the actual CameraX success time and is never replaced with the
intended slot.

Common error codes:

| Code | Meaning |
| --- | --- |
| `CAMERA_PERMISSION_MISSING` | Camera access was removed after setup. |
| `CAMERA_BIND_FAILED` | CameraX could not open/bind the rear camera. Check for another camera owner. |
| `CAMERA_CAPTURE_*` | CameraX returned a categorized still-capture failure. |
| `CAPTURE_TIMEOUT` | Camera work exceeded 90 seconds and was closed. |
| `OVERLAP_PREVENTED` | A prior capture was still active; no second camera operation started. |
| `DUPLICATE_SLOT` | A repeated alarm referred to an already claimed UTC slot. |
| `PROCESS_INTERRUPTED` | Android killed the process after a slot began; the pending record was recovered. |
| `ALARM_SCHEDULE_FAILED` | The next exact alarm could not be registered. |
| `PRECISION_REQUIRES_EXTERNAL_POWER` | Precision mode stopped holding its station wake lock because external power was absent. |
| `PRECISION_REQUIRES_BATTERY_EXEMPTION` | Precision mode could not start while battery optimization was enabled. |

## Stop or uninstall

Stop station mode in the app before uninstalling. Then use **Settings > Apps >
Colors Camera POC > Uninstall**. This does not affect Termux or any Cloudflare
resource. Restore the Termux scheduler afterward if the production capture
system should resume.
