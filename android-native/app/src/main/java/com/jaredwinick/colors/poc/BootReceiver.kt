package com.jaredwinick.colors.poc

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTORE_ACTIONS) return
        val preferences = StationPreferences(context)
        if (!preferences.enabled) return

        val userManager = context.getSystemService(UserManager::class.java)
        if (!userManager.isUserUnlocked) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            preferences.setLastError("CAMERA_PERMISSION_REQUIRED_AFTER_BOOT")
            return
        }

        val serviceIntent = Intent(context, StationService::class.java)
            .setAction(StationService.ACTION_RESTORE)
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    companion object {
        private val RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
