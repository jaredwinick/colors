package com.jaredwinick.colors.camera.persistence

import android.content.Context
import com.jaredwinick.colors.camera.diagnostics.CaptureDiagnostic
import org.json.JSONObject
import java.io.File

class DiagnosticStore(context: Context) {
    private val diagnosticsDirectory = File(context.filesDir, "diagnostics")
    private val recordsFile = File(diagnosticsDirectory, "captures.jsonl")
    private val pendingDirectory = File(diagnosticsDirectory, "pending")

    init {
        diagnosticsDirectory.mkdirs()
        pendingDirectory.mkdirs()
    }

    @Synchronized
    fun begin(record: CaptureDiagnostic) {
        writeAtomically(pendingFile(record.recordId), record.toJson().toString())
    }

    @Synchronized
    fun complete(record: CaptureDiagnostic) {
        if (!containsRecord(record.recordId)) {
            recordsFile.appendText(record.toJson().toString() + "\n")
        }
        pendingFile(record.recordId).delete()
    }

    @Synchronized
    fun recoverInterrupted(nowMillis: Long = System.currentTimeMillis()) {
        pendingDirectory.listFiles { file -> file.extension == "json" }.orEmpty().forEach { file ->
            val pending = runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()
            if (pending == null) {
                file.delete()
                return@forEach
            }
            complete(
                pending.copy(
                    completedAt = nowMillis,
                    result = "ERROR",
                    errorCode = "PROCESS_INTERRUPTED",
                ),
            )
        }
    }

    @Synchronized
    fun records(): List<CaptureDiagnostic> {
        val completed = if (!recordsFile.exists()) emptyList() else recordsFile.useLines { lines ->
            lines.filter { it.isNotBlank() }
                .mapNotNull { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
                .toList()
        }
        val completedIds = completed.mapTo(mutableSetOf()) { it.recordId }
        return completed + pendingRecords().filter { it.recordId !in completedIds }
    }

    private fun pendingRecords(): List<CaptureDiagnostic> =
        pendingDirectory.listFiles { file -> file.extension == "json" }.orEmpty().mapNotNull { file ->
            runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()
        }

    private fun pendingFile(recordId: String) = File(pendingDirectory, "$recordId.json")

    private fun containsRecord(recordId: String): Boolean {
        if (!recordsFile.exists()) return false
        return recordsFile.useLines { lines ->
            lines.any { line ->
                runCatching { JSONObject(line).optString("record_id") == recordId }.getOrDefault(false)
            }
        }
    }

    private fun writeAtomically(target: File, content: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(target)) {
            target.writeText(content)
            temporary.delete()
        }
    }

    private fun CaptureDiagnostic.toJson(): JSONObject = JSONObject().apply {
        put("record_id", recordId)
        put("capture_id", captureId)
        put("session_id", sessionId)
        put("slot_id", slotId)
        put("scheduled_for", scheduledFor)
        put("alarm_received_at", alarmReceivedAt)
        put("trigger_source", triggerSource)
        put("service_received_at", serviceReceivedAt)
        put("service_dispatch_ms", serviceDispatchMs)
        putNullable("capture_started_at", captureStartedAt)
        putNullable("captured_at", capturedAt)
        putNullable("completed_at", completedAt)
        put("alarm_lateness_ms", alarmLatenessMs)
        putNullable("capture_lateness_ms", captureLatenessMs)
        put("result", result)
        putNullable("error_code", errorCode)
        put("screen_interactive", screenInteractive)
        put("charging", charging)
        put("plugged", plugged)
        putNullable("battery_percent", batteryPercent)
        put("device_idle_mode", deviceIdleMode)
        put("power_save_mode", powerSaveMode)
        put("battery_optimization_exempt", batteryOptimizationExempt)
        put("station_wake_lock_held", stationWakeLockHeld)
        putNullable("image_path", imagePath)
        putNullable("image_bytes", imageBytes)
        putNullable("source_width", sourceWidth)
        putNullable("source_height", sourceHeight)
        putNullable("width", width)
        putNullable("height", height)
        putNullable("processing_duration_ms", processingDurationMs)
        putNullable("camera_settings", cameraSettings)
        putNullable("palette", palette)
        putNullable("palette_size", paletteSize)
        putNullable("palette_analysis_width", paletteAnalysisWidth)
        putNullable("palette_analysis_height", paletteAnalysisHeight)
        putNullable("palette_included_pixels", paletteIncludedPixels)
        putNullable("palette_duration_ms", paletteDurationMs)
        putNullable("palette_peak_pss_kib", palettePeakPssKib)
        putNullable("outbox_pending", outboxPending)
        putNullable("outbox_attention", outboxAttention)
        putNullable("outbox_oldest_age_ms", outboxOldestAgeMs)
        putNullable("outbox_storage_bytes", outboxStorageBytes)
        putNullable("upload_attempted", uploadAttempted)
        putNullable("upload_delivered", uploadDelivered)
        putNullable("upload_retried", uploadRetried)
        putNullable("upload_attention_required", uploadAttentionRequired)
        putNullable("upload_deferred", uploadDeferred)
        putNullable("outbox_pending_after_upload", outboxPendingAfterUpload)
        putNullable("upload_error_code", uploadErrorCode)
        putNullable("cycle_action", cycleAction)
        putNullable("recovered_staged_capture_id", recoveredStagedCaptureId)
        putNullable("pre_upload_attempted", preUploadAttempted)
        putNullable("post_upload_attempted", postUploadAttempted)
        putNullable("retention_removed", retentionRemoved)
        putNullable("outbox_staged_after_cycle", outboxStagedAfterCycle)
        putNullable("outbox_pending_after_cycle", outboxPendingAfterCycle)
        put("manual", manual)
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun fromJson(json: JSONObject) = CaptureDiagnostic(
        recordId = json.getString("record_id"),
        captureId = json.optString("capture_id", json.getString("record_id")),
        sessionId = json.optString("session_id"),
        slotId = json.getString("slot_id"),
        scheduledFor = json.getLong("scheduled_for"),
        alarmReceivedAt = json.getLong("alarm_received_at"),
        triggerSource = json.optString("trigger_source", CaptureDiagnostic.TRIGGER_UNKNOWN),
        serviceReceivedAt = json.optLong("service_received_at", json.getLong("alarm_received_at")),
        captureStartedAt = json.nullableLong("capture_started_at"),
        capturedAt = json.nullableLong("captured_at"),
        completedAt = json.nullableLong("completed_at"),
        result = json.optString("result", CaptureDiagnostic.RESULT_IN_PROGRESS),
        errorCode = json.nullableString("error_code"),
        screenInteractive = json.optBoolean("screen_interactive"),
        charging = json.optBoolean("charging"),
        plugged = json.optBoolean("plugged"),
        batteryPercent = json.nullableInt("battery_percent"),
        deviceIdleMode = json.optBoolean("device_idle_mode"),
        powerSaveMode = json.optBoolean("power_save_mode"),
        batteryOptimizationExempt = json.optBoolean("battery_optimization_exempt"),
        stationWakeLockHeld = json.optBoolean("station_wake_lock_held"),
        imagePath = json.nullableString("image_path"),
        imageBytes = json.nullableLong("image_bytes"),
        sourceWidth = json.nullableInt("source_width"),
        sourceHeight = json.nullableInt("source_height"),
        width = json.nullableInt("width"),
        height = json.nullableInt("height"),
        processingDurationMs = json.nullableLong("processing_duration_ms"),
        cameraSettings = json.nullableString("camera_settings"),
        palette = json.nullableString("palette"),
        paletteSize = json.nullableInt("palette_size"),
        paletteAnalysisWidth = json.nullableInt("palette_analysis_width"),
        paletteAnalysisHeight = json.nullableInt("palette_analysis_height"),
        paletteIncludedPixels = json.nullableInt("palette_included_pixels"),
        paletteDurationMs = json.nullableLong("palette_duration_ms"),
        palettePeakPssKib = json.nullableLong("palette_peak_pss_kib"),
        outboxPending = json.nullableInt("outbox_pending"),
        outboxAttention = json.nullableInt("outbox_attention"),
        outboxOldestAgeMs = json.nullableLong("outbox_oldest_age_ms"),
        outboxStorageBytes = json.nullableLong("outbox_storage_bytes"),
        uploadAttempted = json.nullableInt("upload_attempted"),
        uploadDelivered = json.nullableInt("upload_delivered"),
        uploadRetried = json.nullableInt("upload_retried"),
        uploadAttentionRequired = json.nullableInt("upload_attention_required"),
        uploadDeferred = json.nullableInt("upload_deferred"),
        outboxPendingAfterUpload = json.nullableInt("outbox_pending_after_upload"),
        uploadErrorCode = json.nullableString("upload_error_code"),
        cycleAction = json.nullableString("cycle_action"),
        recoveredStagedCaptureId = json.nullableString("recovered_staged_capture_id"),
        preUploadAttempted = json.nullableInt("pre_upload_attempted"),
        postUploadAttempted = json.nullableInt("post_upload_attempted"),
        retentionRemoved = json.nullableInt("retention_removed"),
        outboxStagedAfterCycle = json.nullableInt("outbox_staged_after_cycle"),
        outboxPendingAfterCycle = json.nullableInt("outbox_pending_after_cycle"),
        manual = json.optBoolean("manual"),
    )

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else getLong(key)

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key) || !has(key)) null else getString(key)

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key) || !has(key)) null else getInt(key)
}
