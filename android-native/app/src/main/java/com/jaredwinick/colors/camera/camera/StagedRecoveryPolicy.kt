package com.jaredwinick.colors.camera.camera

enum class StagedFailureDisposition {
    RETRY_STAGED,
    RETAIN_ATTENTION,
}

/** Prevents a deterministic per-image failure from becoming a permanent queue head. */
object StagedRecoveryPolicy {
    fun failureDisposition(
        previousErrorCode: String?,
        currentErrorCode: String,
    ): StagedFailureDisposition = if (
        currentErrorCode == "PALETTE_QUANTIZATION_INVALID" &&
        previousErrorCode == currentErrorCode
    ) {
        StagedFailureDisposition.RETAIN_ATTENTION
    } else {
        StagedFailureDisposition.RETRY_STAGED
    }
}
