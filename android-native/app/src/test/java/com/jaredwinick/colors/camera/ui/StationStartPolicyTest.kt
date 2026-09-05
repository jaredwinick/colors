package com.jaredwinick.colors.camera.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StationStartPolicyTest {
    @Test
    fun `stopped station starts without a restart confirmation`() {
        assertEquals(
            StationStartDecision.START,
            StationStartPolicy.decide(false, 15, true, 30, false),
        )
    }

    @Test
    fun `running station with unchanged schedule is not restarted`() {
        assertEquals(
            StationStartDecision.ALREADY_RUNNING,
            StationStartPolicy.decide(true, 15, true, 15, true),
        )
    }

    @Test
    fun `running station requires confirmation before schedule replacement`() {
        assertEquals(
            StationStartDecision.CONFIRM_RESTART,
            StationStartPolicy.decide(true, 15, true, 30, true),
        )
        assertEquals(
            StationStartDecision.CONFIRM_RESTART,
            StationStartPolicy.decide(true, 15, true, 15, false),
        )
    }
}
