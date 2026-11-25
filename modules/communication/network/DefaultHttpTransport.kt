package com.guardiangrid.modules.communication.network

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Standard HTTP client implementation using Java/Android HttpURLConnection.
 * Requires zero external dependencies and runs on all Android API levels.
 */
class DefaultHttpTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000
) : HttpTransport {

    override fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            val statusCode = connection.responseCode
            val responseStream: InputStream? = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = readStreamToString(responseStream, connection.contentEncoding)
            val headerFields = connection.headerFields ?: emptyMap()

            return HttpResponse(
                statusCode = statusCode,
                body = body,
                headers = headerFields
            )
        } finally {
            connection.disconnect()
        }
    }

    override fun getJson(
        url: String,
        headers: Map<String, String>
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doInput = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            val statusCode = connection.responseCode
            val responseStream: InputStream? = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = readStreamToString(responseStream, connection.contentEncoding)
            val headerFields = connection.headerFields ?: emptyMap()

            return HttpResponse(
                statusCode = statusCode,
                body = body,
                headers = headerFields
            )
        } finally {
            connection.disconnect()
        }
    }

    override fun postMultipartFile(
        url: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
        formFields: Map<String, String>,
        headers: Map<String, String>
    ): HttpResponse {
        val boundary = "----GuardianGridBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs * 2
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            BufferedOutputStream(connection.outputStream).use { out ->
                val crlf = "\r\n"
                for ((key, value) in formFields) {
                    out.write("--$boundary$crlf".toByteArray(StandardCharsets.UTF_8))
                    out.write("Content-Disposition: form-data; name=\"$key\"$crlf$crlf".toByteArray(StandardCharsets.UTF_8))
                    out.write(value.toByteArray(StandardCharsets.UTF_8))
                    out.write(crlf.toByteArray(StandardCharsets.UTF_8))
                }
                out.write("--$boundary$crlf".toByteArray(StandardCharsets.UTF_8))
                out.write(
                    "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"$crlf"
                        .toByteArray(StandardCharsets.UTF_8)
                )
                out.write("Content-Type: $mimeType$crlf$crlf".toByteArray(StandardCharsets.UTF_8))
                out.write(fileBytes)
                out.write(crlf.toByteArray(StandardCharsets.UTF_8))
                out.write("--$boundary--$crlf".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }

            val statusCode = connection.responseCode
            val responseStream: InputStream? = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = readStreamToString(responseStream, connection.contentEncoding)
            val headerFields = connection.headerFields ?: emptyMap()
            return HttpResponse(statusCode = statusCode, body = body, headers = headerFields)
        } finally {
            connection.disconnect()
        }
    }

    override fun getBinary(
        url: String,
        headers: Map<String, String>
    ): BinaryHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs * 2
            doInput = true
            useCaches = false
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val out = ByteArrayOutputStream()
            stream?.use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                }
            }
            val bytes = out.toByteArray()
            return BinaryHttpResponse(
                statusCode = statusCode,
                bytes = if (statusCode in 200..299) bytes else ByteArray(0),
                isSuccessful = statusCode in 200..299,
                errorMessage = if (statusCode in 200..299) null else String(bytes, StandardCharsets.UTF_8).take(160)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readStreamToString(stream: InputStream?, contentEncoding: String?): String {
        if (stream == null) return ""
        val wrappedStream = if ("gzip".equals(contentEncoding, ignoreCase = true)) {
            GZIPInputStream(stream)
        } else {
            stream
        }

        return BufferedReader(InputStreamReader(wrappedStream, StandardCharsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }
}
