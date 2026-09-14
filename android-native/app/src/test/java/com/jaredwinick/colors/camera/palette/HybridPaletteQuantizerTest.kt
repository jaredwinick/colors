package com.jaredwinick.colors.camera.palette

import com.jaredwinick.colors.camera.mask.MaskSize
import com.jaredwinick.colors.camera.mask.NormalizedPoint
import com.jaredwinick.colors.camera.mask.SkyMaskConfig
import com.jaredwinick.colors.camera.mask.SkyMaskRasterizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class HybridPaletteQuantizerTest {
    @Test
    fun `palette limits formatting normalization sorting and determinism are enforced`() {
        val pixels = IntArray(2_400) { index ->
            val value = index % 240
            (value shl 16) or ((value * 3 % 256) shl 8) or (255 - value)
        }

        val first = HybridPaletteQuantizer.quantize(pixels, 8)
        val second = HybridPaletteQuantizer.quantize(pixels, 8)

        assertEquals(first, second)
        assertEquals(8, first.colors.size)
        assertEquals(1.0, first.colors.sumOf { it.weight }, 0.0000001)
        assertTrue(first.colors.all { Regex("#[0-9A-F]{6}").matches(it.hex) })
        assertTrue(first.colors.all { it.weight > 0.0 && it.weight.isFinite() })
        assertEquals(
            first.colors.sortedWith(
                compareByDescending<WeightedPaletteColor> { it.weight }.thenBy { it.hex },
            ),
            first.colors,
        )
        assertThrows(IllegalArgumentException::class.java) {
            HybridPaletteQuantizer.quantize(pixels, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HybridPaletteQuantizer.quantize(pixels, 11)
        }
    }

    @Test
    fun `duplicate quantized colors merge before weights are produced`() {
        val palette = HybridPaletteQuantizer.paletteFromQuantized(
            listOf(
                QuantizedColor(0x112233, 2),
                QuantizedColor(0x112233, 3),
                QuantizedColor(0x445566, 3),
                QuantizedColor(0x778899, 2),
            ),
            totalPixels = 10,
        )

        assertEquals(3, palette.colors.size)
        assertEquals(WeightedPaletteColor("#112233", 0.5), palette.colors[0])
        assertEquals(1.0, palette.colors.sumOf { it.weight }, 0.0000001)
    }

    @Test
    fun `one sampled color is repeated without inventing colors`() {
        val palette = HybridPaletteQuantizer.quantize(IntArray(1_000) { 0x010203 }, 8)

        assertEquals(3, palette.colors.size)
        assertEquals(setOf("#010203"), palette.colors.map { it.hex }.toSet())
        assertEquals(listOf(0.5, 0.25, 0.25), palette.colors.map { it.weight })
        assertEquals(1.0, palette.colors.sumOf { it.weight }, 0.0000001)
    }

    @Test
    fun `two sampled colors retain their aggregate distribution`() {
        val pixels = IntArray(1_000) { index -> if (index < 800) 0x102030 else 0x405060 }

        val first = HybridPaletteQuantizer.quantize(pixels, 8)
        val second = HybridPaletteQuantizer.quantize(pixels, 8)

        assertEquals(first, second)
        assertEquals(3, first.colors.size)
        assertEquals(0.8, first.colors.filter { it.hex == "#102030" }.sumOf { it.weight }, 0.0000001)
        assertEquals(0.2, first.colors.filter { it.hex == "#405060" }.sumOf { it.weight }, 0.0000001)
        assertEquals(1.0, first.colors.sumOf { it.weight }, 0.0000001)
    }

    @Test
    fun `five dominant regions retain three small distinct sunrise accents`() {
        val dominant = listOf(0x29384F, 0x354B69, 0x466082, 0x5B718F, 0x70839B)
        val pixels = buildList {
            dominant.forEachIndexed { group, base ->
                repeat(4_000) { index ->
                    val adjustment = (index % 7) - 3
                    add(adjust(base, adjustment, (group + index) % 5 - 2))
                }
            }
            repeat(30) { add(adjust(0xB43E4C, it % 3 - 1, 0)) }
            repeat(30) { add(adjust(0xD56B35, 0, it % 3 - 1)) }
            repeat(30) { add(adjust(0xC75D91, it % 3 - 1, 1)) }
            add(0x00FF00) // A single sensor-noise outlier must not consume an accent slot.
        }.toIntArray()

        val palette = HybridPaletteQuantizer.quantize(pixels, 8)

        assertEquals(8, palette.colors.size)
        assertTrue(palette.containsNear(0xB43E4C, 35.0))
        assertTrue(palette.containsNear(0xD56B35, 35.0))
        assertTrue(palette.containsNear(0xC75D91, 35.0))
        assertFalse(palette.containsNear(0x00FF00, 50.0))
        assertEquals(1.0, palette.colors.sumOf { it.weight }, 0.0000001)
    }

    @Test
    fun `configured palette sizes remain deterministic from three through ten colors`() {
        val pixels = IntArray(12_000) { index ->
            val red = (index * 17) % 256
            val green = (index * 31 + 47) % 256
            val blue = (index * 13 + 91) % 256
            red shl 16 or (green shl 8) or blue
        }

        (WeightedPalette.MIN_COLORS..WeightedPalette.MAX_COLORS).forEach { requested ->
            val first = HybridPaletteQuantizer.quantize(pixels, requested)
            val second = HybridPaletteQuantizer.quantize(pixels, requested)
            assertEquals(requested, first.colors.size)
            assertEquals(first, second)
            assertEquals(1.0, first.colors.sumOf { it.weight }, 0.0000001)
        }
    }

    @Test
    fun `daylight sunset overcast and night fixtures exclude masked pixels and stay stable`() {
        fixtures().forEach { (name, row) ->
            val source = IntArray(24) { index ->
                if (index / 6 < 3) row[index % 6] else 0xFF00FF
            }
            val mask = SkyMaskRasterizer.rasterize(fixtureMask(), MaskSize(6, 4))
            val included = source.filterIndexed { index, _ -> mask.included[index] }.toIntArray()

            val first = HybridPaletteQuantizer.quantize(included, 8)
            val second = HybridPaletteQuantizer.quantize(included, 8)

            assertEquals(name, first, second)
            assertTrue(name, first.colors.size in WeightedPalette.MIN_COLORS..row.size)
            assertFalse(name, first.colors.any { it.hex == "#FF00FF" })
            assertEquals(name, 1.0, first.colors.sumOf { it.weight }, 0.0000001)
        }
    }

    private fun adjust(rgb: Int, redDelta: Int, blueDelta: Int): Int {
        val red = ((rgb shr 16 and 0xff) + redDelta).coerceIn(0, 255)
        val green = rgb shr 8 and 0xff
        val blue = ((rgb and 0xff) + blueDelta).coerceIn(0, 255)
        return red shl 16 or (green shl 8) or blue
    }

    private fun WeightedPalette.containsNear(expected: Int, maximumDistance: Double): Boolean =
        colors.any { color ->
            val actual = color.hex.removePrefix("#").toInt(16)
            val red = (actual shr 16 and 0xff) - (expected shr 16 and 0xff)
            val green = (actual shr 8 and 0xff) - (expected shr 8 and 0xff)
            val blue = (actual and 0xff) - (expected and 0xff)
            sqrt((red * red + green * green + blue * blue).toDouble()) <= maximumDistance
        }

    private fun fixtureMask() = SkyMaskConfig(
        schemaVersion = 1,
        coordinateSpace = "normalized",
        minimumIncludedFraction = 0.1,
        minimumIncludedPixels = 1,
        includePolygon = listOf(
            NormalizedPoint(0.0, 0.0),
            NormalizedPoint(1.0, 0.0),
            NormalizedPoint(1.0, 0.66),
            NormalizedPoint(0.0, 0.66),
        ),
        excludePolygons = emptyList(),
        excludeRectangles = emptyList(),
    )

    private fun fixtures() = mapOf(
        "daylight" to intArrayOf(0x87BEEB, 0x509BDC, 0xB9D7F0, 0xF5F5EB, 0x69AAE1, 0xD2E6F5),
        "sunset" to intArrayOf(0xFFB45F, 0xF57855, 0xDC5069, 0x78468C, 0xFFD796, 0x4B4178),
        "overcast" to intArrayOf(0x5A6973, 0x788287, 0x969B9B, 0xB4B4AF, 0x69737D, 0xC8C8C3),
        "night" to intArrayOf(0x050A19, 0x0A142D, 0x141E41, 0x1E2850, 0x0F1937, 0x28325A),
    )
}
