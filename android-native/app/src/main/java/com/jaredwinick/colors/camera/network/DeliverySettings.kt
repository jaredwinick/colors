package com.jaredwinick.colors.camera.network

import com.jaredwinick.colors.camera.config.AppConfiguration

data class DeliverySettings(
    val deviceId: String,
    val maxPendingCaptures: Int,
    val maxUploadsPerCycle: Int,
    val requestTimeoutSeconds: Int,
    val initialRetrySeconds: Int,
    val maximumRetrySeconds: Int,
    val notifyAfterAttempts: Int,
    val retentionDays: Int,
    val retentionCount: Int,
)

fun AppConfiguration.deliverySettings(): DeliverySettings = DeliverySettings(
    deviceId = deviceId,
    maxPendingCaptures = maxPendingCaptures,
    maxUploadsPerCycle = maxUploadsPerCycle,
    requestTimeoutSeconds = requestTimeoutSeconds,
    initialRetrySeconds = initialRetrySeconds,
    maximumRetrySeconds = maximumRetrySeconds,
    notifyAfterAttempts = notifyAfterAttempts,
    retentionDays = retentionDays,
    retentionCount = retentionCount,
)
