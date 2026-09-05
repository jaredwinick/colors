package com.jaredwinick.colors.camera.outbox

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxInitializationGateTest {
    @Test
    fun `action requested during initialization runs once after success`() {
        val events = mutableListOf<String>()
        val gate = OutboxInitializationGate()

        gate.runWhenReady(
            onReady = { events += "ready" },
            onFailure = { events += "failed" },
        )
        assertEquals(emptyList<String>(), events)

        gate.completeSuccessfully()

        assertEquals(listOf("ready"), events)
    }

    @Test
    fun `action requested during initialization receives final failure`() {
        val events = mutableListOf<String>()
        val gate = OutboxInitializationGate()

        gate.runWhenReady(
            onReady = { events += "ready" },
            onFailure = { events += "failed" },
        )
        gate.completeWithFailure()

        assertEquals(listOf("failed"), events)
    }

    @Test
    fun `actions requested after completion use the stable result`() {
        val events = mutableListOf<String>()
        val gate = OutboxInitializationGate()
        gate.completeSuccessfully()

        repeat(2) {
            gate.runWhenReady(
                onReady = { events += "ready" },
                onFailure = { events += "failed" },
            )
        }

        assertEquals(listOf("ready", "ready"), events)
    }

    @Test
    fun `cancel drops deferred service actions`() {
        val events = mutableListOf<String>()
        val gate = OutboxInitializationGate()
        gate.runWhenReady(
            onReady = { events += "ready" },
            onFailure = { events += "failed" },
        )

        gate.cancel()

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `completion after service cancellation is ignored`() {
        val gate = OutboxInitializationGate()
        gate.cancel()

        gate.completeSuccessfully()

        gate.runWhenReady(
            onReady = { throw AssertionError("cancelled gate ran a deferred action") },
            onFailure = { throw AssertionError("cancelled gate reported a failure") },
        )
    }
}
