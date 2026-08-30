package com.jaredwinick.colors.camera.diagnostics

data class CaptureDiagnostic(
    val recordId: String,
    val captureId: String = recordId,
    val sessionId: String,
    val slotId: String,
    val scheduledFor: Long,
    val alarmReceivedAt: Long,
    val captureStartedAt: Long? = null,
    val capturedAt: Long? = null,
    val completedAt: Long? = null,
    val result: String = RESULT_IN_PROGRESS,
    val errorCode: String? = null,
    val screenInteractive: Boolean,
    val charging: Boolean,
    val plugged: Boolean = false,
    val batteryPercent: Int? = null,
    val deviceIdleMode: Boolean = false,
    val powerSaveMode: Boolean = false,
    val batteryOptimizationExempt: Boolean = false,
    val stationWakeLockHeld: Boolean = false,
    val triggerSource: String = TRIGGER_UNKNOWN,
    val serviceReceivedAt: Long = alarmReceivedAt,
    val imagePath: String? = null,
    val imageBytes: Long? = null,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val processingDurationMs: Long? = null,
    val cameraSettings: String? = null,
    val manual: Boolean = false,
) {
    val alarmLatenessMs: Long
        get() = alarmReceivedAt - scheduledFor

    val captureLatenessMs: Long?
        get() = capturedAt?.minus(scheduledFor)

    val serviceDispatchMs: Long
        get() = serviceReceivedAt - alarmReceivedAt

    companion object {
        const val RESULT_IN_PROGRESS = "IN_PROGRESS"
        const val RESULT_SUCCESS = "SUCCESS"
        const val TRIGGER_ALARM = "ALARM"
        const val TRIGGER_TIMER = "TIMER"
        const val TRIGGER_MANUAL = "MANUAL"
        const val TRIGGER_UNKNOWN = "UNKNOWN"
    }
}
