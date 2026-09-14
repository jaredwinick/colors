package com.jaredwinick.colors.camera.palette

import java.util.TreeMap
import kotlin.math.ceil
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class QuantizedColor(val rgb: Int, val count: Int)

/**
 * Builds a deterministic palette that preserves the scene's dominant colors while reserving
 * up to three slots for small, perceptually distinct accents.
 *
 * With the default eight-color setting this produces five dominant colors and three accents.
 * Smaller configured palettes always retain at least three dominant slots; larger palettes keep
 * three accent slots and spend the remainder on dominant colors.
 */
object HybridPaletteQuantizer {
    fun quantize(rgbPixels: IntArray, requestedColors: Int): WeightedPalette {
        require(requestedColors in WeightedPalette.MIN_COLORS..WeightedPalette.MAX_COLORS) {
            "Palette color count must be between 3 and 10"
        }
        require(rgbPixels.isNotEmpty()) { "The validated sky mask included no pixels" }

        val histogram = exactHistogram(rgbPixels)
        if (histogram.size < WeightedPalette.MIN_COLORS) {
            return paletteFromQuantized(
                histogram.map { QuantizedColor(it.rgb, it.count) },
                rgbPixels.size,
            )
        }
        val bins = coarseBins(histogram).let { coarse ->
            if (coarse.size >= WeightedPalette.MIN_COLORS) coarse else exactBins(histogram)
        }
        val outputColors = min(requestedColors, bins.size)
        val accentCount = min(MAX_ACCENT_COLORS, (outputColors - MIN_DOMINANT_COLORS).coerceAtLeast(0))
        val dominantCount = outputColors - accentCount

        val selected = dominantCenters(bins, dominantCount).toMutableList()
        selectAccents(
            bins = bins,
            selected = selected,
            requestedAccents = accentCount,
            totalPixels = rgbPixels.size,
        )

        val paletteRgbs = LinkedHashSet<Int>()
        selected.forEach { paletteRgbs += it.toRgb() }
        backfillDistinctColors(paletteRgbs, bins, outputColors)

        val palette = paletteRgbs.toList()
        val paletteLabs = palette.map(OklabColor::fromRgb)
        val counts = IntArray(palette.size)
        val labCache = HashMap<Int, OklabColor>()
        rgbPixels.forEach { pixel ->
            val rgb = pixel and 0x00ffffff
            val lab = labCache.getOrPut(rgb) { OklabColor.fromRgb(rgb) }
            counts[nearestCenter(lab, paletteLabs)]++
        }
        return paletteFromQuantized(
            palette.indices
                .filter { counts[it] > 0 }
                .map { QuantizedColor(palette[it], counts[it]) },
            rgbPixels.size,
        )
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
        require(merged.isNotEmpty()) { "Quantization produced no colors" }
        if (merged.size < WeightedPalette.MIN_COLORS) {
            return lowDiversityPalette(merged, totalPixels)
        }
        return normalize(merged, totalPixels)
    }

    /**
     * The ingest contract requires at least three entries, while a valid dark frame may contain
     * only one or two sampled RGB values. Repeating those exact values is more truthful than
     * inventing nearby colors. Splitting the largest weight preserves the scene's aggregate color
     * distribution and gives every required entry a positive deterministic weight.
     */
    private fun lowDiversityPalette(
        colors: List<QuantizedColor>,
        totalPixels: Int,
    ): WeightedPalette {
        val weights = normalizedMillionths(colors, totalPixels)
        val entries = colors.mapIndexed { index, color ->
            LowDiversityEntry(color.rgb, weights[index])
        }.toMutableList()
        while (entries.size < WeightedPalette.MIN_COLORS) {
            val index = entries.indices.maxBy { entries[it].millionths }
            val entry = entries[index]
            val splitWeight = entry.millionths / 2
            check(splitWeight > 0) { "Low-diversity palette weight cannot be split" }
            entries[index] = entry.copy(millionths = splitWeight)
            entries += entry.copy(millionths = entry.millionths - splitWeight)
        }
        val weighted = entries.map { entry ->
            WeightedPaletteColor(
                hex = WeightedPalette.hex(entry.rgb),
                weight = entry.millionths.toDouble() / WEIGHT_SCALE,
            )
        }.sortedWith(compareByDescending<WeightedPaletteColor> { it.weight }.thenBy { it.hex })
        return WeightedPalette(weighted).requireValid()
    }

    private fun exactHistogram(rgbPixels: IntArray): List<HistogramColor> = rgbPixels
        .asSequence()
        .map { it and 0x00ffffff }
        .groupingBy { it }
        .eachCount()
        .toSortedMap()
        .map { (rgb, count) -> HistogramColor(rgb, count) }

    private fun coarseBins(histogram: List<HistogramColor>): List<BinnedColor> {
        val accumulators = TreeMap<Int, BinAccumulator>()
        histogram.forEach { color ->
            val key = (color.red shr BIN_SHIFT shl 10) or
                (color.green shr BIN_SHIFT shl 5) or
                (color.blue shr BIN_SHIFT)
            accumulators.getOrPut(key, ::BinAccumulator).add(color)
        }
        return accumulators.values.map(BinAccumulator::toColor)
    }

    private fun exactBins(histogram: List<HistogramColor>): List<BinnedColor> = histogram.map { color ->
        BinnedColor(color.rgb, color.count, OklabColor.fromRgb(color.rgb))
    }

    private fun dominantCenters(bins: List<BinnedColor>, requested: Int): List<OklabColor> {
        val centerCount = min(requested, bins.size)
        val totalWeight = bins.sumOf { it.count.toDouble() }
        val mean = OklabColor(
            lightness = bins.sumOf { it.lab.lightness * it.count } / totalWeight,
            a = bins.sumOf { it.lab.a * it.count } / totalWeight,
            b = bins.sumOf { it.lab.b * it.count } / totalWeight,
        )
        val minimumSeedPopulation = max(
            MIN_DOMINANT_SEED_PIXELS,
            ceil(totalWeight * MIN_DOMINANT_SEED_FRACTION).toInt(),
        )
        val seedCandidates = bins.filter { it.count >= minimumSeedPopulation }
            .takeIf { it.size >= centerCount }
            ?: bins
        val centers = mutableListOf(seedCandidates.minBy { it.lab.squaredDistance(mean) }.lab)
        val maximumPopulation = bins.maxOf { it.count }.toDouble()
        while (centers.size < centerCount) {
            val next = seedCandidates.maxBy { bin ->
                bin.lab.minimumSquaredDistance(centers) * (bin.count / maximumPopulation)
            }
            centers += next.lab
        }

        val assignments = IntArray(bins.size)
        repeat(K_MEANS_ITERATIONS) {
            bins.indices.forEach { index ->
                assignments[index] = nearestCenter(bins[index].lab, centers)
            }
            val updated = centers.toMutableList()
            centers.indices.forEach { centerIndex ->
                var total = 0L
                var lightness = 0.0
                var a = 0.0
                var b = 0.0
                bins.indices.forEach { binIndex ->
                    if (assignments[binIndex] == centerIndex) {
                        val population = bins[binIndex].count
                        total += population
                        lightness += bins[binIndex].lab.lightness * population
                        a += bins[binIndex].lab.a * population
                        b += bins[binIndex].lab.b * population
                    }
                }
                if (total > 0) {
                    updated[centerIndex] = OklabColor(
                        lightness / total,
                        a / total,
                        b / total,
                    )
                }
            }
            val movement = centers.indices.maxOf { index ->
                maxOf(
                    kotlin.math.abs(updated[index].lightness - centers[index].lightness),
                    kotlin.math.abs(updated[index].a - centers[index].a),
                    kotlin.math.abs(updated[index].b - centers[index].b),
                )
            }
            centers.indices.forEach { centers[it] = updated[it] }
            if (movement < CONVERGENCE_EPSILON) return centers
        }
        return centers
    }

    private fun selectAccents(
        bins: List<BinnedColor>,
        selected: MutableList<OklabColor>,
        requestedAccents: Int,
        totalPixels: Int,
    ) {
        if (requestedAccents == 0) return
        val minimumPopulation = max(
            MIN_ACCENT_PIXELS,
            ceil(totalPixels * MIN_ACCENT_FRACTION).toInt(),
        )
        val chosenRgb = mutableSetOf<Int>()
        repeat(requestedAccents) {
            val candidate = bins
                .asSequence()
                .filter { it.count >= minimumPopulation && it.rgb !in chosenRgb }
                .filter { it.lab.minimumSquaredDistance(selected) > COLOR_EPSILON }
                .maxByOrNull { bin -> accentScore(bin, selected, totalPixels) }
                ?: return@repeat
            selected += candidate.lab
            chosenRgb += candidate.rgb
        }
    }

    private fun accentScore(
        bin: BinnedColor,
        selected: List<OklabColor>,
        totalPixels: Int,
    ): Double {
        val distance = sqrt(bin.lab.minimumSquaredDistance(selected))
        val frequency = bin.count.toDouble() / totalPixels
        val chroma = sqrt(bin.lab.a * bin.lab.a + bin.lab.b * bin.lab.b)
        val chromaBoost = 0.7 + 0.6 * min(chroma / ACCENT_CHROMA_REFERENCE, 1.0)
        return distance.pow(1.75) * frequency.pow(0.22) * chromaBoost
    }

    private fun backfillDistinctColors(
        paletteRgbs: LinkedHashSet<Int>,
        bins: List<BinnedColor>,
        requestedColors: Int,
    ) {
        val target = min(requestedColors, bins.size)
        while (paletteRgbs.size < target) {
            val selectedLabs = paletteRgbs.map(OklabColor::fromRgb)
            val maximumPopulation = bins.maxOf { it.count }.toDouble()
            val candidate = bins
                .asSequence()
                .filter { it.rgb !in paletteRgbs }
                .maxByOrNull { bin ->
                    bin.lab.minimumSquaredDistance(selectedLabs) * sqrt(bin.count / maximumPopulation)
                }
                ?: break
            paletteRgbs += candidate.rgb
        }
    }

    private fun nearestCenter(color: OklabColor, centers: List<OklabColor>): Int {
        var nearest = 0
        var distance = Double.POSITIVE_INFINITY
        centers.indices.forEach { index ->
            val candidate = color.squaredDistance(centers[index])
            if (candidate < distance) {
                nearest = index
                distance = candidate
            }
        }
        return nearest
    }

    private fun normalize(colors: List<QuantizedColor>, totalPixels: Int): WeightedPalette {
        val millionths = normalizedMillionths(colors, totalPixels)
        require(millionths.all { it > 0 }) { "Quantization produced a zero-weight color" }
        val weighted = colors.mapIndexed { index, color ->
            WeightedPaletteColor(
                hex = WeightedPalette.hex(color.rgb),
                weight = millionths[index].toDouble() / WEIGHT_SCALE,
            )
        }.sortedWith(compareByDescending<WeightedPaletteColor> { it.weight }.thenBy { it.hex })
        return WeightedPalette(weighted).requireValid()
    }

    private fun normalizedMillionths(
        colors: List<QuantizedColor>,
        totalPixels: Int,
    ): MutableList<Int> = colors.map { color ->
        (color.count.toDouble() * WEIGHT_SCALE / totalPixels).roundToInt()
    }.toMutableList()
        .also { weights -> weights[0] += WEIGHT_SCALE - weights.sum() }

    private data class HistogramColor(val rgb: Int, val count: Int) {
        val red: Int get() = rgb shr 16 and 0xff
        val green: Int get() = rgb shr 8 and 0xff
        val blue: Int get() = rgb and 0xff
    }

    private data class BinnedColor(
        val rgb: Int,
        val count: Int,
        val lab: OklabColor,
    )

    private data class LowDiversityEntry(
        val rgb: Int,
        val millionths: Int,
    )

    private class BinAccumulator {
        private var red = 0L
        private var green = 0L
        private var blue = 0L
        private var population = 0

        fun add(color: HistogramColor) {
            red += color.red.toLong() * color.count
            green += color.green.toLong() * color.count
            blue += color.blue.toLong() * color.count
            population += color.count
        }

        fun toColor(): BinnedColor {
            fun average(total: Long): Int = ((total + population / 2) / population).toInt()
            val rgb = average(red) shl 16 or (average(green) shl 8) or average(blue)
            return BinnedColor(rgb, population, OklabColor.fromRgb(rgb))
        }
    }

    private data class OklabColor(
        val lightness: Double,
        val a: Double,
        val b: Double,
    ) {
        fun squaredDistance(other: OklabColor): Double {
            val lightnessDelta = lightness - other.lightness
            val aDelta = a - other.a
            val bDelta = b - other.b
            return lightnessDelta * lightnessDelta + aDelta * aDelta + bDelta * bDelta
        }

        fun minimumSquaredDistance(others: List<OklabColor>): Double =
            others.minOf(::squaredDistance)

        fun toRgb(): Int {
            val lPrime = lightness + 0.3963377774 * a + 0.2158037573 * b
            val mPrime = lightness - 0.1055613458 * a - 0.0638541728 * b
            val sPrime = lightness - 0.0894841775 * a - 1.2914855480 * b
            val l = lPrime * lPrime * lPrime
            val m = mPrime * mPrime * mPrime
            val s = sPrime * sPrime * sPrime
            return channelToSrgb(+4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s) shl 16 or
                (channelToSrgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s) shl 8) or
                channelToSrgb(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
        }

        companion object {
            fun fromRgb(rgb: Int): OklabColor {
                val red = channelToLinear(rgb shr 16 and 0xff)
                val green = channelToLinear(rgb shr 8 and 0xff)
                val blue = channelToLinear(rgb and 0xff)
                val l = 0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue
                val m = 0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue
                val s = 0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue
                val lPrime = cbrt(l)
                val mPrime = cbrt(m)
                val sPrime = cbrt(s)
                return OklabColor(
                    0.2104542553 * lPrime + 0.7936177850 * mPrime - 0.0040720468 * sPrime,
                    1.9779984951 * lPrime - 2.4285922050 * mPrime + 0.4505937099 * sPrime,
                    0.0259040371 * lPrime + 0.7827717662 * mPrime - 0.8086757660 * sPrime,
                )
            }

            private fun channelToLinear(value: Int): Double {
                val srgb = value / 255.0
                return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
            }

            private fun channelToSrgb(linear: Double): Int {
                val srgb = if (linear <= 0.0031308) {
                    12.92 * linear
                } else {
                    1.055 * max(linear, 0.0).pow(1.0 / 2.4) - 0.055
                }
                return (srgb * 255.0).roundToInt().coerceIn(0, 255)
            }
        }
    }

    private const val BIN_SHIFT = 3
    private const val MIN_DOMINANT_COLORS = 3
    private const val MAX_ACCENT_COLORS = 3
    private const val MIN_ACCENT_PIXELS = 4
    private const val MIN_ACCENT_FRACTION = 0.0006
    private const val MIN_DOMINANT_SEED_PIXELS = 4
    private const val MIN_DOMINANT_SEED_FRACTION = 0.0015
    private const val ACCENT_CHROMA_REFERENCE = 0.16
    private const val K_MEANS_ITERATIONS = 32
    private const val CONVERGENCE_EPSILON = 0.0000001
    private const val COLOR_EPSILON = 0.0000000001
    private const val WEIGHT_SCALE = 1_000_000
}
