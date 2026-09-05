package com.jaredwinick.colors.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionCyclePolicyTest {
    @Test
    fun `normal cycle preserves remaining upload budget for the new capture`() {
        val plan = ProductionCyclePolicy.plan(
            pendingAfterPreflight = 0,
            hasStagedCapture = false,
            maximumPendingCaptures = 192,
            configuredUploadBudget = 4,
            preflightAttempts = 0,
        )

        assertEquals(ProductionCycleAction.CAPTURE_NEW, plan.action)
        assertEquals(1, plan.preCaptureUploadLimit)
        assertEquals(4, plan.remainingUploadBudget)
    }

    @Test
    fun `oldest staged work takes priority over a new photograph`() {
        val plan = ProductionCyclePolicy.plan(
            pendingAfterPreflight = 3,
            hasStagedCapture = true,
            maximumPendingCaptures = 3,
            configuredUploadBudget = 4,
            preflightAttempts = 1,
        )

        assertEquals(ProductionCycleAction.RECOVER_STAGED, plan.action)
        assertEquals(3, plan.remainingUploadBudget)
    }

    @Test
    fun `full pending queue pauses new camera work after recovery pass`() {
        val plan = ProductionCyclePolicy.plan(
            pendingAfterPreflight = 2,
            hasStagedCapture = false,
            maximumPendingCaptures = 2,
            configuredUploadBudget = 3,
            preflightAttempts = 1,
        )

        assertEquals(ProductionCycleAction.BACKPRESSURE, plan.action)
        assertEquals(2, plan.remainingUploadBudget)
    }

    @Test
    fun `pre-capture upload is bounded to one short attempt`() {
        assertEquals(1, ProductionCyclePolicy.preCaptureUploadLimit(1))
        assertEquals(1, ProductionCyclePolicy.preCaptureUploadLimit(20))
        assertEquals(10, ProductionCyclePolicy.PRE_CAPTURE_TIMEOUT_SECONDS)
    }

    @Test
    fun `successful staged recovery continues with a current capture only when capacity remains`() {
        assertTrue(ProductionCyclePolicy.shouldCaptureAfterRecovery(191, 192))
        assertFalse(ProductionCyclePolicy.shouldCaptureAfterRecovery(192, 192))
    }
}
