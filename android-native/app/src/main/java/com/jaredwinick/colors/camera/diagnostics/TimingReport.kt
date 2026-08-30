package com.jaredwinick.colors.camera.diagnostics

import java.util.Locale
import com.jaredwinick.colors.camera.schedule.UtcSchedule
import kotlin.math.ceil

data class TimingSummary(
    val expectedSlots: Int,
    val recordedSlots: Int,
    val successfulCaptures: Int,
    val earlyCaptures: Int,
    val missingSlots: Int,
    val duplicateCaptures: Int,
    val duplicateAlarms: Int,
    val p95LatenessMs: Long?,
    val worstLatenessMs: Long?,
    val within60SecondsPercent: Double?,
    val timerCaptures: Int,
    val alarmCaptures: Int,
)

object TimingReport {
    fun summarize(
        records: List<CaptureDiagnostic>,
        sessionId: String,
        firstScheduledAt: Long,
        throughMillis: Long,
        intervalMinutes: Int,
    ): TimingSummary {
        val sessionRecords = records.filter { it.sessionId == sessionId && !it.manual }
        val expected = UtcSchedule.expectedBoundaries(firstScheduledAt, throughMillis, intervalMinutes)
        val recordedSlots = sessionRecords.map { it.scheduledFor }.toSet()
        val completedCaptures = sessionRecords.filter {
            it.result == CaptureDiagnostic.RESULT_SUCCESS && it.captureLatenessMs != null
        }
        // A JPEG assigned to a future slot is evidence of a trigger bug, not a
        // successful capture for that slot. Keep it visible as an early capture
        // while correctly reporting the intended slot as missing.
        val validCaptures = completedCaptures.filter { it.captureLatenessMs!! >= 0 }
        val successfulSlots = validCaptures.mapTo(mutableSetOf()) { it.scheduledFor }
        val duplicates = validCaptures
            .groupingBy { it.scheduledFor }
            .eachCount()
            .values.sumOf { (it - 1).coerceAtLeast(0) }
        val lateness = validCaptures
            .mapNotNull { it.captureLatenessMs }
            .sorted()
        val p95 = if (lateness.isEmpty()) null else lateness[ceil(lateness.size * 0.95).toInt() - 1]
        val within60 = if (lateness.isEmpty()) null else {
            lateness.count { it <= 60_000L } * 100.0 / lateness.size
        }
        return TimingSummary(
            expectedSlots = expected.size,
            recordedSlots = recordedSlots.size,
            successfulCaptures = validCaptures.size,
            earlyCaptures = completedCaptures.size - validCaptures.size,
            missingSlots = expected.count { it !in successfulSlots },
            duplicateCaptures = duplicates,
            duplicateAlarms = sessionRecords.count { it.errorCode == "DUPLICATE_SLOT" },
            p95LatenessMs = p95,
            worstLatenessMs = lateness.maxOrNull(),
            within60SecondsPercent = within60,
            timerCaptures = validCaptures.count {
                it.triggerSource == CaptureDiagnostic.TRIGGER_TIMER
            },
            alarmCaptures = validCaptures.count {
                it.triggerSource == CaptureDiagnostic.TRIGGER_ALARM
            },
        )
    }

    fun display(summary: TimingSummary): String = buildString {
        appendLine("Expected slots: ${summary.expectedSlots}")
        appendLine("Recorded slots: ${summary.recordedSlots}")
        appendLine("Successful captures: ${summary.successfulCaptures}")
        appendLine("Invalid early captures: ${summary.earlyCaptures}")
        appendLine("Missing slots: ${summary.missingSlots}")
        appendLine("Duplicate captures: ${summary.duplicateCaptures}")
        appendLine("Duplicate alarms skipped: ${summary.duplicateAlarms}")
        appendLine("Timer / alarm captures: ${summary.timerCaptures} / ${summary.alarmCaptures}")
        appendLine("Within 60s: ${summary.within60SecondsPercent?.formatPercent() ?: "—"}")
        appendLine("P95 lateness: ${summary.p95LatenessMs?.formatDuration() ?: "—"}")
        append("Worst lateness: ${summary.worstLatenessMs?.formatDuration() ?: "—"}")
    }

    fun csv(records: List<CaptureDiagnostic>): String = buildString {
        appendLine(
            "record_id,capture_id,session_id,slot_id,scheduled_for,trigger_source,trigger_received_at," +
                "service_received_at,service_dispatch_ms,capture_started_at,captured_at,completed_at," +
                "trigger_lateness_ms,capture_lateness_ms,result,error_code,screen_interactive," +
                "charging,plugged,battery_percent,device_idle_mode,power_save_mode," +
                "battery_optimization_exempt,station_wake_lock_held,image_path,image_bytes," +
                "source_width,source_height,width,height,processing_duration_ms,camera_settings,manual",
        )
        records.sortedBy { it.scheduledFor }.forEach { record ->
            appendLine(
                listOf(
                    record.recordId,
                    record.captureId,
                    record.sessionId,
                    record.slotId,
                    UtcSchedule.format(record.scheduledFor),
                    record.triggerSource,
                    UtcSchedule.format(record.alarmReceivedAt),
                    UtcSchedule.format(record.serviceReceivedAt),
                    record.serviceDispatchMs,
                    UtcSchedule.format(record.captureStartedAt),
                    UtcSchedule.format(record.capturedAt),
                    UtcSchedule.format(record.completedAt),
                    record.alarmLatenessMs,
                    record.captureLatenessMs ?: "",
                    record.result,
                    record.errorCode ?: "",
                    record.screenInteractive,
                    record.charging,
                    record.plugged,
                    record.batteryPercent ?: "",
                    record.deviceIdleMode,
                    record.powerSaveMode,
                    record.batteryOptimizationExempt,
                    record.stationWakeLockHeld,
                    record.imagePath ?: "",
                    record.imageBytes ?: "",
                    record.sourceWidth ?: "",
                    record.sourceHeight ?: "",
                    record.width ?: "",
                    record.height ?: "",
                    record.processingDurationMs ?: "",
                    record.cameraSettings ?: "",
                    record.manual,
                ).joinToString(",") { csvEscape(it.toString()) },
            )
        }
    }

    private fun Long.formatDuration(): String = String.format(Locale.US, "%.1fs", this / 1_000.0)
    private fun Double.formatPercent(): String = String.format(Locale.US, "%.1f%%", this)
    private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
