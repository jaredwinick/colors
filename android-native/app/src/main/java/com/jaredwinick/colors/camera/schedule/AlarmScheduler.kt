package com.jaredwinick.colors.camera.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.jaredwinick.colors.camera.camera.StationService
import com.jaredwinick.colors.camera.persistence.StationPreferences

class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val preferences = StationPreferences(context)

    fun scheduleNext(nowMillis: Long = System.currentTimeMillis()): Long {
        check(preferences.enabled) { "Station mode is not enabled" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            preferences.setLastError("EXACT_ALARM_PERMISSION_REQUIRED")
            throw SecurityException("Exact-alarm access is not granted")
        }
        val scheduledFor = UtcSchedule.nextBoundaryMillis(nowMillis, preferences.intervalMinutes)
        val previous = preferences.nextCaptureAt
        if (previous > 0 && previous != scheduledFor) cancelFallback(previous)
        cancelLegacyFallback()

        val pendingIntent = pendingIntent(
            scheduledFor,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) ?: error("Could not create fallback alarm")
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            AlarmTiming.deliveryAt(scheduledFor, preferences.precisionMode),
            pendingIntent,
        )
        preferences.setNextCaptureAt(scheduledFor)
        return scheduledFor
    }

    fun cancelFallback(scheduledFor: Long) {
        if (scheduledFor <= 0) return
        pendingIntent(
            scheduledFor,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let { pendingIntent ->
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    fun cancel() {
        cancelFallback(preferences.nextCaptureAt)
        cancelLegacyFallback()
        preferences.setNextCaptureAt(0)
    }

    private fun pendingIntent(scheduledFor: Long, flags: Int): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_CAPTURE_ALARM)
            // PendingIntent identity ignores extras. A per-slot data URI keeps
            // the current alarm immutable when the next slot is registered.
            .setData(AlarmTiming.identityUri(scheduledFor).toUri())
            .putExtra(StationService.EXTRA_SCHEDULED_FOR, scheduledFor)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    private fun cancelLegacyFallback() {
        val legacyIntent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_CAPTURE_ALARM)
        val legacyPendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            legacyIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (legacyPendingIntent != null) {
            alarmManager.cancel(legacyPendingIntent)
            legacyPendingIntent.cancel()
        }
    }

    companion object {
        private const val REQUEST_CODE = 2701
    }
}
