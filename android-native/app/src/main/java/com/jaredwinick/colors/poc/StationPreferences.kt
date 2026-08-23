package com.jaredwinick.colors.poc

import android.content.Context
import java.util.UUID

class StationPreferences(context: Context) {
    private val protectedContext = context.createDeviceProtectedStorageContext()
    private val preferences = protectedContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val enabled: Boolean get() = preferences.getBoolean(KEY_ENABLED, false)
    val intervalMinutes: Int get() = preferences.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MINUTES)
    val nextCaptureAt: Long get() = preferences.getLong(KEY_NEXT_CAPTURE, 0)
    val lastCaptureAt: Long get() = preferences.getLong(KEY_LAST_CAPTURE, 0)
    val lastError: String? get() = preferences.getString(KEY_LAST_ERROR, null)
    val sessionId: String get() = preferences.getString(KEY_SESSION_ID, "") ?: ""
    val firstScheduledAt: Long get() = preferences.getLong(KEY_FIRST_SCHEDULED, 0)
    val sessionStoppedAt: Long get() = preferences.getLong(KEY_SESSION_STOPPED, 0)

    fun start(intervalMinutes: Int, nowMillis: Long = System.currentTimeMillis()) {
        UtcSchedule.validateIntervalMinutes(intervalMinutes)
        val first = UtcSchedule.nextBoundaryMillis(nowMillis, intervalMinutes)
        preferences.edit()
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_INTERVAL, intervalMinutes)
            .putString(KEY_SESSION_ID, UUID.randomUUID().toString())
            .putLong(KEY_FIRST_SCHEDULED, first)
            .putLong(KEY_NEXT_CAPTURE, first)
            .putLong(KEY_SESSION_STOPPED, 0)
            .remove(KEY_CLAIMED_SLOTS)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun stop(nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, false)
            .putLong(KEY_SESSION_STOPPED, nowMillis)
            .putLong(KEY_NEXT_CAPTURE, 0)
            .apply()
    }

    fun setNextCaptureAt(value: Long) {
        preferences.edit().putLong(KEY_NEXT_CAPTURE, value).apply()
    }

    fun setLastCapture(value: Long) {
        preferences.edit()
            .putLong(KEY_LAST_CAPTURE, value)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun setLastError(code: String) {
        preferences.edit().putString(KEY_LAST_ERROR, code).apply()
    }

    @Synchronized
    fun claimScheduledSlot(scheduledFor: Long): Boolean {
        val cutoff = scheduledFor - CLAIM_RETENTION_MS
        val claimed = preferences.getStringSet(KEY_CLAIMED_SLOTS, emptySet()).orEmpty()
            .mapNotNull(String::toLongOrNull)
            .filter { it >= cutoff }
            .mapTo(mutableSetOf()) { it.toString() }
        if (!claimed.add(scheduledFor.toString())) return false
        return preferences.edit().putStringSet(KEY_CLAIMED_SLOTS, claimed).commit()
    }

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 15
        private const val FILE_NAME = "station"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_NEXT_CAPTURE = "next_capture_at"
        private const val KEY_LAST_CAPTURE = "last_capture_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_FIRST_SCHEDULED = "first_scheduled_at"
        private const val KEY_SESSION_STOPPED = "session_stopped_at"
        private const val KEY_CLAIMED_SLOTS = "claimed_slots"
        private const val CLAIM_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
