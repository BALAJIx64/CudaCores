package com.guardiangrid.modules.communication.network

import java.io.IOException

/**
 * Standard HTTP response data class.
 */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val isSuccessful: Boolean = statusCode in 200..299
)

data class BinaryHttpResponse(
    val statusCode: Int,
    val bytes: ByteArray,
    val isSuccessful: Boolean = statusCode in 200..299,
    val errorMessage: String? = null
)

/**
 * Abstraction for making outbound HTTP calls.
 * Decouples network I/O from Android platform or testing environments.
 */
interface HttpTransport {

    /**
     * Executes an HTTP POST with a JSON body.
     */
    @Throws(IOException::class)
    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse

    /**
     * Executes an HTTP GET request.
     */
    @Throws(IOException::class)
    fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse

    /**
     * Executes an HTTP POST using multipart/form-data to upload a binary file (e.g. WAV audio).
     */
    @Throws(IOException::class)
    fun postMultipartFile(
        url: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
        formFields: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): HttpResponse = HttpResponse(
        statusCode = 501,
        body = """{"ok":false,"description":"Multipart upload not supported by this transport"}"""
    )

    /**
     * Executes an HTTP GET returning raw binary bytes (e.g. WAV audio clip).
     */
    @Throws(IOException::class)
    fun getBinary(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): BinaryHttpResponse = BinaryHttpResponse(
        statusCode = 501,
        bytes = ByteArray(0),
        errorMessage = "Binary fetch not supported by this transport"
    )
}
