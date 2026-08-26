package com.jaredwinick.colors.camera.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigurationTest {
    @Test
    fun `production defaults are valid and match the Termux pipeline`() {
        val defaults = AppConfiguration.defaults().requireValid()

        assertEquals(15, defaults.intervalMinutes)
        assertTrue(defaults.precisionMode)
        assertEquals("android-sky-camera", defaults.deviceId)
        assertEquals(1_920, defaults.maxImageDimension)
        assertEquals(85, defaults.jpegQuality)
        assertEquals(6, defaults.paletteColors)
        assertEquals(180, defaults.paletteAnalysisDimension)
        assertEquals(192, defaults.maxPendingCaptures)
        assertEquals(4, defaults.maxUploadsPerCycle)
    }

    @Test
    fun `invalid values are rejected before persistence`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppConfiguration.defaults().copy(paletteColors = 2).requireValid()
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppConfiguration.defaults().copy(initialRetrySeconds = 90, maximumRetrySeconds = 60)
                .requireValid()
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppConfiguration.defaults().copy(deviceId = "contains spaces").requireValid()
        }
    }

    @Test
    fun `schema one configuration migrates without losing existing settings`() {
        val migrated = ConfigurationCodec.decode(
            mapOf(
                ConfigurationCodec.Keys.SCHEMA_VERSION to "1",
                ConfigurationCodec.Keys.INTERVAL_MINUTES to "30",
                ConfigurationCodec.Keys.PRECISION_MODE to "false",
                ConfigurationCodec.Keys.DEVICE_ID to "window-s9",
            ),
        )

        assertEquals(AppConfiguration.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(30, migrated.intervalMinutes)
        assertFalse(migrated.precisionMode)
        assertEquals("window-s9", migrated.deviceId)
        assertEquals(AppConfiguration.defaults().retentionCount, migrated.retentionCount)
    }

    @Test
    fun `future configuration schema is not interpreted`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationCodec.decode(
                mapOf(ConfigurationCodec.Keys.SCHEMA_VERSION to "999"),
            )
        }
    }
}
