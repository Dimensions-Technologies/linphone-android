package org.linphone.activities.main.history.viewmodels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import java.util.Locale
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.models.contact.ContactDirectoryRules
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

    private var boxes: List<VoicemailBoxWithMessages> = emptyList()
    private var names: Map<String, String> = emptyMap()

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
        Snapshot(boxes, names.toMap(), matches.toMap(), date)
    }.subscribe(
        { snapshot ->
            this.boxes = snapshot.boxes
            this.names = snapshot.names
            this.matchedCalls = snapshot.matchedCalls
            this.today = snapshot.today
            update()
        },
        { e -> Log.e(e, "[Voicemail] Voicemail list failed") }
    )

    override fun onCleared() {
        subscription.dispose()
        super.onCleared()
    }

    @Synchronized
    private fun update() {
        val formatter = PhoneFormatterService.getInstance(context)
        val list = VoicemailRules.rows(boxes).map { row ->
            when (row) {
                is VoicemailRow.BoxHeader -> VoicemailListItem(
                    "box:${row.box.box.id}",
                    row,
                    title = row.box.box.name.orEmpty(),
                    subtitle = row.box.box.number.orEmpty(),
                    expanded = row.expanded,
                    showName = row.showName
                )
                is VoicemailRow.FolderHeader -> VoicemailListItem(
                    "folder:${row.boxId}:${row.folder}",
                    row,
                    title = folderLabel(row.folder),
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
                    val friendlyDate = DateUtils.formatFriendlyDate(timestamp, today)
                    VoicemailListItem(
                        "message:${message.mediaId}",
                        row,
                        title = name ?: formatted.ifEmpty {
                            context.getString(
                                R.string.unknown_formatted_number
                            )
                        },
                        subtitle = if (name != null) formatted else "",
                        date = friendlyDate,
                        time = DateUtils.toLocaleHMString(timestamp),
                        length = VoicemailRules.formatLength(message.length),
                        expanded = message.mediaId in expanded,
                        canSave = !VoicemailRules.isSaved(message),
                        canCall = number != null,
                        hasCallDetails = message.mediaId in matchedCalls,
                        busy = message.mediaId in busy,
                        loadingTranscription = message.mediaId in loadingTranscriptions,
                        transcription = message.transcription?.text?.takeIf { it.isNotBlank() },
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

    fun onBoxClicked(item: VoicemailListItem) {
        val row = item.row as? VoicemailRow.BoxHeader ?: return
        if (row.showName) VoicemailBoxService.toggleBox(item.boxId)
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

/** A row of the Voicemail list, ready to show. */
data class VoicemailListItem(
    val key: String,
    val row: VoicemailRow,
    val title: String = "",
    val subtitle: String = "",
    val date: String = "",
    val time: String = "",
    val length: String = "",
    val expanded: Boolean = false,
    // Box headers: the name shows only with more than one box
    val showName: Boolean = false,
    val canSave: Boolean = false,
    val canCall: Boolean = false,
    // Matched to the call that went to it, whose details it can open
    val hasCallDetails: Boolean = false,
    val busy: Boolean = false,
    val loadingTranscription: Boolean = false,
    val transcription: String? = null,
    val playState: VoicemailPlayState = VoicemailPlayState.NONE
) {
    val boxId: String get() = when (row) {
        is VoicemailRow.BoxHeader -> row.box.box.id
        is VoicemailRow.FolderHeader -> row.boxId
        is VoicemailRow.EmptyFolder -> row.boxId
        is VoicemailRow.Message -> row.boxId
    }
    val message: VoicemailMessage? get() = (row as? VoicemailRow.Message)?.message
    val isPlaying: Boolean get() = playState == VoicemailPlayState.PLAYING
    val isLoadingAudio: Boolean get() = playState == VoicemailPlayState.LOADING
}
