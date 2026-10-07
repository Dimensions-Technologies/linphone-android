package org.linphone.activities.main.history.viewmodels

import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.reactivex.rxjava3.disposables.Disposable
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.awaitFirst
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.activities.main.history.GatewayAudioUris
import org.linphone.models.CustomerLicence
import org.linphone.models.LicenceDisplayState
import org.linphone.models.callhistory.CallRecordingInfo
import org.linphone.models.callsession.CallDetailRules
import org.linphone.models.callsession.CallDetailTab
import org.linphone.models.callsession.CallLabelFormatter
import org.linphone.models.callsession.CallSession
import org.linphone.models.callsession.Conversation
import org.linphone.models.callsession.DetailRow
import org.linphone.models.callsession.InteractionTag
import org.linphone.models.callsession.TextStyle
import org.linphone.models.callsession.TimelineRow
import org.linphone.models.callsession.TranscriptLine
import org.linphone.models.callsession.UiText
import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.contact.ContactItemModel
import org.linphone.services.APIClientService
import org.linphone.services.LicenceService
import org.linphone.services.PhoneFormatterService
import org.linphone.services.RecordingsService
import org.linphone.services.UserGroupService
import org.linphone.services.UserService
import org.linphone.utils.Log
import org.threeten.bp.ZoneId

/**
 * The call detail page, ported from the web client's conversation component: the call's parties
 * and start, a timeline of who spoke, a player for its recordings (or a voicemail), and the
 * Overview, Transcription, Call Data, Tags and Info tabs.
 */
class CallDetailViewModel : ViewModel() {
    /** An item of the player's playlist. */
    data class PlaylistItem(val uri: Uri, val label: String, val mediaId: String)

    private val context get() = coreContext.context
    private val api get() = APIClientService(context).getUCGatewayService()

    val isLoading = MutableLiveData(true)
    val loadFailed = MutableLiveData(false)
    val title = MutableLiveData("")
    val start = MutableLiveData("")
    val tabs = MutableLiveData<List<CallDetailTab>>(emptyList())
    val selectedTab = MutableLiveData<CallDetailTab?>(null)
    val rows = MutableLiveData<List<DetailRow>>(emptyList())
    val timeline = MutableLiveData<List<TimelineRow>>(emptyList())
    val playlist = MutableLiveData<List<PlaylistItem>>(emptyList())

    /** The recordings in the playlist, in order, listed under the player to pick from. */
    val recordings = MutableLiveData<List<CallRecordingInfo>>(emptyList())

    /** The transcript paragraph (its idx) playing now, or -1. */
    val highlightedIndex = MutableLiveData(-1)

    private var documentId: String? = null
    private var session: CallSession? = null
    private var segmentIndex = 0
    private var licence: CustomerLicence? = null
    private var canPlayRecordings = false
    private var conversation: Conversation? = null
    private var isConversationLoading = true
    private var tagDefinitions: List<InteractionTag> = emptyList()
    private var directories: List<Pair<ContactDirectoryModel, List<ContactItemModel>>> = emptyList()
    private var contact: Pair<ContactDirectoryModel, ContactItemModel>? = null
    private var transcriptLines: List<TranscriptLine> = emptyList()
    private var currentTab: CallDetailTab? = null

    private var directoriesSubscription: Disposable? = null

    private val countryCode get() = PhoneFormatterService.getInstance(context).getPbxCountryCode()

    /** Loads the call, once. With a voicemail, its message plays instead of the recordings. */
    fun load(documentId: String, voicemailBoxId: String?, voicemailMediaId: String?) {
        if (this.documentId == documentId) return
        this.documentId = documentId

        directoriesSubscription = UserGroupService.getInstance(context).directoryContacts.subscribe(
            { directories ->
                this.directories = directories
                session?.let { onDirectoriesChanged(it) }
            },
            { e -> Log.e(e, "[Call Detail] Directories failed") }
        )

        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    val response = api.getCallSession(documentId)
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code()}")
                    response.body()?.data ?: throw Exception("No session")
                }
                this@CallDetailViewModel.session = session
                segmentIndex = CallDetailRules.defaultSegmentIndex(session)

                licence = LicenceService.getLicence()
                canPlayRecordings = try {
                    UserService.getInstance(context).user.awaitFirst()
                        .hasPermission(CallDetailRules.RECORDING_PERMISSION)
                } catch (e: Exception) {
                    false
                }

                onDirectoriesChanged(session)
                isLoading.value = false
                update()

                val recordings = loadRecordings(session)
                val playingVoicemail = voicemailBoxId != null && voicemailMediaId != null
                this@CallDetailViewModel.recordings.value = if (playingVoicemail) emptyList() else recordings.filter { it.id != null }
                playlist.value = if (playingVoicemail) {
                    listOf(
                        PlaylistItem(
                            GatewayAudioUris.voicemail(voicemailBoxId!!, voicemailMediaId!!),
                            context.getString(R.string.call_detail_voicemail),
                            "voicemail-$voicemailMediaId"
                        )
                    )
                } else {
                    recordings.mapIndexedNotNull { i, rec ->
                        rec.id?.let {
                            PlaylistItem(
                                GatewayAudioUris.recording(session.id.orEmpty(), it),
                                context.getString(R.string.call_detail_recording, i + 1),
                                it
                            )
                        }
                    }
                }

                if (CallDetailRules.showTags(licence)) loadTagDefinitions()
                loadConversation(session, recordings)
            } catch (e: Exception) {
                Log.e(e, "[Call Detail] Failed to load session $documentId")
                isLoading.value = false
                loadFailed.value = true
            }
        }
    }

    override fun onCleared() {
        directoriesSubscription?.dispose()
        super.onCleared()
    }

    // The recordings endpoint is only asked by those allowed to play them
    private suspend fun loadRecordings(session: CallSession): List<CallRecordingInfo> {
        if (!canPlayRecordings || session.id == null) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                RecordingsService.getInstance(context).getRecordingInfoList(
                    session.id
                )
            }
        } catch (e: Exception) {
            Log.e(e, "[Call Detail] Failed to load recordings")
            emptyList()
        }
    }

    private suspend fun loadTagDefinitions() {
        tagDefinitions = try {
            withContext(Dispatchers.IO) { api.getInteractionTags().body().orEmpty() }
        } catch (e: Exception) {
            Log.e(e, "[Call Detail] Failed to load tag definitions")
            emptyList()
        }
        update()
    }

    private suspend fun loadConversation(session: CallSession, recordings: List<CallRecordingInfo>) {
        isConversationLoading = true
        update()
        conversation = if (!CallDetailRules.hasTranscription(session)) {
            Conversation(error = context.getString(R.string.call_detail_not_transcribed))
        } else {
            val recordingId = CallDetailRules.bestRecordingId(segment())
            if (recordingId == null || recordings.none { it.id == recordingId }) {
                Conversation(
                    error = context.getString(R.string.call_detail_no_recording_permission)
                )
            } else {
                fetchConversation(recordingId)
            }
        }
        isConversationLoading = false

        val segment = segment()
        transcriptLines = CallDetailRules.transcriptLines(
            conversation?.transcription,
            CallDetailRules.channelLabels(
                segment,
                conversation?.summary?.speakers?.map,
                countryCode
            ),
            context.getString(R.string.call_detail_unknown)
        )
        timeline.value = CallDetailRules.timelineRows(
            conversation?.transcription,
            CallDetailRules.channelLabels(
                timelineSegment(),
                conversation?.summary?.speakers?.map,
                countryCode
            )
        )
        update()
    }

    private suspend fun fetchConversation(recordingId: String): Conversation = withContext(
        Dispatchers.IO
    ) {
        try {
            val response = api.getConversationSummary(recordingId)
            if (!response.isSuccessful) throw Exception("HTTP ${response.code()}")
            val summary = response.body() ?: throw Exception("No summary")
            val transcription = try {
                summary.transcriptionId?.let { api.getConversationTranscription(it).body() }
            } catch (e: Exception) {
                Log.e(e, "[Call Detail] Failed to load the transcription")
                null
            }
            Conversation(summary, transcription)
        } catch (e: Exception) {
            Log.e(e, "[Call Detail] Failed to load the conversation of $recordingId")
            Conversation(error = e.message)
        }
    }

    private fun segment() = session?.calls?.getOrNull(segmentIndex)

    // The timeline labels its channels from the segment with the analysed recording
    private fun timelineSegment() = conversation?.summary?.recordingId?.let { recordingId ->
        session?.calls?.firstOrNull { call ->
            call.conns.orEmpty().any { recordingId in it.recording?.recordingIds.orEmpty() }
        }
    }

    private fun onDirectoriesChanged(session: CallSession) {
        val countryCode = countryCode
        // The header names the last segment's parties
        session.calls?.lastOrNull()?.let { last ->
            title.postValue(
                CallLabelFormatter.callLabel(last, countryCode) { number ->
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
                }
            )
            start.postValue(
                last.start?.let {
                    CallDetailRules.formatDateTime(
                        it,
                        ZoneId.systemDefault(),
                        Locale.getDefault()
                    )
                }.orEmpty()
            )
        }
        contact = CallDetailRules.contactFor(session, segment(), directories, countryCode)
        update()
    }

    fun selectTab(tab: CallDetailTab) {
        currentTab = tab
        update()
    }

    fun onPlaybackPosition(seconds: Double?) {
        val index = if (seconds == null) {
            -1
        } else {
            CallDetailRules.highlightedIndex(
                conversation?.transcription,
                seconds
            )
        }
        if (index != highlightedIndex.value) highlightedIndex.value = index
    }

    /** The transcript as text, for its copy button. */
    fun transcriptText(): String = CallDetailRules.transcriptText(
        transcriptLines,
        context.getString(R.string.call_detail_call_transcription),
        context.getString(R.string.call_detail_low_confidence)
    )

    @Synchronized
    private fun update() {
        val session = session ?: return
        val licence = licence
        val visible = CallDetailRules.visibleTabs(
            canPlayRecordings,
            CallDetailRules.hasTranscription(session),
            licence?.hasFeature(CustomerLicence.RECORDING_TRANSCRIPTION) == true,
            CallDetailRules.showTags(licence),
            contact != null
        )
        tabs.postValue(visible)
        val tab = currentTab?.takeIf { it in visible } ?: visible.first()
        currentTab = tab
        selectedTab.postValue(tab)

        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        rows.postValue(
            when (tab) {
                CallDetailTab.OVERVIEW -> CallDetailRules.overviewRows(
                    conversation,
                    isConversationLoading,
                    licence?.hasFeature(CustomerLicence.RECORDING_TRANSCRIPTION) == true,
                    licence?.displayState(CustomerLicence.RECORDING_ANALYTICS) ?: LicenceDisplayState.HIDDEN
                )
                CallDetailTab.TRANSCRIPTION -> {
                    val transcript = CallDetailRules.transcriptionRows(
                        conversation,
                        isConversationLoading,
                        transcriptLines
                    )
                    // The copy button heads a transcript
                    if (transcript.any { it is DetailRow.Transcript }) {
                        listOf(
                            DetailRow.Card(
                                UiText.Res(R.string.call_detail_call_transcription),
                                transcriptText()
                            )
                        ) + transcript
                    } else {
                        transcript
                    }
                }
                CallDetailTab.DATA -> listOf(
                    DetailRow.Card(UiText.Res(R.string.call_detail_tab_data))
                ) +
                    CallDetailRules.callDataRows(segment(), session.id, countryCode, zone, locale)
                CallDetailTab.TAGS -> {
                    val tags = CallDetailRules.tagRows(session.tags, tagDefinitions, zone, locale)
                    listOf(DetailRow.Card(UiText.Res(R.string.call_detail_tag_data))) + tags.ifEmpty {
                        listOf(
                            DetailRow.Text(
                                UiText.Res(R.string.call_detail_no_tags),
                                TextStyle.MUTED
                            )
                        )
                    }
                }
                CallDetailTab.INFO -> contact?.let { (directory, item) ->
                    listOf(DetailRow.Card(UiText.Res(R.string.call_detail_contact_info))) +
                        CallDetailRules.infoRows(directory, item)
                }.orEmpty()
            }
        )
    }
}
