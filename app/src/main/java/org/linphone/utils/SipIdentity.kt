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

    /**
     * The other party's number from a P-Asserted-Identity, when it names someone other than the
     * call's remote address (e.g. a caller retrieved from a parking slot, or after a transfer), else
     * null. Only the number: as the web client, the PAI's display name is never shown, since the PBX
     * can fill it from a source the client filters out (e.g. a HubSpot contact, WI 36371).
     */
    fun otherPartyNumber(pai: String?, remoteUsername: String?): String? =
        userPart(pai)?.takeIf { it != remoteUsername }
}
