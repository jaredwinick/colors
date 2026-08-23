package com.jaredwinick.colors.poc

import android.content.Context
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
        put("session_id", sessionId)
        put("slot_id", slotId)
        put("scheduled_for", scheduledFor)
        put("alarm_received_at", alarmReceivedAt)
        putNullable("capture_started_at", captureStartedAt)
        putNullable("captured_at", capturedAt)
        putNullable("completed_at", completedAt)
        put("alarm_lateness_ms", alarmLatenessMs)
        putNullable("capture_lateness_ms", captureLatenessMs)
        put("result", result)
        putNullable("error_code", errorCode)
        put("screen_interactive", screenInteractive)
        put("charging", charging)
        putNullable("image_path", imagePath)
        put("manual", manual)
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun fromJson(json: JSONObject) = CaptureDiagnostic(
        recordId = json.getString("record_id"),
        sessionId = json.optString("session_id"),
        slotId = json.getString("slot_id"),
        scheduledFor = json.getLong("scheduled_for"),
        alarmReceivedAt = json.getLong("alarm_received_at"),
        captureStartedAt = json.nullableLong("capture_started_at"),
        capturedAt = json.nullableLong("captured_at"),
        completedAt = json.nullableLong("completed_at"),
        result = json.optString("result", CaptureDiagnostic.RESULT_IN_PROGRESS),
        errorCode = json.nullableString("error_code"),
        screenInteractive = json.optBoolean("screen_interactive"),
        charging = json.optBoolean("charging"),
        imagePath = json.nullableString("image_path"),
        manual = json.optBoolean("manual"),
    )

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else getLong(key)

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key) || !has(key)) null else getString(key)
}
