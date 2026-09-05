package com.jaredwinick.colors.camera.outbox

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class DurableCaptureStoreTest {
    private lateinit var context: Context
    private lateinit var rootName: String
    private lateinit var databaseName: String
    private lateinit var store: DurableCaptureStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString()
        rootName = "durable-test-$suffix"
        databaseName = "durable-test-$suffix.db"
        store = DurableCaptureStore(context, rootName, databaseName)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(databaseName)
        File(context.filesDir, rootName).deleteRecursively()
    }

    @Test
    fun `processing state recovers to staged after process restart without deleting JPEG`() {
        val captureId = UUID.randomUUID().toString()
        val raw = store.rawFile(captureId).apply { writeBytes(jpeg(1)) }
        store.recordStaged(captureId, "2026-09-01T12:00:00Z", DEVICE_ID)
        store.markProcessing(captureId)
        val interruptedOutput = store.normalizedTempFile(captureId).apply { writeBytes(jpeg(2)) }
        store.close()

        store = DurableCaptureStore(context, rootName, databaseName)
        val report = store.reconcile(Instant.parse("2026-09-01T12:01:00Z"))

        assertEquals(1, report.recoveredStaged)
        assertTrue(raw.isFile)
        assertFalse(interruptedOutput.exists())
        assertEquals(1, store.summary().staged)
        assertEquals(0, store.summary().attentionRequired)
    }

    @Test
    fun `processing failure returns source to staged with context for the next cycle`() {
        val captureId = UUID.randomUUID().toString()
        val contextJson = """{"kind":"staged_capture_context","schema_version":1}"""
        val raw = store.rawFile(captureId).apply { writeBytes(jpeg(21)) }
        store.recordStaged(
            captureId,
            "2026-09-01T12:00:00Z",
            DEVICE_ID,
            stagingMetadataJson = contextJson,
        )
        store.markProcessing(captureId)
        store.normalizedTempFile(captureId).writeBytes(jpeg(22))

        val returned = store.returnToStaged(captureId, "PALETTE_MASK_INVALID")

        assertEquals(DurableCaptureState.STAGED, returned.state)
        assertEquals("PALETTE_MASK_INVALID", returned.lastErrorCode)
        assertEquals(contextJson, returned.processingMetadataJson)
        assertTrue(raw.isFile)
        assertFalse(store.normalizedTempFile(captureId).exists())
    }

    @Test
    fun `oldest staged work is deterministic`() {
        val later = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        val tieSecond = "00000000-0000-4000-8000-000000000002"
        val tieFirst = "00000000-0000-4000-8000-000000000001"
        listOf(
            Triple(later, "2026-09-01T12:05:00Z", 31),
            Triple(tieSecond, "2026-09-01T12:00:00Z", 32),
            Triple(tieFirst, "2026-09-01T12:00:00Z", 33),
        ).forEach { (captureId, capturedAt, marker) ->
            store.rawFile(captureId).writeBytes(jpeg(marker))
            store.recordStaged(captureId, capturedAt, DEVICE_ID)
        }

        assertEquals(tieFirst, store.oldestStaged()?.captureId)
    }

    @Test
    fun `identical enqueue converges and conflicting UUID retains evidence for attention`() {
        val captureId = UUID.randomUUID().toString()
        val payload = stageForEnqueue(captureId, "2026-09-01T12:00:00Z", jpeg(2))

        val first = store.enqueue(store.normalizedTempFile(captureId), payload)
        store.normalizedTempFile(captureId).writeBytes(jpeg(2))
        val duplicate = store.enqueue(store.normalizedTempFile(captureId), payload)

        assertFalse(first.duplicate)
        assertTrue(duplicate.duplicate)
        assertEquals(first.record.immutableFingerprint, duplicate.record.immutableFingerprint)

        store.normalizedTempFile(captureId).writeBytes(jpeg(3))
        assertThrows(CaptureIdConflictException::class.java) {
            store.enqueue(store.normalizedTempFile(captureId), payload)
        }
        val summary = store.summary()
        assertEquals(1, summary.pending)
        assertEquals(1, summary.conflicts)
        assertTrue(File(context.filesDir, rootName).resolve("quarantine").walk().any(File::isFile))
    }

    @Test
    fun `pending order and limit are deterministic and retry eligibility is persisted`() {
        val laterId = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        val tieFirstId = "00000000-0000-4000-8000-000000000001"
        val tieSecondId = "00000000-0000-4000-8000-000000000002"
        enqueue(tieSecondId, "2026-09-01T12:00:00Z", jpeg(4))
        enqueue(laterId, "2026-09-01T12:05:00Z", jpeg(5))
        enqueue(tieFirstId, "2026-09-01T12:00:00Z", jpeg(6))

        assertEquals(
            listOf(tieFirstId, tieSecondId, laterId),
            store.pendingOldestFirst().map(DurableCaptureRecord::captureId),
        )
        val summary = store.summary()
        assertEquals(3, summary.pending)
        assertEquals("2026-09-01T12:00:00Z", summary.oldestPendingAt)
        assertTrue(summary.storageBytes > 0)

        store.recordRetry(
            tieFirstId,
            "NETWORK_UNAVAILABLE",
            nextEligibleRetryAt = Instant.parse("2026-09-01T12:10:00Z"),
            attemptedAt = Instant.parse("2026-09-01T12:01:00Z"),
        )
        assertEquals(
            listOf(tieSecondId, laterId),
            store.pendingEligible(Instant.parse("2026-09-01T12:06:00Z"), 10)
                .map(DurableCaptureRecord::captureId),
        )
    }

    @Test
    fun `delivered retention never removes pending work`() {
        val oldDelivered = UUID.randomUUID().toString()
        val newDelivered = UUID.randomUUID().toString()
        val pending = UUID.randomUUID().toString()
        enqueue(oldDelivered, "2026-08-01T00:00:00Z", jpeg(7))
        enqueue(newDelivered, "2026-08-31T00:00:00Z", jpeg(8))
        enqueue(pending, "2026-07-01T00:00:00Z", jpeg(9))
        store.markDelivered(
            oldDelivered,
            "{\"idempotentReplay\":false}",
            Instant.parse("2026-08-01T00:01:00Z"),
        )
        store.markDelivered(
            newDelivered,
            "{\"idempotentReplay\":false}",
            Instant.parse("2026-08-31T00:01:00Z"),
        )

        assertEquals(
            1,
            store.applyDeliveredRetention(
                retentionDays = 365,
                retentionCount = 1,
                now = Instant.parse("2026-09-01T00:00:00Z"),
            ),
        )
        assertEquals(1, store.summary().pending)
        assertEquals(pending, store.pendingOldestFirst().single().captureId)
        assertEquals(1, store.summary().delivered)
    }

    @Test
    fun `delivery conflict moves immutable evidence to durable attention`() {
        val captureId = UUID.randomUUID().toString()
        val pending = enqueue(captureId, "2026-09-01T12:00:00Z", jpeg(14))

        val attention = store.markDeliveryAttention(
            captureId,
            "IDEMPOTENCY_CONFLICT",
            Instant.parse("2026-09-01T12:05:00Z"),
        )

        assertEquals(DurableCaptureState.ATTENTION_REQUIRED, attention.state)
        assertEquals(1, attention.attemptCount)
        assertEquals("IDEMPOTENCY_CONFLICT", attention.lastErrorCode)
        assertFalse(File(pending.imagePath).exists())
        assertTrue(File(attention.imagePath).isFile)
        assertEquals(
            "ATTENTION_REQUIRED",
            JSONObject(File(requireNotNull(attention.metadataPath)).readText()).getString("state"),
        )
        store.close()
        store = DurableCaptureStore(context, rootName, databaseName)
        store.reconcile(Instant.parse("2026-09-01T12:06:00Z"))
        assertEquals(1, store.summary().attentionRequired)
        assertEquals(0, store.summary().pending)
        assertTrue(File(requireNotNull(store.record(captureId)).imagePath).isFile)
    }

    @Test
    fun `startup repair marks changed immutable evidence and does not delete it`() {
        val captureId = UUID.randomUUID().toString()
        val record = enqueue(captureId, "2026-09-01T12:00:00Z", jpeg(10))
        val image = File(record.imagePath)
        image.writeBytes(jpeg(11))

        val report = store.reconcile(Instant.parse("2026-09-01T12:05:00Z"))

        assertEquals(1, report.attentionRequired)
        assertTrue(image.isFile)
        assertEquals(1, store.summary().attentionRequired)
        assertEquals(0, store.summary().pending)
    }

    @Test
    fun `version one database migrates to current schema without losing rows`() {
        store.close()
        context.deleteDatabase(databaseName)
        val database = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        createVersionOneSchema(database)
        database.execSQL(
            "INSERT INTO captures " +
                "(capture_id,state,captured_at,device_id,mime_type,byte_count,image_sha256," +
                "image_path,attempt_count,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "00000000-0000-4000-8000-000000000003",
                "ATTENTION_REQUIRED",
                "2026-09-01T00:00:00Z",
                DEVICE_ID,
                "image/jpeg",
                3,
                "abc",
                "/preserved/evidence.jpg",
                0,
                "2026-09-01T00:00:00Z",
                "2026-09-01T00:00:00Z",
            ),
        )
        database.version = 1
        database.close()

        store = DurableCaptureStore(context, rootName, databaseName)

        assertEquals(1, store.summary().attentionRequired)
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { migrated ->
            assertEquals(DurableCaptureDatabase.DATABASE_VERSION, migrated.version)
            migrated.rawQuery("SELECT COUNT(*) FROM capture_conflicts", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun `legacy image and metadata migrate only after a durable pending copy commits`() {
        val captureId = UUID.randomUUID().toString()
        val legacy = File(context.cacheDir, "legacy-${UUID.randomUUID()}").apply { mkdirs() }
        val image = File(legacy, "$captureId.jpg").apply { writeBytes(jpeg(12)) }
        val metadata = File(legacy, "$captureId.json").apply {
            writeText(
                """
                {
                  "capture_id":"$captureId",
                  "captured_at":"2026-09-01T12:00:00Z",
                  "mime_type":"image/jpeg",
                  "bytes":${image.length()},
                  "palette":$PALETTE
                }
                """.trimIndent(),
            )
        }

        assertTrue(store.importLegacyCapture(image, metadata, DEVICE_ID))

        assertFalse(image.exists())
        assertFalse(metadata.exists())
        val migrated = store.pendingOldestFirst().single()
        assertEquals(captureId, migrated.captureId)
        assertTrue(File(migrated.imagePath).isFile)
        assertTrue(File(requireNotNull(migrated.metadataPath)).isFile)
        legacy.deleteRecursively()
    }

    private fun enqueue(captureId: String, capturedAt: String, bytes: ByteArray): DurableCaptureRecord {
        val payload = stageForEnqueue(captureId, capturedAt, bytes)
        return store.enqueue(store.normalizedTempFile(captureId), payload).record
    }

    private fun stageForEnqueue(
        captureId: String,
        capturedAt: String,
        bytes: ByteArray,
    ): DurableCapturePayload {
        store.rawFile(captureId).writeBytes(bytes)
        store.recordStaged(captureId, capturedAt, DEVICE_ID)
        store.markProcessing(captureId)
        store.normalizedTempFile(captureId).writeBytes(bytes)
        return DurableCapturePayload(
            captureId = captureId,
            capturedAt = capturedAt,
            deviceId = DEVICE_ID,
            mimeType = "image/jpeg",
            byteCount = bytes.size.toLong(),
            paletteJson = PALETTE,
            processingMetadataJson = "{\"source\":\"unit-test\"}",
        )
    }

    private fun jpeg(marker: Int): ByteArray = byteArrayOf(
        0xff.toByte(),
        0xd8.toByte(),
        marker.toByte(),
        0xff.toByte(),
        0xd9.toByte(),
    )

    private fun createVersionOneSchema(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE captures (
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
    }

    companion object {
        private const val DEVICE_ID = "android-sky-camera"
        private const val PALETTE =
            "[{\"hex\":\"#112233\",\"weight\":0.5}," +
                "{\"hex\":\"#445566\",\"weight\":0.3}," +
                "{\"hex\":\"#778899\",\"weight\":0.2}]"
    }
}
