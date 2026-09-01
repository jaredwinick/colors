package com.jaredwinick.colors.camera.config

import com.jaredwinick.colors.camera.schedule.UtcSchedule

enum class CameraLens {
    BACK,
    FRONT,
}

enum class FocusMode {
    INFINITY,
    CONTINUOUS_AUTO,
}

enum class WhiteBalanceMode {
    DAYLIGHT,
    AUTO,
}

data class AppConfiguration(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val intervalMinutes: Int = 15,
    val precisionMode: Boolean = true,
    val deviceId: String = "android-sky-camera",
    val cameraLens: CameraLens = CameraLens.BACK,
    val focusMode: FocusMode = FocusMode.INFINITY,
    val whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.DAYLIGHT,
    val exposureCompensationTenthsEv: Int = -3,
    val maxImageDimension: Int = 1_920,
    val jpegQuality: Int = 85,
    val paletteColors: Int = 8,
    val paletteAnalysisDimension: Int = 180,
    val maxPendingCaptures: Int = 192,
    val maxUploadsPerCycle: Int = 4,
    val requestTimeoutSeconds: Int = 120,
    val initialRetrySeconds: Int = 60,
    val maximumRetrySeconds: Int = 3_600,
    val notifyAfterAttempts: Int = 3,
    val retentionDays: Int = 7,
    val retentionCount: Int = 672,
    val logMaxBytes: Int = 1_048_576,
    val debugEndpointOverride: String? = null,
) {
    fun requireValid(): AppConfiguration {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported configuration schema version: $schemaVersion"
        }
        UtcSchedule.validateIntervalMinutes(intervalMinutes)
        require(DEVICE_ID_PATTERN.matches(deviceId)) {
            "Device ID must contain 1-100 letters, numbers, dots, underscores, or hyphens"
        }
        require(exposureCompensationTenthsEv in -20..20) {
            "Exposure compensation must be between -20 and 20 tenths of an EV"
        }
        require(maxImageDimension in 320..8_192) {
            "Maximum image dimension must be between 320 and 8192 pixels"
        }
        require(jpegQuality in 1..95) { "JPEG quality must be between 1 and 95" }
        require(paletteColors in 3..10) { "Palette colors must be between 3 and 10" }
        require(paletteAnalysisDimension in 32..1_024) {
            "Palette analysis dimension must be between 32 and 1024 pixels"
        }
        require(maxPendingCaptures in 1..10_000) {
            "Maximum pending captures must be between 1 and 10000"
        }
        require(maxUploadsPerCycle in 1..maxPendingCaptures) {
            "Uploads per cycle must be between 1 and the pending-capture limit"
        }
        require(requestTimeoutSeconds in 5..600) {
            "Request timeout must be between 5 and 600 seconds"
        }
        require(initialRetrySeconds in 1..86_400) {
            "Initial retry must be between 1 and 86400 seconds"
        }
        require(maximumRetrySeconds in initialRetrySeconds..604_800) {
            "Maximum retry must be at least the initial retry and no more than 604800 seconds"
        }
        require(notifyAfterAttempts in 1..1_000) {
            "Notification threshold must be between 1 and 1000 attempts"
        }
        require(retentionDays in 0..3_650) { "Retention days must be between 0 and 3650" }
        require(retentionCount in 0..100_000) { "Retention count must be between 0 and 100000" }
        require(logMaxBytes in 65_536..100_000_000) {
            "Log limit must be between 65536 and 100000000 bytes"
        }
        debugEndpointOverride?.let(EndpointPolicy::requireValidOverride)
        return this
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 4
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,100}")

        fun defaults(): AppConfiguration = AppConfiguration()
    }
}
