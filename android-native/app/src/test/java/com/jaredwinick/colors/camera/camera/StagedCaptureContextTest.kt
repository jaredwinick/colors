package com.jaredwinick.colors.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StagedCaptureContextTest {
    @Test
    fun `staged camera context survives a processing restart`() {
        val expected = StagedCaptureContext(
            selectedCamera = "BACK",
            cameraId = "0",
            appliedSettings = AppliedCameraSettings(
                focusMode = AppliedFocusMode.MANUAL_INFINITY,
                whiteBalanceMode = AppliedWhiteBalanceMode.DAYLIGHT,
                exposureCompensationIndex = -1,
                exposureCompensationEv = -0.333,
                fallbacks = listOf("EXPOSURE_COMPENSATION_CLAMPED"),
            ),
        )

        val restored = StagedCaptureContext.fromJsonOrFallback(expected.toJson(), "FRONT")

        assertEquals(expected, restored)
    }

    @Test
    fun `legacy staged work receives explicit safe fallback settings`() {
        val restored = StagedCaptureContext.fromJsonOrFallback(null, "BACK")

        assertEquals("BACK", restored.selectedCamera)
        assertEquals("RECOVERED_UNKNOWN", restored.cameraId)
        assertEquals(AppliedFocusMode.CAMERA_DEFAULT, restored.appliedSettings.focusMode)
        assertNull(restored.appliedSettings.exposureCompensationIndex)
        assertEquals(listOf("STAGED_CONTEXT_UNAVAILABLE"), restored.appliedSettings.fallbacks)
    }
}
