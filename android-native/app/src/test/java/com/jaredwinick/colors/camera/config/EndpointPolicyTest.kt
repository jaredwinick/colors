package com.jaredwinick.colors.camera.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointPolicyTest {
    private val production = "https://example.workers.dev/api/ingest"

    @Test
    fun `release builds always use the production endpoint`() {
        assertEquals(
            production,
            EndpointPolicy.resolve(production, "https://staging.example/api", false),
        )
    }

    @Test
    fun `debug builds allow HTTPS and loopback HTTP overrides`() {
        assertEquals(
            "https://staging.example/api",
            EndpointPolicy.resolve(production, "https://staging.example/api", true),
        )
        assertEquals(
            "http://127.0.0.1:8787/api/ingest",
            EndpointPolicy.resolve(production, "http://127.0.0.1:8787/api/ingest", true),
        )
    }

    @Test
    fun `plain HTTP is rejected for non-loopback hosts`() {
        assertThrows(IllegalArgumentException::class.java) {
            EndpointPolicy.resolve(production, "http://192.168.1.10:8787/api/ingest", true)
        }
    }
}
