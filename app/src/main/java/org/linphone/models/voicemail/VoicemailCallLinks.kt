package org.linphone.models.voicemail

import androidx.annotation.Keep
import org.linphone.models.callhistory.CallHistoryItem

/** The call a voicemail was left on, remembered by its media id until the voicemail is deleted. */
@Keep
data class VoicemailCallLink(val boxId: String = "", val documentId: String = "")

/** A voicemail for the History list, and the call it was left on when that call isn't listed. */
data class UnlistedVoicemail(
    val boxId: String,
    val message: VoicemailMessage,
    val callDocumentId: String?
)

/** Where the voicemails go in the History list: with their call, or as rows of their own. */
data class HistoryVoicemails(
    // The call's document id to the voicemail it left (box id and message)
    val byCall: Map<String, Pair<String, VoicemailMessage>>,
    val unlisted: List<UnlistedVoicemail>
)

/**
 * Links voicemails to the calls they were left on (WI 37000). A call ages out of the call history
 * (60 days, or 250 calls later), so each link is remembered while the call and the voicemail are
 * both loaded, and kept until the voicemail is deleted. A voicemail whose call had already gone
 * before they were seen together can't be linked.
 */
object VoicemailCallLinks {
    // Plenty for every mailbox's first page; past it the oldest links are dropped
    const val MAX_LINKS = 2000

    /** Each voicemail's media id to the document id of the loaded call it was left on. */
    fun match(
        boxes: List<VoicemailBoxWithMessages>,
        history: List<CallHistoryItem>,
        countryCode: String
    ): Map<String, String> {
        val matches = LinkedHashMap<String, String>()
        for (box in boxes) {
            for (message in box.messages) {
                val documentId = try {
                    VoicemailRules.matchCall(message, history, countryCode)
                } catch (e: Exception) {
                    null
                }
                if (!documentId.isNullOrEmpty()) matches[message.mediaId] = documentId
            }
        }
        return matches
    }

    /**
     * The links to keep: the stored ones with the new matches added, less those of voicemails that
     * have been deleted. A box's voicemails count as deleted only once the whole box is loaded (no
     * next page) with messages in it, since a box whose messages failed to load has none.
     */
    fun updated(
        stored: Map<String, VoicemailCallLink>,
        boxes: List<VoicemailBoxWithMessages>,
        matched: Map<String, String>
    ): Map<String, VoicemailCallLink> {
        val boxOf = HashMap<String, String>()
        for (box in boxes) for (message in box.messages) boxOf[message.mediaId] = box.box.id
        val complete = boxes.filter { it.nextStartKey == null && it.messages.isNotEmpty() }
            .associate { box -> box.box.id to box.messages.map { it.mediaId }.toSet() }

        val result = LinkedHashMap<String, VoicemailCallLink>()
        for ((mediaId, link) in stored) {
            val held = complete[link.boxId]
            if (held != null && mediaId !in held) continue
            result[mediaId] = link
        }
        for ((mediaId, documentId) in matched) {
            val boxId = boxOf[mediaId] ?: continue
            // Re-added so it counts as the newest
            result.remove(mediaId)
            result[mediaId] = VoicemailCallLink(boxId, documentId)
        }
        while (result.size > MAX_LINKS) result.remove(result.keys.first())
        return result
    }

    /** Each voicemail's call: the loaded call it matches, else the one remembered for it. */
    fun withLinks(
        boxes: List<VoicemailBoxWithMessages>,
        matched: Map<String, String>,
        stored: Map<String, String>
    ): Map<String, String> {
        val links = HashMap<String, String>()
        for (box in boxes) {
            for (message in box.messages) {
                (matched[message.mediaId] ?: stored[message.mediaId])?.let { links[message.mediaId] = it }
            }
        }
        return links
    }

    /**
     * Puts each voicemail with the listed call it was left on (the first voicemail, if a call left
     * more than one), or else in a row of its own, with the call it was left on when that's known.
     */
    fun forHistory(
        boxes: List<VoicemailBoxWithMessages>,
        links: Map<String, String>,
        listedCalls: Set<String>
    ): HistoryVoicemails {
        val byCall = HashMap<String, Pair<String, VoicemailMessage>>()
        val unlisted = mutableListOf<UnlistedVoicemail>()
        for (box in boxes) {
            for (message in box.messages) {
                val documentId = links[message.mediaId]
                if (documentId != null && documentId in listedCalls) {
                    if (documentId !in byCall) byCall[documentId] = box.box.id to message
                } else {
                    unlisted.add(UnlistedVoicemail(box.box.id, message, documentId))
                }
            }
        }
        return HistoryVoicemails(byCall, unlisted)
    }
}
