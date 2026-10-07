package org.linphone.models.voicemail

import androidx.annotation.Keep
import org.threeten.bp.ZonedDateTime

/** A voicemail box as the gateway returns it (GET voicemailbox), as on the web client. */
@Keep
data class VoicemailBox(
    val id: String = "",
    val name: String? = null,
    val number: String? = null,
    val totalMessages: Int = 0,
    val saved: Int = 0,
    val new: Int = 0,
    // Whether the owning user's main callflow currently routes calls into this mailbox
    val enabled: Boolean = false
)

/** A page of a box's messages (GET voicemailbox/{id}/messages). */
@Keep
data class VoicemailMessagePage(
    val messages: List<VoicemailMessage>? = null,
    val nextStartKey: String? = null
)

@Keep
data class VoicemailMessage(
    val mediaId: String = "",
    val callId: String? = null,
    // new, saved or deleted; a missing folder counts as new
    val folder: String? = null,
    // Milliseconds
    val length: Long = 0,
    val timestamp: ZonedDateTime? = null,
    val from: String? = null,
    val to: String? = null,
    val callerIdNumber: String? = null,
    val callerIdName: String? = null,
    // Fetched on demand (VoicemailBoxService.getOrFetchTranscription); absent until then
    val transcription: VoicemailTranscription? = null
)

@Keep
data class VoicemailTranscription(
    val result: String? = null,
    val text: String? = null
)

/** A box with its first page of messages, and its collapsed state in the list, as held by the app. */
@Keep
data class VoicemailBoxWithMessages(
    val box: VoicemailBox,
    val messages: List<VoicemailMessage> = emptyList(),
    val nextStartKey: String? = null,
    val collapsed: Boolean = false,
    val collapsedFolders: List<String> = emptyList()
)
