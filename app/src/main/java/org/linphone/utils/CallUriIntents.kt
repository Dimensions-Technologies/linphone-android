package org.linphone.utils

import android.content.Intent

object CallUriIntents {
    // Carries a call link from LoginActivity through the sign-in flow to MainActivity
    const val EXTRA_CALL_URI = "CallUri"

    // Must match the schemes in LoginActivity's intent filter in the manifest
    val CALL_URI_SCHEMES = setOf("tel", "sip", "sips", "linphone", "sip-linphone")

    // Browsers open tel: links with ACTION_VIEW, while other apps' call buttons use ACTION_DIAL,
    // so match on the URI scheme rather than the action
    fun isCallUri(action: String?, scheme: String?): Boolean {
        return when (action) {
            Intent.ACTION_VIEW, Intent.ACTION_DIAL -> scheme?.lowercase() in CALL_URI_SCHEMES
            else -> false
        }
    }

    // The call link LoginActivity should hold until the user is signed in: the intent's own link
    // when it's a call link, otherwise one carried back as an extra after a cancelled sign-in
    fun callUriToHold(action: String?, data: String?, extraCallUri: String?): String? {
        val scheme = data?.substringBefore(':', missingDelimiterValue = "")
        return if (isCallUri(action, scheme)) data else extraCallUri
    }
}
