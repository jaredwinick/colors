package com.jaredwinick.colors.camera.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jaredwinick.colors.camera.BuildConfig
import com.jaredwinick.colors.camera.R
import com.jaredwinick.colors.camera.camera.StationService
import com.jaredwinick.colors.camera.config.ConfigurationStore
import com.jaredwinick.colors.camera.config.EndpointPolicy
import com.jaredwinick.colors.camera.config.SecureTokenStore
import com.jaredwinick.colors.camera.diagnostics.OperatorExports
import com.jaredwinick.colors.camera.mask.SkyMaskRepository
import com.jaredwinick.colors.camera.outbox.DurableCaptureState
import com.jaredwinick.colors.camera.outbox.SafeCaptureRecord
import com.jaredwinick.colors.camera.persistence.DiagnosticStore
import com.jaredwinick.colors.camera.persistence.ProductionCaptureRepository
import com.jaredwinick.colors.camera.persistence.StationPreferences
import com.jaredwinick.colors.camera.schedule.UtcSchedule
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class OperationsActivity : AppCompatActivity() {
    private lateinit var captures: ProductionCaptureRepository
    private lateinit var configurationStore: ConfigurationStore
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var diagnostics: DiagnosticStore
    private lateinit var masks: SkyMaskRepository
    private lateinit var preferences: StationPreferences
    private lateinit var status: TextView
    private lateinit var records: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_operations)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.station_operations)
        captures = ProductionCaptureRepository(this)
        configurationStore = ConfigurationStore(this)
        tokenStore = SecureTokenStore(this)
        diagnostics = DiagnosticStore(this)
        masks = SkyMaskRepository(this)
        preferences = StationPreferences(this)
        status = findViewById(R.id.operationsStatus)
        records = findViewById(R.id.operationsRecords)

        findViewById<Button>(R.id.retryUploads).setOnClickListener { retryUploads() }
        findViewById<Button>(R.id.applyRetention).setOnClickListener { applyRetention() }
        findViewById<Button>(R.id.shareQueue).setOnClickListener { shareQueue() }
        findViewById<Button>(R.id.shareDiagnostics).setOnClickListener { shareDiagnostics() }
        findViewById<Button>(R.id.shareConfiguration).setOnClickListener { shareConfiguration() }
        findViewById<Button>(R.id.shareMask).setOnClickListener { shareMask() }
        findViewById<Button>(R.id.refreshOperations).setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        runCatching {
            val configuration = configurationStore.load()
            val summary = captures.summary()
            val safeRecords = captures.safeOperatorRecords(DISPLAY_RECORD_LIMIT)
            val failures = captures.failureNotificationState(configuration.notifyAfterAttempts)
            val lastConfirmedUpload = preferences.lastConfirmedUploadAt.takeIf { it > 0 }
                ?.let(UtcSchedule::format)
                ?: captures.lastConfirmedUploadAt()
                ?: "—"
            status.text = buildString {
                appendLine("Token: ${tokenStore.status()}")
                appendLine("Last confirmed upload: $lastConfirmedUpload")
                appendLine("Pending / attention: ${summary.pending} / ${summary.totalAttention}")
                appendLine("Staged / processing: ${summary.staged} / ${summary.processing}")
                appendLine("Delivered retained: ${summary.delivered}")
                appendLine(
                    "Local storage: " +
                        String.format(Locale.US, "%.2f MiB", summary.storageBytes / 1_048_576.0),
                )
                append(
                    "Failure alert: ${if (failures.required) "ACTIVE" else "clear"} " +
                        "(${failures.repeatedFailureCount} repeated; " +
                        "${failures.attentionCount} attention)",
                )
            }
            records.text = if (safeRecords.isEmpty()) {
                "No capture records."
            } else {
                safeRecords.joinToString("\n\n", transform = ::displayRecord)
            }
        }.onFailure { error ->
            status.text = "Operations unavailable: ${safeMessage(error)}"
            records.text = "No queue details available."
        }
    }

    private fun retryUploads() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, StationService::class.java).setAction(StationService.ACTION_UPLOAD_PENDING),
        )
        toast("Oldest eligible uploads requested")
    }

    private fun applyRetention() {
        val configuration = configurationStore.load()
        executor.execute {
            val result = runCatching {
                captures.applyDeliveredRetention(
                    configuration.retentionDays,
                    configuration.retentionCount,
                )
            }
            mainHandler.post {
                result.onSuccess { removed ->
                    toast("Delivered retention applied; removed $removed local capture(s)")
                    refresh()
                }.onFailure { error -> toast("Retention failed: ${safeMessage(error)}") }
            }
        }
    }

    private fun shareQueue() = runCatching {
        shareText(
            fileName = "colors-safe-outbox.json",
            mimeType = "application/json",
            content = OperatorExports.queue(captures.summary(), captures.safeOperatorRecords(1_000)),
            chooserTitle = "Share safe outbox inspection",
        )
    }.onFailure { toast("Queue export failed: ${safeMessage(it)}") }

    private fun shareDiagnostics() = runCatching {
        val maximumBytes = configurationStore.load().logMaxBytes
        shareText(
            fileName = "colors-redacted-diagnostics.jsonl",
            mimeType = "application/x-ndjson",
            content = OperatorExports.redactedDiagnostics(diagnostics.records(), maximumBytes),
            chooserTitle = "Share redacted diagnostics",
        )
    }.onFailure { toast("Diagnostic export failed: ${safeMessage(it)}") }

    private fun shareConfiguration() = runCatching {
        val configuration = configurationStore.load()
        shareText(
            fileName = "colors-configuration.json",
            mimeType = "application/json",
            content = OperatorExports.configuration(
                configuration = configuration,
                effectiveEndpoint = EndpointPolicy.resolve(
                    BuildConfig.INGEST_ENDPOINT,
                    configuration.debugEndpointOverride,
                    BuildConfig.ALLOW_ENDPOINT_OVERRIDE,
                ),
                tokenConfigured = tokenStore.status() == "configured",
            ),
            chooserTitle = "Share non-secret configuration",
        )
    }.onFailure { toast("Configuration export failed: ${safeMessage(it)}") }

    private fun shareMask() = runCatching {
        shareText(
            fileName = "colors-sky-mask.json",
            mimeType = "application/json",
            content = masks.activeJson(),
            chooserTitle = "Share active sky mask",
        )
    }.onFailure { toast("Mask export failed: ${safeMessage(it)}") }

    private fun shareText(
        fileName: String,
        mimeType: String,
        content: String,
        chooserTitle: String,
    ) {
        val exportDirectory = File(filesDir, "exports").apply { mkdirs() }
        val export = File(exportDirectory, fileName)
        export.writeText(content)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", export)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                chooserTitle,
            ),
        )
    }

    private fun displayRecord(record: SafeCaptureRecord): String = buildString {
        appendLine("${record.state.name}: ${record.captureId}")
        appendLine("Captured: ${record.capturedAt}; ${record.byteCount} bytes")
        append("Attempts: ${record.attemptCount}; ${disposition(record)}")
        record.lastErrorCode?.let { append("; error: $it") }
        record.nextEligibleRetryAt?.let { append("; retry: $it") }
        record.deliveredAt?.let { append("; delivered: $it") }
    }

    private fun disposition(record: SafeCaptureRecord): String = when (record.state) {
        DurableCaptureState.STAGED -> "recoverable local source"
        DurableCaptureState.PROCESSING -> "local processing"
        DurableCaptureState.PENDING_UPLOAD -> if (record.lastErrorCode == null) {
            "ready to upload"
        } else {
            "retryable upload"
        }
        DurableCaptureState.DELIVERED -> "server confirmed"
        DurableCaptureState.ATTENTION_REQUIRED -> "operator attention required"
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.take(160)?.replace(Regex("https?://\\S+"), "[endpoint]") ?: "unknown error"

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val DISPLAY_RECORD_LIMIT = 30
    }
}
