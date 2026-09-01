package com.jaredwinick.colors.camera.outbox

import android.content.Context
import com.jaredwinick.colors.camera.processing.CaptureArtifactPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class DurableCapturePayload(
    val captureId: String,
    val capturedAt: String,
    val deviceId: String,
    val mimeType: String,
    val byteCount: Long,
    val paletteJson: String,
    val processingMetadataJson: String,
) {
    fun requireValid(): DurableCapturePayload {
        CaptureArtifactPolicy.requireCaptureId(captureId)
        CaptureArtifactPolicy.requireUtcTimestamp(capturedAt)
        require(DEVICE_ID.matches(deviceId)) { "Device ID is invalid" }
        require(mimeType == "image/jpeg") { "Durable capture must be an image/jpeg" }
        CaptureArtifactPolicy.requireJpegSize(byteCount)
        val palette = JSONArray(paletteJson)
        require(palette.length() in 3..10) { "Palette must contain 3-10 colors" }
        JSONObject(processingMetadataJson)
        return this
    }

    companion object {
        private val DEVICE_ID = Regex("[A-Za-z0-9._-]{1,100}")
    }
}

class DurableCaptureStore internal constructor(
    context: Context,
    rootName: String = "durable-captures",
    databaseName: String = DurableCaptureDatabase.DATABASE_NAME,
) {
    private val root = File(context.filesDir, rootName).apply { mkdirs() }
    private val workDirectory = File(root, "work").apply { mkdirs() }
    private val pendingDirectory = File(root, "pending").apply { mkdirs() }
    private val deliveredDirectory = File(root, "delivered").apply { mkdirs() }
    private val attentionDirectory = File(root, "attention").apply { mkdirs() }
    private val quarantineDirectory = File(root, "quarantine").apply { mkdirs() }
    private val database = DurableCaptureDatabase(context, databaseName)

    fun rawFile(captureId: String): File {
        CaptureArtifactPolicy.requireCaptureId(captureId)
        return File(workDirectory, "$captureId.raw.jpg")
    }

    fun normalizedTempFile(captureId: String): File {
        CaptureArtifactPolicy.requireCaptureId(captureId)
        return File(workDirectory, "$captureId.normalized.tmp.jpg")
    }

    @Synchronized
    fun importLegacyCapture(
        image: File,
        metadata: File,
        fallbackDeviceId: String,
        now: Instant = Instant.now(),
    ): Boolean = runCatching {
        val json = JSONObject(metadata.readText())
        val captureId = json.getString("capture_id")
        if (database.record(captureId)?.immutableFingerprint != null) {
            image.delete()
            metadata.delete()
            return@runCatching true
        }
        val capturedAt = json.getString("captured_at")
        val deviceId = json.optString("device_id").ifBlank { fallbackDeviceId }
        require(image.isFile && image.length() == json.getLong("bytes"))
        val raw = rawFile(captureId)
        copySynced(image, raw)
        recordStaged(captureId, capturedAt, deviceId, now)
        markProcessing(captureId, now)
        val normalized = normalizedTempFile(captureId)
        copySynced(image, normalized)
        json.put("device_id", deviceId)
        val palette = json.optJSONArray("palette")
        if (palette != null && palette.length() in 3..10) {
            enqueue(
                normalized,
                DurableCapturePayload(
                    captureId = captureId,
                    capturedAt = capturedAt,
                    deviceId = deviceId,
                    mimeType = json.getString("mime_type"),
                    byteCount = image.length(),
                    paletteJson = palette.toString(),
                    processingMetadataJson = json.toString(),
                ),
                now,
            )
        } else {
            retainProcessedAttention(
                normalized,
                captureId,
                "LEGACY_PALETTE_UNAVAILABLE",
                json.toString(),
                now,
            )
        }
        image.delete()
        metadata.delete()
        true
    }.getOrDefault(false)

    @Synchronized
    fun recordStaged(
        captureId: String,
        capturedAt: String,
        deviceId: String,
        now: Instant = Instant.now(),
    ): DurableCaptureRecord {
        CaptureArtifactPolicy.requireCaptureId(captureId)
        CaptureArtifactPolicy.requireUtcTimestamp(capturedAt)
        require(Regex("[A-Za-z0-9._-]{1,100}").matches(deviceId)) { "Device ID is invalid" }
        val raw = rawFile(captureId)
        require(raw.isFile && raw.length() > 0) { "Captured JPEG is missing or empty" }
        val timestamp = now.toString()
        val existing = database.record(captureId)
        if (existing?.immutableFingerprint != null) {
            preserveConflict(
                captureId = captureId,
                evidence = raw,
                existingFingerprint = existing.immutableFingerprint,
                incomingFingerprint = sha256(raw),
                detectedAt = timestamp,
            )
        }
        if (existing != null && (
                existing.capturedAt != capturedAt || existing.deviceId != deviceId
            )
        ) {
            preserveConflict(
                captureId = captureId,
                evidence = raw,
                existingFingerprint = existing.immutableFingerprint,
                incomingFingerprint = DurableCapturePolicy.sha256(raw.readBytes()),
                detectedAt = timestamp,
            )
        }
        return DurableCaptureRecord(
            captureId = captureId,
            state = DurableCaptureState.STAGED,
            capturedAt = capturedAt,
            deviceId = deviceId,
            mimeType = "image/jpeg",
            byteCount = raw.length(),
            imageSha256 = sha256(raw),
            paletteJson = null,
            processingMetadataJson = null,
            immutableFingerprint = null,
            imagePath = raw.absolutePath,
            metadataPath = null,
            attemptCount = 0,
            lastAttemptAt = null,
            lastErrorCode = null,
            nextEligibleRetryAt = null,
            deliveredAt = null,
            deliveryConfirmationJson = null,
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp,
        ).also(database::upsert)
    }

    @Synchronized
    fun markProcessing(captureId: String, now: Instant = Instant.now()) {
        val record = requireNotNull(database.record(captureId)) { "Staged capture is missing" }
        require(record.state == DurableCaptureState.STAGED) { "Only staged captures can be processed" }
        database.updateState(captureId, DurableCaptureState.PROCESSING, now.toString())
    }

    @Synchronized
    fun enqueue(
        normalizedTemp: File,
        payload: DurableCapturePayload,
        now: Instant = Instant.now(),
    ): EnqueueResult {
        payload.requireValid()
        require(normalizedTemp.isFile && normalizedTemp.length() == payload.byteCount) {
            "Normalized JPEG does not match durable metadata"
        }
        val imageSha256 = sha256(normalizedTemp)
        val fingerprint = DurableCapturePolicy.immutableFingerprint(
            payload.captureId,
            payload.capturedAt,
            payload.deviceId,
            payload.mimeType,
            payload.byteCount,
            imageSha256,
            payload.paletteJson,
        )
        val existing = database.record(payload.captureId)
        if (existing?.immutableFingerprint != null) {
            if (existing.immutableFingerprint == fingerprint && validateImmutableFiles(existing)) {
                normalizedTemp.delete()
                rawFile(payload.captureId).delete()
                return EnqueueResult(existing, duplicate = true)
            }
            preserveConflict(
                captureId = payload.captureId,
                evidence = normalizedTemp,
                existingFingerprint = existing.immutableFingerprint,
                incomingFingerprint = fingerprint,
                detectedAt = now.toString(),
            )
        }
        require(existing == null || existing.state in setOf(
            DurableCaptureState.STAGED,
            DurableCaptureState.PROCESSING,
            DurableCaptureState.ATTENTION_REQUIRED,
        )) { "Capture cannot transition to pending from ${existing?.state}" }

        val finalDirectory = File(pendingDirectory, payload.captureId)
        val stagingDirectory = File(
            workDirectory,
            ".${payload.captureId}.${UUID.randomUUID()}.staging",
        )
        val timestamp = now.toString()
        try {
            check(stagingDirectory.mkdir()) { "Could not create capture staging directory" }
            val stagedImage = File(stagingDirectory, IMAGE_NAME)
            copySynced(normalizedTemp, stagedImage)
            val stagedMetadata = File(stagingDirectory, METADATA_NAME)
            writeSynced(
                stagedMetadata,
                sidecar(
                    payload = payload,
                    imageSha256 = imageSha256,
                    fingerprint = fingerprint,
                    state = DurableCaptureState.PENDING_UPLOAD,
                    createdAt = existing?.createdAt ?: timestamp,
                    updatedAt = timestamp,
                ).toString(),
            )
            if (finalDirectory.exists()) {
                quarantine(stagingDirectory, "DUPLICATE_PENDING_DIRECTORY", timestamp)
                throw CaptureIdConflictException(
                    "Pending capture directory already exists for ${payload.captureId}",
                )
            }
            move(stagingDirectory, finalDirectory)
            val finalImage = File(finalDirectory, IMAGE_NAME)
            val finalMetadata = File(finalDirectory, METADATA_NAME)
            val record = DurableCaptureRecord(
                captureId = payload.captureId,
                state = DurableCaptureState.PENDING_UPLOAD,
                capturedAt = payload.capturedAt,
                deviceId = payload.deviceId,
                mimeType = payload.mimeType,
                byteCount = payload.byteCount,
                imageSha256 = imageSha256,
                paletteJson = payload.paletteJson,
                processingMetadataJson = payload.processingMetadataJson,
                immutableFingerprint = fingerprint,
                imagePath = finalImage.absolutePath,
                metadataPath = finalMetadata.absolutePath,
                attemptCount = 0,
                lastAttemptAt = null,
                lastErrorCode = null,
                nextEligibleRetryAt = null,
                deliveredAt = null,
                deliveryConfirmationJson = null,
                createdAt = existing?.createdAt ?: timestamp,
                updatedAt = timestamp,
            )
            database.upsert(record)
            normalizedTemp.delete()
            rawFile(payload.captureId).delete()
            return EnqueueResult(record, duplicate = false)
        } catch (error: Exception) {
            if (stagingDirectory.exists()) quarantine(stagingDirectory, "ENQUEUE_INTERRUPTED", timestamp)
            throw error
        }
    }

    @Synchronized
    fun retainProcessedAttention(
        normalizedTemp: File,
        captureId: String,
        errorCode: String,
        processingMetadataJson: String,
        now: Instant = Instant.now(),
    ): DurableCaptureRecord {
        DurableCapturePolicy.requireSafeErrorCode(errorCode)
        JSONObject(processingMetadataJson)
        val existing = requireNotNull(database.record(captureId)) { "Staged capture is missing" }
        require(normalizedTemp.isFile && normalizedTemp.length() > 0)
        val directory = File(attentionDirectory, captureId).apply { mkdirs() }
        val finalImage = File(directory, IMAGE_NAME)
        val finalMetadata = File(directory, METADATA_NAME)
        copySynced(normalizedTemp, finalImage)
        writeSynced(finalMetadata, processingMetadataJson)
        val timestamp = now.toString()
        return existing.copy(
            state = DurableCaptureState.ATTENTION_REQUIRED,
            mimeType = "image/jpeg",
            byteCount = finalImage.length(),
            imageSha256 = sha256(finalImage),
            paletteJson = null,
            processingMetadataJson = processingMetadataJson,
            immutableFingerprint = null,
            imagePath = finalImage.absolutePath,
            metadataPath = finalMetadata.absolutePath,
            lastErrorCode = errorCode,
            updatedAt = timestamp,
        ).also { record ->
            database.upsert(record)
            normalizedTemp.delete()
            rawFile(captureId).delete()
        }
    }

    @Synchronized
    fun markAttention(
        captureId: String,
        errorCode: String,
        now: Instant = Instant.now(),
    ) {
        DurableCapturePolicy.requireSafeErrorCode(errorCode)
        val existing = database.record(captureId)
        val evidence = listOf(rawFile(captureId), normalizedTempFile(captureId))
            .firstOrNull(File::exists)
        val retained = evidence?.let { file ->
            val directory = File(attentionDirectory, captureId).apply { mkdirs() }
            val destination = File(directory, file.name)
            move(file, destination)
            destination
        }
        if (existing != null) {
            database.upsert(
                existing.copy(
                    state = DurableCaptureState.ATTENTION_REQUIRED,
                    imagePath = retained?.absolutePath ?: existing.imagePath,
                    lastErrorCode = errorCode,
                    updatedAt = now.toString(),
                ),
            )
        }
    }

    fun discardUncapturedWork(captureId: String) {
        if (database.record(captureId) == null) {
            rawFile(captureId).delete()
            normalizedTempFile(captureId).delete()
        } else {
            markAttention(captureId, "CAPTURE_WORK_ABANDONED")
        }
    }

    @Synchronized
    fun pendingOldestFirst(): List<DurableCaptureRecord> =
        DurableCapturePolicy.pendingOldestFirst(database.allRecords())

    @Synchronized
    fun pendingEligible(now: Instant, limit: Int): List<DurableCaptureRecord> {
        require(limit >= 0)
        return pendingOldestFirst().filter { record ->
            record.nextEligibleRetryAt?.let { !Instant.parse(it).isAfter(now) } ?: true
        }.take(limit)
    }

    @Synchronized
    fun recordRetry(
        captureId: String,
        errorCode: String,
        nextEligibleRetryAt: Instant,
        attemptedAt: Instant = Instant.now(),
    ): DurableCaptureRecord {
        DurableCapturePolicy.requireSafeErrorCode(errorCode)
        val existing = requireNotNull(database.record(captureId)) { "Pending capture is missing" }
        require(existing.state == DurableCaptureState.PENDING_UPLOAD)
        return existing.copy(
            attemptCount = existing.attemptCount + 1,
            lastAttemptAt = attemptedAt.toString(),
            lastErrorCode = errorCode,
            nextEligibleRetryAt = nextEligibleRetryAt.toString(),
            updatedAt = attemptedAt.toString(),
        ).also { updated ->
            database.upsert(updated)
            updateSidecar(updated)
        }
    }

    @Synchronized
    fun markDelivered(
        captureId: String,
        confirmationJson: String,
        deliveredAt: Instant = Instant.now(),
    ): DurableCaptureRecord {
        JSONObject(confirmationJson)
        val existing = requireNotNull(database.record(captureId)) { "Pending capture is missing" }
        require(existing.state == DurableCaptureState.PENDING_UPLOAD)
        require(validateImmutableFiles(existing)) { "Pending capture evidence is inconsistent" }
        val sourceDirectory = File(existing.imagePath).parentFile!!
        val destination = File(deliveredDirectory, captureId)
        require(!destination.exists()) { "Delivered capture directory already exists" }
        val timestamp = deliveredAt.toString()
        val updated = existing.copy(
            state = DurableCaptureState.DELIVERED,
            imagePath = File(destination, IMAGE_NAME).absolutePath,
            metadataPath = File(destination, METADATA_NAME).absolutePath,
            attemptCount = existing.attemptCount + 1,
            lastAttemptAt = timestamp,
            lastErrorCode = null,
            nextEligibleRetryAt = null,
            deliveredAt = timestamp,
            deliveryConfirmationJson = confirmationJson,
            updatedAt = timestamp,
        )
        updateSidecar(updated.copy(metadataPath = existing.metadataPath))
        move(sourceDirectory, destination)
        database.upsert(updated)
        return updated
    }

    @Synchronized
    fun applyDeliveredRetention(
        retentionDays: Int,
        retentionCount: Int,
        now: Instant = Instant.now(),
    ): Int {
        val records = database.allRecords()
        val removeIds = DurableCapturePolicy.deliveredRetentionIds(
            records,
            retentionDays,
            retentionCount,
            now,
        )
        removeIds.forEach { captureId ->
            val record = database.record(captureId) ?: return@forEach
            val directory = File(record.imagePath).parentFile
            if (directory != null && directory.isDescendantOf(deliveredDirectory)) {
                directory.deleteRecursively()
            }
            database.delete(captureId)
        }
        return removeIds.size
    }

    @Synchronized
    fun summary(): OutboxSummary {
        val records = database.allRecords()
        fun count(state: DurableCaptureState) = records.count { it.state == state }
        val oldest = DurableCapturePolicy.pendingOldestFirst(records).firstOrNull()?.capturedAt
        return OutboxSummary(
            staged = count(DurableCaptureState.STAGED),
            processing = count(DurableCaptureState.PROCESSING),
            pending = count(DurableCaptureState.PENDING_UPLOAD),
            delivered = count(DurableCaptureState.DELIVERED),
            attentionRequired = count(DurableCaptureState.ATTENTION_REQUIRED),
            conflicts = database.conflictCount(),
            oldestPendingAt = oldest,
            storageBytes = root.walkTopDown().filter(File::isFile).sumOf(File::length),
        )
    }

    @Synchronized
    fun latestLocalImage(): File? = database.allRecords()
        .filter { it.state in setOf(
            DurableCaptureState.PENDING_UPLOAD,
            DurableCaptureState.DELIVERED,
            DurableCaptureState.ATTENTION_REQUIRED,
        ) }
        .sortedWith(compareByDescending<DurableCaptureRecord> { it.capturedAt }.thenByDescending { it.captureId })
        .map { File(it.imagePath) }
        .firstOrNull(File::isFile)

    @Synchronized
    fun reconcile(now: Instant = Instant.now()): ReconciliationReport {
        var report = ReconciliationReport()
        workDirectory.listFiles(File::isDirectory).orEmpty().forEach { staging ->
            val metadata = File(staging, METADATA_NAME)
            val captureId = runCatching { JSONObject(metadata.readText()).getString("capture_id") }
                .getOrNull()
            if (captureId != null && runCatching {
                    CaptureArtifactPolicy.requireCaptureId(captureId)
                }.isSuccess
            ) {
                val destination = File(pendingDirectory, captureId)
                if (!destination.exists()) move(staging, destination)
                val recovered = recoverSidecar(destination, DurableCaptureState.PENDING_UPLOAD, now)
                report = if (recovered) {
                    report.copy(recoveredPending = report.recoveredPending + 1)
                } else {
                    report.copy(quarantined = report.quarantined + 1)
                }
            } else {
                quarantine(staging, "UNTRACKED_STAGING_DIRECTORY", now.toString())
                report = report.copy(quarantined = report.quarantined + 1)
            }
        }
        workDirectory.listFiles(File::isFile).orEmpty().forEach { evidence ->
            val captureId = evidence.name.substringBefore('.')
            val tracked = runCatching { CaptureArtifactPolicy.requireCaptureId(captureId) }.isSuccess &&
                database.record(captureId) != null
            if (!tracked) {
                val destination = quarantine(evidence, "UNTRACKED_CAPTURE_EVIDENCE", now.toString())
                if (runCatching { CaptureArtifactPolicy.requireCaptureId(captureId) }.isSuccess) {
                    database.insertConflict(
                        captureId,
                        null,
                        sha256(destination),
                        destination.absolutePath,
                        now.toString(),
                    )
                }
                report = report.copy(quarantined = report.quarantined + 1)
            }
        }
        listOf(pendingDirectory to DurableCaptureState.PENDING_UPLOAD, deliveredDirectory to DurableCaptureState.DELIVERED)
            .forEach { (directory, expectedState) ->
                directory.listFiles(File::isDirectory).orEmpty().forEach capture@{ captureDirectory ->
                    val captureId = captureDirectory.name
                    if (runCatching { CaptureArtifactPolicy.requireCaptureId(captureId) }.isFailure) {
                        quarantine(captureDirectory, "INVALID_CAPTURE_DIRECTORY", now.toString())
                        report = report.copy(quarantined = report.quarantined + 1)
                        return@capture
                    }
                    val sidecarState = runCatching {
                        DurableCaptureState.valueOf(
                            JSONObject(File(captureDirectory, METADATA_NAME).readText())
                                .getString("state"),
                        )
                    }.getOrNull()
                    if (
                        expectedState == DurableCaptureState.PENDING_UPLOAD &&
                        sidecarState == DurableCaptureState.DELIVERED
                    ) {
                        val destination = File(deliveredDirectory, captureId)
                        if (!destination.exists()) move(captureDirectory, destination)
                        val recovered = recoverSidecar(destination, DurableCaptureState.DELIVERED, now)
                        report = if (recovered) {
                            report.copy(recoveredPending = report.recoveredPending + 1)
                        } else {
                            report.copy(quarantined = report.quarantined + 1)
                        }
                        return@capture
                    }
                    val existing = database.record(captureId)
                    val expectedImage = File(captureDirectory, IMAGE_NAME).absolutePath
                    if (
                        existing == null || existing.state != expectedState ||
                        existing.imagePath != expectedImage
                    ) {
                        val recovered = recoverSidecar(captureDirectory, expectedState, now)
                        report = if (recovered) {
                            report.copy(recoveredPending = report.recoveredPending + 1)
                        } else {
                            report.copy(quarantined = report.quarantined + 1)
                        }
                    }
                }
            }

        database.allRecords().forEach { record ->
            when (record.state) {
                DurableCaptureState.PROCESSING -> {
                    if (File(record.imagePath).isFile) {
                        database.updateState(
                            record.captureId,
                            DurableCaptureState.STAGED,
                            now.toString(),
                            "PROCESS_INTERRUPTED_RECOVERABLE",
                        )
                        report = report.copy(recoveredStaged = report.recoveredStaged + 1)
                    } else {
                        database.updateState(
                            record.captureId,
                            DurableCaptureState.ATTENTION_REQUIRED,
                            now.toString(),
                            "STAGED_IMAGE_MISSING",
                        )
                        report = report.copy(attentionRequired = report.attentionRequired + 1)
                    }
                }
                DurableCaptureState.STAGED -> if (!File(record.imagePath).isFile) {
                    database.updateState(
                        record.captureId,
                        DurableCaptureState.ATTENTION_REQUIRED,
                        now.toString(),
                        "STAGED_IMAGE_MISSING",
                    )
                    report = report.copy(attentionRequired = report.attentionRequired + 1)
                }
                DurableCaptureState.PENDING_UPLOAD,
                DurableCaptureState.DELIVERED,
                -> if (!validateImmutableFiles(record)) {
                    database.updateState(
                        record.captureId,
                        DurableCaptureState.ATTENTION_REQUIRED,
                        now.toString(),
                        "IMMUTABLE_EVIDENCE_INCONSISTENT",
                    )
                    report = report.copy(attentionRequired = report.attentionRequired + 1)
                }
                DurableCaptureState.ATTENTION_REQUIRED -> Unit
            }
        }
        return report
    }

    @Synchronized
    fun close() = database.close()

    private fun recoverSidecar(
        directory: File,
        expectedState: DurableCaptureState,
        now: Instant,
    ): Boolean = runCatching {
        val metadataFile = File(directory, METADATA_NAME)
        val imageFile = File(directory, IMAGE_NAME)
        val json = JSONObject(metadataFile.readText())
        val payload = DurableCapturePayload(
            captureId = json.getString("capture_id"),
            capturedAt = json.getString("captured_at"),
            deviceId = json.getString("device_id"),
            mimeType = json.getString("mime_type"),
            byteCount = json.getLong("bytes"),
            paletteJson = json.getJSONArray("palette").toString(),
            processingMetadataJson = json.getJSONObject("processing_metadata").toString(),
        ).requireValid()
        require(imageFile.length() == payload.byteCount)
        val hash = sha256(imageFile)
        require(hash == json.getString("image_sha256"))
        val fingerprint = DurableCapturePolicy.immutableFingerprint(
            payload.captureId,
            payload.capturedAt,
            payload.deviceId,
            payload.mimeType,
            payload.byteCount,
            hash,
            payload.paletteJson,
        )
        require(fingerprint == json.getString("immutable_fingerprint"))
        val timestamp = now.toString()
        database.upsert(
            DurableCaptureRecord(
                captureId = payload.captureId,
                state = expectedState,
                capturedAt = payload.capturedAt,
                deviceId = payload.deviceId,
                mimeType = payload.mimeType,
                byteCount = payload.byteCount,
                imageSha256 = hash,
                paletteJson = payload.paletteJson,
                processingMetadataJson = payload.processingMetadataJson,
                immutableFingerprint = fingerprint,
                imagePath = imageFile.absolutePath,
                metadataPath = metadataFile.absolutePath,
                attemptCount = json.optInt("attempt_count", 0),
                lastAttemptAt = json.optNullableString("last_attempt_at"),
                lastErrorCode = json.optNullableString("last_error_code"),
                nextEligibleRetryAt = json.optNullableString("next_eligible_retry_at"),
                deliveredAt = json.optNullableString("delivered_at"),
                deliveryConfirmationJson = json.optJSONObject("delivery_confirmation")?.toString(),
                createdAt = json.optString("created_at", timestamp),
                updatedAt = timestamp,
            ),
        )
        true
    }.getOrElse {
        quarantine(directory, "SIDECAR_RECOVERY_FAILED", now.toString())
        false
    }

    private fun sidecar(
        payload: DurableCapturePayload,
        imageSha256: String,
        fingerprint: String,
        state: DurableCaptureState,
        createdAt: String,
        updatedAt: String,
    ) = JSONObject().apply {
        put("schema_version", SIDECAR_SCHEMA_VERSION)
        put("capture_id", payload.captureId)
        put("state", state.name)
        put("captured_at", payload.capturedAt)
        put("device_id", payload.deviceId)
        put("image_file_name", IMAGE_NAME)
        put("mime_type", payload.mimeType)
        put("bytes", payload.byteCount)
        put("image_sha256", imageSha256)
        put("immutable_fingerprint", fingerprint)
        put("palette", JSONArray(payload.paletteJson))
        put("processing_metadata", JSONObject(payload.processingMetadataJson))
        put("attempt_count", 0)
        put("last_attempt_at", JSONObject.NULL)
        put("last_error_code", JSONObject.NULL)
        put("next_eligible_retry_at", JSONObject.NULL)
        put("delivered_at", JSONObject.NULL)
        put("delivery_confirmation", JSONObject.NULL)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
    }

    private fun validateImmutableFiles(record: DurableCaptureRecord): Boolean {
        val image = File(record.imagePath)
        val metadata = record.metadataPath?.let(::File)
        return image.isFile && image.length() == record.byteCount &&
            sha256(image) == record.imageSha256 && metadata?.isFile == true
    }

    private fun updateSidecar(record: DurableCaptureRecord) {
        val path = record.metadataPath?.let(::File) ?: return
        if (!path.isFile) return
        val json = JSONObject(path.readText()).apply {
            put("state", record.state.name)
            put("attempt_count", record.attemptCount)
            put("last_attempt_at", record.lastAttemptAt ?: JSONObject.NULL)
            put("last_error_code", record.lastErrorCode ?: JSONObject.NULL)
            put("next_eligible_retry_at", record.nextEligibleRetryAt ?: JSONObject.NULL)
            put("delivered_at", record.deliveredAt ?: JSONObject.NULL)
            put(
                "delivery_confirmation",
                record.deliveryConfirmationJson?.let(::JSONObject) ?: JSONObject.NULL,
            )
            put("updated_at", record.updatedAt)
        }
        val temporary = File(path.parentFile, ".${path.name}.${UUID.randomUUID()}.tmp")
        writeSynced(temporary, json.toString())
        move(temporary, path)
    }

    private fun preserveConflict(
        captureId: String,
        evidence: File,
        existingFingerprint: String?,
        incomingFingerprint: String,
        detectedAt: String,
    ): Nothing {
        val directory = File(
            quarantineDirectory,
            "$captureId-conflict-${UUID.randomUUID()}",
        ).apply { mkdirs() }
        val destination = File(directory, evidence.name)
        if (evidence.isDirectory) move(evidence, destination) else copySynced(evidence, destination)
        database.insertConflict(
            captureId,
            existingFingerprint,
            incomingFingerprint,
            destination.absolutePath,
            detectedAt,
        )
        throw CaptureIdConflictException(
            "Capture ID $captureId was reused with conflicting immutable content; evidence was retained",
        )
    }

    private fun quarantine(evidence: File, reason: String, detectedAt: String): File {
        DurableCapturePolicy.requireSafeErrorCode(reason)
        val destination = File(
            quarantineDirectory,
            "${evidence.name}-${UUID.randomUUID()}",
        )
        move(evidence, destination)
        val manifest = if (destination.isDirectory) {
            File(destination, "quarantine.json")
        } else {
            File(destination.parentFile, "${destination.name}.quarantine.json")
        }
        writeSynced(
            manifest,
            JSONObject().apply {
                put("reason", reason)
                put("detected_at", detectedAt)
                put("evidence_path", destination.absolutePath)
            }.toString(),
        )
        return destination
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16_384)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copySynced(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun writeSynced(file: File, value: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun move(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        runCatching {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun File.isDescendantOf(parent: File): Boolean =
        canonicalFile.toPath().startsWith(parent.canonicalFile.toPath())

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    companion object {
        private const val IMAGE_NAME = "capture.jpg"
        private const val METADATA_NAME = "capture.json"
        private const val SIDECAR_SCHEMA_VERSION = 1
    }
}
