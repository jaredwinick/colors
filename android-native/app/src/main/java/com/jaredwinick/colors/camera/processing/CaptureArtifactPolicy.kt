package com.jaredwinick.colors.camera.processing

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

data class ImageDimensions(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
    }
}

data class ImageTransformPlan(
    val rotationDegrees: Int,
    val flipHorizontal: Boolean,
    val orientedDimensions: ImageDimensions,
    val finalDimensions: ImageDimensions,
)

object ImageTransformPlanner {
    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        exifOrientation: Int,
        maximumDimension: Int,
    ): ImageTransformPlan {
        require(maximumDimension > 0) { "Maximum dimension must be positive" }
        val source = ImageDimensions(sourceWidth, sourceHeight)
        val (rotation, flip) = when (exifOrientation) {
            2 -> 0 to true
            3 -> 180 to false
            4 -> 180 to true
            5 -> 270 to true
            6 -> 90 to false
            7 -> 90 to true
            8 -> 270 to false
            else -> 0 to false
        }
        val oriented = if (rotation == 90 || rotation == 270) {
            ImageDimensions(source.height, source.width)
        } else {
            source
        }
        val longest = maxOf(oriented.width, oriented.height)
        val final = if (longest <= maximumDimension) {
            oriented
        } else {
            val scale = maximumDimension.toDouble() / longest
            ImageDimensions(
                width = (oriented.width * scale).roundToInt().coerceAtLeast(1),
                height = (oriented.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
        return ImageTransformPlan(rotation, flip, oriented, final)
    }
}

object CaptureArtifactPolicy {
    const val MAX_JPEG_BYTES = 12L * 1_024 * 1_024
    private val UUID_V4 = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        RegexOption.IGNORE_CASE,
    )

    fun requireCaptureId(value: String): String {
        require(UUID_V4.matches(value)) { "Capture ID must be a UUIDv4" }
        require(UUID.fromString(value).version() == 4) { "Capture ID must be a UUIDv4" }
        return value
    }

    fun requireUtcTimestamp(value: String): String {
        val instant = runCatching { Instant.parse(value) }
            .getOrElse { throw IllegalArgumentException("Capture time must be UTC ISO-8601") }
        require(value.endsWith("Z") && instant.toString() == value) {
            "Capture time must be normalized UTC ISO-8601"
        }
        return value
    }

    fun requireJpegSize(bytes: Long): Long {
        require(bytes in 1..MAX_JPEG_BYTES) { "JPEG exceeds the 12 MB Worker limit" }
        return bytes
    }

    fun isDisplayReadyExifOrientation(value: Int): Boolean = value == 0 || value == 1
}

object IncompleteCaptureCleaner {
    fun clean(workDirectory: File, imageDirectory: File, metadataDirectory: File): Int {
        var removed = 0
        fun remove(file: File) {
            if (file.delete()) removed += 1
        }
        workDirectory.listFiles().orEmpty().forEach(::remove)
        imageDirectory.listFiles { file -> file.name.endsWith(".tmp") }.orEmpty().forEach(::remove)
        metadataDirectory.listFiles { file -> file.name.endsWith(".tmp") }.orEmpty().forEach(::remove)
        imageDirectory.listFiles { file ->
            file.extension.equals("jpg", ignoreCase = true) &&
                runCatching { CaptureArtifactPolicy.requireCaptureId(file.nameWithoutExtension) }.isSuccess &&
                !File(metadataDirectory, "${file.nameWithoutExtension}.json").exists()
        }.orEmpty().forEach(::remove)
        return removed
    }
}
