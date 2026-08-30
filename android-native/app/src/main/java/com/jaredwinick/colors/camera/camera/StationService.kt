package com.jaredwinick.colors.camera.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.jaredwinick.colors.camera.R
import com.jaredwinick.colors.camera.config.CameraLens
import com.jaredwinick.colors.camera.config.AppConfiguration
import com.jaredwinick.colors.camera.config.ConfigurationStore
import com.jaredwinick.colors.camera.diagnostics.CaptureDiagnostic
import com.jaredwinick.colors.camera.persistence.DiagnosticStore
import com.jaredwinick.colors.camera.persistence.ProductionCaptureMetadata
import com.jaredwinick.colors.camera.persistence.ProductionCaptureRepository
import com.jaredwinick.colors.camera.persistence.StationPreferences
import com.jaredwinick.colors.camera.processing.CaptureProcessingException
import com.jaredwinick.colors.camera.processing.ImageNormalizer
import com.jaredwinick.colors.camera.schedule.AlarmScheduler
import com.jaredwinick.colors.camera.schedule.UtcSchedule
import com.jaredwinick.colors.camera.ui.MainActivity
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCamera2Interop::class)
class StationService : LifecycleService() {
    private lateinit var preferences: StationPreferences
    private lateinit var diagnostics: DiagnosticStore
    private lateinit var configurationStore: ConfigurationStore
    private lateinit var captureRepository: ProductionCaptureRepository
    private val imageNormalizer = ImageNormalizer()
    private val processingExecutor = Executors.newSingleThreadExecutor()
    private val captureInProgress = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stationWakeLock: PowerManager.WakeLock? = null
    private var precisionTimer: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        preferences = StationPreferences(this)
        diagnostics = DiagnosticStore(this)
        configurationStore = ConfigurationStore(this)
        captureRepository = ProductionCaptureRepository(this)
        diagnostics.recoverInterrupted()
        createNotificationChannel()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START, ACTION_RESTORE -> restoreSchedule()
            ACTION_STOP -> stopStation()
            ACTION_CAPTURE -> {
                // An alarm may be the normal trigger or may be recovering a
                // process that Android removed. Restore the next precision
                // timer and its station wake lock before doing camera work.
                if (preferences.enabled && preferences.precisionMode) {
                    configurePrecisionRuntime(preferences.nextCaptureAt)
                }
                capture(
                    scheduledFor = intent.getLongExtra(EXTRA_SCHEDULED_FOR, System.currentTimeMillis()),
                    manual = false,
                    triggerSource = CaptureDiagnostic.TRIGGER_ALARM,
                    triggerReceivedAt = intent.getLongExtra(
                        EXTRA_TRIGGER_RECEIVED_AT,
                        System.currentTimeMillis(),
                    ),
                    slotAlreadyClaimed = false,
                )
            }
            ACTION_CAPTURE_TEST -> capture(
                scheduledFor = System.currentTimeMillis(),
                manual = true,
                triggerSource = CaptureDiagnostic.TRIGGER_MANUAL,
                triggerReceivedAt = System.currentTimeMillis(),
                slotAlreadyClaimed = false,
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
            .onSuccess(::configurePrecisionRuntime)
            .onFailure { preferences.setLastError("ALARM_SCHEDULE_FAILED") }
        updateNotification()
    }

    private fun stopStation() {
        AlarmScheduler(this).cancel()
        cancelPrecisionTimer()
        releaseStationWakeLock()
        preferences.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun capture(
        scheduledFor: Long,
        manual: Boolean,
        triggerSource: String,
        triggerReceivedAt: Long,
        slotAlreadyClaimed: Boolean,
    ) {
        val serviceReceivedAt = System.currentTimeMillis()
        if (!manual && !preferences.enabled) return

        val sessionId = if (manual) "manual" else preferences.sessionId
        val power = PowerSnapshotReader.read(this)
        val captureId = UUID.randomUUID().toString()
        val record = CaptureDiagnostic(
            recordId = captureId,
            captureId = captureId,
            sessionId = sessionId,
            slotId = if (manual) "manual-$serviceReceivedAt" else "utc-$scheduledFor",
            scheduledFor = scheduledFor,
            alarmReceivedAt = triggerReceivedAt,
            serviceReceivedAt = serviceReceivedAt,
            triggerSource = triggerSource,
            screenInteractive = power.screenInteractive,
            charging = power.charging,
            plugged = power.plugged,
            batteryPercent = power.batteryPercent,
            deviceIdleMode = power.deviceIdleMode,
            powerSaveMode = power.powerSaveMode,
            batteryOptimizationExempt = power.batteryOptimizationExempt,
            stationWakeLockHeld = stationWakeLock?.isHeld == true,
            manual = manual,
        )

        if (!manual && !slotAlreadyClaimed && !preferences.claimScheduledSlot(scheduledFor)) {
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
        var cameraProvider: ProcessCameraProvider? = null
        val watchdog = Runnable {
            if (finished.compareAndSet(false, true)) {
                cameraProvider?.unbindAll()
                captureRepository.abandon(captureId)
                releaseWakeLock(wakeLock)
                finishCapture(activeRecord, "CAPTURE_TIMEOUT")
            }
        }
        mainHandler.postDelayed(watchdog, CAPTURE_TIMEOUT_MS)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (finished.get()) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                provider.unbindAll()
                val configuration = configurationStore.load()
                val selector = when (configuration.cameraLens) {
                    CameraLens.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                    CameraLens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                }
                val cameraInfo = selector.filter(provider.availableCameraInfos).firstOrNull()
                    ?: throw IllegalStateException("Configured camera is unavailable")
                val imageCaptureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(configuration.jpegQuality)
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                val appliedSettings = CameraControlConfigurator.configure(
                    imageCaptureBuilder,
                    cameraInfo,
                    configuration,
                )
                val cameraId = Camera2CameraInfo.from(cameraInfo).cameraId
                val imageCapture = imageCaptureBuilder.build()
                provider.bindToLifecycle(this, selector, imageCapture)

                val startedAt = System.currentTimeMillis()
                val outputFile = captureRepository.rawFile(captureId)
                activeRecord = record.copy(
                    captureStartedAt = startedAt,
                    cameraSettings = appliedSettings.diagnosticSummary(),
                )
                diagnostics.begin(activeRecord)
                imageCapture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(outputFile).build(),
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            if (finished.get()) {
                                captureRepository.abandon(captureId)
                                return
                            }
                            cameraProvider?.unbindAll()
                            val capturedAt = System.currentTimeMillis()
                            processCapturedImage(
                                captureId = captureId,
                                rawFile = outputFile,
                                capturedAt = capturedAt,
                                record = activeRecord,
                                configuration = configuration,
                                selectedCamera = configuration.cameraLens.name,
                                cameraId = cameraId,
                                appliedSettings = appliedSettings,
                                finished = finished,
                                watchdog = watchdog,
                                wakeLock = wakeLock,
                            )
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (!finished.compareAndSet(false, true)) return
                            mainHandler.removeCallbacks(watchdog)
                            cameraProvider?.unbindAll()
                            captureRepository.abandon(captureId)
                            releaseWakeLock(wakeLock)
                            finishCapture(activeRecord, "CAMERA_CAPTURE_${exception.imageCaptureError}")
                        }
                    },
                )
            } catch (_: Exception) {
                if (finished.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(watchdog)
                    cameraProvider?.unbindAll()
                    captureRepository.abandon(captureId)
                    releaseWakeLock(wakeLock)
                    finishCapture(activeRecord, "CAMERA_BIND_FAILED")
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processCapturedImage(
        captureId: String,
        rawFile: File,
        capturedAt: Long,
        record: CaptureDiagnostic,
        configuration: AppConfiguration,
        selectedCamera: String,
        cameraId: String,
        appliedSettings: AppliedCameraSettings,
        finished: AtomicBoolean,
        watchdog: Runnable,
        wakeLock: PowerManager.WakeLock,
    ) {
        processingExecutor.execute {
            val processingStartedAt = SystemClock.elapsedRealtime()
            val normalizedTemp = captureRepository.normalizedTempFile(captureId)
            val normalization = runCatching {
                imageNormalizer.normalize(
                    source = rawFile,
                    destination = normalizedTemp,
                    maximumDimension = configuration.maxImageDimension,
                    jpegQuality = configuration.jpegQuality,
                )
            }
            if (!finished.compareAndSet(false, true)) {
                captureRepository.abandon(captureId)
                return@execute
            }
            mainHandler.removeCallbacks(watchdog)
            val processingDurationMs = SystemClock.elapsedRealtime() - processingStartedAt
            val completion = normalization.mapCatching { image ->
                val metadata = ProductionCaptureMetadata(
                    captureId = captureId,
                    capturedAt = Instant.ofEpochMilli(capturedAt).toString(),
                    sourceDimensions = image.sourceDimensions,
                    finalDimensions = image.finalDimensions,
                    byteCount = image.byteCount,
                    selectedCamera = selectedCamera,
                    cameraId = cameraId,
                    jpegQuality = configuration.jpegQuality,
                    maximumDimension = configuration.maxImageDimension,
                    sourceExifOrientation = image.sourceExifOrientation,
                    cameraSettings = appliedSettings,
                    processingDurationMs = processingDurationMs,
                )
                val finalFile = captureRepository.commit(normalizedTemp, metadata)
                image to finalFile
            }
            rawFile.delete()

            mainHandler.post {
                releaseWakeLock(wakeLock)
                completion.fold(
                    onSuccess = { (image, finalFile) ->
                        val completedAt = System.currentTimeMillis()
                        diagnostics.complete(
                            record.copy(
                                capturedAt = capturedAt,
                                completedAt = completedAt,
                                result = CaptureDiagnostic.RESULT_SUCCESS,
                                imagePath = finalFile.absolutePath,
                                imageBytes = image.byteCount,
                                sourceWidth = image.sourceDimensions.width,
                                sourceHeight = image.sourceDimensions.height,
                                width = image.finalDimensions.width,
                                height = image.finalDimensions.height,
                                processingDurationMs = processingDurationMs,
                                cameraSettings = appliedSettings.diagnosticSummary(),
                            ),
                        )
                        preferences.setLastCapture(capturedAt)
                        captureInProgress.set(false)
                        updateNotification()
                        stopIfManualOnly()
                    },
                    onFailure = { error ->
                        captureRepository.abandon(captureId)
                        val code = if (error is CaptureProcessingException) {
                            error.code
                        } else {
                            "CAPTURE_COMMIT_FAILED"
                        }
                        finishCapture(
                            record.copy(
                                capturedAt = capturedAt,
                                processingDurationMs = processingDurationMs,
                                cameraSettings = appliedSettings.diagnosticSummary(),
                            ),
                            code,
                        )
                    },
                )
            }
        }
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

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock) {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun configurePrecisionRuntime(scheduledFor: Long) {
        if (!preferences.precisionMode || !preferences.enabled) {
            cancelPrecisionTimer()
            releaseStationWakeLock()
            return
        }
        val power = PowerSnapshotReader.read(this)
        when {
            !power.plugged -> {
                preferences.setLastError("PRECISION_REQUIRES_EXTERNAL_POWER")
                cancelPrecisionTimer()
                releaseStationWakeLock()
            }
            !power.batteryOptimizationExempt -> {
                preferences.setLastError("PRECISION_REQUIRES_BATTERY_EXEMPTION")
                cancelPrecisionTimer()
                releaseStationWakeLock()
            }
            else -> {
                acquireStationWakeLock()
                schedulePrecisionTimer(scheduledFor)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireStationWakeLock() {
        if (stationWakeLock?.isHeld == true) return
        stationWakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:precision-station",
        ).apply {
            setReferenceCounted(false)
            // Deliberately unbounded for this opt-in, powered-device experiment.
            // stopStation(), onDestroy(), or a failed power prerequisite releases it.
            acquire()
        }
    }

    private fun releaseStationWakeLock() {
        stationWakeLock?.let(::releaseWakeLock)
        stationWakeLock = null
    }

    private fun schedulePrecisionTimer(scheduledFor: Long) {
        cancelPrecisionTimer()
        val runnable = Runnable { onPrecisionTimer(scheduledFor) }
        precisionTimer = runnable
        val delay = (scheduledFor - System.currentTimeMillis()).coerceAtLeast(0)
        mainHandler.postAtTime(runnable, SystemClock.uptimeMillis() + delay)
    }

    private fun cancelPrecisionTimer() {
        precisionTimer?.let(mainHandler::removeCallbacks)
        precisionTimer = null
    }

    private fun onPrecisionTimer(scheduledFor: Long) {
        precisionTimer = null
        if (!preferences.enabled || !preferences.precisionMode) return
        val firedAt = System.currentTimeMillis()
        if (firedAt < scheduledFor) {
            schedulePrecisionTimer(scheduledFor)
            return
        }

        // Own the slot before touching AlarmManager. A fallback alarm that is
        // already being delivered will therefore observe this claim and exit.
        if (!preferences.claimScheduledSlot(scheduledFor)) {
            capture(
                scheduledFor = scheduledFor,
                manual = false,
                triggerSource = CaptureDiagnostic.TRIGGER_TIMER,
                triggerReceivedAt = firedAt,
                slotAlreadyClaimed = false,
            )
            return
        }

        // Cancel this slot's uniquely identified fallback, then register the
        // next one. If cancellation races with delivery, the old alarm still
        // contains this slot (never the next slot) and exits on the claim above.
        AlarmScheduler(this).cancelFallback(scheduledFor)
        runCatching {
            AlarmScheduler(this).scheduleNext(maxOf(firedAt, scheduledFor))
        }.onSuccess(::configurePrecisionRuntime)
            .onFailure { preferences.setLastError("ALARM_RESCHEDULE_FAILED") }

        capture(
            scheduledFor = scheduledFor,
            manual = false,
            triggerSource = CaptureDiagnostic.TRIGGER_TIMER,
            triggerReceivedAt = firedAt,
            slotAlreadyClaimed = true,
        )
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
            preferences.enabled && preferences.precisionMode ->
                "Precision next: ${UtcSchedule.format(preferences.nextCaptureAt)}"
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
            description = "Status for UTC-aligned sky captures"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        cancelPrecisionTimer()
        releaseStationWakeLock()
        processingExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.jaredwinick.colors.camera.START"
        const val ACTION_STOP = "com.jaredwinick.colors.camera.STOP"
        const val ACTION_RESTORE = "com.jaredwinick.colors.camera.RESTORE"
        const val ACTION_CAPTURE = "com.jaredwinick.colors.camera.CAPTURE"
        const val ACTION_CAPTURE_TEST = "com.jaredwinick.colors.camera.CAPTURE_TEST"
        const val EXTRA_SCHEDULED_FOR = "scheduled_for"
        const val EXTRA_TRIGGER_RECEIVED_AT = "trigger_received_at"

        private const val CHANNEL_ID = "camera_station_v1"
        private const val NOTIFICATION_ID = 2701
        private const val CAPTURE_TIMEOUT_MS = 90_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 120_000L
    }
}
