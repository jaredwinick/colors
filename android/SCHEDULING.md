# Recurring Android captures

The dedicated Galaxy S9+ runs `capture_and_upload.sh` through Android's
JobScheduler. Android periodic jobs are intentionally inexact, so the target is
approximately one cycle every 15 minutes rather than a wall-clock alarm.

## Production policy

The scheduler controller reserves job ID `1701` and registers these constraints:

| Setting | Value | Reason |
| --- | --- | --- |
| Period | `900000` ms by default | Android 7+ minimum periodic interval |
| Network | `any` | Avoids the Termux:API Android 9 null-network bug and defers while offline |
| Charging required | `false` | Continues through a brief power interruption |
| Battery not low | `false` | The dedicated, normally powered camera remains eligible |
| Storage not low | `true` | Avoids adding images while Android reports storage pressure |
| Persisted | `true` | Requests that Android retain the job through reboot |

Reinstalling or replacing job `1701` updates the existing registration rather
than creating a duplicate. `capture_and_upload.sh` also takes a non-blocking
file lock, so an overlapping invocation exits without starting a second camera
or upload cycle.

No explicit Termux wake lock is held. JobScheduler wakes the device to start the
job, Termux starts the capture script as a background task in its foreground
service, and the S9+ has already completed screen-off camera probes. A Termux
wake lock also holds a Wi-Fi lock and has no built-in timeout, so a killed script
could leave unnecessary power usage. Revisit this choice only if the timing
trial shows starts without matching cycle completions.

The foreground/kiosk fallback is not required because unattended camera access
passed on this Android 9 phone. If a later OS or vendor change blocks it, use a
Tasker Time profile plus Termux:Tasker to invoke the same single-cycle script.

## Phone installation

Quick Share these two additional files into Android Downloads:

- `schedule_capture_job.sh`
- `schedule_timing.py`

Then run:

```sh
cp ~/storage/downloads/schedule_capture_job.sh ~/colors/
cp ~/storage/downloads/schedule_timing.py ~/colors/
chmod 700 ~/colors/schedule_capture_job.sh ~/colors/schedule_timing.py
```

The earlier camera probe used job ID `1101`. Retire it before registering the
production job so two independent schedules cannot use the camera:

```sh
termux-job-scheduler --cancel --job-id 1101
```

Register the default 15-minute production schedule:

```sh
~/colors/schedule_capture_job.sh install
```

To select a longer period, pass milliseconds. The controller stores the value
in `~/.config/colors/schedule-period-ms` for later status reports and reboots:

```sh
~/colors/schedule_capture_job.sh replace 1800000
```

Periods below 900,000 ms and values above Android's signed 32-bit command limit
are rejected before registration.

## Inspect, replace, and cancel

```sh
~/colors/schedule_capture_job.sh status
~/colors/schedule_capture_job.sh replace
~/colors/schedule_capture_job.sh cancel
```

`status` prints every pending Termux job, warns if job `1701` is absent, reports
timing statistics since the latest successful registration, and shows the last
30 job-log lines. Earlier manual test cycles remain in the log but are excluded
from drift calculations. Cancellation removes only job `1701`; captures, queued
uploads, delivered copies, and logs remain.

For a compact timing-only report:

```sh
~/colors/schedule_capture_job.sh timing
```

## Samsung Android 9 settings

Keep the phone powered and confirm both Termux apps remain exempt from Samsung
sleep controls:

1. In **Settings > Apps > Special access > Optimize battery usage**, show all
   apps and turn optimization off for **Termux** and **Termux:API**.
2. In **Settings > Device care > Battery**, remove both apps from Sleeping apps
   and add them to the never-sleep/unmonitored list if that option is present.
3. Allow Termux and Termux:API notifications so background-service or repeated
   upload failures remain visible.

Samsung menu wording varies slightly by One UI release. Do not enable a global
device wake lock for the initial trial.

## Acceptance run

Start with the phone plugged in and Wi-Fi connected. Record results in Issue
`#17`; timestamps from `status` are sufficient evidence.

1. **Registration:** run `install`, then `status`. Confirm job `1701` is shown as
   periodic, persisted, and points to `~/colors/capture_and_upload.sh`.
2. **Screen on:** leave the phone alone for at least 20 minutes and confirm a new
   successful cycle appears.
3. **Screen off / Doze:** turn off the display without opening Termux for at
   least 60 minutes. Confirm multiple later cycles complete and do not overlap.
4. **No network:** disable Wi-Fi and mobile data for at least 20 minutes. The
   `network=any` constraint should defer the job, not run a failing cycle.
   Restore Wi-Fi and confirm scheduling and uploads resume.
5. **Battery:** while safely powered or with adequate remaining charge, confirm
   `status` still shows `battery-not-low` is not required. The job should not be
   gated by charging state; do not intentionally deep-discharge the mounted
   phone.
6. **Reboot:** reboot without cancelling job `1701`. Do not manually launch
   Termux after startup. After at least 20 minutes, confirm another cycle reached
   the Worker, then run `status` and confirm job `1701` remains persisted.
7. **Timing drift:** after at least three hours (preferably 12 or more starts),
   run `timing` and paste the report into Issue `#17`.

Every successful run ends with `Cycle complete`. A later `Starting durable
capture cycle` without a matching completion is the signal to investigate a
wake lock or foreground/kiosk fallback. Timing gaps while offline are expected
because the Android 9 compatibility constraint requires a network.

## Official behavior used here

The Termux command documents the 900,000 ms Android 7+ minimum and the available
network, battery, storage, charging, and persistence flags:

- <https://raw.githubusercontent.com/termux/termux-api-package/master/scripts/termux-job-scheduler.in>

Termux:API maps those flags to `JobInfo.Builder`, replaces an existing job with
the same ID, and launches the executable through Termux's foreground service:

- <https://github.com/termux/termux-api/blob/master/app/src/main/java/com/termux/api/apis/JobSchedulerAPI.java>
