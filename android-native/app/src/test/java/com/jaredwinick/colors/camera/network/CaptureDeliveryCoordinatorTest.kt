package com.jaredwinick.colors.camera.network

import com.jaredwinick.colors.camera.outbox.DurableCapturePolicy
import com.jaredwinick.colors.camera.outbox.DurableCaptureRecord
import com.jaredwinick.colors.camera.outbox.DurableCaptureState
import com.jaredwinick.colors.camera.outbox.OutboxSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CaptureDeliveryCoordinatorTest {
    @Test
    fun `oldest eligible work is delivered within the configured budget`() {
        val queue = FakeQueue(
            mutableListOf(
                record("ffffffff-ffff-4fff-8fff-ffffffffffff", "2026-09-01T12:10:00Z"),
                record("00000000-0000-4000-8000-000000000002", "2026-09-01T12:00:00Z"),
                record("00000000-0000-4000-8000-000000000001", "2026-09-01T12:00:00Z"),
            ),
        )
        val transport = FakeTransport {
            UploadAttemptResult.Delivered(UploadConfirmation(201, false, "/api/images/test.jpg"))
        }
        val summary = coordinator(queue, transport).drain(
            ENDPOINT,
            settings(maxUploads = 2),
            Instant.parse("2026-09-01T12:20:00Z"),
        )

        assertEquals(2, summary.attempted)
        assertEquals(2, summary.delivered)
        assertEquals(1, summary.deferred)
        assertEquals(
            listOf(
                "00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002",
            ),
            transport.captureIds,
        )
        assertEquals(1, summary.pendingAfter)
    }

    @Test
    fun `retry backoff is persisted and notification starts at threshold`() {
        val id = "10000000-0000-4000-8000-000000000001"
        val queue = FakeQueue(mutableListOf(record(id, "2026-09-01T11:00:00Z")))
        val transport = FakeTransport { UploadAttemptResult.Retry("SERVER_RETRYABLE") }
        val coordinator = coordinator(queue, transport)
        val start = Instant.parse("2026-09-01T12:00:00Z")

        val first = coordinator.drain(ENDPOINT, settings(), start)
        val deferred = coordinator.drain(ENDPOINT, settings(), start.plusSeconds(30))
        val second = coordinator.drain(ENDPOINT, settings(), start.plusSeconds(60))
        val third = coordinator.drain(ENDPOINT, settings(), start.plusSeconds(180))

        assertFalse(first.notificationRequired)
        assertEquals("SERVER_RETRYABLE", first.lastAttemptErrorCode)
        assertEquals(1, deferred.deferred)
        assertEquals(0, deferred.attempted)
        assertFalse(second.notificationRequired)
        assertTrue(third.notificationRequired)
        val updated = queue.records.single()
        assertEquals(3, updated.attemptCount)
        assertEquals("2026-09-01T12:07:00Z", updated.nextEligibleRetryAt)
        assertEquals("SERVER_RETRYABLE", updated.lastErrorCode)
    }

    @Test
    fun `conflict moves immutable evidence to attention without retry`() {
        val queue = FakeQueue(mutableListOf(record(CAPTURE_ID, "2026-09-01T11:00:00Z")))
        val transport = FakeTransport { UploadAttemptResult.Attention("IDEMPOTENCY_CONFLICT") }

        val summary = coordinator(queue, transport).drain(
            ENDPOINT,
            settings(),
            Instant.parse("2026-09-01T12:00:00Z"),
        )

        assertEquals(1, summary.attentionRequired)
        assertEquals(0, summary.pendingAfter)
        assertTrue(summary.notificationRequired)
        assertEquals("IDEMPOTENCY_CONFLICT", summary.lastAttemptErrorCode)
        assertEquals(DurableCaptureState.ATTENTION_REQUIRED, queue.records.single().state)
        assertEquals("IDEMPOTENCY_CONFLICT", queue.records.single().lastErrorCode)
    }

    @Test
    fun `missing Keystore credential defers work without recording a network attempt`() {
        val queue = FakeQueue(mutableListOf(record(CAPTURE_ID, "2026-09-01T11:00:00Z")))
        val transport = FakeTransport { error("transport must not be called") }
        val summary = CaptureDeliveryCoordinator(queue, transport) { null }.drain(
            ENDPOINT,
            settings(),
            Instant.parse("2026-09-01T12:00:00Z"),
        )

        assertEquals(0, summary.attempted)
        assertEquals(1, summary.pendingAfter)
        assertEquals("INGEST_TOKEN_UNAVAILABLE", summary.cycleErrorCode)
        assertEquals(0, queue.records.single().attemptCount)
        assertFalse(summary.toString().contains(TOKEN))
    }

    @Test
    fun `exponential backoff is capped`() {
        val settings = settings().copy(initialRetrySeconds = 60, maximumRetrySeconds = 3_600)
        assertEquals(60, CaptureDeliveryCoordinator.backoffSeconds(1, settings))
        assertEquals(120, CaptureDeliveryCoordinator.backoffSeconds(2, settings))
        assertEquals(3_600, CaptureDeliveryCoordinator.backoffSeconds(20, settings))
    }

    private fun coordinator(queue: FakeQueue, transport: FakeTransport) =
        CaptureDeliveryCoordinator(queue, transport) { TOKEN }

    private fun settings(maxUploads: Int = 4) = DeliverySettings(
        deviceId = "galaxy-s9-window",
        maxPendingCaptures = 192,
        maxUploadsPerCycle = maxUploads,
        requestTimeoutSeconds = 120,
        initialRetrySeconds = 60,
        maximumRetrySeconds = 3_600,
        notifyAfterAttempts = 3,
        retentionDays = 7,
        retentionCount = 672,
    )

    private class FakeTransport(
        private val result: (DurableCaptureRecord) -> UploadAttemptResult,
    ) : CaptureUploadTransport {
        val captureIds = mutableListOf<String>()

        override fun upload(
            endpoint: String,
            bearerToken: String,
            record: DurableCaptureRecord,
            timeoutSeconds: Int,
        ): UploadAttemptResult {
            assertEquals(ENDPOINT, endpoint)
            assertEquals(TOKEN, bearerToken)
            captureIds += record.captureId
            return result(record)
        }
    }

    private class FakeQueue(val records: MutableList<DurableCaptureRecord>) : DeliveryQueue {
        override fun summary(): OutboxSummary = OutboxSummary(
            staged = records.count { it.state == DurableCaptureState.STAGED },
            processing = records.count { it.state == DurableCaptureState.PROCESSING },
            pending = records.count { it.state == DurableCaptureState.PENDING_UPLOAD },
            delivered = records.count { it.state == DurableCaptureState.DELIVERED },
            attentionRequired = records.count { it.state == DurableCaptureState.ATTENTION_REQUIRED },
            conflicts = 0,
            oldestPendingAt = DurableCapturePolicy.pendingOldestFirst(records).firstOrNull()?.capturedAt,
            storageBytes = 0,
        )

        override fun pendingEligible(now: Instant, limit: Int): List<DurableCaptureRecord> =
            DurableCapturePolicy.pendingOldestFirst(records).filter { record ->
                record.nextEligibleRetryAt?.let { !Instant.parse(it).isAfter(now) } ?: true
            }.take(limit)

        override fun recordRetry(
            captureId: String,
            errorCode: String,
            nextEligibleRetryAt: Instant,
            attemptedAt: Instant,
        ): DurableCaptureRecord = replace(captureId) { record ->
            record.copy(
                attemptCount = record.attemptCount + 1,
                lastAttemptAt = attemptedAt.toString(),
                lastErrorCode = errorCode,
                nextEligibleRetryAt = nextEligibleRetryAt.toString(),
            )
        }

        override fun markDelivered(
            captureId: String,
            confirmationJson: String,
            deliveredAt: Instant,
        ): DurableCaptureRecord = replace(captureId) { record ->
            record.copy(
                state = DurableCaptureState.DELIVERED,
                attemptCount = record.attemptCount + 1,
                deliveredAt = deliveredAt.toString(),
                deliveryConfirmationJson = confirmationJson,
            )
        }

        override fun markDeliveryAttention(
            captureId: String,
            errorCode: String,
            detectedAt: Instant,
        ): DurableCaptureRecord = replace(captureId) { record ->
            record.copy(
                state = DurableCaptureState.ATTENTION_REQUIRED,
                attemptCount = record.attemptCount + 1,
                lastAttemptAt = detectedAt.toString(),
                lastErrorCode = errorCode,
            )
        }

        private fun replace(
            captureId: String,
            transform: (DurableCaptureRecord) -> DurableCaptureRecord,
        ): DurableCaptureRecord {
            val index = records.indexOfFirst { it.captureId == captureId }
            return transform(records[index]).also { records[index] = it }
        }
    }

    companion object {
        private const val ENDPOINT = "https://example.workers.dev/api/ingest"
        private const val TOKEN = "secret-token-material-never-logged"
        private const val CAPTURE_ID = "71fc4bc3-9ec7-4389-bf2c-ba09813844c0"

        private fun record(captureId: String, capturedAt: String) = DurableCaptureRecord(
            captureId = captureId,
            state = DurableCaptureState.PENDING_UPLOAD,
            capturedAt = capturedAt,
            deviceId = "galaxy-s9-window",
            mimeType = "image/jpeg",
            byteCount = 7,
            imageSha256 = "hash",
            paletteJson = "[]",
            processingMetadataJson = "{}",
            immutableFingerprint = "fingerprint",
            imagePath = "/pending/$captureId/capture.jpg",
            metadataPath = "/pending/$captureId/capture.json",
            attemptCount = 0,
            lastAttemptAt = null,
            lastErrorCode = null,
            nextEligibleRetryAt = null,
            deliveredAt = null,
            deliveryConfirmationJson = null,
            createdAt = capturedAt,
            updatedAt = capturedAt,
        )
    }
}
