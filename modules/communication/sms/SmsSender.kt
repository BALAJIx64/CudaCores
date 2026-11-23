package com.guardiangrid.modules.communication.sms

/**
 * Result of an SMS transmission attempt.
 */
data class SmsSendResult(
    val isSuccess: Boolean,
    val partsCount: Int = 1,
    val errorMessage: String? = null
)

/**
 * Abstraction for sending cellular SMS messages.
 * Decouples telephony hardware from testing and simulation environments.
 */
interface SmsSender {
    /**
     * Sends an SMS message to the specified destination phone number.
     */
    fun sendSms(destinationAddress: String, text: String): SmsSendResult

    /**
     * Checks if cellular telephony is currently available.
     */
    fun isAvailable(): Boolean
}

/**
 * Recorded SMS for test verification.
 */
data class SentSmsRecord(
    val destinationAddress: String,
    val text: String,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

/**
 * Hermetic mock implementation of SmsSender for automated testing.
 */
class MockSmsSender(
    var isTelephonyAvailable: Boolean = true,
    var simulateFailure: Boolean = false,
    var failureReason: String = "Simulated carrier error"
) : SmsSender {

    private val sentMessagesList = mutableListOf<SentSmsRecord>()
    val sentMessages: List<SentSmsRecord> get() = sentMessagesList

    override fun sendSms(destinationAddress: String, text: String): SmsSendResult {
        if (!isTelephonyAvailable) {
            return SmsSendResult(
                isSuccess = false,
                errorMessage = "Cellular radio unavailable or airplane mode active"
            )
        }
        if (simulateFailure) {
            return SmsSendResult(
                isSuccess = false,
                errorMessage = failureReason
            )
        }

        val parts = if (text.length > 160) (text.length + 152) / 153 else 1
        sentMessagesList.add(SentSmsRecord(destinationAddress, text))
        return SmsSendResult(isSuccess = true, partsCount = parts)
    }

    override fun isAvailable(): Boolean = isTelephonyAvailable

    fun clear() {
        sentMessagesList.clear()
        isTelephonyAvailable = true
        simulateFailure = false
    }
}
