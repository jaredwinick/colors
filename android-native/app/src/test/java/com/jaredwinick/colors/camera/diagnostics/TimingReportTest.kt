package com.jaredwinick.colors.camera.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(0, summary.earlyCaptures)
        assertEquals(1, summary.missingSlots)
        assertEquals(1, summary.duplicateCaptures)
        assertEquals(0, summary.duplicateAlarms)
        assertEquals(0, summary.timerCaptures)
        assertEquals(0, summary.alarmCaptures)
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

    @Test
    fun `summary distinguishes precision timer from fallback alarm captures`() {
        val first = 1_800_000L
        val records = listOf(
            success("timer", first, 1_000L).copy(triggerSource = CaptureDiagnostic.TRIGGER_TIMER),
            success("alarm", first + 900_000L, 2_000L)
                .copy(triggerSource = CaptureDiagnostic.TRIGGER_ALARM),
        )

        val summary = TimingReport.summarize(
            records = records,
            sessionId = "trial",
            firstScheduledAt = first,
            throughMillis = first + 900_000L,
            intervalMinutes = 15,
        )

        assertEquals(1, summary.timerCaptures)
        assertEquals(1, summary.alarmCaptures)
    }

    @Test
    fun `csv includes trigger power and upload diagnostics`() {
        val record = success("timer", 1_800_000L, 1_000L).copy(
            triggerSource = CaptureDiagnostic.TRIGGER_TIMER,
            serviceReceivedAt = 1_800_250L,
            plugged = true,
            batteryPercent = 91,
            batteryOptimizationExempt = true,
            stationWakeLockHeld = true,
            uploadAttempted = 2,
            uploadDelivered = 1,
            uploadRetried = 1,
            uploadAttentionRequired = 0,
            uploadDeferred = 0,
            outboxPendingAfterUpload = 1,
            uploadErrorCode = "UPLOAD_RETRY_THRESHOLD",
            cycleAction = "CAPTURE_NEW",
            recoveredStagedCaptureId = "old-capture-id",
            preUploadAttempted = 1,
            postUploadAttempted = 1,
            retentionRemoved = 2,
            outboxStagedAfterCycle = 0,
            outboxPendingAfterCycle = 1,
        )

        val csv = TimingReport.csv(listOf(record))

        assertTrue(csv.contains("trigger_source"))
        assertTrue(csv.contains("service_dispatch_ms"))
        assertTrue(csv.contains("station_wake_lock_held"))
        assertTrue(csv.contains("upload_delivered"))
        assertTrue(csv.contains("outbox_pending_after_upload"))
        assertTrue(csv.contains("recovered_staged_capture_id"))
        assertTrue(csv.contains("outbox_pending_after_cycle"))
        assertTrue(csv.contains("\"TIMER\""))
        assertTrue(csv.contains("\"91\""))
        assertTrue(csv.contains("\"UPLOAD_RETRY_THRESHOLD\""))
        assertTrue(csv.contains("\"CAPTURE_NEW\""))
        assertTrue(csv.contains("\"old-capture-id\""))
    }

    @Test
    fun `skipped and early records do not hide missing scheduled captures`() {
        val first = 1_800_000L
        val second = first + 900_000L
        val records = listOf(
            success("first", first, 1_500L)
                .copy(triggerSource = CaptureDiagnostic.TRIGGER_TIMER),
            CaptureDiagnostic(
                recordId = "overlap",
                sessionId = "trial",
                slotId = "utc-$second",
                scheduledFor = second,
                alarmReceivedAt = first + 100L,
                completedAt = first + 100L,
                result = "SKIPPED",
                errorCode = "OVERLAP_PREVENTED",
                screenInteractive = false,
                charging = true,
                triggerSource = CaptureDiagnostic.TRIGGER_ALARM,
            ),
            CaptureDiagnostic(
                recordId = "duplicate",
                sessionId = "trial",
                slotId = "utc-$second",
                scheduledFor = second,
                alarmReceivedAt = second + 50L,
                completedAt = second + 50L,
                result = "SKIPPED",
                errorCode = "DUPLICATE_SLOT",
                screenInteractive = false,
                charging = true,
                triggerSource = CaptureDiagnostic.TRIGGER_TIMER,
            ),
            success("early", second, -898_000L)
                .copy(triggerSource = CaptureDiagnostic.TRIGGER_ALARM),
        )

        val summary = TimingReport.summarize(records, "trial", first, second, 15)

        assertEquals(2, summary.recordedSlots)
        assertEquals(1, summary.successfulCaptures)
        assertEquals(1, summary.earlyCaptures)
        assertEquals(1, summary.missingSlots)
        assertEquals(1_500L, summary.worstLatenessMs)
        assertEquals(1, summary.timerCaptures)
        assertEquals(0, summary.alarmCaptures)
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
