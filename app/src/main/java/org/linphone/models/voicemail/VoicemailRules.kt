package org.linphone.models.voicemail

import org.linphone.models.callhistory.CallDirections
import org.linphone.models.callhistory.CallHistoryItem
import org.linphone.models.callhistory.CallTypes
import org.linphone.models.callsession.PhoneFormat

/**
 * Visual voicemail rules, ported from the web client (voicemail-box.service.ts and
 * voicemail-list.component.ts).
 */
object VoicemailRules {
    const val FOLDER_NEW = "new"
    const val FOLDER_SAVED = "saved"
    const val FOLDER_DELETED = "deleted"

    const val PERMISSION = "customer.user.uc.voicemail"

    // Folders are shown in this order; any other folder comes after them
    private val FOLDER_ORDER = listOf(FOLDER_NEW, FOLDER_SAVED, FOLDER_DELETED)

    // Shown even when empty, so an empty mailbox still gives the user somewhere to look
    private val ALWAYS_SHOWN_FOLDERS = listOf(FOLDER_NEW, FOLDER_SAVED)

    fun folderOf(message: VoicemailMessage): String =
        message.folder?.takeIf { it.isNotBlank() } ?: FOLDER_NEW

    fun isSaved(message: VoicemailMessage) = folderOf(message) == FOLDER_SAVED

    /** The number to call the caller back on, and to match to a contact. */
    fun callerNumber(message: VoicemailMessage): String? =
        message.callerIdNumber?.takeIf { it.isNotBlank() } ?: message.from?.takeIf { it.isNotBlank() }

    /**
     * Unheard voicemails across all boxes, from each box's own folder counts rather than the
     * messages held, so it's right beyond the first page.
     */
    fun newCount(boxes: List<VoicemailBoxWithMessages>): Int = boxes.sumOf {
        it.box.new.coerceAtLeast(
            0
        )
    }

    /**
     * The gateway returns null rather than [] for a user with no mailbox, and a box whose voicemail
     * callflow action is skipped comes back disabled: voicemail is hidden unless one is enabled.
     */
    fun hasEnabledBox(boxes: List<VoicemailBoxWithMessages>): Boolean = boxes.any { it.box.enabled }

    /** Whether to show voicemail at all: licensed, permitted, and with a mailbox calls go to. */
    fun isAvailable(licensed: Boolean, permitted: Boolean, boxes: List<VoicemailBoxWithMessages>) =
        licensed && permitted && hasEnabledBox(boxes)

    /** A box's messages by folder, in folder order, newest first within each. */
    fun groupByFolder(messages: List<VoicemailMessage>): List<Pair<String, List<VoicemailMessage>>> {
        val byFolder = linkedMapOf<String, MutableList<VoicemailMessage>>()
        for (folder in ALWAYS_SHOWN_FOLDERS) byFolder[folder] = mutableListOf()
        for (message in messages) byFolder.getOrPut(folderOf(message)) { mutableListOf() }.add(
            message
        )

        return byFolder.entries
            .map { (folder, list) -> folder to list.sortedByDescending { it.timestamp?.toInstant()?.toEpochMilli() ?: 0L } }
            .sortedBy { (folder, _) -> folderOrderIndex(folder) }
    }

    private fun folderOrderIndex(folder: String): Int =
        FOLDER_ORDER.indexOf(folder).let { if (it == -1) FOLDER_ORDER.size else it }

    /** Every row of the list: box headers, folder headers, messages, and empty-folder notes. */
    fun rows(boxes: List<VoicemailBoxWithMessages>): List<VoicemailRow> {
        val rows = mutableListOf<VoicemailRow>()
        val single = boxes.size == 1
        for (box in boxes) {
            rows.add(
                VoicemailRow.BoxHeader(box, showName = !single, expanded = single || !box.collapsed)
            )
            // With one mailbox there's nothing to collapse it against, so it's always expanded
            if (!single && box.collapsed) continue

            for ((folder, messages) in groupByFolder(box.messages)) {
                val expanded = folder !in box.collapsedFolders
                rows.add(VoicemailRow.FolderHeader(box.box.id, folder, expanded))
                if (!expanded) continue
                if (messages.isEmpty()) rows.add(VoicemailRow.EmptyFolder(box.box.id, folder))
                for (message in messages) rows.add(VoicemailRow.Message(box.box.id, message))
            }
        }
        return rows
    }

    /** Carries transcriptions already fetched forward onto a refreshed box, by media id. */
    fun withCachedTranscriptions(
        fresh: List<VoicemailMessage>,
        previous: VoicemailBoxWithMessages?
    ): List<VoicemailMessage> {
        if (previous == null) return fresh
        val cached = previous.messages.filter { it.transcription != null }.associateBy { it.mediaId }
        return fresh.map { message ->
            if (message.transcription == null) {
                cached[message.mediaId]?.let { message.copy(transcription = it.transcription) } ?: message
            } else {
                message
            }
        }
    }

    /**
     * The box with the message taken out, and its folder counts moved with it (floored at 0), so
     * the badge agrees with the list until the server's counts arrive on the next refresh.
     */
    fun withoutMessage(box: VoicemailBoxWithMessages, mediaId: String): VoicemailBoxWithMessages {
        val message = box.messages.firstOrNull { it.mediaId == mediaId } ?: return box
        return box.copy(
            box = withCountDelta(box.box, folderOf(message), -1),
            messages = box.messages.filterNot { it.mediaId == mediaId }
        )
    }

    /** Puts a message removed by [withoutMessage] back at [index], unless it's already there. */
    fun withMessage(box: VoicemailBoxWithMessages, message: VoicemailMessage, index: Int): VoicemailBoxWithMessages {
        if (box.messages.any { it.mediaId == message.mediaId }) return box
        val messages = box.messages.toMutableList()
        messages.add(index.coerceIn(0, messages.size), message)
        return box.copy(box = withCountDelta(box.box, folderOf(message), 1), messages = messages)
    }

    private fun withCountDelta(box: VoicemailBox, folder: String, delta: Int): VoicemailBox = box.copy(
        new = if (folder == FOLDER_NEW) (box.new + delta).coerceAtLeast(0) else box.new,
        saved = if (folder == FOLDER_SAVED) (box.saved + delta).coerceAtLeast(0) else box.saved,
        totalMessages = (box.totalMessages + delta).coerceAtLeast(0)
    )

    fun withTranscription(
        boxes: List<VoicemailBoxWithMessages>,
        boxId: String,
        mediaId: String,
        transcription: VoicemailTranscription
    ): List<VoicemailBoxWithMessages> = boxes.map { box ->
        if (box.box.id != boxId) {
            box
        } else {
            box.copy(
                messages = box.messages.map {
                    if (it.mediaId == mediaId) it.copy(transcription = transcription) else it
                }
            )
        }
    }

    fun toggledBox(boxes: List<VoicemailBoxWithMessages>, boxId: String) =
        boxes.map { if (it.box.id == boxId) it.copy(collapsed = !it.collapsed) else it }

    fun toggledFolder(boxes: List<VoicemailBoxWithMessages>, boxId: String, folder: String) = boxes.map {
        if (it.box.id != boxId) {
            it
        } else if (folder in it.collapsedFolders) {
            it.copy(collapsedFolders = it.collapsedFolders - folder)
        } else {
            it.copy(collapsedFolders = it.collapsedFolders + folder)
        }
    }

    /** The message length (milliseconds) as m:ss, or h:mm:ss. */
    fun formatLength(lengthMs: Long): String {
        var seconds = Math.round(lengthMs.coerceAtLeast(0) / 1000.0).toInt()
        val hours = seconds / 3600
        seconds %= 3600
        val minutes = seconds / 60
        seconds %= 60
        val pad = { v: Int -> v.toString().padStart(2, '0') }
        return if (hours > 0) {
            "$hours:${pad(minutes)}:${pad(seconds)}"
        } else {
            "${pad(minutes)}:${pad(
                seconds
            )}"
        }
    }

    // A voicemail is matched to a call that started up to this long before it
    const val CALL_MATCH_WINDOW_MS = 90_000L

    /**
     * The document id of the call that most likely went to this voicemail, as the web client finds
     * it (call-history.service.ts findCallNear): the closest call from the same number that started
     * up to 90 seconds before. Calls that went to voicemail are recorded as answered, so missed and
     * answered calls are both considered.
     */
    fun matchCall(message: VoicemailMessage, history: List<CallHistoryItem>, countryCode: String): String? {
        val number = callerNumber(message) ?: return null
        val time = message.timestamp?.toInstant()?.toEpochMilli() ?: return null
        val target = PhoneFormat.toE164(number, countryCode)

        var closest: CallHistoryItem? = null
        var closestDelta = Long.MAX_VALUE
        for (item in history) {
            val start = item.startTime?.toInstant()?.toEpochMilli() ?: continue
            val itemNumber = if (item.callType == CallTypes.External) {
                item.cli
            } else if (item.callDirection == CallDirections.Incoming.value) {
                item.callingUserNumber
            } else {
                null
            }
            if (itemNumber.isNullOrEmpty() || PhoneFormat.toE164(itemNumber, countryCode) != target) continue
            val delta = time - start
            if (delta < 0 || delta > CALL_MATCH_WINDOW_MS) continue
            if (delta < closestDelta) {
                closest = item
                closestDelta = delta
            }
        }
        return closest?.documentId
    }

    /** The file extension for the audio's content type, as the web client names downloads. */
    fun audioExtension(contentType: String?): String = when {
        contentType == null -> "mp3"
        contentType.contains("wav") -> "wav"
        contentType.contains("mp4") || contentType.contains("m4a") -> "m4a"
        else -> "mp3"
    }
}

sealed class VoicemailRow {
    data class BoxHeader(
        val box: VoicemailBoxWithMessages,
        val showName: Boolean,
        val expanded: Boolean
    ) : VoicemailRow()
    data class FolderHeader(val boxId: String, val folder: String, val expanded: Boolean) : VoicemailRow()
    data class EmptyFolder(val boxId: String, val folder: String) : VoicemailRow()
    data class Message(val boxId: String, val message: VoicemailMessage) : VoicemailRow()
}
