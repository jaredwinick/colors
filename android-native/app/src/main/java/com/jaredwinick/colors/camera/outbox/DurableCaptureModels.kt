package com.jaredwinick.colors.camera.outbox

import java.security.MessageDigest
import java.time.Instant

enum class DurableCaptureState {
    STAGED,
    PROCESSING,
    PENDING_UPLOAD,
    DELIVERED,
    ATTENTION_REQUIRED,
}

data class DurableCaptureRecord(
    val captureId: String,
    val state: DurableCaptureState,
    val capturedAt: String,
    val deviceId: String,
    val mimeType: String,
    val byteCount: Long,
    val imageSha256: String,
    val paletteJson: String?,
    val processingMetadataJson: String?,
    val immutableFingerprint: String?,
    val imagePath: String,
    val metadataPath: String?,
    val attemptCount: Int,
    val lastAttemptAt: String?,
    val lastErrorCode: String?,
    val nextEligibleRetryAt: String?,
    val deliveredAt: String?,
    val deliveryConfirmationJson: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class OutboxSummary(
    val staged: Int,
    val processing: Int,
    val pending: Int,
    val delivered: Int,
    val attentionRequired: Int,
    val conflicts: Int,
    val oldestPendingAt: String?,
    val storageBytes: Long,
) {
    val totalAttention: Int get() = attentionRequired + conflicts

    fun oldestPendingAgeMillis(now: Instant = Instant.now()): Long? = oldestPendingAt?.let {
        (now.toEpochMilli() - Instant.parse(it).toEpochMilli()).coerceAtLeast(0)
    }
}

data class ReconciliationReport(
    val recoveredStaged: Int = 0,
    val recoveredPending: Int = 0,
    val quarantined: Int = 0,
    val attentionRequired: Int = 0,
)

data class EnqueueResult(
    val record: DurableCaptureRecord,
    val duplicate: Boolean,
)

class CaptureIdConflictException(message: String) : IllegalStateException(message)

object DurableCapturePolicy {
    private val SAFE_ERROR = Regex("[A-Z0-9_]{1,100}")

    fun requireSafeErrorCode(value: String): String {
        require(SAFE_ERROR.matches(value)) { "Outbox error code must be safe" }
        return value
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun immutableFingerprint(
        captureId: String,
        capturedAt: String,
        deviceId: String,
        mimeType: String,
        byteCount: Long,
        imageSha256: String,
        paletteJson: String,
    ): String = sha256(
        listOf(
            captureId,
            capturedAt,
            deviceId,
            mimeType,
            byteCount.toString(),
            imageSha256,
            paletteJson,
        ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
    )

    fun pendingOldestFirst(records: List<DurableCaptureRecord>): List<DurableCaptureRecord> =
        records.filter { it.state == DurableCaptureState.PENDING_UPLOAD }
            .sortedWith(compareBy<DurableCaptureRecord> { it.capturedAt }.thenBy { it.captureId })

    fun deliveredRetentionIds(
        records: List<DurableCaptureRecord>,
        retentionDays: Int,
        retentionCount: Int,
        now: Instant,
    ): Set<String> {
        require(retentionDays >= 0 && retentionCount >= 0)
        val cutoff = now.minusSeconds(retentionDays.toLong() * 86_400)
        return records.filter { it.state == DurableCaptureState.DELIVERED }
            .sortedWith(
                compareByDescending<DurableCaptureRecord> { it.deliveredAt ?: it.capturedAt }
                    .thenByDescending { it.captureId },
            )
            .filterIndexed { index, record ->
                val timestamp = Instant.parse(record.deliveredAt ?: record.capturedAt)
                index >= retentionCount || timestamp.isBefore(cutoff)
            }
            .mapTo(mutableSetOf(), DurableCaptureRecord::captureId)
    }
}
