package com.jaredwinick.colors.poc

import org.junit.Assert.assertEquals
import org.junit.Test

class TimingReportTest {
    @Test
    fun `summary identifies missing duplicate and late slots`() {
        val first = 1_800_000L
        val records = listOf(
            success("a", first, 20_000L),
            success("b", first + 900_000L, 70_000L),
            success("c", first + 900_000L, 75_000L),
        )

        val summary = TimingReport.summarize(
            records = records,
            sessionId = "trial",
            firstScheduledAt = first,
            throughMillis = first + 1_800_000L,
            intervalMinutes = 15,
        )

        assertEquals(3, summary.expectedSlots)
        assertEquals(2, summary.recordedSlots)
        assertEquals(3, summary.successfulCaptures)
        assertEquals(1, summary.missingSlots)
        assertEquals(1, summary.duplicateCaptures)
        assertEquals(0, summary.duplicateAlarms)
        assertEquals(75_000L, summary.p95LatenessMs)
        assertEquals(75_000L, summary.worstLatenessMs)
    }

    @Test
    fun `manual records do not affect scheduled summary`() {
        val manual = success("manual", 123L, 1L).copy(manual = true)

        val summary = TimingReport.summarize(listOf(manual), "trial", 900_000L, 900_000L, 15)

        assertEquals(1, summary.expectedSlots)
        assertEquals(0, summary.recordedSlots)
        assertEquals(0, summary.successfulCaptures)
    }

    private fun success(id: String, scheduledFor: Long, lateness: Long) = CaptureDiagnostic(
        recordId = id,
        sessionId = "trial",
        slotId = id,
        scheduledFor = scheduledFor,
        alarmReceivedAt = scheduledFor + 5_000L,
        captureStartedAt = scheduledFor + 10_000L,
        capturedAt = scheduledFor + lateness,
        completedAt = scheduledFor + lateness,
        result = CaptureDiagnostic.RESULT_SUCCESS,
        screenInteractive = false,
        charging = true,
    )
}
