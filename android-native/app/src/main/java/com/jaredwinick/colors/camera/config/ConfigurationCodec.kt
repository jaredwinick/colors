package com.jaredwinick.colors.camera.config

object ConfigurationCodec {
    fun encode(configuration: AppConfiguration): Map<String, String> {
        configuration.requireValid()
        return buildMap {
            put(Keys.SCHEMA_VERSION, configuration.schemaVersion.toString())
            put(Keys.INTERVAL_MINUTES, configuration.intervalMinutes.toString())
            put(Keys.PRECISION_MODE, configuration.precisionMode.toString())
            put(Keys.DEVICE_ID, configuration.deviceId)
            put(Keys.CAMERA_LENS, configuration.cameraLens.name)
            put(Keys.FOCUS_MODE, configuration.focusMode.name)
            put(Keys.WHITE_BALANCE_MODE, configuration.whiteBalanceMode.name)
            put(
                Keys.EXPOSURE_COMPENSATION_TENTHS_EV,
                configuration.exposureCompensationTenthsEv.toString(),
            )
            put(Keys.MAX_IMAGE_DIMENSION, configuration.maxImageDimension.toString())
            put(Keys.JPEG_QUALITY, configuration.jpegQuality.toString())
            put(Keys.PALETTE_COLORS, configuration.paletteColors.toString())
            put(Keys.PALETTE_ANALYSIS_DIMENSION, configuration.paletteAnalysisDimension.toString())
            put(Keys.MAX_PENDING_CAPTURES, configuration.maxPendingCaptures.toString())
            put(Keys.MAX_UPLOADS_PER_CYCLE, configuration.maxUploadsPerCycle.toString())
            put(Keys.REQUEST_TIMEOUT_SECONDS, configuration.requestTimeoutSeconds.toString())
            put(Keys.INITIAL_RETRY_SECONDS, configuration.initialRetrySeconds.toString())
            put(Keys.MAXIMUM_RETRY_SECONDS, configuration.maximumRetrySeconds.toString())
            put(Keys.NOTIFY_AFTER_ATTEMPTS, configuration.notifyAfterAttempts.toString())
            put(Keys.RETENTION_DAYS, configuration.retentionDays.toString())
            put(Keys.RETENTION_COUNT, configuration.retentionCount.toString())
            put(Keys.LOG_MAX_BYTES, configuration.logMaxBytes.toString())
            configuration.debugEndpointOverride?.let { put(Keys.DEBUG_ENDPOINT_OVERRIDE, it) }
        }
    }

    fun decode(stored: Map<String, String>): AppConfiguration {
        val values = ConfigurationMigration.migrate(stored)
        val defaults = AppConfiguration.defaults()
        return AppConfiguration(
            schemaVersion = values.int(Keys.SCHEMA_VERSION, defaults.schemaVersion),
            intervalMinutes = values.int(Keys.INTERVAL_MINUTES, defaults.intervalMinutes),
            precisionMode = values.boolean(Keys.PRECISION_MODE, defaults.precisionMode),
            deviceId = values[Keys.DEVICE_ID] ?: defaults.deviceId,
            cameraLens = values[Keys.CAMERA_LENS]
                ?.let { runCatching { CameraLens.valueOf(it) }.getOrNull() }
                ?: defaults.cameraLens,
            focusMode = values[Keys.FOCUS_MODE]
                ?.let { runCatching { FocusMode.valueOf(it) }.getOrNull() }
                ?: defaults.focusMode,
            whiteBalanceMode = values[Keys.WHITE_BALANCE_MODE]
                ?.let { runCatching { WhiteBalanceMode.valueOf(it) }.getOrNull() }
                ?: defaults.whiteBalanceMode,
            exposureCompensationTenthsEv = values.int(
                Keys.EXPOSURE_COMPENSATION_TENTHS_EV,
                defaults.exposureCompensationTenthsEv,
            ),
            maxImageDimension = values.int(Keys.MAX_IMAGE_DIMENSION, defaults.maxImageDimension),
            jpegQuality = values.int(Keys.JPEG_QUALITY, defaults.jpegQuality),
            paletteColors = values.int(Keys.PALETTE_COLORS, defaults.paletteColors),
            paletteAnalysisDimension = values.int(
                Keys.PALETTE_ANALYSIS_DIMENSION,
                defaults.paletteAnalysisDimension,
            ),
            maxPendingCaptures = values.int(Keys.MAX_PENDING_CAPTURES, defaults.maxPendingCaptures),
            maxUploadsPerCycle = values.int(
                Keys.MAX_UPLOADS_PER_CYCLE,
                defaults.maxUploadsPerCycle,
            ),
            requestTimeoutSeconds = values.int(
                Keys.REQUEST_TIMEOUT_SECONDS,
                defaults.requestTimeoutSeconds,
            ),
            initialRetrySeconds = values.int(Keys.INITIAL_RETRY_SECONDS, defaults.initialRetrySeconds),
            maximumRetrySeconds = values.int(Keys.MAXIMUM_RETRY_SECONDS, defaults.maximumRetrySeconds),
            notifyAfterAttempts = values.int(Keys.NOTIFY_AFTER_ATTEMPTS, defaults.notifyAfterAttempts),
            retentionDays = values.int(Keys.RETENTION_DAYS, defaults.retentionDays),
            retentionCount = values.int(Keys.RETENTION_COUNT, defaults.retentionCount),
            logMaxBytes = values.int(Keys.LOG_MAX_BYTES, defaults.logMaxBytes),
            debugEndpointOverride = values[Keys.DEBUG_ENDPOINT_OVERRIDE]?.takeIf(String::isNotBlank),
        ).requireValid()
    }

    object Keys {
        const val SCHEMA_VERSION = "schema_version"
        const val INTERVAL_MINUTES = "interval_minutes"
        const val PRECISION_MODE = "precision_mode"
        const val DEVICE_ID = "device_id"
        const val CAMERA_LENS = "camera_lens"
        const val FOCUS_MODE = "focus_mode"
        const val WHITE_BALANCE_MODE = "white_balance_mode"
        const val EXPOSURE_COMPENSATION_TENTHS_EV = "exposure_compensation_tenths_ev"
        const val MAX_IMAGE_DIMENSION = "max_image_dimension"
        const val JPEG_QUALITY = "jpeg_quality"
        const val PALETTE_COLORS = "palette_colors"
        const val PALETTE_ANALYSIS_DIMENSION = "palette_analysis_dimension"
        const val MAX_PENDING_CAPTURES = "max_pending_captures"
        const val MAX_UPLOADS_PER_CYCLE = "max_uploads_per_cycle"
        const val REQUEST_TIMEOUT_SECONDS = "request_timeout_seconds"
        const val INITIAL_RETRY_SECONDS = "initial_retry_seconds"
        const val MAXIMUM_RETRY_SECONDS = "maximum_retry_seconds"
        const val NOTIFY_AFTER_ATTEMPTS = "notify_after_attempts"
        const val RETENTION_DAYS = "retention_days"
        const val RETENTION_COUNT = "retention_count"
        const val LOG_MAX_BYTES = "log_max_bytes"
        const val DEBUG_ENDPOINT_OVERRIDE = "debug_endpoint_override"
    }

    private fun Map<String, String>.int(key: String, default: Int): Int =
        get(key)?.toIntOrNull() ?: default

    private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean =
        get(key)?.toBooleanStrictOrNull() ?: default
}

object ConfigurationMigration {
    fun migrate(stored: Map<String, String>): Map<String, String> {
        if (stored.isEmpty()) return ConfigurationCodec.encode(AppConfiguration.defaults())
        val version = stored[ConfigurationCodec.Keys.SCHEMA_VERSION]?.toIntOrNull() ?: 1
        require(version <= AppConfiguration.CURRENT_SCHEMA_VERSION) {
            "Configuration schema $version is newer than this application supports"
        }
        val migrated = stored.toMutableMap()
        if (version < 2) {
            val defaults = ConfigurationCodec.encode(AppConfiguration.defaults())
            defaults.forEach { (key, value) -> migrated.putIfAbsent(key, value) }
            migrated[ConfigurationCodec.Keys.SCHEMA_VERSION] = "2"
        }
        if (version < 3) {
            val defaults = ConfigurationCodec.encode(AppConfiguration.defaults())
            listOf(
                ConfigurationCodec.Keys.FOCUS_MODE,
                ConfigurationCodec.Keys.WHITE_BALANCE_MODE,
                ConfigurationCodec.Keys.EXPOSURE_COMPENSATION_TENTHS_EV,
            ).forEach { key -> migrated.putIfAbsent(key, defaults.getValue(key)) }
            migrated[ConfigurationCodec.Keys.SCHEMA_VERSION] = "3"
        }
        return migrated
    }
}
