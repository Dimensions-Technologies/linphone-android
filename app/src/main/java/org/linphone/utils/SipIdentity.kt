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
     * The number a P-Asserted-Identity gives the other party: its user part, or its display name
     * when that's "anonymous" (any case). On a retrieve from park the PBX sends a withheld caller's
     * real number in the URI and hides it only by that display name (no Privacy header), so the
     * number is never used for one - as the web client (WI 36896).
     */
    fun assertedNumber(header: String?): String? {
        val match = header?.let { identityPattern.find(it.trim()) } ?: return null
        val displayName = match.groupValues[1].trim()
        return if (displayName.equals("anonymous", ignoreCase = true)) displayName else match.groupValues[2]
    }

    /**
     * The other party's number from a P-Asserted-Identity (see assertedNumber), when it names
     * someone other than the call's remote address (e.g. a caller retrieved from a parking slot, or
     * after a transfer), else null. Only the number: as the web client, the PAI's display name is
     * otherwise never shown, since the PBX can fill it from a source the client filters out (e.g. a
     * HubSpot contact, WI 36371).
     */
    fun otherPartyNumber(pai: String?, remoteUsername: String?): String? =
        assertedNumber(pai)?.takeIf { it != remoteUsername }
}
