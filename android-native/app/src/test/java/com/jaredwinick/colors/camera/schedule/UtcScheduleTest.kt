package com.jaredwinick.colors.camera.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class UtcScheduleTest {
    @Test
    fun `next boundary aligns to a UTC quarter hour`() {
        val now = Instant.parse("2026-08-22T12:07:31Z").toEpochMilli()
        val expected = Instant.parse("2026-08-22T12:15:00Z").toEpochMilli()

        assertEquals(expected, UtcSchedule.nextBoundaryMillis(now, 15))
    }

    @Test
    fun `a time exactly on a boundary advances to the next slot`() {
        val now = Instant.parse("2026-08-22T12:15:00Z").toEpochMilli()
        val expected = Instant.parse("2026-08-22T12:30:00Z").toEpochMilli()

        assertEquals(expected, UtcSchedule.nextBoundaryMillis(now, 15))
    }

    @Test
    fun `interval must divide evenly into a UTC day`() {
        assertThrows(IllegalArgumentException::class.java) {
            UtcSchedule.validateIntervalMinutes(17)
        }
    }

    @Test
    fun `expected boundaries include every elapsed slot`() {
        val first = Instant.parse("2026-08-22T12:15:00Z").toEpochMilli()
        val through = Instant.parse("2026-08-22T13:00:01Z").toEpochMilli()

        assertEquals(
            listOf(first, first + 900_000L, first + 1_800_000L, first + 2_700_000L),
            UtcSchedule.expectedBoundaries(first, through, 15),
        )
    }
}
