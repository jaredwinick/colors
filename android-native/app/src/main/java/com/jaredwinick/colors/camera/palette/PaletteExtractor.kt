package com.jaredwinick.colors.camera.palette

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import com.jaredwinick.colors.camera.mask.MaskSize
import com.jaredwinick.colors.camera.mask.SkyMaskRepository
import java.io.File

data class PaletteStatistics(
    val sourceSize: MaskSize,
    val analysisSize: MaskSize,
    val includedPixels: Int,
    val requestedColors: Int,
    val paletteColors: Int,
    val elapsedMs: Long,
    val peakPssKib: Long,
)

data class PaletteExtractionResult(
    val palette: WeightedPalette,
    val statistics: PaletteStatistics,
)

class PaletteExtractionException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class PaletteExtractor {
    fun extract(
        sourceFile: File,
        requestedColors: Int,
        analysisDimension: Int,
        masks: SkyMaskRepository,
    ): PaletteExtractionResult {
        val startedAt = SystemClock.elapsedRealtime()
        var peakPssKib = Debug.getPss().toLong()
        var source: Bitmap? = null
        var analysis: Bitmap? = null
        try {
            require(requestedColors in WeightedPalette.MIN_COLORS..WeightedPalette.MAX_COLORS) {
                "Palette color count must be between 3 and 10"
            }
            require(analysisDimension > 0) { "Palette analysis dimension must be positive" }
            val sourceBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
                ?: throw PaletteExtractionException(
                    "PALETTE_IMAGE_DECODE_FAILED",
                    "The normalized production JPEG could not be decoded",
                )
            source = sourceBitmap
            val sourceSize = MaskSize(sourceBitmap.width, sourceBitmap.height)
            val analysisSize = com.jaredwinick.colors.camera.mask.SkyMaskRasterizer.analysisSize(
                sourceSize,
                analysisDimension,
            )
            val analysisBitmap = if (analysisSize == sourceSize) {
                sourceBitmap
            } else {
                Bitmap.createScaledBitmap(
                    sourceBitmap,
                    analysisSize.width,
                    analysisSize.height,
                    true,
                )
            }
            analysis = analysisBitmap
            peakPssKib = maxOf(peakPssKib, Debug.getPss().toLong())
            val mask = try {
                masks.validatedAnalysisMask(sourceSize, analysisDimension)
            } catch (error: Exception) {
                throw PaletteExtractionException(
                    "PALETTE_MASK_INVALID",
                    error.message ?: "The active sky mask is invalid at analysis resolution",
                    error,
                )
            }
            check(mask.size == analysisSize) { "Mask and analysis image dimensions differ" }
            val allPixels = IntArray(analysisSize.width * analysisSize.height)
            analysisBitmap.getPixels(
                allPixels,
                0,
                analysisSize.width,
                0,
                0,
                analysisSize.width,
                analysisSize.height,
            )
            val includedPixels = IntArray(mask.statistics.includedPixels)
            var includedIndex = 0
            mask.included.indices.forEach { index ->
                if (mask.included[index]) includedPixels[includedIndex++] = allPixels[index]
            }
            check(includedIndex == includedPixels.size) { "Mask sample count changed unexpectedly" }
            peakPssKib = maxOf(peakPssKib, Debug.getPss().toLong())
            val palette = try {
                MedianCutQuantizer.quantize(includedPixels, requestedColors)
            } catch (error: IllegalArgumentException) {
                throw PaletteExtractionException(
                    "PALETTE_QUANTIZATION_INVALID",
                    error.message ?: "Palette quantization failed",
                    error,
                )
            }
            peakPssKib = maxOf(peakPssKib, Debug.getPss().toLong())
            return PaletteExtractionResult(
                palette = palette,
                statistics = PaletteStatistics(
                    sourceSize = sourceSize,
                    analysisSize = analysisSize,
                    includedPixels = includedPixels.size,
                    requestedColors = requestedColors,
                    paletteColors = palette.colors.size,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    peakPssKib = peakPssKib,
                ),
            )
        } catch (error: PaletteExtractionException) {
            throw error
        } catch (error: Exception) {
            throw PaletteExtractionException(
                "PALETTE_EXTRACTION_FAILED",
                error.message ?: "Palette extraction failed",
                error,
            )
        } finally {
            analysis?.takeIf { it !== source }?.recycle()
            source?.recycle()
        }
    }
}
