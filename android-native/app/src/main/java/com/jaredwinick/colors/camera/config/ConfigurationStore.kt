package com.jaredwinick.colors.camera.config

import android.content.Context

class ConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): AppConfiguration {
        val stored = preferences.all.mapValues { (_, value) -> value.toString() }
        return runCatching { ConfigurationCodec.decode(stored) }
            .fold(
                onSuccess = { configuration ->
                    if (ConfigurationCodec.encode(configuration) != stored) save(configuration)
                    configuration
                },
                // Preserve invalid or future-schema data for diagnosis. Safe
                // defaults keep the app operable without overwriting it.
                onFailure = { AppConfiguration.defaults() },
            )
    }

    fun save(configuration: AppConfiguration) {
        val values = ConfigurationCodec.encode(configuration)
        preferences.edit().clear().apply {
            values.forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    companion object {
        private const val FILE_NAME = "app_configuration"
    }
}
