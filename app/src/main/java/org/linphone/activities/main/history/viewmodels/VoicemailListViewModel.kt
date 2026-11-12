package org.linphone.activities.main.history.viewmodels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.models.contact.CallAvatarRules
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.voicemail.VoicemailBox
import org.linphone.models.voicemail.VoicemailBoxWithMessages
import org.linphone.models.voicemail.VoicemailMessage
import org.linphone.models.voicemail.VoicemailRow
import org.linphone.models.voicemail.VoicemailRules
import org.linphone.services.CallHistoryService
import org.linphone.services.PhoneFormatterService
import org.linphone.services.UserGroupService
import org.linphone.services.VoicemailBoxService
import org.linphone.utils.DateUtils
import org.linphone.utils.Event
import org.linphone.utils.Log
import org.threeten.bp.LocalDateTime
import org.threeten.bp.ZoneId

/** The Voicemail tab of the call history, ported from the web client's voicemail-list.component.ts. */
class VoicemailListViewModel : ViewModel() {
    private val context get() = coreContext.context

    val items = MutableLiveData<List<VoicemailListItem>>(emptyList())
    val isEmpty = MutableLiveData(false)

    // The mailbox bar (WI 36610, as on iOS): the mailbox shown, which opens a picker of the others
    // when there's more than one, with the Call voicemail button beside it
    val hasBoxes = MutableLiveData(false)
    val hasManyBoxes = MutableLiveData(false)
    val boxTitle = MutableLiveData("")
    val boxBadge = MutableLiveData("")
    val boxDescription = MutableLiveData("")

    /** Opens the mailbox picker: the boxes, and the index of the one shown. */
    val openPickerEvent = MutableLiveData<Event<Pair<List<VoicemailBoxOption>, Int>>>()

    // The mailbox chosen in the picker; the first is shown until one is
    @Volatile private var chosenBoxId: String? = null

    /** The message in the player, and its state, set by the fragment that owns the player. */
    var currentMediaId: String? = null
        private set
    private var playState = VoicemailPlayState.NONE

    val playEvent = MutableLiveData<Event<Pair<String, VoicemailMessage>>>()
    val togglePlaybackEvent = MutableLiveData<Event<Unit>>()
    val confirmDeleteEvent = MutableLiveData<Event<Pair<String, VoicemailMessage>>>()
    private data class Snapshot(
        val boxes: List<VoicemailBoxWithMessages>,
        val names: Map<String, String>,
        val avatars: Map<String, String>,
        val matchedCalls: Map<String, String>,
        val today: LocalDateTime
    )

    val callEvent = MutableLiveData<Event<String>>()

    /** Opens the call's detail page, playing the voicemail: document id, box id, media id. */
    val viewDetailsEvent = MutableLiveData<Event<Triple<String, String, String>>>()
    val messageEvent = MutableLiveData<Event<String>>()

    private val expanded = mutableSetOf<String>()
    private val loadingTranscriptions = mutableSetOf<String>()
    private val busy = mutableSetOf<String>()

    // Transcriptions are fetched for the rows' previews once each, one at a time, as on iOS.
    // (Declared before the subscription, which emits while the view model is being built.)
    private val transcriptionsAsked = mutableSetOf<String>()
    private val fetchingTranscriptions = AtomicBoolean(false)

    private var boxes: List<VoicemailBoxWithMessages> = emptyList()
    private var names: Map<String, String> = emptyMap()

    // Media id to the matched contact's picture
    private var avatars: Map<String, String> = emptyMap()

    // Media id to the document id of the call that went to it
    private var matchedCalls: Map<String, String> = emptyMap()
    private var today: LocalDateTime = LocalDateTime.now()

    private val subscription: Disposable = Observable.combineLatest(
        VoicemailBoxService.boxes,
        UserGroupService.getInstance(context).directoryContacts.startWithItem(emptyList()),
        CallHistoryService.getInstance(context).history,
        DateUtils.todaysDate
    ) { boxes, directories, history, date ->
        // Callers named after the directory contact with their number, as on the web client
        val countryCode = PhoneFormatterService.getInstance(context).getPbxCountryCode()
        val names = mutableMapOf<String, String>()
        val avatars = mutableMapOf<String, String>()
        for (box in boxes) {
            for (message in box.messages) {
                val number = VoicemailRules.callerNumber(message) ?: continue
                try {
                    ContactDirectoryRules.findContactAndDirectoryByPhone(
                        directories,
                        number,
                        countryCode
                    )
                        ?.let { (directory, contact) ->
                            CallAvatarRules.contactAvatarUrl(contact)?.let { avatars[message.mediaId] = it }
                            ContactDirectoryRules.toContactMatch(
                                directory,
                                contact
                            ).displayName
                        }
                        ?.let { names[message.mediaId] = it }
                } catch (e: Exception) {
                    Log.w("[Voicemail] Failed to match ${message.mediaId} to a contact: $e")
                }
            }
        }
        // Each matched to the call that preceded it, so it can link to the call's details
        val matches = mutableMapOf<String, String>()
        for (box in boxes) {
            for (message in box.messages) {
                VoicemailRules.matchCall(message, history, countryCode)?.let { matches[message.mediaId] = it }
            }
        }
        Snapshot(boxes, names.toMap(), avatars.toMap(), matches.toMap(), date)
    }.subscribe(
        { snapshot ->
            this.boxes = snapshot.boxes
            this.names = snapshot.names
            this.avatars = snapshot.avatars
            this.matchedCalls = snapshot.matchedCalls
            this.today = snapshot.today
            update()
            fetchTranscriptions()
        },
        { e -> Log.e(e, "[Voicemail] Voicemail list failed") }
    )

    /**
     * Fetches the shown box's transcriptions, newest first; each arrives through the boxes. Another
     * box's are fetched when it's picked.
     */
    private fun fetchTranscriptions() {
        if (!fetchingTranscriptions.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                while (true) {
                    val shown = listOfNotNull(VoicemailRules.selectedBox(boxes, chosenBoxId))
                    val (boxId, message) =
                        VoicemailRules.nextTranscriptionToFetch(shown, transcriptionsAsked) ?: break
                    transcriptionsAsked.add(message.mediaId)
                    VoicemailBoxService.getOrFetchTranscription(boxId, message.mediaId)
                }
            } catch (e: Exception) {
                Log.e(e, "[Voicemail] Failed to fetch transcriptions")
            } finally {
                fetchingTranscriptions.set(false)
            }
        }
    }

    override fun onCleared() {
        subscription.dispose()
        super.onCleared()
    }

    @Synchronized
    private fun update() {
        val formatter = PhoneFormatterService.getInstance(context)
        val box = VoicemailRules.selectedBox(boxes, chosenBoxId)
        val title = box?.let { VoicemailRules.boxTitle(it.box) }.orEmpty()
        val badge = box?.let { badge(it.box) }.orEmpty()
        hasBoxes.postValue(box != null)
        hasManyBoxes.postValue(boxes.size > 1)
        boxTitle.postValue(title)
        boxBadge.postValue(badge)
        boxDescription.postValue(
            listOf(title, badge).filter { it.isNotEmpty() }.joinToString(", ") +
                if (boxes.size > 1) ". " + context.getString(R.string.voicemail_choose_box) else ""
        )

        val list = VoicemailRules.rows(box).map { row ->
            when (row) {
                is VoicemailRow.FolderHeader -> VoicemailListItem(
                    "folder:${row.boxId}:${row.folder}",
                    row,
                    title = VoicemailRules.folderHeading(folderLabel(row.folder), row.count),
                    expanded = row.expanded
                )
                is VoicemailRow.EmptyFolder -> VoicemailListItem(
                    "empty:${row.boxId}:${row.folder}",
                    row,
                    title = emptyFolderLabel(row.folder)
                )
                is VoicemailRow.Message -> {
                    val message = row.message
                    val number = VoicemailRules.callerNumber(message)
                    val formatted = number?.let { formatter.formatDisplayNumber(it) }.orEmpty()
                    val name = names[message.mediaId]
                    val timestamp = message.timestamp?.withZoneSameInstant(ZoneId.systemDefault())
                    val friendlyDate = VoicemailRules.formatDay(
                        timestamp,
                        today.toLocalDate(),
                        context.getString(R.string.today),
                        context.getString(R.string.yesterday),
                        Locale.getDefault()
                    )
                    VoicemailListItem(
                        "message:${message.mediaId}",
                        row,
                        title = name ?: formatted.ifEmpty {
                            context.getString(
                                R.string.unknown_formatted_number
                            )
                        },
                        subtitle = VoicemailRules.messageSubtitle(formatted, message.length),
                        // As iOS: the contact's picture, else initials from their name or the
                        // caller ID name (a number gets the generic icon)
                        avatarUrl = avatars[message.mediaId],
                        avatarName = name ?: message.callerIdName?.takeIf { it.isNotBlank() },
                        date = friendlyDate,
                        expanded = message.mediaId in expanded,
                        canSave = !VoicemailRules.isSaved(message),
                        canCall = number != null,
                        hasCallDetails = message.mediaId in matchedCalls,
                        busy = message.mediaId in busy,
                        loadingTranscription = message.mediaId in loadingTranscriptions,
                        transcription = message.transcription?.text?.trim()?.takeIf { it.isNotEmpty() },
                        transcriptionFetched = message.transcription != null,
                        playState = if (message.mediaId == currentMediaId) playState else VoicemailPlayState.NONE
                    )
                }
            }
        }
        items.postValue(list)
        isEmpty.postValue(boxes.isEmpty())
    }

    private fun folderLabel(folder: String): String = when (folder) {
        VoicemailRules.FOLDER_NEW -> context.getString(R.string.voicemail_folder_new)
        VoicemailRules.FOLDER_SAVED -> context.getString(R.string.voicemail_folder_saved)
        VoicemailRules.FOLDER_DELETED -> context.getString(R.string.voicemail_folder_deleted)
        else -> folder.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun emptyFolderLabel(folder: String): String = when (folder) {
        VoicemailRules.FOLDER_NEW -> context.getString(R.string.voicemail_no_new_messages)
        VoicemailRules.FOLDER_SAVED -> context.getString(R.string.voicemail_no_saved_messages)
        else -> context.getString(R.string.voicemail_no_folder_messages, folderLabel(folder))
    }

    private fun badge(box: VoicemailBox): String = VoicemailRules.boxBadge(
        box,
        { context.getString(R.string.voicemail_box_new, it) },
        { context.resources.getQuantityString(R.plurals.voicemail_box_messages, it, it) }
    )

    /** The mailbox bar: opens the picker of the boxes, with more than one. */
    fun onBoxBarClicked() {
        if (boxes.size < 2) return
        val shown = VoicemailRules.selectedBox(boxes, chosenBoxId)
        val options = boxes.map {
            VoicemailBoxOption(it.box.id, VoicemailRules.boxTitle(it.box), badge(it.box))
        }
        openPickerEvent.value = Event(options to boxes.indexOf(shown))
    }

    /** Shows the mailbox picked. */
    fun onBoxPicked(boxId: String) {
        if (boxId == VoicemailRules.selectedBox(boxes, chosenBoxId)?.box?.id) return
        chosenBoxId = boxId
        expanded.clear()
        update()
        fetchTranscriptions()
    }

    fun onFolderClicked(item: VoicemailListItem) {
        val row = item.row as? VoicemailRow.FolderHeader ?: return
        VoicemailBoxService.toggleFolder(row.boxId, row.folder)
    }

    /** Expands or collapses the message, fetching its transcription the first time. */
    fun onMessageClicked(item: VoicemailListItem) {
        val message = item.message ?: return
        if (!expanded.add(message.mediaId)) {
            expanded.remove(message.mediaId)
            update()
            return
        }
        if (message.transcription != null) {
            update()
            return
        }
        loadingTranscriptions.add(message.mediaId)
        update()
        viewModelScope.launch {
            try {
                VoicemailBoxService.getOrFetchTranscription(item.boxId, message.mediaId)
            } finally {
                loadingTranscriptions.remove(message.mediaId)
                update()
            }
        }
    }

    /** Plays the message, or pauses or resumes it if it's the one in the player. */
    fun onPlayClicked(item: VoicemailListItem) {
        val message = item.message ?: return
        if (message.mediaId == currentMediaId && playState != VoicemailPlayState.NONE) {
            togglePlaybackEvent.value = Event(Unit)
        } else {
            playEvent.value = Event(item.boxId to message)
        }
    }

    fun onPlayerStateChanged(mediaId: String?, state: VoicemailPlayState) {
        currentMediaId = mediaId
        playState = if (mediaId == null) VoicemailPlayState.NONE else state
        update()
    }

    fun onSaveClicked(item: VoicemailListItem) {
        val message = item.message ?: return
        if (!busy.add(message.mediaId)) return
        update()
        viewModelScope.launch {
            try {
                if (!VoicemailBoxService.save(item.boxId, message.mediaId)) {
                    messageEvent.value = Event(
                        context.getString(R.string.voicemail_save_failed, item.title)
                    )
                }
            } finally {
                busy.remove(message.mediaId)
                update()
            }
        }
    }

    fun onDeleteClicked(item: VoicemailListItem) {
        val message = item.message ?: return
        if (message.mediaId in busy) return
        confirmDeleteEvent.value = Event(item.boxId to message)
    }

    /** Deletes once confirmed. The row goes straight away, and comes back if the delete fails. */
    fun delete(boxId: String, message: VoicemailMessage, name: String) {
        if (!busy.add(message.mediaId)) return
        update()
        viewModelScope.launch {
            try {
                if (!VoicemailBoxService.deleteOptimistically(boxId, message.mediaId)) {
                    messageEvent.value = Event(
                        context.getString(R.string.voicemail_delete_failed, name)
                    )
                }
            } finally {
                busy.remove(message.mediaId)
                update()
            }
        }
    }

    /** The name the message is shown under, for dialogs. */
    fun displayName(message: VoicemailMessage): String? =
        names[message.mediaId] ?: VoicemailRules.callerNumber(message)?.let {
            PhoneFormatterService.getInstance(context).formatDisplayNumber(it)
        }

    fun onViewDetailsClicked(item: VoicemailListItem) {
        val message = item.message ?: return
        val documentId = matchedCalls[message.mediaId] ?: return
        viewDetailsEvent.value = Event(Triple(documentId, item.boxId, message.mediaId))
    }

    fun onCallClicked(item: VoicemailListItem) {
        val number = item.message?.let { VoicemailRules.callerNumber(it) } ?: return
        callEvent.value = Event(number)
    }

    fun dialVoicemail() {
        coreContext.dialVoicemail()
    }
}

enum class VoicemailPlayState { NONE, LOADING, PLAYING, PAUSED }

/** A mailbox in the picker: its name and number, and its new or message count. */
data class VoicemailBoxOption(val id: String, val title: String, val badge: String)

/** A row of the Voicemail list, ready to show. */
data class VoicemailListItem(
    val key: String,
    val row: VoicemailRow,
    val title: String = "",
    val subtitle: String = "",
    val avatarUrl: String? = null,
    val avatarName: String? = null,
    val date: String = "",
    val expanded: Boolean = false,
    val canSave: Boolean = false,
    val canCall: Boolean = false,
    // Matched to the call that went to it, whose details it can open
    val hasCallDetails: Boolean = false,
    val busy: Boolean = false,
    val loadingTranscription: Boolean = false,
    val transcription: String? = null,
    // Fetched, so a missing transcription means there isn't one rather than not known yet
    val transcriptionFetched: Boolean = false,
    val playState: VoicemailPlayState = VoicemailPlayState.NONE
) {
    // The row's preview line, as on iOS: the transcription, or that there isn't one once that's
    // known. Not while expanded, where the transcription shows in full.
    val showTranscriptionPreview: Boolean get() = !expanded && transcription != null
    val showNoTranscription: Boolean get() = !expanded && transcriptionFetched && transcription == null

    val boxId: String get() = when (row) {
        is VoicemailRow.FolderHeader -> row.boxId
        is VoicemailRow.EmptyFolder -> row.boxId
        is VoicemailRow.Message -> row.boxId
    }
    val message: VoicemailMessage? get() = (row as? VoicemailRow.Message)?.message
    val isPlaying: Boolean get() = playState == VoicemailPlayState.PLAYING
    val isLoadingAudio: Boolean get() = playState == VoicemailPlayState.LOADING

    // The message in the player, playing, paused or loading, highlighted as on iOS
    val isCurrent: Boolean get() = playState != VoicemailPlayState.NONE

    // The row's text in full, for its long-press tooltip, since each line can be cut short
    val fullText: String get() =
        listOfNotNull(title, transcription.takeIf { showTranscriptionPreview }, subtitle)
            .filter { it.isNotEmpty() }
            .joinToString("\n")
}
