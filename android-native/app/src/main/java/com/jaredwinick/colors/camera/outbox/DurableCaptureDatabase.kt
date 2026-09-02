package com.jaredwinick.colors.camera.outbox

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class DurableCaptureDatabase(
    context: Context,
    name: String = DATABASE_NAME,
) : SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {
    override fun onConfigure(database: SQLiteDatabase) {
        super.onConfigure(database)
        database.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        createCapturesV1(database)
        upgradeToV2(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version < 1) {
            createCapturesV1(database)
            version = 1
        }
        if (version < 2) {
            upgradeToV2(database)
            version = 2
        }
        require(version == newVersion) { "Unsupported durable capture database migration" }
    }

    @Synchronized
    fun record(captureId: String): DurableCaptureRecord? = readableDatabase.query(
        CAPTURES,
        null,
        "capture_id = ?",
        arrayOf(captureId),
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    @Synchronized
    fun allRecords(): List<DurableCaptureRecord> = readableDatabase.query(
        CAPTURES,
        null,
        null,
        null,
        null,
        null,
        "captured_at ASC, capture_id ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecord()) } }

    @Synchronized
    fun upsert(record: DurableCaptureRecord) {
        writableDatabase.runInTransaction {
            insertWithOnConflict(
                CAPTURES,
                null,
                record.toValues(),
                SQLiteDatabase.CONFLICT_REPLACE,
            ).also { check(it != -1L) { "Could not persist durable capture" } }
        }
    }

    @Synchronized
    fun updateState(
        captureId: String,
        state: DurableCaptureState,
        updatedAt: String,
        errorCode: String? = null,
    ) {
        errorCode?.let(DurableCapturePolicy::requireSafeErrorCode)
        val values = ContentValues().apply {
            put("state", state.name)
            put("updated_at", updatedAt)
            putNullable("last_error_code", errorCode)
        }
        check(
            writableDatabase.update(
                CAPTURES,
                values,
                "capture_id = ?",
                arrayOf(captureId),
            ) == 1,
        ) { "Durable capture does not exist: $captureId" }
    }

    @Synchronized
    fun insertConflict(
        captureId: String,
        existingFingerprint: String?,
        incomingFingerprint: String,
        evidencePath: String,
        detectedAt: String,
    ) {
        val values = ContentValues().apply {
            put("capture_id", captureId)
            putNullable("existing_fingerprint", existingFingerprint)
            put("incoming_fingerprint", incomingFingerprint)
            put("evidence_path", evidencePath)
            put("detected_at", detectedAt)
        }
        check(writableDatabase.insert(CONFLICTS, null, values) != -1L) {
            "Could not persist capture conflict"
        }
    }

    @Synchronized
    fun conflictCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $CONFLICTS",
        null,
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    @Synchronized
    fun summary(): OutboxSummary = readableDatabase.rawQuery(
        """
        SELECT
            COALESCE(SUM(CASE WHEN state = 'STAGED' THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN state = 'PROCESSING' THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN state = 'PENDING_UPLOAD' THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN state = 'DELIVERED' THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN state = 'ATTENTION_REQUIRED' THEN 1 ELSE 0 END), 0),
            (SELECT COUNT(*) FROM $CONFLICTS),
            MIN(CASE WHEN state = 'PENDING_UPLOAD' THEN captured_at END)
        FROM $CAPTURES
        """.trimIndent(),
        null,
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Could not inspect durable capture queue" }
        OutboxSummary(
            staged = cursor.getInt(0),
            processing = cursor.getInt(1),
            pending = cursor.getInt(2),
            delivered = cursor.getInt(3),
            attentionRequired = cursor.getInt(4),
            conflicts = cursor.getInt(5),
            oldestPendingAt = if (cursor.isNull(6)) null else cursor.getString(6),
            storageBytes = 0,
        )
    }

    @Synchronized
    fun delete(captureId: String) {
        writableDatabase.delete(CAPTURES, "capture_id = ?", arrayOf(captureId))
    }

    private fun createCapturesV1(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $CAPTURES (
                capture_id TEXT PRIMARY KEY NOT NULL,
                state TEXT NOT NULL,
                captured_at TEXT NOT NULL,
                device_id TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                byte_count INTEGER NOT NULL,
                image_sha256 TEXT NOT NULL,
                palette_json TEXT,
                processing_metadata_json TEXT,
                image_path TEXT NOT NULL,
                metadata_path TEXT,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_attempt_at TEXT,
                last_error_code TEXT,
                next_eligible_retry_at TEXT,
                delivered_at TEXT,
                delivery_confirmation_json TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS captures_queue_order " +
                "ON $CAPTURES(state, captured_at, capture_id)",
        )
    }

    private fun upgradeToV2(database: SQLiteDatabase) {
        if (!database.hasColumn(CAPTURES, "immutable_fingerprint")) {
            database.execSQL("ALTER TABLE $CAPTURES ADD COLUMN immutable_fingerprint TEXT")
        }
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $CONFLICTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                capture_id TEXT NOT NULL,
                existing_fingerprint TEXT,
                incoming_fingerprint TEXT NOT NULL,
                evidence_path TEXT NOT NULL,
                detected_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) if (cursor.getString(name) == column) return@use true
            false
        }

    private fun DurableCaptureRecord.toValues() = ContentValues().apply {
        put("capture_id", captureId)
        put("state", state.name)
        put("captured_at", capturedAt)
        put("device_id", deviceId)
        put("mime_type", mimeType)
        put("byte_count", byteCount)
        put("image_sha256", imageSha256)
        putNullable("palette_json", paletteJson)
        putNullable("processing_metadata_json", processingMetadataJson)
        putNullable("immutable_fingerprint", immutableFingerprint)
        put("image_path", imagePath)
        putNullable("metadata_path", metadataPath)
        put("attempt_count", attemptCount)
        putNullable("last_attempt_at", lastAttemptAt)
        putNullable("last_error_code", lastErrorCode)
        putNullable("next_eligible_retry_at", nextEligibleRetryAt)
        putNullable("delivered_at", deliveredAt)
        putNullable("delivery_confirmation_json", deliveryConfirmationJson)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
    }

    private fun Cursor.toRecord() = DurableCaptureRecord(
        captureId = string("capture_id"),
        state = DurableCaptureState.valueOf(string("state")),
        capturedAt = string("captured_at"),
        deviceId = string("device_id"),
        mimeType = string("mime_type"),
        byteCount = long("byte_count"),
        imageSha256 = string("image_sha256"),
        paletteJson = nullableString("palette_json"),
        processingMetadataJson = nullableString("processing_metadata_json"),
        immutableFingerprint = nullableString("immutable_fingerprint"),
        imagePath = string("image_path"),
        metadataPath = nullableString("metadata_path"),
        attemptCount = integer("attempt_count"),
        lastAttemptAt = nullableString("last_attempt_at"),
        lastErrorCode = nullableString("last_error_code"),
        nextEligibleRetryAt = nullableString("next_eligible_retry_at"),
        deliveredAt = nullableString("delivered_at"),
        deliveryConfirmationJson = nullableString("delivery_confirmation_json"),
        createdAt = string("created_at"),
        updatedAt = string("updated_at"),
    )

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.nullableString(column: String): String? =
        getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }
    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.integer(column: String): Int = getInt(getColumnIndexOrThrow(column))

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    companion object {
        const val DATABASE_NAME = "colors-durable-captures.db"
        const val DATABASE_VERSION = 2
        private const val CAPTURES = "captures"
        private const val CONFLICTS = "capture_conflicts"
    }
}

private inline fun SQLiteDatabase.runInTransaction(action: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        action()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}
