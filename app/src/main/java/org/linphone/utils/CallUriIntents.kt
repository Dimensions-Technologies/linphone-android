package org.linphone.utils

import android.content.Intent

object CallUriIntents {
    private val CALL_URI_SCHEMES = setOf("tel", "sip", "sips", "linphone", "sip-linphone")

    // Browsers open tel: links with ACTION_VIEW, while other apps' call buttons use ACTION_DIAL,
    // so match on the URI scheme rather than the action
    fun isCallUri(action: String?, scheme: String?): Boolean {
        return when (action) {
            Intent.ACTION_VIEW, Intent.ACTION_DIAL -> scheme?.lowercase() in CALL_URI_SCHEMES
            else -> false
        }
    }
}
