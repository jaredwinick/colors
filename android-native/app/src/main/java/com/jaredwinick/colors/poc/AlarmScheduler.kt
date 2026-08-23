package com.jaredwinick.colors.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

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
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_CAPTURE_ALARM)
            .putExtra(StationService.EXTRA_SCHEDULED_FOR, scheduledFor)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, scheduledFor, pendingIntent)
        preferences.setNextCaptureAt(scheduledFor)
        return scheduledFor
    }

    fun cancel() {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_CAPTURE_ALARM)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        preferences.setNextCaptureAt(0)
    }

    companion object {
        private const val REQUEST_CODE = 2701
    }
}
