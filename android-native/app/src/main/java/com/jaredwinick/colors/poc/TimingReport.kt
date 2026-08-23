package com.jaredwinick.colors.poc

import java.util.Locale
import kotlin.math.ceil

data class TimingSummary(
    val expectedSlots: Int,
    val recordedSlots: Int,
    val successfulCaptures: Int,
    val missingSlots: Int,
    val duplicateCaptures: Int,
    val duplicateAlarms: Int,
    val p95LatenessMs: Long?,
    val worstLatenessMs: Long?,
    val within60SecondsPercent: Double?,
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
        val duplicates = sessionRecords
            .filter { it.result == CaptureDiagnostic.RESULT_SUCCESS }
            .groupingBy { it.scheduledFor }
            .eachCount()
            .values.sumOf { (it - 1).coerceAtLeast(0) }
        val lateness = sessionRecords
            .filter { it.result == CaptureDiagnostic.RESULT_SUCCESS }
            .mapNotNull { it.captureLatenessMs }
            .sorted()
        val p95 = if (lateness.isEmpty()) null else lateness[ceil(lateness.size * 0.95).toInt() - 1]
        val within60 = if (lateness.isEmpty()) null else {
            lateness.count { it <= 60_000L } * 100.0 / lateness.size
        }
        return TimingSummary(
            expectedSlots = expected.size,
            recordedSlots = recordedSlots.size,
            successfulCaptures = lateness.size,
            missingSlots = expected.count { it !in recordedSlots },
            duplicateCaptures = duplicates,
            duplicateAlarms = sessionRecords.count { it.errorCode == "DUPLICATE_SLOT" },
            p95LatenessMs = p95,
            worstLatenessMs = lateness.maxOrNull(),
            within60SecondsPercent = within60,
        )
    }

    fun display(summary: TimingSummary): String = buildString {
        appendLine("Expected slots: ${summary.expectedSlots}")
        appendLine("Recorded slots: ${summary.recordedSlots}")
        appendLine("Successful captures: ${summary.successfulCaptures}")
        appendLine("Missing slots: ${summary.missingSlots}")
        appendLine("Duplicate captures: ${summary.duplicateCaptures}")
        appendLine("Duplicate alarms skipped: ${summary.duplicateAlarms}")
        appendLine("Within 60s: ${summary.within60SecondsPercent?.formatPercent() ?: "—"}")
        appendLine("P95 lateness: ${summary.p95LatenessMs?.formatDuration() ?: "—"}")
        append("Worst lateness: ${summary.worstLatenessMs?.formatDuration() ?: "—"}")
    }

    fun csv(records: List<CaptureDiagnostic>): String = buildString {
        appendLine(
            "record_id,session_id,slot_id,scheduled_for,alarm_received_at,capture_started_at," +
                "captured_at,completed_at,alarm_lateness_ms,capture_lateness_ms,result,error_code," +
                "screen_interactive,charging,image_path,manual",
        )
        records.sortedBy { it.scheduledFor }.forEach { record ->
            appendLine(
                listOf(
                    record.recordId,
                    record.sessionId,
                    record.slotId,
                    UtcSchedule.format(record.scheduledFor),
                    UtcSchedule.format(record.alarmReceivedAt),
                    UtcSchedule.format(record.captureStartedAt),
                    UtcSchedule.format(record.capturedAt),
                    UtcSchedule.format(record.completedAt),
                    record.alarmLatenessMs,
                    record.captureLatenessMs ?: "",
                    record.result,
                    record.errorCode ?: "",
                    record.screenInteractive,
                    record.charging,
                    record.imagePath ?: "",
                    record.manual,
                ).joinToString(",") { csvEscape(it.toString()) },
            )
        }
    }

    private fun Long.formatDuration(): String = String.format(Locale.US, "%.1fs", this / 1_000.0)
    private fun Double.formatPercent(): String = String.format(Locale.US, "%.1f%%", this)
    private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
