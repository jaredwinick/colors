package com.jaredwinick.colors.camera.ui

enum class StationStartDecision {
    START,
    ALREADY_RUNNING,
    CONFIRM_RESTART,
}

object StationStartPolicy {
    fun decide(
        running: Boolean,
        savedIntervalMinutes: Int,
        savedPrecisionMode: Boolean,
        requestedIntervalMinutes: Int,
        requestedPrecisionMode: Boolean,
    ): StationStartDecision {
        if (!running) return StationStartDecision.START
        return if (
            savedIntervalMinutes == requestedIntervalMinutes &&
            savedPrecisionMode == requestedPrecisionMode
        ) {
            StationStartDecision.ALREADY_RUNNING
        } else {
            StationStartDecision.CONFIRM_RESTART
        }
    }
}
