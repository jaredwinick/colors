package com.jaredwinick.colors.poc

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: StationPreferences
    private lateinit var diagnostics: DiagnosticStore
    private lateinit var intervalInput: EditText
    private lateinit var statusText: TextView
    private lateinit var reportText: TextView
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var permissionAction: (() -> Unit)? = null

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionAction?.invoke()
        } else {
            toast("Camera permission is required for the proof of concept")
        }
        permissionAction = null
        refresh()
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preferences = StationPreferences(this)
        diagnostics = DiagnosticStore(this)
        intervalInput = findViewById(R.id.intervalMinutes)
        statusText = findViewById(R.id.statusText)
        reportText = findViewById(R.id.reportText)
        intervalInput.setText(String.format(Locale.US, "%d", preferences.intervalMinutes))

        findViewById<Button>(R.id.startStation).setOnClickListener {
            withCameraPermission(::startStation)
        }
        findViewById<Button>(R.id.stopStation).setOnClickListener { stopStation() }
        findViewById<Button>(R.id.captureNow).setOnClickListener {
            withCameraPermission(::captureNow)
        }
        findViewById<Button>(R.id.exportReport).setOnClickListener { shareReport() }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun withCameraPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            permissionAction = action
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startStation() {
        val interval = intervalInput.text.toString().toIntOrNull()
        if (interval == null) {
            toast("Enter an interval in minutes")
            return
        }
        val intervalError = runCatching { UtcSchedule.validateIntervalMinutes(interval) }.exceptionOrNull()
        if (intervalError != null) {
            toast(intervalError.message ?: "Invalid interval")
            return
        }
        if (!canScheduleExactAlarms()) {
            openExactAlarmSettings()
            toast("Allow exact alarms, then tap Start station again")
            return
        }

        preferences.start(interval)
        val intent = Intent(this, StationService::class.java).setAction(StationService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        toast("Station started")
        refresh()
    }

    private fun stopStation() {
        AlarmScheduler(this).cancel()
        preferences.stop()
        val serviceIntent = Intent(this, StationService::class.java)
        stopService(serviceIntent)
        toast("Station stopped; captures and diagnostics were retained")
        refresh()
    }

    private fun captureNow() {
        val intent = Intent(this, StationService::class.java)
            .setAction(StationService.ACTION_CAPTURE_TEST)
        ContextCompat.startForegroundService(this, intent)
        toast("Camera test requested")
    }

    private fun refresh() {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        statusText.text = buildString {
            appendLine("Station: ${if (preferences.enabled) "RUNNING" else "STOPPED"}")
            appendLine("Camera permission: ${if (cameraGranted) "granted" else "required"}")
            appendLine("Exact alarms: ${if (canScheduleExactAlarms()) "available" else "permission required"}")
            appendLine("Interval: ${preferences.intervalMinutes} minutes")
            appendLine("Next capture: ${UtcSchedule.format(preferences.nextCaptureAt)}")
            appendLine("Last capture: ${UtcSchedule.format(preferences.lastCaptureAt)}")
            append("Last error: ${preferences.lastError ?: "—"}")
        }

        val sessionId = preferences.sessionId
        if (sessionId.isBlank() || preferences.firstScheduledAt <= 0) {
            reportText.setText(R.string.no_timing_trial)
            return
        }
        val through = if (preferences.enabled) {
            System.currentTimeMillis()
        } else {
            preferences.sessionStoppedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        }
        val summary = TimingReport.summarize(
            records = diagnostics.records(),
            sessionId = sessionId,
            firstScheduledAt = preferences.firstScheduledAt,
            throughMillis = through,
            intervalMinutes = preferences.intervalMinutes,
        )
        reportText.text = TimingReport.display(summary)
    }

    private fun shareReport() {
        val exportDirectory = File(filesDir, "exports").apply { mkdirs() }
        val reportFile = File(exportDirectory, "colors-camera-timing.csv")
        reportFile.writeText(TimingReport.csv(diagnostics.records()))
        val uri = FileProvider.getUriForFile(this, "$packageName.files", reportFile)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share timing report"))
    }

    private fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 5_000L
    }
}
