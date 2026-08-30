package com.jaredwinick.colors.camera.camera

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlin.math.roundToInt

data class PowerSnapshot(
    val screenInteractive: Boolean,
    val charging: Boolean,
    val plugged: Boolean,
    val batteryPercent: Int?,
    val deviceIdleMode: Boolean,
    val powerSaveMode: Boolean,
    val batteryOptimizationExempt: Boolean,
)

object PowerSnapshotReader {
    fun read(context: Context): PowerSnapshot {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pluggedValue = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            (level * 100.0 / scale).roundToInt()
        } else {
            null
        }
        return PowerSnapshot(
            screenInteractive = powerManager.isInteractive,
            charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == BatteryManager.BATTERY_STATUS_FULL,
            plugged = pluggedValue != 0,
            batteryPercent = percent,
            deviceIdleMode = powerManager.isDeviceIdleMode,
            powerSaveMode = powerManager.isPowerSaveMode,
            batteryOptimizationExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName),
        )
    }
}
