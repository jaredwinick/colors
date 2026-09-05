package com.jaredwinick.colors.camera.camera

import org.json.JSONArray
import org.json.JSONObject

data class StagedCaptureContext(
    val selectedCamera: String,
    val cameraId: String,
    val appliedSettings: AppliedCameraSettings,
) {
    fun toJson(): String = JSONObject().apply {
        put("kind", KIND)
        put("schema_version", SCHEMA_VERSION)
        put("selected_camera", selectedCamera)
        put("camera_id", cameraId)
        put("focus_mode", appliedSettings.focusMode.name)
        put("white_balance_mode", appliedSettings.whiteBalanceMode.name)
        put("exposure_compensation_index", appliedSettings.exposureCompensationIndex ?: JSONObject.NULL)
        put("exposure_compensation_ev", appliedSettings.exposureCompensationEv ?: JSONObject.NULL)
        put("fallbacks", JSONArray(appliedSettings.fallbacks))
    }.toString()

    companion object {
        private const val KIND = "staged_capture_context"
        private const val SCHEMA_VERSION = 1

        fun fromJsonOrFallback(
            value: String?,
            selectedCameraFallback: String,
        ): StagedCaptureContext = runCatching {
            val json = JSONObject(requireNotNull(value))
            require(json.getString("kind") == KIND)
            require(json.getInt("schema_version") == SCHEMA_VERSION)
            val fallbacks = json.getJSONArray("fallbacks")
            StagedCaptureContext(
                selectedCamera = json.getString("selected_camera"),
                cameraId = json.getString("camera_id"),
                appliedSettings = AppliedCameraSettings(
                    focusMode = AppliedFocusMode.valueOf(json.getString("focus_mode")),
                    whiteBalanceMode = AppliedWhiteBalanceMode.valueOf(
                        json.getString("white_balance_mode"),
                    ),
                    exposureCompensationIndex = json.nullableInt("exposure_compensation_index"),
                    exposureCompensationEv = json.nullableDouble("exposure_compensation_ev"),
                    fallbacks = buildList {
                        repeat(fallbacks.length()) { index -> add(fallbacks.getString(index)) }
                    },
                ),
            )
        }.getOrElse {
            StagedCaptureContext(
                selectedCamera = selectedCameraFallback,
                cameraId = "RECOVERED_UNKNOWN",
                appliedSettings = AppliedCameraSettings(
                    focusMode = AppliedFocusMode.CAMERA_DEFAULT,
                    whiteBalanceMode = AppliedWhiteBalanceMode.CAMERA_DEFAULT,
                    exposureCompensationIndex = null,
                    exposureCompensationEv = null,
                    fallbacks = listOf("STAGED_CONTEXT_UNAVAILABLE"),
                ),
            )
        }

        private fun JSONObject.nullableInt(key: String): Int? =
            if (!has(key) || isNull(key)) null else getInt(key)

        private fun JSONObject.nullableDouble(key: String): Double? =
            if (!has(key) || isNull(key)) null else getDouble(key)
    }
}
