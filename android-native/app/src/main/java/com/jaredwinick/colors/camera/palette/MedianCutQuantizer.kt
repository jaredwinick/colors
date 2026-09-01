package com.jaredwinick.colors.camera.palette

import kotlin.math.roundToInt

data class HistogramColor(val rgb: Int, val count: Int) {
    val red: Int get() = rgb shr 16 and 0xff
    val green: Int get() = rgb shr 8 and 0xff
    val blue: Int get() = rgb and 0xff
}

data class QuantizedColor(val rgb: Int, val count: Int)

object MedianCutQuantizer {
    fun quantize(rgbPixels: IntArray, requestedColors: Int): WeightedPalette {
        require(requestedColors in WeightedPalette.MIN_COLORS..WeightedPalette.MAX_COLORS) {
            "Palette color count must be between 3 and 10"
        }
        require(rgbPixels.isNotEmpty()) { "The validated sky mask included no pixels" }
        val histogram = rgbPixels.asSequence()
            .map { it and 0x00ffffff }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
            .map { (rgb, count) -> HistogramColor(rgb, count) }
        require(histogram.size >= WeightedPalette.MIN_COLORS) {
            "Quantization produced ${histogram.size} colors; the ingest API requires 3-10"
        }

        val boxes = mutableListOf(ColorBox(histogram))
        while (boxes.size < requestedColors) {
            val candidate = boxes.withIndex()
                .filter { it.value.colors.size > 1 }
                .maxWithOrNull(
                    compareBy<IndexedValue<ColorBox>> { it.value.splitScore }
                        .thenBy { it.value.maximumRange }
                        .thenBy { it.value.population }
                        .thenByDescending { it.value.minimumRgb }
                        .thenByDescending { it.index },
                ) ?: break
            val split = candidate.value.split()
            boxes.removeAt(candidate.index)
            boxes.add(split.first)
            boxes.add(split.second)
        }

        return paletteFromQuantized(boxes.map(ColorBox::representative), rgbPixels.size)
    }

    internal fun paletteFromQuantized(
        quantizedColors: List<QuantizedColor>,
        totalPixels: Int,
    ): WeightedPalette {
        require(totalPixels > 0) { "Total sampled pixels must be positive" }
        require(quantizedColors.all { it.count > 0 }) { "Quantized counts must be positive" }
        require(quantizedColors.sumOf(QuantizedColor::count) == totalPixels) {
            "Quantized counts must match the sampled pixel total"
        }
        val merged = quantizedColors
            .groupingBy(QuantizedColor::rgb)
            .fold(0) { total, color -> total + color.count }
            .entries
            .map { QuantizedColor(it.key, it.value) }
            .sortedWith(compareByDescending<QuantizedColor> { it.count }.thenBy { it.rgb })
        require(merged.size >= WeightedPalette.MIN_COLORS) {
            "Quantization produced ${merged.size} colors; the ingest API requires 3-10"
        }
        return normalize(merged, totalPixels)
    }

    private fun normalize(colors: List<QuantizedColor>, totalPixels: Int): WeightedPalette {
        val millionths = colors.map { color ->
            (color.count.toDouble() * WEIGHT_SCALE / totalPixels).roundToInt()
        }.toMutableList()
        millionths[0] += WEIGHT_SCALE - millionths.sum()
        require(millionths.all { it > 0 }) { "Quantization produced a zero-weight color" }
        val weighted = colors.mapIndexed { index, color ->
                WeightedPaletteColor(
                    hex = WeightedPalette.hex(color.rgb),
                    weight = millionths[index].toDouble() / WEIGHT_SCALE,
                )
            }
            .sortedWith(
                compareByDescending<WeightedPaletteColor> { it.weight }.thenBy { it.hex },
            )
        return WeightedPalette(weighted).requireValid()
    }

    private data class ColorBox(val colors: List<HistogramColor>) {
        val population: Int = colors.sumOf(HistogramColor::count)
        private val redRange = colors.maxOf(HistogramColor::red) - colors.minOf(HistogramColor::red)
        private val greenRange = colors.maxOf(HistogramColor::green) - colors.minOf(HistogramColor::green)
        private val blueRange = colors.maxOf(HistogramColor::blue) - colors.minOf(HistogramColor::blue)
        val maximumRange: Int = maxOf(redRange, greenRange, blueRange)
        val splitScore: Long = population.toLong() * (maximumRange + 1L)
        val minimumRgb: Int = colors.minOf(HistogramColor::rgb)

        fun split(): Pair<ColorBox, ColorBox> {
            val channel = when (maximumRange) {
                redRange -> Channel.RED
                greenRange -> Channel.GREEN
                else -> Channel.BLUE
            }
            val ordered = colors.sortedWith(channel.comparator)
            val target = (population + 1) / 2
            var cumulative = 0
            var splitIndex = 1
            for (index in 0 until ordered.lastIndex) {
                cumulative += ordered[index].count
                splitIndex = index + 1
                if (cumulative >= target) break
            }
            return ColorBox(ordered.subList(0, splitIndex)) to
                ColorBox(ordered.subList(splitIndex, ordered.size))
        }

        fun representative(): QuantizedColor {
            fun average(component: (HistogramColor) -> Int): Int {
                val weighted = colors.sumOf { color -> component(color).toLong() * color.count }
                return ((weighted + population / 2) / population).toInt().coerceIn(0, 255)
            }
            val rgb = average(HistogramColor::red) shl 16 or
                (average(HistogramColor::green) shl 8) or
                average(HistogramColor::blue)
            return QuantizedColor(rgb, population)
        }
    }

    private enum class Channel(val comparator: Comparator<HistogramColor>) {
        RED(
            compareBy<HistogramColor> { it.red }
                .thenBy { it.green }
                .thenBy { it.blue }
                .thenBy { it.rgb },
        ),
        GREEN(
            compareBy<HistogramColor> { it.green }
                .thenBy { it.red }
                .thenBy { it.blue }
                .thenBy { it.rgb },
        ),
        BLUE(
            compareBy<HistogramColor> { it.blue }
                .thenBy { it.red }
                .thenBy { it.green }
                .thenBy { it.rgb },
        ),
    }

    private const val WEIGHT_SCALE = 1_000_000
}
