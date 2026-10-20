package org.linphone.models.contact

/**
 * The lines naming a live call, as the web client's call view (call-view.component.ts
 * getCallInfoFields): the name, formatted when it's a number, then the number on a line of its own.
 */
object CallHeaderRules {
    /**
     * Whether the number gets its own line: only for an outgoing call that isn't to the user's own
     * voicemail, and only when the name isn't already that number (e.g. an unmatched call).
     */
    fun showsNumber(outgoing: Boolean, voicemail: Boolean, name: String?, number: String?): Boolean =
        outgoing && !voicemail && !number.isNullOrEmpty() && number != name
}
