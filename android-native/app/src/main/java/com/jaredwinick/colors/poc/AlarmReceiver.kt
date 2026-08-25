package com.jaredwinick.colors.poc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CAPTURE_ALARM) return
        val preferences = StationPreferences(context)
        if (!preferences.enabled) return

        val scheduledFor = intent.getLongExtra(
            StationService.EXTRA_SCHEDULED_FOR,
            preferences.nextCaptureAt,
        )
        val receivedAt = System.currentTimeMillis()

        // Precision mode normally replaces this fallback alarm with the next
        // slot before it can be delivered. Ignore a stale delivery if the
        // in-process timer already claimed and captured this slot.
        if (preferences.isScheduledSlotClaimed(scheduledFor)) return

        // Protect the cadence before camera work begins. Every later alarm is
        // calculated from a fresh UTC boundary, never from the prior completion.
        runCatching { AlarmScheduler(context).scheduleNext(System.currentTimeMillis()) }
            .onFailure { preferences.setLastError("ALARM_RESCHEDULE_FAILED") }

        val serviceIntent = Intent(context, StationService::class.java)
            .setAction(StationService.ACTION_CAPTURE)
            .putExtra(StationService.EXTRA_SCHEDULED_FOR, scheduledFor)
            .putExtra(StationService.EXTRA_TRIGGER_RECEIVED_AT, receivedAt)
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    companion object {
        const val ACTION_CAPTURE_ALARM = "com.jaredwinick.colors.poc.CAPTURE_ALARM"
    }
}
