package com.jaredwinick.colors.poc

data class CaptureDiagnostic(
    val recordId: String,
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
    val imagePath: String? = null,
    val manual: Boolean = false,
) {
    val alarmLatenessMs: Long
        get() = alarmReceivedAt - scheduledFor

    val captureLatenessMs: Long?
        get() = capturedAt?.minus(scheduledFor)

    companion object {
        const val RESULT_IN_PROGRESS = "IN_PROGRESS"
        const val RESULT_SUCCESS = "SUCCESS"
    }
}
