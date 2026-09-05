package com.jaredwinick.colors.camera.diagnostics

import com.jaredwinick.colors.camera.config.AppConfiguration
import com.jaredwinick.colors.camera.config.ConfigurationCodec
import com.jaredwinick.colors.camera.outbox.OutboxSummary
import com.jaredwinick.colors.camera.outbox.SafeCaptureRecord
import org.json.JSONArray
import org.json.JSONObject

object OperatorExports {
    fun configuration(
        configuration: AppConfiguration,
        effectiveEndpoint: String,
        tokenConfigured: Boolean,
    ): String = JSONObject().apply {
        val safeConfiguration = ConfigurationCodec.encode(configuration)
            .filterKeys { it != ConfigurationCodec.Keys.DEBUG_ENDPOINT_OVERRIDE }
        put("export_schema_version", 1)
        put("credential_configured", tokenConfigured)
        put("effective_endpoint", effectiveEndpoint.substringBefore('?'))
        put("configuration", JSONObject(safeConfiguration))
    }.toString(2) + "\n"

    fun queue(summary: OutboxSummary, records: List<SafeCaptureRecord>): String =
        JSONObject().apply {
            put("export_schema_version", 1)
            put("summary", JSONObject().apply {
                put("staged", summary.staged)
                put("processing", summary.processing)
                put("pending", summary.pending)
                put("delivered", summary.delivered)
                put("attention_required", summary.attentionRequired)
                put("conflicts", summary.conflicts)
                put("oldest_pending_at", summary.oldestPendingAt ?: JSONObject.NULL)
                put("storage_bytes", summary.storageBytes)
            })
            put("captures", JSONArray().apply {
                records.forEach { record -> put(record.toJson()) }
            })
        }.toString(2) + "\n"

    fun redactedDiagnostics(
        records: List<CaptureDiagnostic>,
        maximumBytes: Int,
    ): String {
        require(maximumBytes >= 1)
        val selected = ArrayDeque<String>()
        var bytes = 0
        records.sortedByDescending { it.scheduledFor }.forEach { record ->
            val line = record.toRedactedJson().toString() + "\n"
            val lineBytes = line.toByteArray(Charsets.UTF_8).size
            if (bytes + lineBytes <= maximumBytes) {
                selected.addFirst(line)
                bytes += lineBytes
            }
        }
        return selected.joinToString("")
    }

    private fun SafeCaptureRecord.toJson(): JSONObject = JSONObject().apply {
        put("capture_id", captureId)
        put("state", state.name)
        put("captured_at", capturedAt)
        put("bytes", byteCount)
        put("attempt_count", attemptCount)
        put("last_attempt_at", lastAttemptAt ?: JSONObject.NULL)
        put("last_error_code", lastErrorCode ?: JSONObject.NULL)
        put("next_eligible_retry_at", nextEligibleRetryAt ?: JSONObject.NULL)
        put("delivered_at", deliveredAt ?: JSONObject.NULL)
    }

    private fun CaptureDiagnostic.toRedactedJson(): JSONObject = JSONObject().apply {
        put("record_id", recordId)
        put("capture_id", captureId)
        put("session_id", sessionId)
        put("slot_id", slotId)
        put("scheduled_for_ms", scheduledFor)
        put("trigger_source", triggerSource)
        put("alarm_received_at_ms", alarmReceivedAt)
        put("service_received_at_ms", serviceReceivedAt)
        put("capture_started_at_ms", captureStartedAt ?: JSONObject.NULL)
        put("captured_at_ms", capturedAt ?: JSONObject.NULL)
        put("completed_at_ms", completedAt ?: JSONObject.NULL)
        put("result", result)
        put("error_code", errorCode ?: JSONObject.NULL)
        put("screen_interactive", screenInteractive)
        put("charging", charging)
        put("plugged", plugged)
        put("battery_percent", batteryPercent ?: JSONObject.NULL)
        put("device_idle_mode", deviceIdleMode)
        put("power_save_mode", powerSaveMode)
        put("station_wake_lock_held", stationWakeLockHeld)
        put("image_bytes", imageBytes ?: JSONObject.NULL)
        put("processing_duration_ms", processingDurationMs ?: JSONObject.NULL)
        put("camera_settings", cameraSettings ?: JSONObject.NULL)
        put("palette", palette ?: JSONObject.NULL)
        put("upload_attempted", uploadAttempted ?: JSONObject.NULL)
        put("upload_delivered", uploadDelivered ?: JSONObject.NULL)
        put("upload_retried", uploadRetried ?: JSONObject.NULL)
        put("upload_attention_required", uploadAttentionRequired ?: JSONObject.NULL)
        put("upload_error_code", uploadErrorCode ?: JSONObject.NULL)
        put("cycle_action", cycleAction ?: JSONObject.NULL)
        put("recovered_staged_capture_id", recoveredStagedCaptureId ?: JSONObject.NULL)
        put("outbox_staged_after_cycle", outboxStagedAfterCycle ?: JSONObject.NULL)
        put("outbox_pending_after_cycle", outboxPendingAfterCycle ?: JSONObject.NULL)
        put("manual", manual)
    }
}
