package org.linphone.models.callhistory

import java.net.URLEncoder

/** SIP URIs for call history numbers the core can't interpret (CallHistoryItemViewModel.getAddress). */
object CallHistoryAddresses {
    // For a call with no usable number, so a history row always has an address
    const val ANONYMOUS = "sip:anonymous@anonymous.invalid"

    /** The number as the user part of a SIP URI on the account's domain, or anonymous. */
    fun sipUri(number: String?, domain: String?): String {
        val user = number?.filterNot(Char::isWhitespace).orEmpty()
        if (user.isEmpty() || domain.isNullOrBlank()) return ANONYMOUS
        // Escapes what isn't allowed in a user part (# included); * and + stay as dialled
        val escaped = URLEncoder.encode(user, "UTF-8").replace("%2B", "+")
        return "sip:$escaped@$domain"
    }
}
