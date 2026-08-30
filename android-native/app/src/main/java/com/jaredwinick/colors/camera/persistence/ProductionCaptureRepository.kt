package com.jaredwinick.colors.camera.persistence

import android.content.Context
import android.os.Environment
import com.jaredwinick.colors.camera.camera.AppliedCameraSettings
import com.jaredwinick.colors.camera.processing.CaptureArtifactPolicy
import com.jaredwinick.colors.camera.processing.ImageDimensions
import com.jaredwinick.colors.camera.processing.IncompleteCaptureCleaner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ProductionCaptureMetadata(
    val captureId: String,
    val capturedAt: String,
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
    val imageFileName: String = "$captureId.jpg",
    val mimeType: String = "image/jpeg",
    val completeUnmaskedView: Boolean = true,
    val schemaVersion: Int = 1,
) {
    fun requireValid(): ProductionCaptureMetadata {
        CaptureArtifactPolicy.requireCaptureId(captureId)
        CaptureArtifactPolicy.requireUtcTimestamp(capturedAt)
        CaptureArtifactPolicy.requireJpegSize(byteCount)
        require(imageFileName == "$captureId.jpg") { "Image filename must match capture ID" }
        require(mimeType == "image/jpeg") { "Production capture must be a JPEG" }
        require(jpegQuality in 1..95) { "JPEG quality must be between 1 and 95" }
        require(maximumDimension > 0) { "Maximum dimension must be positive" }
        require(processingDurationMs >= 0) { "Processing duration must not be negative" }
        require(completeUnmaskedView) { "Production image must remain complete and unmasked" }
        return this
    }
}

class ProductionCaptureRepository(context: Context) {
    private val root = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
    private val imageDirectory = File(root, "captures").apply { mkdirs() }
    private val metadataDirectory = File(root, "capture-metadata").apply { mkdirs() }
    private val workDirectory = File(root, "capture-work").apply { mkdirs() }

    init {
        IncompleteCaptureCleaner.clean(workDirectory, imageDirectory, metadataDirectory)
    }

    fun rawFile(captureId: String): File {
        CaptureArtifactPolicy.requireCaptureId(captureId)
        return File(workDirectory, "$captureId.raw.jpg")
    }

    fun normalizedTempFile(captureId: String): File {
        CaptureArtifactPolicy.requireCaptureId(captureId)
        return File(imageDirectory, ".$captureId.jpg.tmp")
    }

    @Synchronized
    fun commit(normalizedTemp: File, metadata: ProductionCaptureMetadata): File {
        metadata.requireValid()
        require(normalizedTemp == normalizedTempFile(metadata.captureId)) {
            "Normalized temporary path does not match capture ID"
        }
        require(normalizedTemp.exists()) { "Normalized JPEG is missing" }
        require(normalizedTemp.length() == metadata.byteCount) { "JPEG byte count changed before commit" }
        val finalImage = File(imageDirectory, metadata.imageFileName)
        val finalMetadata = File(metadataDirectory, "${metadata.captureId}.json")
        val temporaryMetadata = File(metadataDirectory, ".${metadata.captureId}.json.tmp")
        require(!finalImage.exists() && !finalMetadata.exists()) { "Capture ID is already committed" }

        try {
            move(normalizedTemp, finalImage)
            writeSynced(temporaryMetadata, metadata.toJson().toString())
            move(temporaryMetadata, finalMetadata)
            return finalImage
        } catch (error: Exception) {
            normalizedTemp.delete()
            temporaryMetadata.delete()
            finalMetadata.delete()
            finalImage.delete()
            throw error
        }
    }

    fun abandon(captureId: String) {
        rawFile(captureId).delete()
        normalizedTempFile(captureId).delete()
    }

    @Synchronized
    fun latestCommittedImage(): File? = metadataDirectory
        .listFiles { file -> file.extension.equals("json", ignoreCase = true) }
        .orEmpty()
        .sortedByDescending(File::lastModified)
        .firstNotNullOfOrNull { metadata ->
            File(imageDirectory, "${metadata.nameWithoutExtension}.jpg").takeIf(File::isFile)
        }

    private fun move(source: File, destination: File) {
        runCatching {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeSynced(file: File, value: String) {
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun ProductionCaptureMetadata.toJson(): JSONObject = JSONObject().apply {
        put("schema_version", schemaVersion)
        put("capture_id", captureId)
        put("captured_at", capturedAt)
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
    }
}
