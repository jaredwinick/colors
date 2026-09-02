package com.jaredwinick.colors.camera.network

import com.jaredwinick.colors.camera.outbox.DurableCaptureRecord
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

data class UploadConfirmation(
    val httpStatus: Int,
    val idempotentReplay: Boolean,
    val imageUrl: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("http_status", httpStatus)
        put("idempotent_replay", idempotentReplay)
        put("image_url", imageUrl)
    }.toString()
}

sealed interface UploadAttemptResult {
    data class Delivered(val confirmation: UploadConfirmation) : UploadAttemptResult
    data class Retry(val errorCode: String) : UploadAttemptResult
    data class Attention(val errorCode: String) : UploadAttemptResult
}

interface CaptureUploadTransport {
    fun upload(
        endpoint: String,
        bearerToken: String,
        record: DurableCaptureRecord,
        timeoutSeconds: Int,
    ): UploadAttemptResult
}

class SecureCaptureUploader(
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
    private val boundaryFactory: () -> String = { "colors-${UUID.randomUUID()}" },
) : CaptureUploadTransport {
    override fun upload(
        endpoint: String,
        bearerToken: String,
        record: DurableCaptureRecord,
        timeoutSeconds: Int,
    ): UploadAttemptResult {
        val image = File(record.imagePath)
        val requestBody = runCatching {
            require(record.mimeType == JPEG_MIME_TYPE) { "Only JPEG captures can be uploaded" }
            require(record.byteCount in 1..MAX_IMAGE_BYTES) { "Capture exceeds upload size policy" }
            require(image.isFile && image.length() == record.byteCount) {
                "Queued image does not match immutable metadata"
            }
            requireNotNull(record.paletteJson) { "Queued palette is missing" }
            MultipartEncoder.encode(record, image, boundaryFactory())
        }.getOrElse { return UploadAttemptResult.Attention("UPLOAD_EVIDENCE_INVALID") }

        val connection = runCatching { connectionFactory(URL(endpoint)) }
            .getOrElse { return UploadAttemptResult.Attention("INGEST_ENDPOINT_INVALID") }
        return try {
            val timeoutMillis = Math.multiplyExact(timeoutSeconds, 1_000)
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=${requestBody.boundary}",
            )
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setFixedLengthStreamingMode(requestBody.bytes.size)
            connection.outputStream.use { output -> output.write(requestBody.bytes) }

            val status = connection.responseCode
            when {
                status in 300..399 -> UploadAttemptResult.Attention("REDIRECT_REJECTED")
                status == HttpURLConnection.HTTP_CONFLICT ->
                    UploadAttemptResult.Attention("IDEMPOTENCY_CONFLICT")
                status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN ->
                    UploadAttemptResult.Attention("AUTHORIZATION_REJECTED")
                status in setOf(400, 404, 405, 413, 415, 422) ->
                    UploadAttemptResult.Attention("REQUEST_REJECTED")
                status in setOf(408, 425, 429) || status in 500..599 ->
                    UploadAttemptResult.Retry("SERVER_RETRYABLE")
                status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_CREATED ->
                    parseSuccess(connection, status, record.captureId)
                else -> UploadAttemptResult.Attention("HTTP_RESPONSE_REJECTED")
            }
        } catch (_: SocketTimeoutException) {
            UploadAttemptResult.Retry("REQUEST_TIMEOUT")
        } catch (_: IOException) {
            UploadAttemptResult.Retry("NETWORK_REQUEST_FAILED")
        } catch (_: Exception) {
            UploadAttemptResult.Retry("UPLOAD_TRANSPORT_FAILED")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSuccess(
        connection: HttpURLConnection,
        status: Int,
        expectedCaptureId: String,
    ): UploadAttemptResult {
        val body = runCatching { readBounded(connection.inputStream, MAX_SUCCESS_RESPONSE_BYTES) }
            .getOrElse { error ->
                return if (error is ResponseTooLargeException) {
                    UploadAttemptResult.Retry("SUCCESS_RESPONSE_TOO_LARGE")
                } else {
                    UploadAttemptResult.Retry("SUCCESS_RESPONSE_READ_FAILED")
                }
            }
        val json = runCatching { JSONObject(String(body, StandardCharsets.UTF_8)) }
            .getOrElse { return UploadAttemptResult.Retry("MALFORMED_SUCCESS_RESPONSE") }
        val capture = json.optJSONObject("capture")
            ?: return UploadAttemptResult.Retry("MALFORMED_SUCCESS_RESPONSE")
        if (capture.optString("id") != expectedCaptureId) {
            return UploadAttemptResult.Retry("SUCCESS_CAPTURE_ID_MISMATCH")
        }
        if (!json.has("idempotentReplay") || json.isNull("idempotentReplay")) {
            return UploadAttemptResult.Retry("SUCCESS_REPLAY_FLAG_MISSING")
        }
        val replay = runCatching { json.getBoolean("idempotentReplay") }
            .getOrElse { return UploadAttemptResult.Retry("SUCCESS_REPLAY_FLAG_INVALID") }
        if ((status == HttpURLConnection.HTTP_OK) != replay) {
            return UploadAttemptResult.Retry("SUCCESS_STATUS_REPLAY_MISMATCH")
        }
        val imageUrl = capture.optString("imageUrl")
        if (!imageUrl.startsWith("/api/images/")) {
            return UploadAttemptResult.Retry("SUCCESS_IMAGE_ROUTE_INVALID")
        }
        return UploadAttemptResult.Delivered(
            UploadConfirmation(status, replay, imageUrl),
        )
    }

    private fun readBounded(input: java.io.InputStream, maximumBytes: Int): ByteArray =
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maximumBytes) throw ResponseTooLargeException()
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }

    companion object {
        const val MAX_IMAGE_BYTES = 12L * 1_024 * 1_024
        const val MAX_SUCCESS_RESPONSE_BYTES = 64 * 1_024
        private const val JPEG_MIME_TYPE = "image/jpeg"
        private const val USER_AGENT = "colors-native-android/1"
    }
}

internal data class EncodedMultipart(val boundary: String, val bytes: ByteArray)

internal object MultipartEncoder {
    fun encode(record: DurableCaptureRecord, image: File, boundary: String): EncodedMultipart {
        require(boundary.matches(Regex("[A-Za-z0-9-]{1,70}"))) { "Invalid multipart boundary" }
        val output = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            output.write("--$boundary\r\n".utf8())
            output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".utf8())
            output.write(value.utf8())
            output.write("\r\n".utf8())
        }
        field("capture_id", record.captureId)
        field("captured_at", record.capturedAt)
        field("device_id", record.deviceId)
        field("palette", requireNotNull(record.paletteJson))
        output.write("--$boundary\r\n".utf8())
        output.write(
            "Content-Disposition: form-data; name=\"image\"; filename=\"capture.jpg\"\r\n".utf8(),
        )
        output.write("Content-Type: image/jpeg\r\n\r\n".utf8())
        image.inputStream().use { it.copyTo(output) }
        output.write("\r\n--$boundary--\r\n".utf8())
        return EncodedMultipart(boundary, output.toByteArray())
    }

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
}

private class ResponseTooLargeException : IOException()
