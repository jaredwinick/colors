package com.jaredwinick.colors.camera.camera

import com.jaredwinick.colors.camera.config.FocusMode
import com.jaredwinick.colors.camera.config.WhiteBalanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSettingsPlannerTest {
    @Test
    fun `sky defaults choose infinity daylight and mild highlight protection`() {
        val plan = CameraSettingsPlanner.plan(
            requestedFocusMode = FocusMode.INFINITY,
            requestedWhiteBalanceMode = WhiteBalanceMode.DAYLIGHT,
            requestedExposureTenthsEv = -3,
            capabilities = capabilities(),
        )

        assertEquals(AppliedFocusMode.MANUAL_INFINITY, plan.focusMode)
        assertEquals(AppliedWhiteBalanceMode.DAYLIGHT, plan.whiteBalanceMode)
        assertEquals(-1, plan.exposureCompensationIndex)
        assertEquals(-1.0 / 3.0, plan.exposureCompensationEv!!, 0.0001)
        assertTrue(plan.fallbacks.isEmpty())
        assertTrue(plan.diagnosticSummary().contains("night_extension=OFF"))
    }

    @Test
    fun `unsupported controls fall back predictably and visibly`() {
        val plan = CameraSettingsPlanner.plan(
            requestedFocusMode = FocusMode.INFINITY,
            requestedWhiteBalanceMode = WhiteBalanceMode.DAYLIGHT,
            requestedExposureTenthsEv = -3,
            capabilities = capabilities().copy(
                supportsManualInfinityFocus = false,
                supportsDaylightWhiteBalance = false,
                exposureCompensation = null,
            ),
        )

        assertEquals(AppliedFocusMode.CONTINUOUS_AUTO, plan.focusMode)
        assertEquals(AppliedWhiteBalanceMode.AUTO, plan.whiteBalanceMode)
        assertEquals(null, plan.exposureCompensationIndex)
        assertEquals(
            listOf(
                "INFINITY_FOCUS_UNAVAILABLE",
                "DAYLIGHT_WHITE_BALANCE_UNAVAILABLE",
                "EXPOSURE_COMPENSATION_UNAVAILABLE",
            ),
            plan.fallbacks,
        )
    }

    @Test
    fun `exposure compensation is clamped to camera range`() {
        val plan = CameraSettingsPlanner.plan(
            requestedFocusMode = FocusMode.CONTINUOUS_AUTO,
            requestedWhiteBalanceMode = WhiteBalanceMode.AUTO,
            requestedExposureTenthsEv = -20,
            capabilities = capabilities().copy(
                exposureCompensation = ExposureCompensationSupport(-3, 3, 1, 3),
            ),
        )

        assertEquals(-3, plan.exposureCompensationIndex)
        assertEquals(-1.0, plan.exposureCompensationEv!!, 0.0001)
        assertTrue("EXPOSURE_COMPENSATION_CLAMPED" in plan.fallbacks)
    }

    private fun capabilities() = CameraCapabilitySnapshot(
        supportsManualInfinityFocus = true,
        hasFixedInfinityFocus = false,
        supportsContinuousAutoFocus = true,
        supportsDaylightWhiteBalance = true,
        supportsAutoWhiteBalance = true,
        exposureCompensation = ExposureCompensationSupport(-12, 12, 1, 3),
    )
}
