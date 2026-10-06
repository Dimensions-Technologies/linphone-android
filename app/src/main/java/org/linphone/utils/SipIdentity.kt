package org.linphone.utils

object SipIdentity {
    // As the web client (libwebphone) reads a P-Asserted-Identity: "Display Name" <sip:user@host>
    private val identityPattern = Regex(
        """^"?([^"<]*?)"?\s*<sips?:([^@>]+)@""",
        RegexOption.IGNORE_CASE
    )

    /** The user part (usually the number) of a name-addr header value, or null if it isn't one. */
    fun userPart(header: String?): String? =
        header?.let { identityPattern.find(it.trim())?.groupValues?.get(2) }
}
