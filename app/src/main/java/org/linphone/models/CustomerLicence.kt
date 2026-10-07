package org.linphone.models

import androidx.annotation.Keep

/**
 * The customer's licence (GET licence): feature key to value, where 0 is hidden, -1 disabled and
 * anything else enabled. Ported from the web client's models/customer-licence.ts.
 */
@Keep
data class CustomerLicence(
    val tenantId: String? = null,
    val features: Map<String, Int>? = null
) {
    // As the web client's hasFeature: any non-zero value, so disabled (-1) counts too
    fun hasFeature(feature: String): Boolean = (features?.get(feature) ?: 0) != 0

    /** How a licensed feature is shown: enabled, disabled (greyed out) or hidden. */
    fun displayState(feature: String): LicenceDisplayState = when (features?.get(feature)) {
        null, 0 -> LicenceDisplayState.HIDDEN
        -1 -> LicenceDisplayState.DISABLED
        else -> LicenceDisplayState.ENABLED
    }

    companion object {
        const val VOICEMAIL_ENABLED = "customer.ucwebclient.voicemail.enabled"
        const val RECORDING_TRANSCRIPTION = "customer.recordingtranscription"
        const val RECORDING_ANALYTICS = "customer.recordinganalytics"
        const val CALL_TAGGING = "customer.tagging.calls"
        const val DISPOSITIONS = "customer.dispositions"
    }
}

enum class LicenceDisplayState { DISABLED, HIDDEN, ENABLED }
