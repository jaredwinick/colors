package com.jaredwinick.colors.camera.outbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableCapturePolicyTest {
    @Test
    fun `safe operator inspection prioritizes actionable records and excludes private fields`() {
        val records = listOf(
            record("delivered", DurableCaptureState.DELIVERED, "2026-09-05T12:03:00Z"),
            record("staged", DurableCaptureState.STAGED, "2026-09-05T12:02:00Z"),
            record("pending", DurableCaptureState.PENDING_UPLOAD, "2026-09-05T12:01:00Z"),
            record("attention", DurableCaptureState.ATTENTION_REQUIRED, "2026-09-05T12:00:00Z"),
        )

        val safe = DurableCapturePolicy.safeOperatorRecords(records, 4)

        assertEquals(listOf("attention", "pending", "staged", "delivered"), safe.map { it.captureId })
        assertFalse(safe.toString().contains("/private/"))
        assertFalse(safe.toString().contains("server-response"))
    }

    @Test
    fun `failure notification persists at threshold and clears after delivery`() {
        val retrying = record(
            "retrying",
            DurableCaptureState.PENDING_UPLOAD,
            "2026-09-05T12:00:00Z",
        ).copy(attemptCount = 3, lastErrorCode = "NETWORK_REQUEST_FAILED")

        val active = DurableCapturePolicy.failureNotificationState(listOf(retrying), 0, 3)
        val recovered = DurableCapturePolicy.failureNotificationState(
            listOf(retrying.copy(state = DurableCaptureState.DELIVERED)),
            0,
            3,
        )

        assertTrue(active.required)
        assertEquals(1, active.pendingCount)
        assertEquals("NETWORK_REQUEST_FAILED", active.lastErrorCode)
        assertFalse(recovered.required)
        assertEquals(null, recovered.lastErrorCode)
    }

    @Test
    fun `attention evidence requests notification immediately`() {
        val state = DurableCapturePolicy.failureNotificationState(emptyList(), 1, 99)

        assertTrue(state.required)
        assertEquals(1, state.attentionCount)
    }

    private fun record(
        id: String,
        state: DurableCaptureState,
        capturedAt: String,
    ) = DurableCaptureRecord(
        captureId = id,
        state = state,
        capturedAt = capturedAt,
        deviceId = "private-device",
        mimeType = "image/jpeg",
        byteCount = 123,
        imageSha256 = "private-hash",
        paletteJson = "private-palette",
        processingMetadataJson = "private-processing",
        immutableFingerprint = "private-fingerprint",
        imagePath = "/private/path.jpg",
        metadataPath = "/private/server-response.json",
        attemptCount = 0,
        lastAttemptAt = null,
        lastErrorCode = null,
        nextEligibleRetryAt = null,
        deliveredAt = if (state == DurableCaptureState.DELIVERED) capturedAt else null,
        deliveryConfirmationJson = "server-response",
        createdAt = capturedAt,
        updatedAt = capturedAt,
    )
}
