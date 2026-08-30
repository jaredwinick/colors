package com.jaredwinick.colors.camera.mask

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

data class MaskPreviewResult(
    val output: File,
    val statistics: MaskStatistics,
)

class MaskPreviewRenderer {
    fun render(
        sourceFile: File,
        outputFile: File,
        config: SkyMaskConfig,
        calibrationGrid: Boolean,
    ): MaskPreviewResult {
        val source = BitmapFactory.decodeFile(sourceFile.absolutePath)
            ?: throw IllegalArgumentException("The latest production image could not be decoded")
        try {
            val size = MaskSize(source.width, source.height)
            val mask = SkyMaskRasterizer.rasterize(config, size)
            val preview = source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("Could not allocate the mask preview")
            try {
                tint(preview, mask)
                annotate(preview, config, calibrationGrid)
                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { output ->
                    check(preview.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                        "Could not encode the mask preview"
                    }
                    output.fd.sync()
                }
                return MaskPreviewResult(outputFile, mask.statistics)
            } finally {
                preview.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun tint(bitmap: Bitmap, mask: RasterizedSkyMask) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels.indices.forEach { index ->
            pixels[index] = if (mask.included[index]) {
                blend(pixels[index], CYAN, INCLUDED_TINT)
            } else {
                blend(pixels[index], RED, EXCLUDED_TINT)
            }
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun annotate(bitmap: Bitmap, config: SkyMaskConfig, grid: Boolean) {
        val canvas = Canvas(bitmap)
        val size = MaskSize(bitmap.width, bitmap.height)
        val longest = maxOf(bitmap.width, bitmap.height)
        val lineWidth = maxOf(2f, longest / 480f)
        val labelSize = maxOf(18f, longest / 55f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            color = CYAN
        }

        if (grid) drawGrid(canvas, size, labelSize)
        drawPolygon(canvas, config.includePolygon, size, paint.apply { color = CYAN })

        if (grid) {
            val pointRadius = maxOf(5f, lineWidth + 3f)
            val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = YELLOW
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.BLACK
                textSize = labelSize
                setShadowLayer(2f, 0f, 0f, Color.WHITE)
            }
            config.includePolygon.drop(2).forEachIndexed { relativeIndex, point ->
                val index = relativeIndex + 2
                val pixel = SkyMaskRasterizer.pixelPoint(point, size)
                canvas.drawCircle(pixel.x.toFloat(), pixel.y.toFloat(), pointRadius, pointPaint)
                canvas.drawText(
                    index.toString(),
                    pixel.x + pointRadius + 2f,
                    (pixel.y - pointRadius).coerceAtLeast(labelSize),
                    textPaint,
                )
            }
        }

        paint.color = YELLOW
        config.excludePolygons.forEach { drawPolygon(canvas, it, size, paint) }
        config.excludeRectangles.forEach { rectangle ->
            val topLeft = SkyMaskRasterizer.pixelPoint(
                NormalizedPoint(rectangle.left, rectangle.top),
                size,
            )
            val bottomRight = SkyMaskRasterizer.pixelPoint(
                NormalizedPoint(rectangle.right, rectangle.bottom),
                size,
            )
            canvas.drawRect(
                topLeft.x.toFloat(),
                topLeft.y.toFloat(),
                bottomRight.x.toFloat(),
                bottomRight.y.toFloat(),
                paint,
            )
        }
        drawLegend(canvas, longest, labelSize, grid)
    }

    private fun drawGrid(canvas: Canvas, size: MaskSize, textSize: Float) {
        val linePaint = Paint().apply {
            color = Color.rgb(235, 235, 235)
            strokeWidth = 1f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(235, 235, 235)
            this.textSize = textSize * 0.72f
            setShadowLayer(2f, 0f, 0f, Color.BLACK)
        }
        for (step in 1..9) {
            val position = step / 10.0
            val x = SkyMaskRasterizer.pixelPoint(NormalizedPoint(position, 0.0), size).x
            val y = SkyMaskRasterizer.pixelPoint(NormalizedPoint(0.0, position), size).y
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), (size.height - 1).toFloat(), linePaint)
            canvas.drawLine(0f, y.toFloat(), (size.width - 1).toFloat(), y.toFloat(), linePaint)
            canvas.drawText("x=${"%.1f".format(java.util.Locale.US, position)}", x + 3f, textSize, textPaint)
            canvas.drawText("y=${"%.1f".format(java.util.Locale.US, position)}", 3f, y + textSize, textPaint)
        }
    }

    private fun drawPolygon(
        canvas: Canvas,
        points: List<NormalizedPoint>,
        size: MaskSize,
        paint: Paint,
    ) {
        val pixels = points.map { SkyMaskRasterizer.pixelPoint(it, size) }
        val path = Path().apply {
            moveTo(pixels.first().x.toFloat(), pixels.first().y.toFloat())
            pixels.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawLegend(canvas: Canvas, longest: Int, textSize: Float, grid: Boolean) {
        val padding = maxOf(8f, longest / 160f)
        val rowHeight = textSize * 1.35f
        val rows = if (grid) 3 else 2
        val width = textSize * 14.5f
        val background = Paint().apply { color = Color.BLACK }
        canvas.drawRect(
            padding,
            padding,
            padding + width,
            padding + rowHeight * rows + padding,
            background,
        )
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.textSize = textSize
        }
        textPaint.color = CYAN
        canvas.drawText("CYAN: sampled sky", padding * 2, padding + textSize, textPaint)
        textPaint.color = Color.rgb(255, 120, 125)
        canvas.drawText("RED: excluded", padding * 2, padding + textSize + rowHeight, textPaint)
        if (grid) {
            textPaint.color = YELLOW
            canvas.drawText(
                "YELLOW: boundary point",
                padding * 2,
                padding + textSize + rowHeight * 2,
                textPaint,
            )
        }
    }

    private fun blend(source: Int, tint: Int, amount: Double): Int = Color.rgb(
        mix(Color.red(source), Color.red(tint), amount),
        mix(Color.green(source), Color.green(tint), amount),
        mix(Color.blue(source), Color.blue(tint), amount),
    )

    private fun mix(source: Int, tint: Int, amount: Double): Int =
        (source * (1.0 - amount) + tint * amount).roundToInt().coerceIn(0, 255)

    companion object {
        private val CYAN = Color.rgb(0, 210, 225)
        private val RED = Color.rgb(210, 25, 40)
        private val YELLOW = Color.rgb(255, 230, 0)
        private const val INCLUDED_TINT = 0.16
        private const val EXCLUDED_TINT = 0.58
    }
}

