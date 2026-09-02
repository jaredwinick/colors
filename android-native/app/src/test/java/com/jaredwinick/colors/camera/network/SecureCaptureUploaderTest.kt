package com.jaredwinick.colors.camera.network

import com.jaredwinick.colors.camera.outbox.DurableCaptureRecord
import com.jaredwinick.colors.camera.outbox.DurableCapturePolicy
import com.jaredwinick.colors.camera.outbox.DurableCaptureState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SecureCaptureUploaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var server: TestHttpServer
    private lateinit var endpoint: String
    private lateinit var record: DurableCaptureRecord
    private val requests = mutableListOf<RecordedRequest>()
    @Volatile
    private var responder: (Socket, RecordedRequest) -> Unit = { socket, _ ->
        respond(socket, 201, successJson(201))
    }

    @Before
    fun setUp() {
        val image = temporary.newFile("capture.jpg").apply { writeBytes(IMAGE_BYTES) }
        record = DurableCaptureRecord(
            captureId = CAPTURE_ID,
            state = DurableCaptureState.PENDING_UPLOAD,
            capturedAt = "2026-09-01T12:00:00Z",
            deviceId = "galaxy-s9-window",
            mimeType = "image/jpeg",
            byteCount = image.length(),
            imageSha256 = DurableCapturePolicy.sha256(IMAGE_BYTES),
            paletteJson = PALETTE,
            processingMetadataJson = "{}",
            immutableFingerprint = "immutable-test-fingerprint",
            imagePath = image.absolutePath,
            metadataPath = null,
            attemptCount = 0,
            lastAttemptAt = null,
            lastErrorCode = null,
            nextEligibleRetryAt = null,
            deliveredAt = null,
            deliveryConfirmationJson = null,
            createdAt = "2026-09-01T12:00:01Z",
            updatedAt = "2026-09-01T12:00:01Z",
        )
        server = TestHttpServer { socket, request ->
            synchronized(requests) { requests += request }
            responder(socket, request)
        }
        endpoint = "http://127.0.0.1:${server.port}/api/ingest"
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `201 sends exact Worker multipart contract and confirms first delivery`() {
        val result = uploader().upload(endpoint, TOKEN, record, 5)

        assertEquals(
            UploadAttemptResult.Delivered(UploadConfirmation(201, false, imageRoute())),
            result,
        )
        val request = requests.single()
        assertEquals("Bearer $TOKEN", request.authorization)
        assertEquals("multipart/form-data; boundary=colors-test-boundary", request.contentType)
        val parts = parseMultipart(request.body, "colors-test-boundary")
        assertEquals(CAPTURE_ID, parts.getValue("capture_id").toString(StandardCharsets.UTF_8))
        assertEquals(record.capturedAt, parts.getValue("captured_at").toString(StandardCharsets.UTF_8))
        assertEquals(record.deviceId, parts.getValue("device_id").toString(StandardCharsets.UTF_8))
        assertEquals(PALETTE, parts.getValue("palette").toString(StandardCharsets.UTF_8))
        assertArrayEquals(IMAGE_BYTES, parts.getValue("image"))
        assertFalse(result.toString().contains(TOKEN))
    }

    @Test
    fun `exact 200 replay is accepted without changing immutable request fields`() {
        responder = { socket, _ -> respond(socket, 200, successJson(200)) }

        val result = uploader().upload(endpoint, TOKEN, record, 5)

        assertEquals(
            UploadAttemptResult.Delivered(UploadConfirmation(200, true, imageRoute())),
            result,
        )
    }

    @Test
    fun `409 conflict and authentication or validation failures require attention`() {
        listOf(
            409 to "IDEMPOTENCY_CONFLICT",
            401 to "AUTHORIZATION_REJECTED",
            403 to "AUTHORIZATION_REJECTED",
            400 to "REQUEST_REJECTED",
            413 to "REQUEST_REJECTED",
            415 to "REQUEST_REJECTED",
            422 to "REQUEST_REJECTED",
        ).forEach { (status, code) ->
            responder = { socket, _ -> respond(socket, status, "{\"error\":\"not logged\"}") }
            assertEquals(
                UploadAttemptResult.Attention(code),
                uploader().upload(endpoint, TOKEN, record, 5),
            )
        }
    }

    @Test
    fun `server failures are retryable and response bodies are not retained`() {
        responder = { socket, _ -> respond(socket, 503, "secret server diagnostic") }

        val result = uploader().upload(endpoint, TOKEN, record, 5)

        assertEquals(UploadAttemptResult.Retry("SERVER_RETRYABLE"), result)
        assertFalse(result.toString().contains("secret server diagnostic"))
    }

    @Test
    fun `redirect is rejected without forwarding credentials`() {
        responder = { socket, _ ->
            respond(socket, 307, "", mapOf("Location" to "$endpoint/redirected"))
        }

        val result = uploader().upload(endpoint, TOKEN, record, 5)

        assertEquals(UploadAttemptResult.Attention("REDIRECT_REJECTED"), result)
        assertEquals(1, requests.size)
    }

    @Test
    fun `oversized or malformed success responses remain retryable`() {
        responder = { socket, _ ->
            respond(socket, 201, "x".repeat(SecureCaptureUploader.MAX_SUCCESS_RESPONSE_BYTES + 1))
        }
        assertEquals(
            UploadAttemptResult.Retry("SUCCESS_RESPONSE_TOO_LARGE"),
            uploader().upload(endpoint, TOKEN, record, 5),
        )

        responder = { socket, _ -> respond(socket, 201, "not-json") }
        assertEquals(
            UploadAttemptResult.Retry("MALFORMED_SUCCESS_RESPONSE"),
            uploader().upload(endpoint, TOKEN, record, 5),
        )
    }

    @Test
    fun `mismatched capture status replay and image route are not confirmed`() {
        responder = { socket, _ ->
            respond(socket, 201, successJson(201).replace(CAPTURE_ID, OTHER_CAPTURE_ID))
        }
        assertEquals(
            UploadAttemptResult.Retry("SUCCESS_CAPTURE_ID_MISMATCH"),
            uploader().upload(endpoint, TOKEN, record, 5),
        )

        responder = { socket, _ -> respond(socket, 201, successJson(200)) }
        assertEquals(
            UploadAttemptResult.Retry("SUCCESS_STATUS_REPLAY_MISMATCH"),
            uploader().upload(endpoint, TOKEN, record, 5),
        )

        responder = { socket, _ ->
            respond(socket, 201, successJson(201).replace("false", "\"false\""))
        }
        assertEquals(
            UploadAttemptResult.Retry("SUCCESS_REPLAY_FLAG_INVALID"),
            uploader().upload(endpoint, TOKEN, record, 5),
        )

        responder = { socket, _ ->
            respond(socket, 201, successJson(201).replace("/api/images/", "https://other.invalid/"))
        }
        assertEquals(
            UploadAttemptResult.Retry("SUCCESS_IMAGE_ROUTE_INVALID"),
            uploader().upload(endpoint, TOKEN, record, 5),
        )
    }

    @Test
    fun `disconnect is retryable`() {
        responder = { socket, _ -> socket.close() }

        val result = uploader().upload(endpoint, TOKEN, record, 5)

        assertTrue(result is UploadAttemptResult.Retry)
    }

    @Test
    fun `changed image evidence is rejected before any credential is sent`() {
        File(record.imagePath).writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))

        val result = uploader().upload(endpoint, TOKEN, record, 5)

        assertEquals(UploadAttemptResult.Attention("UPLOAD_EVIDENCE_INVALID"), result)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `timeout after commit can retry the same capture and accept replay`() {
        var calls = 0
        responder = { socket, _ ->
            calls += 1
            if (calls == 1) {
                Thread.sleep(1_300)
                runCatching { respond(socket, 201, successJson(201)) }
            } else {
                respond(socket, 200, successJson(200))
            }
        }

        assertEquals(
            UploadAttemptResult.Retry("REQUEST_TIMEOUT"),
            uploader().upload(endpoint, TOKEN, record, 1),
        )
        assertEquals(
            UploadAttemptResult.Delivered(UploadConfirmation(200, true, imageRoute())),
            uploader().upload(endpoint, TOKEN, record, 5),
        )
        assertEquals(2, requests.size)
        assertArrayEquals(requests[0].body, requests[1].body)
    }

    private fun uploader() = SecureCaptureUploader(boundaryFactory = { "colors-test-boundary" })

    private fun successJson(status: Int): String =
        """{"capture":{"id":"$CAPTURE_ID","imageUrl":"${imageRoute()}"},"idempotentReplay":${status == 200}}"""

    private fun imageRoute() = "/api/images/2026/09/01/$CAPTURE_ID.jpg"

    companion object {
        private const val CAPTURE_ID = "71fc4bc3-9ec7-4389-bf2c-ba09813844c0"
        private const val OTHER_CAPTURE_ID = "6fa459ea-ee8a-4ca4-894e-db77e160355e"
        private const val TOKEN = "test-token-that-never-appears-in-results"
        private const val PALETTE =
            "[{\"hex\":\"#335577\",\"weight\":0.5},{\"hex\":\"#7799BB\",\"weight\":0.3},{\"hex\":\"#DDEEFF\",\"weight\":0.2}]"
        private val IMAGE_BYTES = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())

        private fun respond(
            socket: Socket,
            status: Int,
            body: String,
            headers: Map<String, String> = emptyMap(),
        ) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            socket.getOutputStream().buffered().use { output ->
                output.write("HTTP/1.1 $status ${reason(status)}\r\n".toByteArray())
                headers.forEach { (name, value) ->
                    output.write("$name: $value\r\n".toByteArray())
                }
                output.write("Content-Type: application/json\r\n".toByteArray())
                output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                output.write(bytes)
            }
        }

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            307 -> "Temporary Redirect"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            422 -> "Unprocessable Content"
            503 -> "Service Unavailable"
            else -> "Response"
        }

        private fun parseMultipart(body: ByteArray, boundary: String): Map<String, ByteArray> {
            val delimiter = "--$boundary".toByteArray(StandardCharsets.UTF_8)
            return split(body, delimiter).mapNotNull { raw ->
                val part = raw.dropCrlfAndFinalMarker()
                if (part.isEmpty()) return@mapNotNull null
                val separator = "\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
                val headerEnd = part.indexOfSubsequence(separator)
                require(headerEnd >= 0)
                val headers = part.copyOfRange(0, headerEnd).toString(StandardCharsets.UTF_8)
                val name = Regex("name=\"([^\"]+)\"").find(headers)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val content = part.copyOfRange(headerEnd + separator.size, part.size - 2)
                name to content
            }.toMap()
        }

        private fun split(value: ByteArray, delimiter: ByteArray): List<ByteArray> {
            val result = mutableListOf<ByteArray>()
            var start = 0
            while (true) {
                val index = value.indexOfSubsequence(delimiter, start)
                if (index < 0) {
                    result += value.copyOfRange(start, value.size)
                    return result
                }
                result += value.copyOfRange(start, index)
                start = index + delimiter.size
            }
        }

        private fun ByteArray.dropCrlfAndFinalMarker(): ByteArray {
            var start = 0
            var end = size
            while (start + 1 < end && this[start] == 13.toByte() && this[start + 1] == 10.toByte()) start += 2
            if (end - start >= 2 && this[end - 2] == '-'.code.toByte() && this[end - 1] == '-'.code.toByte()) end -= 2
            while (end - start >= 2 && this[end - 2] == 13.toByte() && this[end - 1] == 10.toByte()) end -= 2
            return copyOfRange(start, end)
        }

        private fun ByteArray.indexOfSubsequence(needle: ByteArray, from: Int = 0): Int {
            if (needle.isEmpty()) return from
            if (from > size - needle.size) return -1
            for (index in from..size - needle.size) {
                if (needle.indices.all { this[index + it] == needle[it] }) return index
            }
            return -1
        }
    }
}

private data class RecordedRequest(
    val authorization: String?,
    val contentType: String?,
    val body: ByteArray,
)

private class TestHttpServer(
    private val handler: (Socket, RecordedRequest) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    val port: Int get() = server.localPort

    init {
        executor.execute {
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                executor.execute {
                    runCatching {
                        socket.soTimeout = 5_000
                        handler(socket, readRequest(socket))
                    }
                    runCatching { socket.close() }
                }
            }
        }
    }

    override fun close() {
        running.set(false)
        server.close()
        executor.shutdownNow()
    }

    private fun readRequest(socket: Socket): RecordedRequest {
        val input = BufferedInputStream(socket.getInputStream())
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        val delimiter = byteArrayOf(13, 10, 13, 10)
        while (headerBytes.size() <= 65_536 && matched < delimiter.size) {
            val value = input.read()
            require(value >= 0) { "Connection ended before HTTP headers" }
            headerBytes.write(value)
            matched = if (value.toByte() == delimiter[matched]) matched + 1 else 0
        }
        require(matched == delimiter.size) { "HTTP headers exceeded limit" }
        val lines = String(headerBytes.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n")
        val headers = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else {
                line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
        }.toMap()
        val length = requireNotNull(headers["content-length"]?.toIntOrNull())
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            require(read >= 0) { "Connection ended before HTTP body" }
            offset += read
        }
        return RecordedRequest(
            authorization = headers["authorization"],
            contentType = headers["content-type"],
            body = body,
        )
    }
}
