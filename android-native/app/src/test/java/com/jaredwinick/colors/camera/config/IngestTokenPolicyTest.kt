package com.jaredwinick.colors.camera.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IngestTokenPolicyTest {
    @Test
    fun `token status never contains token material`() {
        val token = "this-is-a-production-secret"

        assertEquals("configured", IngestTokenPolicy.status(token))
        assertEquals("not configured", IngestTokenPolicy.status(null))
    }

    @Test
    fun `multiline and whitespace padded tokens are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IngestTokenPolicy.requireValid(" this-is-a-production-secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            IngestTokenPolicy.requireValid("this-is-a-production\nsecret")
        }
    }
}
