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
}
