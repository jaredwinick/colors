package com.jaredwinick.colors.camera.config

import java.net.URI

object EndpointPolicy {
    fun resolve(
        productionEndpoint: String,
        debugOverride: String?,
        allowDebugOverride: Boolean,
    ): String {
        requireHttpsEndpoint(productionEndpoint)
        if (!allowDebugOverride || debugOverride.isNullOrBlank()) return productionEndpoint
        requireValidOverride(debugOverride)
        return debugOverride
    }

    fun requireValidOverride(value: String) {
        val uri = parse(value)
        require(uri.rawUserInfo == null) { "Endpoint must not contain credentials" }
        require(uri.rawFragment == null) { "Endpoint must not contain a fragment" }
        val scheme = uri.scheme.lowercase()
        val host = uri.host?.lowercase().orEmpty().trim('[', ']')
        val allowed = scheme == "https" ||
            (scheme == "http" && host in setOf("localhost", "127.0.0.1", "::1"))
        require(allowed) { "Endpoint must use HTTPS; HTTP is allowed only for loopback hosts" }
    }

    private fun requireHttpsEndpoint(value: String) {
        val uri = parse(value)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Production endpoint must use HTTPS"
        }
    }

    private fun parse(value: String): URI {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid endpoint") }
        require(uri.isAbsolute && !uri.host.isNullOrBlank()) { "Endpoint must be an absolute URL" }
        return uri
    }
}
