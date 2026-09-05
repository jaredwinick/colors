package com.jaredwinick.colors.camera.camera

import kotlin.math.min

enum class ProductionCycleAction {
    RECOVER_STAGED,
    CAPTURE_NEW,
    BACKPRESSURE,
}

data class ProductionCyclePlan(
    val action: ProductionCycleAction,
    val preCaptureUploadLimit: Int,
    val remainingUploadBudget: Int,
)

object ProductionCyclePolicy {
    const val PRE_CAPTURE_TIMEOUT_SECONDS = 10

    fun preCaptureUploadLimit(configuredBudget: Int): Int {
        require(configuredBudget >= 1)
        return min(1, configuredBudget)
    }

    fun plan(
        pendingAfterPreflight: Int,
        hasStagedCapture: Boolean,
        maximumPendingCaptures: Int,
        configuredUploadBudget: Int,
        preflightAttempts: Int,
    ): ProductionCyclePlan {
        require(pendingAfterPreflight >= 0)
        require(maximumPendingCaptures >= 1)
        require(configuredUploadBudget >= 1)
        require(preflightAttempts in 0..configuredUploadBudget)
        return ProductionCyclePlan(
            action = when {
                hasStagedCapture -> ProductionCycleAction.RECOVER_STAGED
                pendingAfterPreflight >= maximumPendingCaptures ->
                    ProductionCycleAction.BACKPRESSURE
                else -> ProductionCycleAction.CAPTURE_NEW
            },
            preCaptureUploadLimit = preCaptureUploadLimit(configuredUploadBudget),
            remainingUploadBudget = configuredUploadBudget - preflightAttempts,
        )
    }

    fun shouldCaptureAfterRecovery(
        pendingAfterRecovery: Int,
        maximumPendingCaptures: Int,
    ): Boolean {
        require(pendingAfterRecovery >= 0)
        require(maximumPendingCaptures >= 1)
        return pendingAfterRecovery < maximumPendingCaptures
    }
}
