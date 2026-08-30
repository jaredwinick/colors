package com.jaredwinick.colors.camera.ui

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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jaredwinick.colors.camera.R
import com.jaredwinick.colors.camera.camera.PowerSnapshotReader
import com.jaredwinick.colors.camera.camera.StationService
import com.jaredwinick.colors.camera.config.ConfigurationStore
import com.jaredwinick.colors.camera.diagnostics.TimingReport
import com.jaredwinick.colors.camera.persistence.DiagnosticStore
import com.jaredwinick.colors.camera.persistence.ProductionCaptureRepository
import com.jaredwinick.colors.camera.persistence.StationPreferences
import com.jaredwinick.colors.camera.schedule.AlarmScheduler
import com.jaredwinick.colors.camera.schedule.UtcSchedule
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: StationPreferences
    private lateinit var diagnostics: DiagnosticStore
    private lateinit var configurationStore: ConfigurationStore
    private lateinit var captureRepository: ProductionCaptureRepository
    private lateinit var intervalInput: EditText
    private lateinit var precisionModeInput: CheckBox
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
            toast("Camera permission is required for the camera station")
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
        configurationStore = ConfigurationStore(this)
        captureRepository = ProductionCaptureRepository(this)
        intervalInput = findViewById(R.id.intervalMinutes)
        precisionModeInput = findViewById(R.id.precisionMode)
        statusText = findViewById(R.id.statusText)
        reportText = findViewById(R.id.reportText)
        loadConfiguredSchedule()

        findViewById<Button>(R.id.startStation).setOnClickListener {
            withCameraPermission(::startStation)
        }
        findViewById<Button>(R.id.stopStation).setOnClickListener { stopStation() }
        findViewById<Button>(R.id.captureNow).setOnClickListener {
            withCameraPermission(::captureNow)
        }
        findViewById<Button>(R.id.exportReport).setOnClickListener { shareReport() }
        findViewById<Button>(R.id.shareLatestCapture).setOnClickListener { shareLatestCapture() }
        findViewById<Button>(R.id.batterySettings).setOnClickListener {
            openBatteryOptimizationSettings()
        }
        findViewById<Button>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.maskCalibration).setOnClickListener {
            startActivity(Intent(this, MaskCalibrationActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!preferences.enabled) loadConfiguredSchedule()
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

        val precisionMode = precisionModeInput.isChecked
        if (precisionMode) {
            val power = PowerSnapshotReader.read(this)
            if (!power.plugged) {
                toast("Connect external power before starting the precision experiment")
                return
            }
            if (!power.batteryOptimizationExempt) {
                openBatteryOptimizationSettings()
                toast("Turn battery optimization off for Colors Camera, then start again")
                return
            }
        }

        configurationStore.save(
            configurationStore.load().copy(
                intervalMinutes = interval,
                precisionMode = precisionMode,
            ),
        )
        preferences.start(interval, precisionMode)
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
        val power = PowerSnapshotReader.read(this)
        statusText.text = buildString {
            appendLine("Station: ${if (preferences.enabled) "RUNNING" else "STOPPED"}")
            appendLine("Mode: ${if (preferences.precisionMode) "PRECISION EXPERIMENT" else "ALARM ONLY"}")
            appendLine("Camera permission: ${if (cameraGranted) "granted" else "required"}")
            appendLine("Exact alarms: ${if (canScheduleExactAlarms()) "available" else "permission required"}")
            appendLine("External power: ${if (power.plugged) "connected" else "not connected"}")
            appendLine("Charging / battery: ${if (power.charging) "yes" else "no"} / ${power.batteryPercent?.let { "$it%" } ?: "—"}")
            appendLine("Battery optimization: ${if (power.batteryOptimizationExempt) "off" else "ON"}")
            appendLine("Device idle / power save: ${power.deviceIdleMode} / ${power.powerSaveMode}")
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

    private fun shareLatestCapture() {
        val image = captureRepository.latestCommittedImage()
        if (image == null) {
            toast("No completed production capture is available yet")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", image)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share latest production image"))
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

    private fun openBatteryOptimizationSettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun loadConfiguredSchedule() {
        val configuration = configurationStore.load()
        intervalInput.setText(String.format(Locale.US, "%d", configuration.intervalMinutes))
        precisionModeInput.isChecked = configuration.precisionMode
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 5_000L
    }
}
