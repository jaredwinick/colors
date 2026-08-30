package com.jaredwinick.colors.camera.schedule

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object UtcSchedule {
    const val MINUTES_PER_DAY = 1_440
    private const val MILLIS_PER_MINUTE = 60_000L

    fun validateIntervalMinutes(intervalMinutes: Int) {
        require(intervalMinutes in 1..MINUTES_PER_DAY) {
            "Interval must be between 1 and 1440 minutes"
        }
        require(MINUTES_PER_DAY % intervalMinutes == 0) {
            "Interval must divide evenly into a UTC day"
        }
    }

    fun nextBoundaryMillis(nowMillis: Long, intervalMinutes: Int): Long {
        validateIntervalMinutes(intervalMinutes)
        val intervalMillis = intervalMinutes * MILLIS_PER_MINUTE
        return Math.floorDiv(nowMillis, intervalMillis) * intervalMillis + intervalMillis
    }

    fun expectedBoundaries(
        firstBoundaryMillis: Long,
        throughMillis: Long,
        intervalMinutes: Int,
    ): List<Long> {
        validateIntervalMinutes(intervalMinutes)
        if (firstBoundaryMillis <= 0 || throughMillis < firstBoundaryMillis) return emptyList()
        val intervalMillis = intervalMinutes * MILLIS_PER_MINUTE
        val count = ((throughMillis - firstBoundaryMillis) / intervalMillis + 1)
            .coerceAtMost(10_000)
            .toInt()
        return List(count) { firstBoundaryMillis + it * intervalMillis }
    }

    fun format(millis: Long?): String = if (millis == null || millis <= 0) {
        "—"
    } else {
        DISPLAY_FORMAT.format(Instant.ofEpochMilli(millis))
    }

    private val DISPLAY_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)
}
