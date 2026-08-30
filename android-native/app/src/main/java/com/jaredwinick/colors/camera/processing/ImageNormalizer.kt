package com.jaredwinick.colors.camera.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

data class ImageNormalizationResult(
    val sourceDimensions: ImageDimensions,
    val finalDimensions: ImageDimensions,
    val byteCount: Long,
    val sourceExifOrientation: Int,
)

class CaptureProcessingException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class ImageNormalizer {
    fun normalize(
        source: File,
        destination: File,
        maximumDimension: Int,
        jpegQuality: Int,
    ): ImageNormalizationResult {
        require(jpegQuality in 1..95) { "JPEG quality must be between 1 and 95" }
        destination.delete()
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType != "image/jpeg") {
                throw CaptureProcessingException("SOURCE_JPEG_INVALID", "Camera output is not a decodable JPEG")
            }
            val orientation = ExifInterface(source).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
            val transform = ImageTransformPlanner.plan(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                exifOrientation = orientation,
                maximumDimension = maximumDimension,
            )
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maximumDimension)
            }
            val decoded = BitmapFactory.decodeFile(source.absolutePath, options)
                ?: throw CaptureProcessingException("SOURCE_JPEG_INVALID", "Camera JPEG decode failed")
            var current = decoded
            try {
                if (transform.rotationDegrees != 0 || transform.flipHorizontal) {
                    val matrix = Matrix().apply {
                        if (transform.flipHorizontal) postScale(-1f, 1f)
                        if (transform.rotationDegrees != 0) postRotate(transform.rotationDegrees.toFloat())
                    }
                    current = Bitmap.createBitmap(
                        decoded,
                        0,
                        0,
                        decoded.width,
                        decoded.height,
                        matrix,
                        true,
                    )
                }
                if (
                    current.width != transform.finalDimensions.width ||
                    current.height != transform.finalDimensions.height
                ) {
                    val scaled = Bitmap.createScaledBitmap(
                        current,
                        transform.finalDimensions.width,
                        transform.finalDimensions.height,
                        true,
                    )
                    if (current !== decoded) current.recycle()
                    current = scaled
                }
                val rgb = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
                Canvas(rgb).apply {
                    drawColor(Color.BLACK)
                    drawBitmap(current, 0f, 0f, null)
                }
                try {
                    FileOutputStream(destination).use { output ->
                        if (!rgb.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)) {
                            throw CaptureProcessingException("JPEG_ENCODE_FAILED", "JPEG encoder failed")
                        }
                        output.fd.sync()
                    }
                } finally {
                    rgb.recycle()
                }
            } finally {
                if (current !== decoded) current.recycle()
                decoded.recycle()
            }

            if (destination.length() > CaptureArtifactPolicy.MAX_JPEG_BYTES) {
                throw CaptureProcessingException(
                    "FINAL_JPEG_TOO_LARGE",
                    "Normalized JPEG exceeds the 12 MB Worker limit",
                )
            }
            CaptureArtifactPolicy.requireJpegSize(destination.length())
            if (!hasJpegSignature(destination)) {
                throw CaptureProcessingException("FINAL_JPEG_INVALID", "Normalized file has no JPEG signature")
            }
            val verified = BitmapFactory.decodeFile(destination.absolutePath)
                ?: throw CaptureProcessingException("FINAL_JPEG_INVALID", "Normalized JPEG decode failed")
            val verifiedDimensions = ImageDimensions(verified.width, verified.height)
            verified.recycle()
            if (verifiedDimensions != transform.finalDimensions) {
                throw CaptureProcessingException("FINAL_DIMENSIONS_INVALID", "Normalized dimensions are incorrect")
            }
            val finalOrientation = ExifInterface(destination).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
            if (finalOrientation != ExifInterface.ORIENTATION_NORMAL) {
                throw CaptureProcessingException("FINAL_ORIENTATION_INVALID", "EXIF orientation was not normalized")
            }
            return ImageNormalizationResult(
                sourceDimensions = ImageDimensions(bounds.outWidth, bounds.outHeight),
                finalDimensions = verifiedDimensions,
                byteCount = destination.length(),
                sourceExifOrientation = orientation,
            )
        } catch (error: CaptureProcessingException) {
            destination.delete()
            throw error
        } catch (error: IllegalArgumentException) {
            destination.delete()
            throw CaptureProcessingException("NORMALIZATION_REJECTED", error.message ?: "Image rejected", error)
        } catch (error: Exception) {
            destination.delete()
            throw CaptureProcessingException("NORMALIZATION_FAILED", "Image normalization failed", error)
        }
    }

    private fun sampleSize(width: Int, height: Int, maximumDimension: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maximumDimension) sample *= 2
        return sample
    }

    private fun hasJpegSignature(file: File): Boolean = file.inputStream().use { input ->
        input.read() == 0xff && input.read() == 0xd8 && input.read() == 0xff
    }
}
