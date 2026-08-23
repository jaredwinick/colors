package com.jaredwinick.colors.poc

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class StationService : LifecycleService() {
    private lateinit var preferences: StationPreferences
    private lateinit var diagnostics: DiagnosticStore
    private val captureInProgress = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        preferences = StationPreferences(this)
        diagnostics = DiagnosticStore(this)
        diagnostics.recoverInterrupted()
        createNotificationChannel()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START, ACTION_RESTORE -> restoreSchedule()
            ACTION_STOP -> stopStation()
            ACTION_CAPTURE -> capture(
                scheduledFor = intent.getLongExtra(EXTRA_SCHEDULED_FOR, System.currentTimeMillis()),
                manual = false,
            )
            ACTION_CAPTURE_TEST -> capture(
                scheduledFor = System.currentTimeMillis(),
                manual = true,
            )
            null -> if (preferences.enabled) restoreSchedule() else stopSelf()
        }
        return Service.START_STICKY
    }

    private fun restoreSchedule() {
        if (!preferences.enabled) {
            stopSelf()
            return
        }
        runCatching { AlarmScheduler(this).scheduleNext() }
            .onFailure { preferences.setLastError("ALARM_SCHEDULE_FAILED") }
        updateNotification()
    }

    private fun stopStation() {
        AlarmScheduler(this).cancel()
        preferences.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun capture(scheduledFor: Long, manual: Boolean) {
        val receivedAt = System.currentTimeMillis()
        if (!manual && !preferences.enabled) return

        val sessionId = if (manual) "manual" else preferences.sessionId
        val record = CaptureDiagnostic(
            recordId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            slotId = if (manual) "manual-$receivedAt" else "utc-$scheduledFor",
            scheduledFor = scheduledFor,
            alarmReceivedAt = receivedAt,
            screenInteractive = isScreenInteractive(),
            charging = isCharging(),
            manual = manual,
        )

        if (!manual && !preferences.claimScheduledSlot(scheduledFor)) {
            diagnostics.complete(
                record.copy(
                    completedAt = System.currentTimeMillis(),
                    result = "SKIPPED",
                    errorCode = "DUPLICATE_SLOT",
                ),
            )
            return
        }

        if (!captureInProgress.compareAndSet(false, true)) {
            diagnostics.complete(
                record.copy(
                    completedAt = System.currentTimeMillis(),
                    result = "SKIPPED",
                    errorCode = "OVERLAP_PREVENTED",
                ),
            )
            preferences.setLastError("OVERLAP_PREVENTED")
            return
        }

        diagnostics.begin(record)
        updateNotification()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            finishCapture(record, "CAMERA_PERMISSION_MISSING")
            return
        }

        val powerManager = getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:capture",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        val finished = AtomicBoolean(false)
        var activeRecord = record
        var activeOutputFile: File? = null
        var cameraProvider: ProcessCameraProvider? = null
        val watchdog = Runnable {
            if (finished.compareAndSet(false, true)) {
                cameraProvider?.unbindAll()
                activeOutputFile?.delete()
                releaseWakeLock(wakeLock)
                finishCapture(activeRecord, "CAPTURE_TIMEOUT")
            }
        }
        mainHandler.postDelayed(watchdog, CAPTURE_TIMEOUT_MS)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (finished.get()) return@addListener
            try {
                cameraProvider = providerFuture.get()
                cameraProvider?.unbindAll()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(JPEG_QUALITY)
                    .build()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)

                val startedAt = System.currentTimeMillis()
                val outputFile = captureFile(startedAt)
                activeOutputFile = outputFile
                activeRecord = record.copy(captureStartedAt = startedAt)
                diagnostics.begin(activeRecord)
                imageCapture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(outputFile).build(),
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            if (!finished.compareAndSet(false, true)) return
                            mainHandler.removeCallbacks(watchdog)
                            cameraProvider?.unbindAll()
                            releaseWakeLock(wakeLock)
                            val capturedAt = System.currentTimeMillis()
                            diagnostics.complete(
                                activeRecord.copy(
                                    capturedAt = capturedAt,
                                    completedAt = capturedAt,
                                    result = CaptureDiagnostic.RESULT_SUCCESS,
                                    imagePath = outputFile.absolutePath,
                                ),
                            )
                            preferences.setLastCapture(capturedAt)
                            captureInProgress.set(false)
                            updateNotification()
                            stopIfManualOnly()
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (!finished.compareAndSet(false, true)) return
                            mainHandler.removeCallbacks(watchdog)
                            cameraProvider?.unbindAll()
                            releaseWakeLock(wakeLock)
                            outputFile.delete()
                            finishCapture(activeRecord, "CAMERA_CAPTURE_${exception.imageCaptureError}")
                        }
                    },
                )
            } catch (_: Exception) {
                if (finished.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(watchdog)
                    cameraProvider?.unbindAll()
                    releaseWakeLock(wakeLock)
                    finishCapture(record, "CAMERA_BIND_FAILED")
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishCapture(record: CaptureDiagnostic, errorCode: String) {
        val completed = System.currentTimeMillis()
        diagnostics.complete(
            record.copy(
                completedAt = completed,
                result = "ERROR",
                errorCode = errorCode,
            ),
        )
        preferences.setLastError(errorCode)
        captureInProgress.set(false)
        updateNotification()
        stopIfManualOnly()
    }

    private fun stopIfManualOnly() {
        if (!preferences.enabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun captureFile(timestamp: Long): File {
        val root = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        val directory = File(root, "proof-of-concept").apply { mkdirs() }
        return File(directory, "colors-${timestamp}-${UUID.randomUUID()}.jpg")
    }

    private fun isScreenInteractive(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive

    private fun isCharging(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return when (battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
            -> true
            else -> false
        }
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock) {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val content = when {
            captureInProgress.get() -> "Capturing a scheduled sky image"
            preferences.enabled -> "Next: ${UtcSchedule.format(preferences.nextCaptureAt)}"
            else -> "Running one camera test"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_colors_camera)
            .setContentTitle("Colors camera station")
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(preferences.enabled)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Camera station",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Status for UTC-aligned proof-of-concept sky captures"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.jaredwinick.colors.poc.START"
        const val ACTION_STOP = "com.jaredwinick.colors.poc.STOP"
        const val ACTION_RESTORE = "com.jaredwinick.colors.poc.RESTORE"
        const val ACTION_CAPTURE = "com.jaredwinick.colors.poc.CAPTURE"
        const val ACTION_CAPTURE_TEST = "com.jaredwinick.colors.poc.CAPTURE_TEST"
        const val EXTRA_SCHEDULED_FOR = "scheduled_for"

        private const val CHANNEL_ID = "camera_station"
        private const val NOTIFICATION_ID = 2701
        private const val JPEG_QUALITY = 85
        private const val CAPTURE_TIMEOUT_MS = 90_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 120_000L
    }
}
