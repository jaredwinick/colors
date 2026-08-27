package com.jaredwinick.colors.camera.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jaredwinick.colors.camera.BuildConfig
import com.jaredwinick.colors.camera.R
import com.jaredwinick.colors.camera.config.AppConfiguration
import com.jaredwinick.colors.camera.config.CameraLens
import com.jaredwinick.colors.camera.config.ConfigurationStore
import com.jaredwinick.colors.camera.config.EndpointPolicy
import com.jaredwinick.colors.camera.config.SecureTokenStore

class SettingsActivity : AppCompatActivity() {
    private lateinit var configurationStore: ConfigurationStore
    private lateinit var tokenStore: SecureTokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.app_settings)
        configurationStore = ConfigurationStore(this)
        tokenStore = SecureTokenStore(this)

        bind(configurationStore.load())
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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bind(configuration: AppConfiguration) {
        text(R.id.deviceId, configuration.deviceId)
        findViewById<CheckBox>(R.id.frontCamera).isChecked =
            configuration.cameraLens == CameraLens.FRONT
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
            val endpointOverride = if (BuildConfig.ALLOW_ENDPOINT_OVERRIDE) {
                value(R.id.debugEndpointOverride).ifBlank { null }
            } else {
                null
            }
            previous.copy(
                deviceId = value(R.id.deviceId),
                cameraLens = if (findViewById<CheckBox>(R.id.frontCamera).isChecked) {
                    CameraLens.FRONT
                } else {
                    CameraLens.BACK
                },
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
        }.onSuccess { configuration ->
            configurationStore.save(configuration)
            refreshEndpointStatus(configuration)
            toast("Configuration saved")
        }.onFailure { error ->
            toast(error.message ?: "Configuration is invalid")
        }
    }

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
