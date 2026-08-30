package com.jaredwinick.colors.camera.camera

import com.jaredwinick.colors.camera.config.FocusMode
import com.jaredwinick.colors.camera.config.WhiteBalanceMode
import java.util.Locale
import kotlin.math.roundToInt

data class ExposureCompensationSupport(
    val minimumIndex: Int,
    val maximumIndex: Int,
    val stepNumerator: Int,
    val stepDenominator: Int,
)

data class CameraCapabilitySnapshot(
    val supportsManualInfinityFocus: Boolean,
    val hasFixedInfinityFocus: Boolean,
    val supportsContinuousAutoFocus: Boolean,
    val supportsDaylightWhiteBalance: Boolean,
    val supportsAutoWhiteBalance: Boolean,
    val exposureCompensation: ExposureCompensationSupport?,
)

enum class AppliedFocusMode {
    MANUAL_INFINITY,
    FIXED_INFINITY,
    CONTINUOUS_AUTO,
    CAMERA_DEFAULT,
}

enum class AppliedWhiteBalanceMode {
    DAYLIGHT,
    AUTO,
    CAMERA_DEFAULT,
}

data class AppliedCameraSettings(
    val focusMode: AppliedFocusMode,
    val whiteBalanceMode: AppliedWhiteBalanceMode,
    val exposureCompensationIndex: Int?,
    val exposureCompensationEv: Double?,
    val fallbacks: List<String>,
) {
    fun diagnosticSummary(): String = buildString {
        append("focus=${focusMode.name}")
        append(";white_balance=${whiteBalanceMode.name}")
        append(";exposure_index=${exposureCompensationIndex ?: "default"}")
        append(
            ";exposure_ev=${exposureCompensationEv?.let { String.format(Locale.US, "%.3f", it) } ?: "default"}",
        )
        append(";fallbacks=${fallbacks.ifEmpty { listOf("none") }.joinToString("+")}")
        append(";flash=OFF;scene=DISABLED;night_extension=OFF")
    }
}

object CameraSettingsPlanner {
    fun plan(
        requestedFocusMode: FocusMode,
        requestedWhiteBalanceMode: WhiteBalanceMode,
        requestedExposureTenthsEv: Int,
        capabilities: CameraCapabilitySnapshot,
    ): AppliedCameraSettings {
        val fallbacks = mutableListOf<String>()
        val focus = when (requestedFocusMode) {
            FocusMode.INFINITY -> when {
                capabilities.hasFixedInfinityFocus -> AppliedFocusMode.FIXED_INFINITY
                capabilities.supportsManualInfinityFocus -> AppliedFocusMode.MANUAL_INFINITY
                capabilities.supportsContinuousAutoFocus -> {
                    fallbacks += "INFINITY_FOCUS_UNAVAILABLE"
                    AppliedFocusMode.CONTINUOUS_AUTO
                }
                else -> {
                    fallbacks += "FOCUS_CONTROL_UNAVAILABLE"
                    AppliedFocusMode.CAMERA_DEFAULT
                }
            }
            FocusMode.CONTINUOUS_AUTO -> when {
                capabilities.supportsContinuousAutoFocus -> AppliedFocusMode.CONTINUOUS_AUTO
                capabilities.hasFixedInfinityFocus -> {
                    fallbacks += "CONTINUOUS_AF_UNAVAILABLE"
                    AppliedFocusMode.FIXED_INFINITY
                }
                capabilities.supportsManualInfinityFocus -> {
                    fallbacks += "CONTINUOUS_AF_UNAVAILABLE"
                    AppliedFocusMode.MANUAL_INFINITY
                }
                else -> {
                    fallbacks += "FOCUS_CONTROL_UNAVAILABLE"
                    AppliedFocusMode.CAMERA_DEFAULT
                }
            }
        }

        val whiteBalance = when (requestedWhiteBalanceMode) {
            WhiteBalanceMode.DAYLIGHT -> when {
                capabilities.supportsDaylightWhiteBalance -> AppliedWhiteBalanceMode.DAYLIGHT
                capabilities.supportsAutoWhiteBalance -> {
                    fallbacks += "DAYLIGHT_WHITE_BALANCE_UNAVAILABLE"
                    AppliedWhiteBalanceMode.AUTO
                }
                else -> {
                    fallbacks += "WHITE_BALANCE_CONTROL_UNAVAILABLE"
                    AppliedWhiteBalanceMode.CAMERA_DEFAULT
                }
            }
            WhiteBalanceMode.AUTO -> when {
                capabilities.supportsAutoWhiteBalance -> AppliedWhiteBalanceMode.AUTO
                capabilities.supportsDaylightWhiteBalance -> {
                    fallbacks += "AUTO_WHITE_BALANCE_UNAVAILABLE"
                    AppliedWhiteBalanceMode.DAYLIGHT
                }
                else -> {
                    fallbacks += "WHITE_BALANCE_CONTROL_UNAVAILABLE"
                    AppliedWhiteBalanceMode.CAMERA_DEFAULT
                }
            }
        }

        val exposure = capabilities.exposureCompensation
        val exposureIndex: Int?
        val exposureEv: Double?
        if (exposure == null || exposure.stepNumerator <= 0 || exposure.stepDenominator <= 0) {
            exposureIndex = null
            exposureEv = null
            if (requestedExposureTenthsEv != 0) fallbacks += "EXPOSURE_COMPENSATION_UNAVAILABLE"
        } else {
            val requestedIndex = (
                requestedExposureTenthsEv * exposure.stepDenominator.toDouble() /
                    (10.0 * exposure.stepNumerator)
                ).roundToInt()
            exposureIndex = requestedIndex.coerceIn(exposure.minimumIndex, exposure.maximumIndex)
            exposureEv = exposureIndex * exposure.stepNumerator.toDouble() / exposure.stepDenominator
            if (exposureIndex != requestedIndex) fallbacks += "EXPOSURE_COMPENSATION_CLAMPED"
        }

        return AppliedCameraSettings(
            focusMode = focus,
            whiteBalanceMode = whiteBalance,
            exposureCompensationIndex = exposureIndex,
            exposureCompensationEv = exposureEv,
            fallbacks = fallbacks,
        )
    }
}
