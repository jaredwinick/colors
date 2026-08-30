package com.jaredwinick.colors.camera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageCapture
import com.jaredwinick.colors.camera.config.AppConfiguration

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
object CameraControlConfigurator {
    fun configure(
        builder: ImageCapture.Builder,
        cameraInfo: CameraInfo,
        configuration: AppConfiguration,
    ): AppliedCameraSettings {
        val camera2Info = Camera2CameraInfo.from(cameraInfo)
        val autofocusModes = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: intArrayOf()
        val autofocusModeSet = autofocusModes
            .toSet()
        val whiteBalanceModes = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?: intArrayOf()
        val whiteBalanceModeSet = whiteBalanceModes
            .toSet()
        val capabilities = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        val capabilitySet = capabilities
            .toSet()
        val minimumFocusDistance = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val exposureRange = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val exposureStep = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)

        val plan = CameraSettingsPlanner.plan(
            requestedFocusMode = configuration.focusMode,
            requestedWhiteBalanceMode = configuration.whiteBalanceMode,
            requestedExposureTenthsEv = configuration.exposureCompensationTenthsEv,
            capabilities = CameraCapabilitySnapshot(
                supportsManualInfinityFocus =
                    CameraMetadata.CONTROL_AF_MODE_OFF in autofocusModeSet &&
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilitySet &&
                        minimumFocusDistance != null && minimumFocusDistance > 0f,
                hasFixedInfinityFocus = minimumFocusDistance != null && minimumFocusDistance == 0f,
                supportsContinuousAutoFocus =
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in autofocusModeSet,
                supportsDaylightWhiteBalance =
                    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT in whiteBalanceModeSet,
                supportsAutoWhiteBalance = CameraMetadata.CONTROL_AWB_MODE_AUTO in whiteBalanceModeSet,
                exposureCompensation = if (exposureRange != null && exposureStep != null) {
                    ExposureCompensationSupport(
                        minimumIndex = exposureRange.lower,
                        maximumIndex = exposureRange.upper,
                        stepNumerator = exposureStep.numerator,
                        stepDenominator = exposureStep.denominator,
                    )
                } else {
                    null
                },
            ),
        )

        val extender = Camera2Interop.Extender(builder)
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        extender.setCaptureRequestOption(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_EFFECT_MODE,
            CameraMetadata.CONTROL_EFFECT_MODE_OFF,
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_SCENE_MODE,
            CameraMetadata.CONTROL_SCENE_MODE_DISABLED,
        )
        when (plan.focusMode) {
            AppliedFocusMode.MANUAL_INFINITY -> {
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_OFF,
                )
                extender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }
            AppliedFocusMode.FIXED_INFINITY -> extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_OFF,
            )
            AppliedFocusMode.CONTINUOUS_AUTO -> extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            AppliedFocusMode.CAMERA_DEFAULT -> Unit
        }
        when (plan.whiteBalanceMode) {
            AppliedWhiteBalanceMode.DAYLIGHT -> extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            )
            AppliedWhiteBalanceMode.AUTO -> extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_AUTO,
            )
            AppliedWhiteBalanceMode.CAMERA_DEFAULT -> Unit
        }
        plan.exposureCompensationIndex?.let { index ->
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
        }
        return plan
    }
}
