package com.jaredwinick.colors.camera.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jaredwinick.colors.camera.BuildConfig
import com.jaredwinick.colors.camera.R
import com.jaredwinick.colors.camera.config.AppConfiguration
import com.jaredwinick.colors.camera.config.CameraLens
import com.jaredwinick.colors.camera.config.ConfigurationStore
import com.jaredwinick.colors.camera.config.EndpointPolicy
import com.jaredwinick.colors.camera.config.FocusMode
import com.jaredwinick.colors.camera.config.SecureTokenStore
import com.jaredwinick.colors.camera.config.WhiteBalanceMode
import com.jaredwinick.colors.camera.mask.SkyMaskRepository
import com.jaredwinick.colors.camera.palette.PaletteExtractor
import com.jaredwinick.colors.camera.palette.PalettePreviewRenderer
import com.jaredwinick.colors.camera.persistence.ProductionCaptureRepository
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {
    private lateinit var configurationStore: ConfigurationStore
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var captures: ProductionCaptureRepository
    private lateinit var masks: SkyMaskRepository
    private val paletteExtractor = PaletteExtractor()
    private val palettePreviewRenderer = PalettePreviewRenderer()
    private val previewExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewedPaletteSettings: Pair<Int, Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.app_settings)
        configurationStore = ConfigurationStore(this)
        tokenStore = SecureTokenStore(this)
        captures = ProductionCaptureRepository(this)
        masks = SkyMaskRepository(this)

        bind(configurationStore.load())
        watchPaletteSettings()
        findViewById<Button>(R.id.previewPaletteSettings).setOnClickListener {
            previewPaletteSettings()
        }
        findViewById<Button>(R.id.saveConfiguration).setOnClickListener { saveConfiguration() }
        findViewById<Button>(R.id.saveToken).setOnClickListener { saveToken() }
        findViewById<Button>(R.id.clearToken).setOnClickListener {
            tokenStore.clear()
            findViewById<EditText>(R.id.ingestToken).text.clear()
            refreshCredentialStatus()
            toast("Ingest token cleared")
        }
        refreshCredentialStatus()
    }

    override fun onDestroy() {
        previewExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bind(configuration: AppConfiguration) {
        text(R.id.deviceId, configuration.deviceId)
        findViewById<CheckBox>(R.id.frontCamera).isChecked =
            configuration.cameraLens == CameraLens.FRONT
        findViewById<CheckBox>(R.id.continuousAutoFocus).isChecked =
            configuration.focusMode == FocusMode.CONTINUOUS_AUTO
        findViewById<CheckBox>(R.id.automaticWhiteBalance).isChecked =
            configuration.whiteBalanceMode == WhiteBalanceMode.AUTO
        text(R.id.exposureCompensationTenthsEv, configuration.exposureCompensationTenthsEv)
        text(R.id.maxImageDimension, configuration.maxImageDimension)
        text(R.id.jpegQuality, configuration.jpegQuality)
        text(R.id.paletteColors, configuration.paletteColors)
        text(R.id.paletteAnalysisDimension, configuration.paletteAnalysisDimension)
        text(R.id.maxPendingCaptures, configuration.maxPendingCaptures)
        text(R.id.maxUploadsPerCycle, configuration.maxUploadsPerCycle)
        text(R.id.requestTimeoutSeconds, configuration.requestTimeoutSeconds)
        text(R.id.initialRetrySeconds, configuration.initialRetrySeconds)
        text(R.id.maximumRetrySeconds, configuration.maximumRetrySeconds)
        text(R.id.notifyAfterAttempts, configuration.notifyAfterAttempts)
        text(R.id.retentionDays, configuration.retentionDays)
        text(R.id.retentionCount, configuration.retentionCount)
        text(R.id.logMaxBytes, configuration.logMaxBytes)

        val endpointInput = findViewById<EditText>(R.id.debugEndpointOverride)
        val endpointVisibility = if (BuildConfig.ALLOW_ENDPOINT_OVERRIDE) View.VISIBLE else View.GONE
        endpointInput.visibility = endpointVisibility
        findViewById<TextView>(R.id.debugEndpointOverrideLabel).visibility = endpointVisibility
        endpointInput.setText(configuration.debugEndpointOverride.orEmpty())
        refreshEndpointStatus(configuration)
    }

    private fun saveConfiguration() {
        runCatching {
            val previous = configurationStore.load()
            val candidate = candidateConfiguration(previous)
            val paletteChanged = candidate.paletteKey() != previous.paletteKey()
            if (paletteChanged) {
                require(previewedPaletteSettings == candidate.paletteKey()) {
                    "Generate a palette preview for these color and analysis settings before saving"
                }
                require(findViewById<CheckBox>(R.id.confirmPalettePreview).isChecked) {
                    "Confirm the palette preview before saving these settings"
                }
            }
            candidate
        }.onSuccess { configuration ->
            configurationStore.save(configuration)
            refreshEndpointStatus(configuration)
            previewedPaletteSettings = null
            findViewById<CheckBox>(R.id.confirmPalettePreview).apply {
                isChecked = false
                isEnabled = false
            }
            toast("Configuration saved")
        }.onFailure { error ->
            toast(error.message ?: "Configuration is invalid")
        }
    }

    private fun candidateConfiguration(previous: AppConfiguration): AppConfiguration {
        val endpointOverride = if (BuildConfig.ALLOW_ENDPOINT_OVERRIDE) {
            value(R.id.debugEndpointOverride).ifBlank { null }
        } else {
            null
        }
        return previous.copy(
            deviceId = value(R.id.deviceId),
            cameraLens = if (findViewById<CheckBox>(R.id.frontCamera).isChecked) {
                CameraLens.FRONT
            } else {
                CameraLens.BACK
            },
            focusMode = if (findViewById<CheckBox>(R.id.continuousAutoFocus).isChecked) {
                FocusMode.CONTINUOUS_AUTO
            } else {
                FocusMode.INFINITY
            },
            whiteBalanceMode = if (findViewById<CheckBox>(R.id.automaticWhiteBalance).isChecked) {
                WhiteBalanceMode.AUTO
            } else {
                WhiteBalanceMode.DAYLIGHT
            },
            exposureCompensationTenthsEv = number(R.id.exposureCompensationTenthsEv),
            maxImageDimension = number(R.id.maxImageDimension),
            jpegQuality = number(R.id.jpegQuality),
            paletteColors = number(R.id.paletteColors),
            paletteAnalysisDimension = number(R.id.paletteAnalysisDimension),
            maxPendingCaptures = number(R.id.maxPendingCaptures),
            maxUploadsPerCycle = number(R.id.maxUploadsPerCycle),
            requestTimeoutSeconds = number(R.id.requestTimeoutSeconds),
            initialRetrySeconds = number(R.id.initialRetrySeconds),
            maximumRetrySeconds = number(R.id.maximumRetrySeconds),
            notifyAfterAttempts = number(R.id.notifyAfterAttempts),
            retentionDays = number(R.id.retentionDays),
            retentionCount = number(R.id.retentionCount),
            logMaxBytes = number(R.id.logMaxBytes),
            debugEndpointOverride = endpointOverride,
        ).requireValid()
    }

    private fun previewPaletteSettings() {
        val candidate = runCatching { candidateConfiguration(configurationStore.load()) }
            .getOrElse { error ->
                showPalettePreviewError(error)
                return
            }
        val image = captures.latestCommittedImage()
        if (image == null) {
            showPalettePreviewError(
                IllegalStateException("Take a production capture before previewing palette settings"),
            )
            return
        }
        invalidatePalettePreview("Generating palette preview…")
        val previewKey = candidate.paletteKey()
        val output = File(filesDir, "exports/colors-palette-settings-preview.jpg")
        previewExecutor.execute {
            runCatching {
                val extraction = paletteExtractor.extract(
                    sourceFile = image,
                    requestedColors = candidate.paletteColors,
                    analysisDimension = candidate.paletteAnalysisDimension,
                    masks = masks,
                )
                palettePreviewRenderer.render(image, output, masks.active(), extraction.palette)
                extraction
            }.onSuccess { extraction ->
                mainHandler.post {
                    if (currentPaletteKeyOrNull() != previewKey) {
                        invalidatePalettePreview("Palette settings changed; generate a new preview.")
                        return@post
                    }
                    previewedPaletteSettings = previewKey
                    findViewById<ImageView>(R.id.palettePreviewImage).setImageBitmap(
                        BitmapFactory.decodeFile(output.absolutePath),
                    )
                    findViewById<TextView>(R.id.palettePreviewStatus).text = buildString {
                        val statistics = extraction.statistics
                        appendLine("Preview ready: ${statistics.paletteColors} colors")
                        appendLine(
                            "Analysis ${statistics.analysisSize.width}×${statistics.analysisSize.height}; " +
                                "${statistics.includedPixels} masked-sky pixels",
                        )
                        appendLine(
                            "${statistics.elapsedMs} ms; peak process memory " +
                                "${String.format(Locale.US, "%.1f", statistics.peakPssKib / 1024.0)} MiB",
                        )
                        append(extraction.palette.toJson())
                    }
                    findViewById<CheckBox>(R.id.confirmPalettePreview).apply {
                        isChecked = false
                        isEnabled = true
                    }
                }
            }.onFailure { error -> mainHandler.post { showPalettePreviewError(error) } }
        }
    }

    private fun watchPaletteSettings() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                invalidatePalettePreview("Palette settings changed; generate a preview before saving them.")
            }
            override fun afterTextChanged(value: Editable?) = Unit
        }
        findViewById<EditText>(R.id.paletteColors).addTextChangedListener(watcher)
        findViewById<EditText>(R.id.paletteAnalysisDimension).addTextChangedListener(watcher)
    }

    private fun invalidatePalettePreview(message: String) {
        previewedPaletteSettings = null
        findViewById<CheckBox>(R.id.confirmPalettePreview).apply {
            isChecked = false
            isEnabled = false
        }
        findViewById<TextView>(R.id.palettePreviewStatus).text = message
    }

    private fun showPalettePreviewError(error: Throwable) {
        invalidatePalettePreview("ERROR: ${error.message ?: "Palette preview failed"}")
        toast(error.message ?: "Palette preview failed")
    }

    private fun currentPaletteKeyOrNull(): Pair<Int, Int>? {
        val colors = value(R.id.paletteColors).toIntOrNull() ?: return null
        val dimension = value(R.id.paletteAnalysisDimension).toIntOrNull() ?: return null
        return colors to dimension
    }

    private fun AppConfiguration.paletteKey(): Pair<Int, Int> =
        paletteColors to paletteAnalysisDimension

    private fun saveToken() {
        val input = value(R.id.ingestToken)
        runCatching { tokenStore.save(input) }
            .onSuccess {
                findViewById<EditText>(R.id.ingestToken).text.clear()
                refreshCredentialStatus()
                toast("Ingest token saved in Android Keystore")
            }
            .onFailure { error -> toast(error.message ?: "Could not save ingest token") }
    }

    private fun refreshEndpointStatus(configuration: AppConfiguration) {
        val endpoint = EndpointPolicy.resolve(
            BuildConfig.INGEST_ENDPOINT,
            configuration.debugEndpointOverride,
            BuildConfig.ALLOW_ENDPOINT_OVERRIDE,
        )
        findViewById<TextView>(R.id.endpointStatus).text = "Effective endpoint: $endpoint"
    }

    private fun refreshCredentialStatus() {
        findViewById<TextView>(R.id.tokenStatus).text =
            "Credential: ${tokenStore.status()} (value is never displayed)"
    }

    private fun value(id: Int): String = findViewById<EditText>(id).text.toString()

    private fun number(id: Int): Int = value(id).toIntOrNull()
        ?: throw IllegalArgumentException("Every numeric setting must contain a whole number")

    private fun text(id: Int, value: Any) {
        findViewById<EditText>(id).setText(value.toString())
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
