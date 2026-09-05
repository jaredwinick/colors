package com.jaredwinick.colors.camera.network

import com.jaredwinick.colors.camera.outbox.DurableCaptureRecord
import com.jaredwinick.colors.camera.outbox.OutboxSummary
import java.time.Instant
import kotlin.math.min

interface DeliveryQueue {
    fun summary(): OutboxSummary
    fun pendingEligible(now: Instant, limit: Int): List<DurableCaptureRecord>
    fun recordRetry(
        captureId: String,
        errorCode: String,
        nextEligibleRetryAt: Instant,
        attemptedAt: Instant,
    ): DurableCaptureRecord
    fun markDelivered(
        captureId: String,
        confirmationJson: String,
        deliveredAt: Instant,
    ): DurableCaptureRecord
    fun markDeliveryAttention(
        captureId: String,
        errorCode: String,
        detectedAt: Instant,
    ): DurableCaptureRecord
}

data class DeliveryCycleSummary(
    val attempted: Int,
    val delivered: Int,
    val retried: Int,
    val attentionRequired: Int,
    val deferred: Int,
    val pendingAfter: Int,
    val notificationRequired: Boolean,
    val cycleErrorCode: String? = null,
    val lastAttemptErrorCode: String? = null,
)

class CaptureDeliveryCoordinator(
    private val captures: DeliveryQueue,
    private val transport: CaptureUploadTransport,
    private val tokenReader: () -> String?,
) {
    fun drain(
        endpoint: String,
        settings: DeliverySettings,
        now: Instant = Instant.now(),
    ): DeliveryCycleSummary {
        val allPending = captures.summary().pending
        val eligible = captures.pendingEligible(now, settings.maxUploadsPerCycle)
        if (eligible.isEmpty()) {
            return DeliveryCycleSummary(0, 0, 0, 0, allPending, allPending, false)
        }
        val token = runCatching { tokenReader() }.getOrNull()
        if (token == null) {
            return DeliveryCycleSummary(
                attempted = 0,
                delivered = 0,
                retried = 0,
                attentionRequired = 0,
                deferred = allPending,
                pendingAfter = allPending,
                notificationRequired = true,
                cycleErrorCode = "INGEST_TOKEN_UNAVAILABLE",
            )
        }

        var delivered = 0
        var retried = 0
        var attention = 0
        var notify = false
        var lastAttemptErrorCode: String? = null
        eligible.forEach { record ->
            when (
                val result = transport.upload(
                    endpoint,
                    token,
                    record,
                    settings.requestTimeoutSeconds,
                )
            ) {
                is UploadAttemptResult.Delivered -> {
                    captures.markDelivered(record.captureId, result.confirmation.toJson(), now)
                    delivered += 1
                }
                is UploadAttemptResult.Retry -> {
                    val attempt = record.attemptCount + 1
                    val updated = captures.recordRetry(
                        record.captureId,
                        result.errorCode,
                        now.plusSeconds(backoffSeconds(attempt, settings)),
                        now,
                    )
                    retried += 1
                    lastAttemptErrorCode = result.errorCode
                    notify = notify || updated.attemptCount >= settings.notifyAfterAttempts
                }
                is UploadAttemptResult.Attention -> {
                    captures.markDeliveryAttention(record.captureId, result.errorCode, now)
                    attention += 1
                    lastAttemptErrorCode = result.errorCode
                    notify = true
                }
            }
        }
        val pendingAfter = captures.summary().pending
        return DeliveryCycleSummary(
            attempted = eligible.size,
            delivered = delivered,
            retried = retried,
            attentionRequired = attention,
            deferred = (allPending - eligible.size).coerceAtLeast(0),
            pendingAfter = pendingAfter,
            notificationRequired = notify,
            lastAttemptErrorCode = lastAttemptErrorCode,
        )
    }

    companion object {
        fun backoffSeconds(attempt: Int, settings: DeliverySettings): Long {
            require(attempt >= 1)
            val exponent = min(attempt - 1, 20)
            val multiplier = 1L shl exponent
            return min(
                settings.initialRetrySeconds.toLong() * multiplier,
                settings.maximumRetrySeconds.toLong(),
            )
        }
    }
}
