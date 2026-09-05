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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.jaredwinick.colors.camera.BuildConfig
import com.jaredwinick.colors.camera.R
import com.jaredwinick.colors.camera.config.CameraLens
import com.jaredwinick.colors.camera.config.AppConfiguration
import com.jaredwinick.colors.camera.config.ConfigurationStore
import com.jaredwinick.colors.camera.config.EndpointPolicy
import com.jaredwinick.colors.camera.config.SecureTokenStore
import com.jaredwinick.colors.camera.diagnostics.CaptureDiagnostic
import com.jaredwinick.colors.camera.mask.SkyMaskRepository
import com.jaredwinick.colors.camera.network.CaptureDeliveryCoordinator
import com.jaredwinick.colors.camera.network.DeliveryCycleSummary
import com.jaredwinick.colors.camera.network.SecureCaptureUploader
import com.jaredwinick.colors.camera.network.deliverySettings
import com.jaredwinick.colors.camera.outbox.OutboxInitializationGate
import com.jaredwinick.colors.camera.outbox.DurableCaptureRecord
import com.jaredwinick.colors.camera.outbox.OutboxSummary
import com.jaredwinick.colors.camera.palette.PaletteExtractionException
import com.jaredwinick.colors.camera.palette.PaletteExtractionResult
import com.jaredwinick.colors.camera.palette.PaletteExtractor
import com.jaredwinick.colors.camera.persistence.DiagnosticStore
import com.jaredwinick.colors.camera.persistence.ProductionCaptureMetadata
import com.jaredwinick.colors.camera.persistence.ProductionCaptureRepository
import com.jaredwinick.colors.camera.persistence.StationPreferences
import com.jaredwinick.colors.camera.processing.CaptureProcessingException
import com.jaredwinick.colors.camera.processing.ImageNormalizer
import com.jaredwinick.colors.camera.processing.ImageNormalizationResult
import com.jaredwinick.colors.camera.schedule.AlarmScheduler
import com.jaredwinick.colors.camera.schedule.UtcSchedule
import com.jaredwinick.colors.camera.ui.MainActivity
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class StationService : LifecycleService() {
    private lateinit var preferences: StationPreferences
    private lateinit var diagnostics: DiagnosticStore
    private lateinit var configurationStore: ConfigurationStore
    private lateinit var captureRepository: ProductionCaptureRepository
    private lateinit var deliveryCoordinator: CaptureDeliveryCoordinator
    private lateinit var skyMasks: SkyMaskRepository
    private val imageNormalizer = ImageNormalizer()
    private val paletteExtractor = PaletteExtractor()
    private val processingExecutor = Executors.newSingleThreadExecutor()
    private val captureInProgress = AtomicBoolean(false)
    private val uploadInProgress = AtomicBoolean(false)
    private val outboxInitialization = OutboxInitializationGate()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stationWakeLock: PowerManager.WakeLock? = null
    private var precisionTimer: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        preferences = StationPreferences(this)
        diagnostics = DiagnosticStore(this)
        configurationStore = ConfigurationStore(this)
        captureRepository = ProductionCaptureRepository(this)
        val tokenStore = SecureTokenStore(this)
        deliveryCoordinator = CaptureDeliveryCoordinator(
            captures = captureRepository,
            transport = SecureCaptureUploader(),
            tokenReader = tokenStore::read,
        )
        val startupConfiguration = configurationStore.load()
        processingExecutor.execute {
            runCatching {
                captureRepository.migrateLegacy(startupConfiguration.deviceId)
                captureRepository.reconcile()
                captureRepository.applyDeliveredRetention(
                    startupConfiguration.retentionDays,
                    startupConfiguration.retentionCount,
                )
            }.fold(
                onSuccess = {
                    mainHandler.post {
                        outboxInitialization.completeSuccessfully()
                        updateNotification()
                    }
                },
                onFailure = {
                    mainHandler.post {
                        preferences.setLastError("OUTBOX_INITIALIZATION_FAILED")
                        updateNotification()
                        outboxInitialization.completeWithFailure()
                    }
                },
            )
        }
        skyMasks = SkyMaskRepository(this)
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
                requestCapture(
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
            ACTION_CAPTURE_TEST -> requestCapture(
                scheduledFor = System.currentTimeMillis(),
                manual = true,
                triggerSource = CaptureDiagnostic.TRIGGER_MANUAL,
                triggerReceivedAt = System.currentTimeMillis(),
                slotAlreadyClaimed = false,
            )
            ACTION_UPLOAD_PENDING -> requestUploadPendingOnly()
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

    private fun requestCapture(
        scheduledFor: Long,
        manual: Boolean,
        triggerSource: String,
        triggerReceivedAt: Long,
        slotAlreadyClaimed: Boolean,
    ) {
        val serviceReceivedAt = System.currentTimeMillis()
        outboxInitialization.runWhenReady(
            onReady = {
                capture(
                    scheduledFor = scheduledFor,
                    manual = manual,
                    triggerSource = triggerSource,
                    triggerReceivedAt = triggerReceivedAt,
                    serviceReceivedAt = serviceReceivedAt,
                    slotAlreadyClaimed = slotAlreadyClaimed,
                )
            },
            onFailure = {
                recordOutboxInitializationFailure(
                    scheduledFor = scheduledFor,
                    manual = manual,
                    triggerSource = triggerSource,
                    triggerReceivedAt = triggerReceivedAt,
                    serviceReceivedAt = serviceReceivedAt,
                    slotAlreadyClaimed = slotAlreadyClaimed,
                )
            },
        )
    }

    private fun capture(
        scheduledFor: Long,
        manual: Boolean,
        triggerSource: String,
        triggerReceivedAt: Long,
        serviceReceivedAt: Long,
        slotAlreadyClaimed: Boolean,
    ) {
        if (!manual && !preferences.enabled) return

        val sessionId = if (manual) "manual" else preferences.sessionId
        val configuration = configurationStore.load()
        val queueSummary = runCatching { captureRepository.summary() }.getOrNull()
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
            outboxPending = queueSummary?.pending,
            outboxAttention = queueSummary?.totalAttention,
            outboxOldestAgeMs = queueSummary?.oldestPendingAgeMillis(),
            outboxStorageBytes = queueSummary?.storageBytes,
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

        if (queueSummary == null) {
            val code = "OUTBOX_INSPECTION_FAILED"
            diagnostics.complete(
                record.copy(
                    completedAt = System.currentTimeMillis(),
                    result = "SKIPPED",
                    errorCode = code,
                ),
            )
            preferences.setLastError(code)
            updateNotification()
            stopIfManualOnly()
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
        beginCyclePreflight(record, configuration)
    }

    private fun beginCyclePreflight(
        record: CaptureDiagnostic,
        configuration: AppConfiguration,
    ) {
        processingExecutor.execute {
            val preflight = runCatching {
                val preUpload = drainOutbox(
                    configuration = configuration,
                    uploadLimit = ProductionCyclePolicy.preCaptureUploadLimit(
                        configuration.maxUploadsPerCycle,
                    ),
                    timeoutSeconds = minOf(
                        configuration.requestTimeoutSeconds,
                        ProductionCyclePolicy.PRE_CAPTURE_TIMEOUT_SECONDS,
                    ),
                )
                val staged = captureRepository.oldestStaged()
                val summary = captureRepository.summary()
                val plan = ProductionCyclePolicy.plan(
                    pendingAfterPreflight = summary.pending,
                    hasStagedCapture = staged != null,
                    maximumPendingCaptures = configuration.maxPendingCaptures,
                    configuredUploadBudget = configuration.maxUploadsPerCycle,
                    preflightAttempts = preUpload.attempted,
                )
                CyclePreflight(preUpload, staged, plan)
            }
            mainHandler.post {
                preflight.fold(
                    onSuccess = { prepared ->
                        val delivery = CycleDeliveryState(
                            preUpload = prepared.preUpload,
                            remainingBudget = prepared.plan.remainingUploadBudget,
                        )
                        when (prepared.plan.action) {
                            ProductionCycleAction.RECOVER_STAGED -> recoverStagedCapture(
                                record = record,
                                staged = requireNotNull(prepared.staged),
                                configuration = configuration,
                                cycleDelivery = delivery,
                            )
                            ProductionCycleAction.BACKPRESSURE -> finishBackpressureCycle(
                                record,
                                configuration,
                                delivery,
                            )
                            ProductionCycleAction.CAPTURE_NEW -> startCameraCapture(
                                record.copy(cycleAction = ProductionCycleAction.CAPTURE_NEW.name),
                                configuration,
                                delivery,
                            )
                        }
                    },
                    onFailure = {
                        finishCapture(record, "CYCLE_PREFLIGHT_FAILED")
                    },
                )
            }
        }
    }

    private fun startCameraCapture(
        record: CaptureDiagnostic,
        configuration: AppConfiguration,
        cycleDelivery: CycleDeliveryState,
    ) {
        val captureId = record.captureId
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
                preserveOrAbandonCapture(captureId, "CAPTURE_TIMEOUT")
                releaseWakeLock(wakeLock)
                finishCapture(activeRecord.withCycleDelivery(cycleDelivery), "CAPTURE_TIMEOUT")
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
                                val lateCapturedAt = System.currentTimeMillis()
                                runCatching {
                                    captureRepository.recordStaged(
                                        captureId,
                                        Instant.ofEpochMilli(lateCapturedAt).toString(),
                                        configuration.deviceId,
                                        StagedCaptureContext(
                                            selectedCamera = configuration.cameraLens.name,
                                            cameraId = cameraId,
                                            appliedSettings = appliedSettings,
                                        ).toJson(),
                                    )
                                }
                                return
                            }
                            cameraProvider?.unbindAll()
                            val capturedAt = System.currentTimeMillis()
                            runCatching {
                                captureRepository.recordStaged(
                                    captureId,
                                    Instant.ofEpochMilli(capturedAt).toString(),
                                    configuration.deviceId,
                                    StagedCaptureContext(
                                        selectedCamera = configuration.cameraLens.name,
                                        cameraId = cameraId,
                                        appliedSettings = appliedSettings,
                                    ).toJson(),
                                )
                            }.onFailure {
                                if (finished.compareAndSet(false, true)) {
                                    mainHandler.removeCallbacks(watchdog)
                                    releaseWakeLock(wakeLock)
                                    finishCapture(
                                        activeRecord.withCycleDelivery(cycleDelivery),
                                        "CAPTURE_STAGE_FAILED",
                                    )
                                }
                                return
                            }
                            // The CameraX operation is complete and the source JPEG is durable.
                            // Processing and delivery have their own recovery semantics, so the
                            // camera watchdog must not race a slow palette or network operation.
                            mainHandler.removeCallbacks(watchdog)
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
                                cycleDelivery = cycleDelivery,
                            )
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (!finished.compareAndSet(false, true)) return
                            mainHandler.removeCallbacks(watchdog)
                            cameraProvider?.unbindAll()
                            captureRepository.abandon(captureId)
                            releaseWakeLock(wakeLock)
                            finishCapture(
                                activeRecord.withCycleDelivery(cycleDelivery),
                                "CAMERA_CAPTURE_${exception.imageCaptureError}",
                            )
                        }
                    },
                )
            } catch (_: Exception) {
                if (finished.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(watchdog)
                    cameraProvider?.unbindAll()
                    captureRepository.abandon(captureId)
                    releaseWakeLock(wakeLock)
                    finishCapture(
                        activeRecord.withCycleDelivery(cycleDelivery),
                        "CAMERA_BIND_FAILED",
                    )
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
        cycleDelivery: CycleDeliveryState,
    ) {
        runCatching { captureRepository.markProcessing(captureId) }
            .onFailure {
                if (finished.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(watchdog)
                    releaseWakeLock(wakeLock)
                    finishCapture(
                        record.withCycleDelivery(cycleDelivery),
                        "PROCESSING_STATE_FAILED",
                    )
                }
                return
        }
        processingExecutor.execute {
            val processingStartedAt = SystemClock.elapsedRealtime()
            val completion = runCatching {
                finalizeProcessedCapture(
                    processed = processStagedWork(
                        captureId = captureId,
                        rawFile = rawFile,
                        capturedAt = capturedAt,
                        deviceId = configuration.deviceId,
                        configuration = configuration,
                        stagedContext = StagedCaptureContext(
                            selectedCamera = selectedCamera,
                            cameraId = cameraId,
                            appliedSettings = appliedSettings,
                        ),
                    ),
                    configuration = configuration,
                    cycleDelivery = cycleDelivery,
                )
            }
            if (!finished.compareAndSet(false, true)) {
                runCatching { captureRepository.returnToStaged(captureId, "PROCESS_INTERRUPTED_RECOVERABLE") }
                return@execute
            }
            completion.exceptionOrNull()?.let { error ->
                val code = processingErrorCode(error)
                runCatching { captureRepository.returnToStaged(captureId, code) }
            }

            mainHandler.post {
                releaseWakeLock(wakeLock)
                completion.fold(
                    onSuccess = { completed ->
                        completeProcessedCapture(
                            record = record,
                            capturedAt = capturedAt,
                            appliedSettings = appliedSettings,
                            completed = completed,
                            result = CaptureDiagnostic.RESULT_SUCCESS,
                        )
                    },
                    onFailure = { error ->
                        val code = processingErrorCode(error)
                        finishCapture(
                            record.copy(
                                capturedAt = capturedAt,
                                processingDurationMs = SystemClock.elapsedRealtime() - processingStartedAt,
                                cameraSettings = appliedSettings.diagnosticSummary(),
                            ).withCycleDelivery(cycleDelivery),
                            code,
                        )
                    },
                )
            }
        }
    }

    private fun processStagedWork(
        captureId: String,
        rawFile: File,
        capturedAt: Long,
        deviceId: String,
        configuration: AppConfiguration,
        stagedContext: StagedCaptureContext,
    ): LocalProcessedCapture {
        val processingStartedAt = SystemClock.elapsedRealtime()
        val normalizedTemp = captureRepository.normalizedTempFile(captureId)
        val image = imageNormalizer.normalize(
            source = rawFile,
            destination = normalizedTemp,
            maximumDimension = configuration.maxImageDimension,
            jpegQuality = configuration.jpegQuality,
        )
        val paletteResult = paletteExtractor.extract(
            sourceFile = normalizedTemp,
            requestedColors = configuration.paletteColors,
            analysisDimension = configuration.paletteAnalysisDimension,
            masks = skyMasks,
        )
        val processingDurationMs = SystemClock.elapsedRealtime() - processingStartedAt
        captureRepository.commit(
            normalizedTemp,
            ProductionCaptureMetadata(
                captureId = captureId,
                capturedAt = Instant.ofEpochMilli(capturedAt).toString(),
                deviceId = deviceId,
                sourceDimensions = image.sourceDimensions,
                finalDimensions = image.finalDimensions,
                byteCount = image.byteCount,
                selectedCamera = stagedContext.selectedCamera,
                cameraId = stagedContext.cameraId,
                jpegQuality = configuration.jpegQuality,
                maximumDimension = configuration.maxImageDimension,
                sourceExifOrientation = image.sourceExifOrientation,
                cameraSettings = stagedContext.appliedSettings,
                processingDurationMs = processingDurationMs,
                palette = paletteResult.palette,
                paletteStatistics = paletteResult.statistics,
            ),
        )
        val finalFile = captureRepository.record(captureId)?.imagePath?.let(::File)
            ?: throw IllegalStateException("Committed capture disappeared from the outbox")
        return LocalProcessedCapture(
            image = image,
            finalFile = finalFile,
            palette = paletteResult,
            processingDurationMs = processingDurationMs,
        )
    }

    private fun recoverStagedCapture(
        record: CaptureDiagnostic,
        staged: DurableCaptureRecord,
        configuration: AppConfiguration,
        cycleDelivery: CycleDeliveryState,
    ) {
        val capturedAt = runCatching { Instant.parse(staged.capturedAt).toEpochMilli() }
            .getOrElse {
                captureRepository.markAttention(staged.captureId, "STAGED_TIMESTAMP_INVALID")
                finishCapture(record.withCycleDelivery(cycleDelivery), "STAGED_TIMESTAMP_INVALID")
                return
            }
        val context = StagedCaptureContext.fromJsonOrFallback(
            staged.processingMetadataJson,
            configuration.cameraLens.name,
        )
        val recoveryRecord = record.copy(
            captureId = staged.captureId,
            capturedAt = capturedAt,
            imagePath = staged.imagePath,
            imageBytes = staged.byteCount,
            cameraSettings = context.appliedSettings.diagnosticSummary(),
            cycleAction = ProductionCycleAction.RECOVER_STAGED.name,
            recoveredStagedCaptureId = staged.captureId,
        )
        runCatching { captureRepository.markProcessing(staged.captureId) }
            .onFailure {
                finishCapture(
                    recoveryRecord.withCycleDelivery(cycleDelivery),
                    "PROCESSING_STATE_FAILED",
                )
                return
            }
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:staged-recovery",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        processingExecutor.execute {
            val processing = runCatching {
                processStagedWork(
                    captureId = staged.captureId,
                    rawFile = File(staged.imagePath),
                    capturedAt = capturedAt,
                    deviceId = staged.deviceId,
                    configuration = configuration,
                    stagedContext = context,
                )
            }
            processing.exceptionOrNull()?.let { error ->
                runCatching {
                    captureRepository.returnToStaged(staged.captureId, processingErrorCode(error))
                }
            }
            val next = processing.mapCatching { recovered ->
                val summary = captureRepository.summary()
                if (ProductionCyclePolicy.shouldCaptureAfterRecovery(
                        summary.pending,
                        configuration.maxPendingCaptures,
                    )
                ) {
                    RecoveryCompletion.ContinueWithCapture
                } else {
                    RecoveryCompletion.Complete(
                        finalizeProcessedCapture(recovered, configuration, cycleDelivery),
                    )
                }
            }
            mainHandler.post {
                releaseWakeLock(wakeLock)
                next.fold(
                    onSuccess = { completion ->
                        when (completion) {
                            RecoveryCompletion.ContinueWithCapture -> startCameraCapture(
                                record = record.copy(
                                    cycleAction = ProductionCycleAction.CAPTURE_NEW.name,
                                    recoveredStagedCaptureId = staged.captureId,
                                ),
                                configuration = configuration,
                                cycleDelivery = cycleDelivery,
                            )
                            is RecoveryCompletion.Complete -> completeProcessedCapture(
                                record = recoveryRecord,
                                capturedAt = capturedAt,
                                appliedSettings = context.appliedSettings,
                                completed = completion.capture,
                                result = "RECOVERED_STAGED",
                            )
                        }
                    },
                    onFailure = { error ->
                        finishCapture(
                            recoveryRecord.withCycleDelivery(cycleDelivery),
                            processingErrorCode(error),
                        )
                    },
                )
            }
        }
    }

    private data class LocalProcessedCapture(
        val image: ImageNormalizationResult,
        val finalFile: File,
        val palette: PaletteExtractionResult?,
        val processingDurationMs: Long,
    )

    private data class ProcessedCapture(
        val local: LocalProcessedCapture,
        val delivery: DeliveryCycleSummary,
        val postUploadAttempted: Int,
        val retentionRemoved: Int,
        val finalSummary: OutboxSummary,
    )

    private sealed interface RecoveryCompletion {
        data object ContinueWithCapture : RecoveryCompletion
        data class Complete(val capture: ProcessedCapture) : RecoveryCompletion
    }

    private fun finalizeProcessedCapture(
        processed: LocalProcessedCapture,
        configuration: AppConfiguration,
        cycleDelivery: CycleDeliveryState,
    ): ProcessedCapture {
        val postUpload = if (cycleDelivery.remainingBudget > 0) {
            drainOutbox(configuration, cycleDelivery.remainingBudget)
        } else {
            null
        }
        return ProcessedCapture(
            local = processed,
            delivery = cycleDelivery.combinedWith(postUpload),
            postUploadAttempted = postUpload?.attempted ?: 0,
            retentionRemoved = applyRetention(configuration),
            finalSummary = captureRepository.summary(),
        )
    }

    private data class CyclePreflight(
        val preUpload: DeliveryCycleSummary,
        val staged: DurableCaptureRecord?,
        val plan: ProductionCyclePlan,
    )

    private data class CycleDeliveryState(
        val preUpload: DeliveryCycleSummary,
        val remainingBudget: Int,
    ) {
        fun combinedWith(postUpload: DeliveryCycleSummary?): DeliveryCycleSummary {
            if (postUpload == null) return preUpload
            return DeliveryCycleSummary(
                attempted = preUpload.attempted + postUpload.attempted,
                delivered = preUpload.delivered + postUpload.delivered,
                retried = preUpload.retried + postUpload.retried,
                attentionRequired = preUpload.attentionRequired + postUpload.attentionRequired,
                deferred = postUpload.deferred,
                pendingAfter = postUpload.pendingAfter,
                notificationRequired = preUpload.notificationRequired || postUpload.notificationRequired,
                cycleErrorCode = postUpload.cycleErrorCode ?: preUpload.cycleErrorCode,
                lastAttemptErrorCode = postUpload.lastAttemptErrorCode ?: preUpload.lastAttemptErrorCode,
            )
        }
    }

    private fun completeProcessedCapture(
        record: CaptureDiagnostic,
        capturedAt: Long,
        appliedSettings: AppliedCameraSettings,
        completed: ProcessedCapture,
        result: String,
    ) {
        val paletteStatistics = completed.local.palette?.statistics
        diagnostics.complete(
            record.copy(
                capturedAt = capturedAt,
                completedAt = System.currentTimeMillis(),
                result = result,
                errorCode = null,
                imagePath = completed.local.finalFile.absolutePath,
                imageBytes = completed.local.image.byteCount,
                sourceWidth = completed.local.image.sourceDimensions.width,
                sourceHeight = completed.local.image.sourceDimensions.height,
                width = completed.local.image.finalDimensions.width,
                height = completed.local.image.finalDimensions.height,
                processingDurationMs = completed.local.processingDurationMs,
                cameraSettings = appliedSettings.diagnosticSummary(),
                palette = completed.local.palette?.palette?.toJson(),
                paletteSize = paletteStatistics?.paletteColors,
                paletteAnalysisWidth = paletteStatistics?.analysisSize?.width,
                paletteAnalysisHeight = paletteStatistics?.analysisSize?.height,
                paletteIncludedPixels = paletteStatistics?.includedPixels,
                paletteDurationMs = paletteStatistics?.elapsedMs,
                palettePeakPssKib = paletteStatistics?.peakPssKib,
                preUploadAttempted = completed.delivery.attempted - completed.postUploadAttempted,
                postUploadAttempted = completed.postUploadAttempted,
                retentionRemoved = completed.retentionRemoved,
                outboxStagedAfterCycle = completed.finalSummary.staged,
                outboxPendingAfterCycle = completed.finalSummary.pending,
            ).withDelivery(completed.delivery),
        )
        if (result == CaptureDiagnostic.RESULT_SUCCESS) preferences.setLastCapture(capturedAt)
        applyDeliveryStatus(completed.delivery)
        captureInProgress.set(false)
        updateNotification()
        stopIfManualOnly()
    }

    private fun CaptureDiagnostic.withCycleDelivery(
        delivery: CycleDeliveryState,
    ): CaptureDiagnostic = copy(
        preUploadAttempted = delivery.preUpload.attempted,
    ).withDelivery(delivery.preUpload)

    private fun finishBackpressureCycle(
        record: CaptureDiagnostic,
        configuration: AppConfiguration,
        cycleDelivery: CycleDeliveryState,
    ) {
        processingExecutor.execute {
            val postUpload = if (cycleDelivery.remainingBudget > 0) {
                drainOutbox(configuration, cycleDelivery.remainingBudget)
            } else {
                null
            }
            val delivery = cycleDelivery.combinedWith(postUpload)
            val retentionRemoved = applyRetention(configuration)
            val finalSummary = captureRepository.summary()
            mainHandler.post {
                diagnostics.complete(
                    record.copy(
                        completedAt = System.currentTimeMillis(),
                        result = "SKIPPED",
                        errorCode = "OUTBOX_BACKPRESSURE",
                        cycleAction = ProductionCycleAction.BACKPRESSURE.name,
                        preUploadAttempted = cycleDelivery.preUpload.attempted,
                        postUploadAttempted = postUpload?.attempted ?: 0,
                        retentionRemoved = retentionRemoved,
                        outboxStagedAfterCycle = finalSummary.staged,
                        outboxPendingAfterCycle = finalSummary.pending,
                    ).withDelivery(delivery),
                )
                applyDeliveryStatus(delivery, "OUTBOX_BACKPRESSURE")
                captureInProgress.set(false)
                updateNotification()
                stopIfManualOnly()
            }
        }
    }

    private fun preserveOrAbandonCapture(captureId: String, errorCode: String) {
        val record = runCatching { captureRepository.record(captureId) }.getOrNull()
        if (record != null && File(record.imagePath).isFile) {
            runCatching { captureRepository.returnToStaged(captureId, errorCode) }
        } else {
            captureRepository.abandon(captureId)
        }
    }

    private fun processingErrorCode(error: Throwable): String = when (error) {
        is PaletteExtractionException -> error.code
        is CaptureProcessingException -> error.code
        else -> "CAPTURE_COMMIT_FAILED"
    }

    private fun recordOutboxInitializationFailure(
        scheduledFor: Long,
        manual: Boolean,
        triggerSource: String,
        triggerReceivedAt: Long,
        serviceReceivedAt: Long,
        slotAlreadyClaimed: Boolean,
    ) {
        if (!manual && !preferences.enabled) return
        val captureId = UUID.randomUUID().toString()
        val power = PowerSnapshotReader.read(this)
        val duplicate = !manual && !slotAlreadyClaimed &&
            !preferences.claimScheduledSlot(scheduledFor)
        val code = if (duplicate) "DUPLICATE_SLOT" else "OUTBOX_INITIALIZATION_FAILED"
        diagnostics.complete(
            CaptureDiagnostic(
                recordId = captureId,
                captureId = captureId,
                sessionId = if (manual) "manual" else preferences.sessionId,
                slotId = if (manual) "manual-$serviceReceivedAt" else "utc-$scheduledFor",
                scheduledFor = scheduledFor,
                alarmReceivedAt = triggerReceivedAt,
                serviceReceivedAt = serviceReceivedAt,
                completedAt = System.currentTimeMillis(),
                result = "SKIPPED",
                errorCode = code,
                screenInteractive = power.screenInteractive,
                charging = power.charging,
                plugged = power.plugged,
                batteryPercent = power.batteryPercent,
                deviceIdleMode = power.deviceIdleMode,
                powerSaveMode = power.powerSaveMode,
                batteryOptimizationExempt = power.batteryOptimizationExempt,
                stationWakeLockHeld = stationWakeLock?.isHeld == true,
                triggerSource = triggerSource,
                manual = manual,
            ),
        )
        if (!duplicate) preferences.setLastError(code)
        updateNotification()
        stopIfManualOnly()
    }

    private fun requestUploadPendingOnly() {
        outboxInitialization.runWhenReady(
            onReady = ::uploadPendingOnly,
            onFailure = {
                preferences.setLastError("OUTBOX_INITIALIZATION_FAILED")
                updateNotification()
                stopIfManualOnly()
            },
        )
    }

    private fun uploadPendingOnly() {
        if (!captureInProgress.compareAndSet(false, true)) {
            preferences.setLastError("OVERLAP_PREVENTED")
            updateNotification()
            return
        }
        val configuration = configurationStore.load()
        uploadInProgress.set(true)
        updateNotification()
        processingExecutor.execute {
            val delivery = drainOutbox(configuration)
            applyRetention(configuration)
            mainHandler.post {
                applyDeliveryStatus(delivery)
                captureInProgress.set(false)
                updateNotification()
                stopIfManualOnly()
            }
        }
    }

    private fun drainOutbox(
        configuration: AppConfiguration,
        uploadLimit: Int = configuration.maxUploadsPerCycle,
        timeoutSeconds: Int = configuration.requestTimeoutSeconds,
    ): DeliveryCycleSummary {
        uploadInProgress.set(true)
        val wakeLock = acquireUploadWakeLock(configuration)
        return try {
            if (uploadLimit <= 0) return noAttemptDelivery()
            if (!networkAvailable()) return noAttemptDelivery("NETWORK_UNAVAILABLE")
            val endpoint = runCatching {
                EndpointPolicy.resolve(
                    BuildConfig.INGEST_ENDPOINT,
                    configuration.debugEndpointOverride,
                    BuildConfig.ALLOW_ENDPOINT_OVERRIDE,
                )
            }.getOrElse {
                return deliveryFailure("INGEST_ENDPOINT_INVALID")
            }
            runCatching {
                deliveryCoordinator.drain(
                    endpoint,
                    configuration.deliverySettings().copy(
                        maxUploadsPerCycle = uploadLimit,
                        requestTimeoutSeconds = timeoutSeconds,
                    ),
                )
            }.getOrElse {
                deliveryFailure("OUTBOX_DELIVERY_FAILED")
            }
        } finally {
            releaseWakeLock(wakeLock)
            uploadInProgress.set(false)
        }
    }

    private fun deliveryFailure(code: String): DeliveryCycleSummary {
        val pending = runCatching { captureRepository.summary().pending }.getOrDefault(0)
        return DeliveryCycleSummary(0, 0, 0, 0, pending, pending, true, code)
    }

    private fun noAttemptDelivery(code: String? = null): DeliveryCycleSummary {
        val pending = runCatching { captureRepository.summary().pending }.getOrDefault(0)
        return DeliveryCycleSummary(0, 0, 0, 0, pending, pending, false, code)
    }

    private fun applyRetention(configuration: AppConfiguration): Int = runCatching {
        captureRepository.applyDeliveredRetention(
            configuration.retentionDays,
            configuration.retentionCount,
        )
    }.getOrDefault(0)

    private fun networkAvailable(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun CaptureDiagnostic.withDelivery(
        delivery: DeliveryCycleSummary?,
    ): CaptureDiagnostic = copy(
        uploadAttempted = delivery?.attempted,
        uploadDelivered = delivery?.delivered,
        uploadRetried = delivery?.retried,
        uploadAttentionRequired = delivery?.attentionRequired,
        uploadDeferred = delivery?.deferred,
        outboxPendingAfterUpload = delivery?.pendingAfter,
        uploadErrorCode = delivery?.cycleErrorCode ?: delivery?.lastAttemptErrorCode,
    )

    private fun applyDeliveryStatus(
        delivery: DeliveryCycleSummary,
        fallbackError: String? = null,
    ) {
        val code = when {
            delivery.cycleErrorCode != null -> delivery.cycleErrorCode
            delivery.attentionRequired > 0 -> "UPLOAD_ATTENTION_REQUIRED"
            delivery.notificationRequired -> "UPLOAD_RETRY_THRESHOLD"
            delivery.retried > 0 -> delivery.lastAttemptErrorCode ?: "UPLOAD_RETRYING"
            else -> fallbackError
        }
        if (code == null) preferences.clearLastError() else preferences.setLastError(code)
    }

    private fun acquireUploadWakeLock(configuration: AppConfiguration): PowerManager.WakeLock {
        val requested = configuration.requestTimeoutSeconds.toLong() *
            configuration.maxUploadsPerCycle.toLong() * 1_000L + 30_000L
        val timeout = requested.coerceIn(60_000L, MAX_UPLOAD_WAKE_LOCK_MS)
        return getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:upload",
        ).apply {
            setReferenceCounted(false)
            acquire(timeout)
        }
    }

    private fun finishCapture(record: CaptureDiagnostic, errorCode: String) {
        val completed = System.currentTimeMillis()
        val configuration = configurationStore.load()
        val retentionRemoved = applyRetention(configuration)
        val finalSummary = runCatching { captureRepository.summary() }.getOrNull()
        diagnostics.complete(
            record.copy(
                completedAt = completed,
                result = "ERROR",
                errorCode = errorCode,
                retentionRemoved = record.retentionRemoved ?: retentionRemoved,
                outboxStagedAfterCycle = finalSummary?.staged,
                outboxPendingAfterCycle = finalSummary?.pending,
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
            requestCapture(
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

        requestCapture(
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
            uploadInProgress.get() -> "Uploading queued sky images"
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
        outboxInitialization.cancel()
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
        const val ACTION_UPLOAD_PENDING = "com.jaredwinick.colors.camera.UPLOAD_PENDING"
        const val EXTRA_SCHEDULED_FOR = "scheduled_for"
        const val EXTRA_TRIGGER_RECEIVED_AT = "trigger_received_at"

        private const val CHANNEL_ID = "camera_station_v1"
        private const val NOTIFICATION_ID = 2701
        private const val CAPTURE_TIMEOUT_MS = 90_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 120_000L
        private const val MAX_UPLOAD_WAKE_LOCK_MS = 10 * 60_000L
    }
}
