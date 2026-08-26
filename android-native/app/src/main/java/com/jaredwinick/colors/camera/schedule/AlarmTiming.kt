package com.jaredwinick.colors.camera.schedule

object AlarmTiming {
    const val PRECISION_FALLBACK_GRACE_MS = 5_000L

    fun deliveryAt(scheduledFor: Long, precisionMode: Boolean): Long =
        scheduledFor + if (precisionMode) PRECISION_FALLBACK_GRACE_MS else 0L

    fun identityUri(scheduledFor: Long): String =
        "colors-camera://capture/$scheduledFor"
}
