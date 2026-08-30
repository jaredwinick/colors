package com.jaredwinick.colors.camera.mask

import kotlin.math.roundToInt

data class MaskSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Mask dimensions must be positive" }
    }
}

data class PixelPoint(val x: Int, val y: Int)

data class MaskStatistics(
    val size: MaskSize,
    val includedPixels: Int,
    val excludedPixels: Int,
    val includedFraction: Double,
)

data class RasterizedSkyMask(
    val size: MaskSize,
    val included: BooleanArray,
    val statistics: MaskStatistics,
) {
    fun includes(x: Int, y: Int): Boolean = included[y * size.width + x]
}

object SkyMaskRasterizer {
    fun rasterize(config: SkyMaskConfig, size: MaskSize): RasterizedSkyMask {
        config.requireValid()
        val pixels = BooleanArray(size.width * size.height)
        fillPolygon(pixels, size, pixelPolygon(config.includePolygon, size), true)
        config.excludePolygons.forEach { polygon ->
            fillPolygon(pixels, size, pixelPolygon(polygon, size), false)
        }
        config.excludeRectangles.forEach { rectangle ->
            val topLeft = pixelPoint(NormalizedPoint(rectangle.left, rectangle.top), size)
            val bottomRight = pixelPoint(NormalizedPoint(rectangle.right, rectangle.bottom), size)
            for (y in topLeft.y..bottomRight.y) {
                val offset = y * size.width
                for (x in topLeft.x..bottomRight.x) pixels[offset + x] = false
            }
        }
        val includedPixels = pixels.count { it }
        val totalPixels = pixels.size
        val includedFraction = includedPixels.toDouble() / totalPixels
        require(includedPixels >= config.minimumIncludedPixels) {
            "Mask includes $includedPixels pixels; minimum is ${config.minimumIncludedPixels}"
        }
        require(includedFraction >= config.minimumIncludedFraction) {
            "Mask includes ${"%.4f".format(java.util.Locale.US, includedFraction)} of the image; " +
                "minimum is ${"%.4f".format(java.util.Locale.US, config.minimumIncludedFraction)}"
        }
        return RasterizedSkyMask(
            size = size,
            included = pixels,
            statistics = MaskStatistics(
                size = size,
                includedPixels = includedPixels,
                excludedPixels = totalPixels - includedPixels,
                includedFraction = includedFraction,
            ),
        )
    }

    fun pixelPoint(point: NormalizedPoint, size: MaskSize): PixelPoint = PixelPoint(
        x = java.lang.Math.rint(point.x * (size.width - 1)).toInt(),
        y = java.lang.Math.rint(point.y * (size.height - 1)).toInt(),
    )

    fun analysisSize(source: MaskSize, maximumDimension: Int): MaskSize {
        require(maximumDimension > 0) { "Analysis dimension must be positive" }
        val longest = maxOf(source.width, source.height)
        if (longest <= maximumDimension) return source
        val scale = maximumDimension.toDouble() / longest
        return MaskSize(
            width = (source.width * scale).roundToInt().coerceAtLeast(1),
            height = (source.height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    private fun pixelPolygon(points: List<NormalizedPoint>, size: MaskSize): List<PixelPoint> =
        points.map { pixelPoint(it, size) }

    private fun fillPolygon(
        pixels: BooleanArray,
        size: MaskSize,
        polygon: List<PixelPoint>,
        value: Boolean,
    ) {
        val minX = polygon.minOf { it.x }.coerceIn(0, size.width - 1)
        val maxX = polygon.maxOf { it.x }.coerceIn(0, size.width - 1)
        val minY = polygon.minOf { it.y }.coerceIn(0, size.height - 1)
        val maxY = polygon.maxOf { it.y }.coerceIn(0, size.height - 1)
        for (y in minY..maxY) {
            val offset = y * size.width
            for (x in minX..maxX) {
                if (containsInclusive(x, y, polygon)) pixels[offset + x] = value
            }
        }
    }

    private fun containsInclusive(x: Int, y: Int, polygon: List<PixelPoint>): Boolean {
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            if (onSegment(x, y, previous, current)) return true
            if ((current.y > y) != (previous.y > y)) {
                val intersection = (previous.x - current.x).toDouble() *
                    (y - current.y).toDouble() / (previous.y - current.y).toDouble() + current.x
                if (x < intersection) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun onSegment(x: Int, y: Int, first: PixelPoint, second: PixelPoint): Boolean {
        val cross = (x - first.x).toLong() * (second.y - first.y) -
            (y - first.y).toLong() * (second.x - first.x)
        return cross == 0L &&
            x in minOf(first.x, second.x)..maxOf(first.x, second.x) &&
            y in minOf(first.y, second.y)..maxOf(first.y, second.y)
    }
}

