package com.jaredwinick.colors.camera.diagnostics

import com.jaredwinick.colors.camera.config.AppConfiguration
import com.jaredwinick.colors.camera.outbox.DurableCaptureState
import com.jaredwinick.colors.camera.outbox.OutboxSummary
import com.jaredwinick.colors.camera.outbox.SafeCaptureRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorExportsTest {
    @Test
    fun `configuration export reports credential presence without receiving its value`() {
        val export = OperatorExports.configuration(
            AppConfiguration.defaults(),
            "https://example.workers.dev/api/ingest?secret=query-token",
            tokenConfigured = true,
        )

        assertTrue(export.contains("\"credential_configured\": true"))
        assertTrue(export.contains("android-sky-camera"))
        assertFalse(export.contains("bearer"))
        assertFalse(export.contains("query-token"))
    }

    @Test
    fun `queue export contains safe retry metadata only`() {
        val export = OperatorExports.queue(
            OutboxSummary(0, 0, 1, 0, 0, 0, "2026-09-05T12:00:00Z", 123),
            listOf(
                SafeCaptureRecord(
                    captureId = "capture-id",
                    state = DurableCaptureState.PENDING_UPLOAD,
                    capturedAt = "2026-09-05T12:00:00Z",
                    byteCount = 123,
                    attemptCount = 2,
                    lastAttemptAt = "2026-09-05T12:01:00Z",
                    lastErrorCode = "NETWORK_REQUEST_FAILED",
                    nextEligibleRetryAt = "2026-09-05T12:03:00Z",
                    deliveredAt = null,
                ),
            ),
        )

        assertTrue(export.contains("NETWORK_REQUEST_FAILED"))
        assertFalse(export.contains("image_path"))
        assertFalse(export.contains("delivery_confirmation"))
    }

    @Test
    fun `redacted diagnostics omit local paths and respect byte limit`() {
        val diagnostic = CaptureDiagnostic(
            recordId = "record",
            sessionId = "session",
            slotId = "slot",
            scheduledFor = 1,
            alarmReceivedAt = 2,
            screenInteractive = false,
            charging = true,
            imagePath = "/data/data/private/capture.jpg",
            result = CaptureDiagnostic.RESULT_SUCCESS,
        )

        val export = OperatorExports.redactedDiagnostics(listOf(diagnostic), 10_000)

        assertTrue(export.toByteArray().size <= 10_000)
        assertTrue(export.contains("\"record_id\":\"record\""))
        assertFalse(export.contains("image_path"))
        assertFalse(export.contains("/data/data"))
    }
}
