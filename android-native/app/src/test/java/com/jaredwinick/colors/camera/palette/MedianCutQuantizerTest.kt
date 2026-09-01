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

class MedianCutQuantizerTest {
    @Test
    fun `palette limits formatting normalization sorting and determinism are enforced`() {
        val pixels = IntArray(2_400) { index ->
            val value = index % 240
            (value shl 16) or ((value * 3 % 256) shl 8) or (255 - value)
        }

        val first = MedianCutQuantizer.quantize(pixels, 8)
        val second = MedianCutQuantizer.quantize(pixels, 8)

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
            MedianCutQuantizer.quantize(pixels, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MedianCutQuantizer.quantize(pixels, 11)
        }
    }

    @Test
    fun `duplicate quantized colors merge before weights are produced`() {
        val palette = MedianCutQuantizer.paletteFromQuantized(
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
    fun `fewer than three distinct sampled colors fails safely`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MedianCutQuantizer.quantize(
                intArrayOf(0x102030, 0x102030, 0x405060, 0x405060),
                8,
            )
        }
        assertTrue(error.message.orEmpty().contains("requires 3-10"))
    }

    @Test
    fun `daylight sunset overcast and night fixtures exclude magenta and stay stable`() {
        fixtures().forEach { (name, row) ->
            val source = IntArray(24) { index ->
                if (index / 6 < 3) row[index % 6] else 0xFF00FF
            }
            val mask = SkyMaskRasterizer.rasterize(fixtureMask(), MaskSize(6, 4))
            val included = source.filterIndexed { index, _ -> mask.included[index] }.toIntArray()

            val palette = MedianCutQuantizer.quantize(included, 8)

            assertEquals(name, 6, palette.colors.size)
            assertFalse(name, palette.colors.any { it.hex == "#FF00FF" })
            assertEquals(
                name,
                row.map { WeightedPalette.hex(it) }.sorted(),
                palette.colors.map { it.hex }.sorted(),
            )
            assertEquals(
                name,
                listOf(0.166667, 0.166667, 0.166667, 0.166667, 0.166667, 0.166665),
                palette.colors.map { it.weight },
            )
            assertEquals(name, 1.0, palette.colors.sumOf { it.weight }, 0.0000001)
        }
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
