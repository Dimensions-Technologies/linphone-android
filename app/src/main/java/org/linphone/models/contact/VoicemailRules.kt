package org.linphone.models.contact

/**
 * Calls to the user's own extension (their presenceId) reach their voicemail box, so the web client
 * names them "Voicemail" rather than after the user themselves.
 */
object VoicemailRules {
    /**
     * A live call (call-view.component.ts getCallInfoFields): an outgoing call to the user's own
     * extension, whatever contact it matches.
     */
    fun isOwnVoicemailCall(outgoing: Boolean, number: String?, presenceId: String?): Boolean =
        outgoing && isOwnNumber(number, presenceId)

    /**
     * A history row (call-history-item-view.ts getFields): a call whose other party is the user's own
     * extension, in either direction.
     */
    fun isOwnNumber(number: String?, presenceId: String?): Boolean =
        !presenceId.isNullOrEmpty() && number == presenceId
}
