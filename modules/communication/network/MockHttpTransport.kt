package com.guardiangrid.modules.communication.network

import java.io.IOException
import java.net.UnknownHostException

/**
 * Recorded HTTP request for test assertions.
 */
data class RecordedHttpRequest(
    val method: String,
    val url: String,
    val body: String?,
    val headers: Map<String, String>
)

/**
 * Configurable mock HTTP transport for hermetic unit testing.
 * Simulates network availability, timeouts, HTTP errors, and API responses.
 */
class MockHttpTransport : HttpTransport {

    var isNetworkAvailable: Boolean = true
    var simulateTimeout: Boolean = false

    private val recordedRequestsList = mutableListOf<RecordedHttpRequest>()
    val recordedRequests: List<RecordedHttpRequest> get() = recordedRequestsList

    private var customHandler: ((RecordedHttpRequest) -> HttpResponse)? = null
    private val queuedResponses = mutableListOf<HttpResponse>()

    fun setHandler(handler: (RecordedHttpRequest) -> HttpResponse) {
        this.customHandler = handler
    }

    fun enqueueResponse(response: HttpResponse) {
        queuedResponses.add(response)
    }

    fun clear() {
        recordedRequestsList.clear()
        queuedResponses.clear()
        customHandler = null
        isNetworkAvailable = true
        simulateTimeout = false
    }

    override fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>
    ): HttpResponse {
        checkNetwork()
        val request = RecordedHttpRequest("POST", url, jsonBody, headers)
        recordedRequestsList.add(request)
        return resolveResponse(request)
    }

    override fun getJson(
        url: String,
        headers: Map<String, String>
    ): HttpResponse {
        checkNetwork()
        val request = RecordedHttpRequest("GET", url, null, headers)
        recordedRequestsList.add(request)
        return resolveResponse(request)
    }

    private fun checkNetwork() {
        if (!isNetworkAvailable) {
            throw UnknownHostException("Mock network unavailable: unable to resolve host")
        }
        if (simulateTimeout) {
            throw IOException("Mock network timeout: socket read timed out")
        }
    }

    private fun resolveResponse(request: RecordedHttpRequest): HttpResponse {
        customHandler?.let { handler ->
            return handler(request)
        }

        if (queuedResponses.isNotEmpty()) {
            return queuedResponses.removeAt(0)
        }

        // Default successful Telegram mock response
        return HttpResponse(
            statusCode = 200,
            body = """
                {
                    "ok": true,
                    "result": {
                        "message_id": 99991,
                        "date": 1700000000,
                        "chat": {
                            "id": 12345678,
                            "type": "channel",
                            "title": "Emergency Channel"
                        },
                        "text": "Alert received"
                    }
                }
            """.trimIndent()
        )
    }
}
