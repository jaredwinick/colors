package com.jaredwinick.colors.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlarmTimingTest {
    @Test
    fun `precision fallback waits behind the in-process timer`() {
        val scheduledFor = 1_800_000L

        assertEquals(scheduledFor, AlarmTiming.deliveryAt(scheduledFor, precisionMode = false))
        assertEquals(
            scheduledFor + AlarmTiming.PRECISION_FALLBACK_GRACE_MS,
            AlarmTiming.deliveryAt(scheduledFor, precisionMode = true),
        )
    }

    @Test
    fun `every scheduled slot has a distinct alarm identity`() {
        val first = AlarmTiming.identityUri(1_800_000L)
        val second = AlarmTiming.identityUri(2_700_000L)

        assertNotEquals(first, second)
        assertEquals("colors-camera://capture/1800000", first)
        assertEquals("colors-camera://capture/2700000", second)
    }
}
