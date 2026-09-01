package com.jaredwinick.colors.camera.palette

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.jaredwinick.colors.camera.mask.MaskPreviewRenderer
import com.jaredwinick.colors.camera.mask.SkyMaskConfig
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class PalettePreviewRenderer {
    private val maskRenderer = MaskPreviewRenderer()

    fun render(
        sourceFile: File,
        outputFile: File,
        maskConfig: SkyMaskConfig,
        palette: WeightedPalette,
    ): File {
        palette.requireValid()
        outputFile.parentFile?.mkdirs()
        val temporaryOverlay = File(outputFile.parentFile, ".${outputFile.name}.mask.tmp.jpg")
        try {
            maskRenderer.render(sourceFile, temporaryOverlay, maskConfig, calibrationGrid = false)
            val overlay = BitmapFactory.decodeFile(temporaryOverlay.absolutePath)
                ?: throw IllegalStateException("The palette mask overlay could not be decoded")
            try {
                val footerHeight = maxOf(72, (overlay.height * 0.08).roundToInt())
                val preview = Bitmap.createBitmap(
                    overlay.width,
                    overlay.height + footerHeight,
                    Bitmap.Config.ARGB_8888,
                )
                try {
                    val canvas = Canvas(preview)
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(overlay, 0f, 0f, null)
                    drawSwatches(canvas, overlay.height, footerHeight, palette)
                    FileOutputStream(outputFile).use { output ->
                        check(preview.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                            "Could not encode the palette preview"
                        }
                        output.fd.sync()
                    }
                } finally {
                    preview.recycle()
                }
            } finally {
                overlay.recycle()
            }
            return outputFile
        } finally {
            temporaryOverlay.delete()
        }
    }

    private fun drawSwatches(
        canvas: Canvas,
        top: Int,
        footerHeight: Int,
        palette: WeightedPalette,
    ) {
        val fill = Paint().apply { style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = maxOf(18f, footerHeight * 0.28f)
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        var left = 0
        var cumulativeWeight = 0.0
        palette.colors.forEachIndexed { index, entry ->
            cumulativeWeight += entry.weight
            val right = if (index == palette.colors.lastIndex) {
                canvas.width
            } else {
                (canvas.width * cumulativeWeight).roundToInt()
                    .coerceIn((left + 1).coerceAtMost(canvas.width), canvas.width)
            }
            fill.color = Color.parseColor(entry.hex)
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), canvas.height.toFloat(), fill)
            if (right - left >= 100) {
                canvas.drawText(
                    "${entry.hex} ${"%.1f".format(java.util.Locale.US, entry.weight * 100)}%",
                    left + 8f,
                    top + text.textSize + 8f,
                    text,
                )
            }
            left = right
        }
    }
}
