package com.jaredwinick.colors.camera.mask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SkyMaskTest {
    @Test
    fun `schema version one JSON round trips`() {
        val decoded = SkyMaskJson.decode(DEFAULT_JSON)

        assertEquals(1, decoded.schemaVersion)
        assertEquals("normalized", decoded.coordinateSpace)
        assertEquals(4, decoded.includePolygon.size)
        assertEquals(1, decoded.excludePolygons.size)
        assertEquals(1, decoded.excludeRectangles.size)
        assertEquals(decoded, SkyMaskJson.decode(SkyMaskJson.encode(decoded)))
    }

    @Test
    fun `invalid polygons coordinates rectangles and thresholds are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfig().copy(includePolygon = validConfig().includePolygon.take(2)).requireValid()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfig().copy(
                includePolygon = validConfig().includePolygon.toMutableList().apply {
                    this[2] = NormalizedPoint(1.01, 1.0)
                },
            ).requireValid()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfig().copy(
                excludeRectangles = listOf(NormalizedRectangle(0.5, 0.2, 0.4, 0.8)),
            ).requireValid()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfig().copy(minimumIncludedPixels = 0).requireValid()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfig().copy(schemaVersion = 2).requireValid()
        }
    }

    @Test
    fun `include polygons exclusions rectangles and edge coordinates rasterize`() {
        val mask = SkyMaskRasterizer.rasterize(validConfig(), MaskSize(11, 11))

        assertTrue(mask.includes(0, 0))
        assertTrue(mask.includes(10, 10))
        assertFalse(mask.includes(5, 5))
        assertFalse(mask.includes(8, 8))
        assertTrue(mask.statistics.includedPixels in 90..120)
    }

    @Test
    fun `included pixel and fraction thresholds are enforced at each size`() {
        val pixelThreshold = validConfig().copy(minimumIncludedPixels = 10_000)
        assertThrows(IllegalArgumentException::class.java) {
            SkyMaskRasterizer.rasterize(pixelThreshold, MaskSize(20, 20))
        }
        val fractionThreshold = validConfig().copy(minimumIncludedFraction = 0.99)
        assertThrows(IllegalArgumentException::class.java) {
            SkyMaskRasterizer.rasterize(fractionThreshold, MaskSize(100, 100))
        }
    }

    @Test
    fun `Galaxy fixture closely matches Python at full and analysis sizes`() {
        val full = SkyMaskRasterizer.rasterize(galaxyConfig(), MaskSize(1_440, 1_920))
        val half = SkyMaskRasterizer.rasterize(galaxyConfig(), MaskSize(720, 960))
        val analysis = SkyMaskRasterizer.rasterize(galaxyConfig(), MaskSize(135, 180))

        assertTrue(abs(full.statistics.includedPixels - 2_460_560) <= 4_000)
        assertTrue(abs(half.statistics.includedPixels - 614_966) <= 2_000)
        assertTrue(abs(analysis.statistics.includedPixels - 21_573) <= 300)
        assertEquals(MaskSize(135, 180), SkyMaskRasterizer.analysisSize(MaskSize(1_440, 1_920), 180))
        assertEquals(MaskSize(68, 90), SkyMaskRasterizer.analysisSize(MaskSize(720, 960), 90))
    }

    private fun validConfig() = SkyMaskConfig(
        schemaVersion = 1,
        coordinateSpace = "normalized",
        minimumIncludedFraction = 0.1,
        minimumIncludedPixels = 1,
        includePolygon = listOf(
            NormalizedPoint(0.0, 0.0),
            NormalizedPoint(1.0, 0.0),
            NormalizedPoint(1.0, 1.0),
            NormalizedPoint(0.0, 1.0),
        ),
        excludePolygons = listOf(
            listOf(
                NormalizedPoint(0.4, 0.4),
                NormalizedPoint(0.6, 0.4),
                NormalizedPoint(0.5, 0.6),
            ),
        ),
        excludeRectangles = listOf(NormalizedRectangle(0.75, 0.75, 0.9, 0.9)),
    )

    private fun galaxyConfig() = SkyMaskConfig(
        schemaVersion = 1,
        coordinateSpace = "normalized",
        minimumIncludedFraction = 0.1,
        minimumIncludedPixels = 1_000,
        includePolygon = listOf(
            NormalizedPoint(0.0, 0.0), NormalizedPoint(1.0, 0.0),
            NormalizedPoint(1.0, 0.895), NormalizedPoint(0.95, 0.895),
            NormalizedPoint(0.9, 0.885), NormalizedPoint(0.85, 0.9),
            NormalizedPoint(0.8, 0.895), NormalizedPoint(0.75, 0.885),
            NormalizedPoint(0.7, 0.895), NormalizedPoint(0.65, 0.885),
            NormalizedPoint(0.6, 0.87), NormalizedPoint(0.55, 0.89),
            NormalizedPoint(0.5, 0.895), NormalizedPoint(0.45, 0.895),
            NormalizedPoint(0.4, 0.9), NormalizedPoint(0.35, 0.9),
            NormalizedPoint(0.3, 0.895), NormalizedPoint(0.25, 0.885),
            NormalizedPoint(0.2, 0.895), NormalizedPoint(0.15, 0.9),
            NormalizedPoint(0.1, 0.89), NormalizedPoint(0.05, 0.875),
            NormalizedPoint(0.0, 0.845),
        ),
        excludePolygons = emptyList(),
        excludeRectangles = emptyList(),
    )

    companion object {
        private val DEFAULT_JSON = """
            {
              "schema_version": 1,
              "coordinate_space": "normalized",
              "minimum_included_fraction": 0.1,
              "minimum_included_pixels": 1,
              "include_polygon": [[0,0],[1,0],[1,1],[0,1]],
              "exclude_polygons": [[[0.4,0.4],[0.6,0.4],[0.5,0.6]]],
              "exclude_rectangles": [[0.75,0.75,0.9,0.9]]
            }
        """.trimIndent()
    }
}
