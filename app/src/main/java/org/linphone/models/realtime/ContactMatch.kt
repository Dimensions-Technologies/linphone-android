package org.linphone.models.realtime

import androidx.annotation.Keep

/**
 * A contact matched to a live call, from the user's directories or pushed by the server
 * (callMatchEvent). tagFields holds the contact's well-known fields by name: title, fullName,
 * companyName, jobTitle, phone1-4, email, crmId, field1-10 and avatar.
 */
@Keep
data class ContactMatch(
    val directoryName: String? = null,
    val directoryId: String? = null,
    val hasAvatar: Boolean = false,
    val contactId: String? = null,
    val tagFields: Map<String, String?>? = null
) {
    // As the web client names a call after a match: the full name, else the company
    val displayName: String?
        get() = tagFields?.get("fullName")?.takeIf { it.isNotBlank() }
            ?: tagFields?.get("companyName")?.takeIf { it.isNotBlank() }
}
