package com.jaredwinick.colors.camera.persistence

import android.content.Context
import android.os.Environment
import com.jaredwinick.colors.camera.camera.AppliedCameraSettings
import com.jaredwinick.colors.camera.outbox.DurableCapturePayload
import com.jaredwinick.colors.camera.outbox.DurableCaptureStore
import com.jaredwinick.colors.camera.outbox.OutboxSummary
import com.jaredwinick.colors.camera.outbox.ReconciliationReport
import com.jaredwinick.colors.camera.palette.PaletteStatistics
import com.jaredwinick.colors.camera.palette.WeightedPalette
import com.jaredwinick.colors.camera.processing.CaptureArtifactPolicy
import com.jaredwinick.colors.camera.processing.ImageDimensions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

data class ProductionCaptureMetadata(
    val captureId: String,
    val capturedAt: String,
    val deviceId: String,
    val sourceDimensions: ImageDimensions,
    val finalDimensions: ImageDimensions,
    val byteCount: Long,
    val selectedCamera: String,
    val cameraId: String,
    val jpegQuality: Int,
    val maximumDimension: Int,
    val sourceExifOrientation: Int,
    val cameraSettings: AppliedCameraSettings,
    val processingDurationMs: Long,
    val palette: WeightedPalette? = null,
    val paletteStatistics: PaletteStatistics? = null,
    val paletteErrorCode: String? = null,
    val imageFileName: String = "$captureId.jpg",
    val mimeType: String = "image/jpeg",
    val completeUnmaskedView: Boolean = true,
    val schemaVersion: Int = 2,
) {
    fun requireValid(): ProductionCaptureMetadata {
        CaptureArtifactPolicy.requireCaptureId(captureId)
        CaptureArtifactPolicy.requireUtcTimestamp(capturedAt)
        require(DEVICE_ID_PATTERN.matches(deviceId)) { "Device ID is invalid" }
        CaptureArtifactPolicy.requireJpegSize(byteCount)
        require(imageFileName == "$captureId.jpg") { "Image filename must match capture ID" }
        require(mimeType == "image/jpeg") { "Production capture must be a JPEG" }
        require(jpegQuality in 1..95) { "JPEG quality must be between 1 and 95" }
        require(maximumDimension > 0) { "Maximum dimension must be positive" }
        require(processingDurationMs >= 0) { "Processing duration must not be negative" }
        require(completeUnmaskedView) { "Production image must remain complete and unmasked" }
        require((palette != null) xor (paletteErrorCode != null)) {
            "Capture metadata must contain either a valid palette or a palette error"
        }
        if (palette != null) {
            palette.requireValid()
            requireNotNull(paletteStatistics) { "Successful palette metadata requires statistics" }
            require(paletteStatistics.paletteColors == palette.colors.size) {
                "Palette statistics must match the stored palette"
            }
        } else {
            require(paletteStatistics == null) { "Failed palette metadata cannot contain statistics" }
            require(PALETTE_ERROR_PATTERN.matches(requireNotNull(paletteErrorCode))) {
                "Palette error code must be safe for diagnostics"
            }
        }
        return this
    }

    companion object {
        private val PALETTE_ERROR_PATTERN = Regex("[A-Z0-9_]{1,100}")
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,100}")
    }
}

class ProductionCaptureRepository(context: Context) {
    private val outbox = DurableCaptureStore(context)
    private val applicationContext = context.applicationContext

    fun migrateLegacy(deviceId: String): Int {
        var migrated = 0
        val legacyRoot = applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val legacyImages = legacyRoot?.let { File(it, "captures") }
        val legacyMetadata = legacyRoot?.let { File(it, "capture-metadata") }
        legacyMetadata?.listFiles { file -> file.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .sortedBy(File::lastModified)
            .forEach { metadata ->
                val image = File(requireNotNull(legacyImages), "${metadata.nameWithoutExtension}.jpg")
                if (image.isFile && outbox.importLegacyCapture(image, metadata, deviceId)) {
                    migrated += 1
                }
            }
        return migrated
    }

    fun rawFile(captureId: String): File {
        CaptureArtifactPolicy.requireCaptureId(captureId)
        return outbox.rawFile(captureId)
    }

    fun normalizedTempFile(captureId: String): File {
        CaptureArtifactPolicy.requireCaptureId(captureId)
        return outbox.normalizedTempFile(captureId)
    }

    fun recordStaged(
        captureId: String,
        capturedAt: String,
        deviceId: String,
        now: Instant = Instant.now(),
    ) = outbox.recordStaged(captureId, capturedAt, deviceId, now)

    fun markProcessing(captureId: String, now: Instant = Instant.now()) =
        outbox.markProcessing(captureId, now)

    @Synchronized
    fun commit(normalizedTemp: File, metadata: ProductionCaptureMetadata): File {
        metadata.requireValid()
        require(normalizedTemp.canonicalFile == normalizedTempFile(metadata.captureId).canonicalFile) {
            "Normalized temporary path does not match capture ID"
        }
        require(normalizedTemp.exists()) { "Normalized JPEG is missing" }
        require(normalizedTemp.length() == metadata.byteCount) { "JPEG byte count changed before commit" }
        val metadataJson = metadata.toJsonObject().toString()
        return if (metadata.palette != null) {
            outbox.enqueue(
                normalizedTemp,
                DurableCapturePayload(
                    captureId = metadata.captureId,
                    capturedAt = metadata.capturedAt,
                    deviceId = metadata.deviceId,
                    mimeType = metadata.mimeType,
                    byteCount = metadata.byteCount,
                    paletteJson = metadata.palette.toJson(),
                    processingMetadataJson = metadataJson,
                ),
            ).record.imagePath.let(::File)
        } else {
            outbox.retainProcessedAttention(
                normalizedTemp = normalizedTemp,
                captureId = metadata.captureId,
                errorCode = requireNotNull(metadata.paletteErrorCode),
                processingMetadataJson = metadataJson,
            ).imagePath.let(::File)
        }
    }

    fun abandon(captureId: String) {
        outbox.discardUncapturedWork(captureId)
    }

    fun markAttention(captureId: String, errorCode: String) =
        outbox.markAttention(captureId, errorCode)

    fun reconcile(): ReconciliationReport = outbox.reconcile()

    fun summary(): OutboxSummary = outbox.summary()

    fun pendingLimitReached(limit: Int): Boolean = summary().pending >= limit

    fun applyDeliveredRetention(days: Int, count: Int): Int =
        outbox.applyDeliveredRetention(days, count)

    fun latestCommittedImage(): File? = outbox.latestLocalImage()

    private fun ProductionCaptureMetadata.toJsonObject(): JSONObject = JSONObject().apply {
        put("schema_version", schemaVersion)
        put("capture_id", captureId)
        put("captured_at", capturedAt)
        put("device_id", deviceId)
        put("image_file_name", imageFileName)
        put("mime_type", mimeType)
        put("bytes", byteCount)
        put("source_width", sourceDimensions.width)
        put("source_height", sourceDimensions.height)
        put("width", finalDimensions.width)
        put("height", finalDimensions.height)
        put("selected_camera", selectedCamera)
        put("camera_id", cameraId)
        put("jpeg_quality", jpegQuality)
        put("maximum_dimension", maximumDimension)
        put("source_exif_orientation", sourceExifOrientation)
        put("processing_duration_ms", processingDurationMs)
        put("complete_unmasked_view", completeUnmaskedView)
        put("flash", "OFF")
        put("scene", "DISABLED")
        put("night_extension", "OFF")
        put("focus_mode", cameraSettings.focusMode.name)
        put("white_balance_mode", cameraSettings.whiteBalanceMode.name)
        put("exposure_compensation_index", cameraSettings.exposureCompensationIndex ?: JSONObject.NULL)
        put("exposure_compensation_ev", cameraSettings.exposureCompensationEv ?: JSONObject.NULL)
        put("camera_fallbacks", JSONArray(cameraSettings.fallbacks))
        put("palette_algorithm", "weighted_median_cut_v1")
        put("palette", palette?.let { JSONArray(it.toJson()) } ?: JSONObject.NULL)
        put("palette_error_code", paletteErrorCode ?: JSONObject.NULL)
        put("palette_statistics", paletteStatistics?.let { statistics ->
            JSONObject().apply {
                put("source_width", statistics.sourceSize.width)
                put("source_height", statistics.sourceSize.height)
                put("analysis_width", statistics.analysisSize.width)
                put("analysis_height", statistics.analysisSize.height)
                put("included_pixels", statistics.includedPixels)
                put("requested_colors", statistics.requestedColors)
                put("palette_colors", statistics.paletteColors)
                put("elapsed_ms", statistics.elapsedMs)
                put("peak_pss_kib", statistics.peakPssKib)
            }
        } ?: JSONObject.NULL)
    }
}
