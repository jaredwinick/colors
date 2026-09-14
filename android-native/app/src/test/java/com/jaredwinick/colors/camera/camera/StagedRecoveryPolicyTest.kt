package com.jaredwinick.colors.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class StagedRecoveryPolicyTest {
    @Test
    fun `first quantization failure remains staged for one recovery attempt`() {
        assertEquals(
            StagedFailureDisposition.RETRY_STAGED,
            StagedRecoveryPolicy.failureDisposition(null, "PALETTE_QUANTIZATION_INVALID"),
        )
    }

    @Test
    fun `repeated quantization failure moves the poison capture to attention`() {
        assertEquals(
            StagedFailureDisposition.RETAIN_ATTENTION,
            StagedRecoveryPolicy.failureDisposition(
                "PALETTE_QUANTIZATION_INVALID",
                "PALETTE_QUANTIZATION_INVALID",
            ),
        )
    }

    @Test
    fun `transient and changed failures remain recoverable`() {
        assertEquals(
            StagedFailureDisposition.RETRY_STAGED,
            StagedRecoveryPolicy.failureDisposition(
                "PROCESS_INTERRUPTED_RECOVERABLE",
                "PALETTE_QUANTIZATION_INVALID",
            ),
        )
        assertEquals(
            StagedFailureDisposition.RETRY_STAGED,
            StagedRecoveryPolicy.failureDisposition(
                "PALETTE_MASK_INVALID",
                "PALETTE_MASK_INVALID",
            ),
        )
    }
}
