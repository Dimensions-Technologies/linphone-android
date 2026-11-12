/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.activities.main.history.viewmodels

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.AsyncSubject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.activities.main.history.data.HistoryEntry
import org.linphone.activities.main.history.data.HistoryListBuilder
import org.linphone.activities.main.history.data.HistoryListItem
import org.linphone.activities.main.history.data.HistoryTexts
import org.linphone.contact.ContactsUpdatedListenerStub
import org.linphone.core.*
import org.linphone.models.callhistory.CallHistoryFieldRules
import org.linphone.models.callhistory.CallHistoryItemViewModel
import org.linphone.models.callhistory.HistoryDirection
import org.linphone.models.callhistory.HistoryTimelineRules
import org.linphone.models.callhistory.PbxType
import org.linphone.models.contact.CallAvatarRules
import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.contact.ContactItemModel
import org.linphone.models.voicemail.VoicemailBoxWithMessages
import org.linphone.models.voicemail.VoicemailMessage
import org.linphone.models.voicemail.VoicemailRules
import org.linphone.services.BrandHighlight
import org.linphone.services.CallHistoryService
import org.linphone.services.ContactsAccessService
import org.linphone.services.DirectoriesService
import org.linphone.services.PhoneFormatterService
import org.linphone.services.TransferService
import org.linphone.services.UserGroupService
import org.linphone.services.VoicemailBoxService
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils
import org.linphone.utils.BrandColors
import org.linphone.utils.Log
import org.threeten.bp.ZoneId

class CallLogsListViewModel : ViewModel() {
    // The selected tab's underline: the brand's highlight, as on iOS
    val highlightColor = BrandHighlight.color(coreContext.context)

    // The Call voicemail button, as on iOS: filled with the brand's primary, its icon white. (iOS's
    // primary-contrast comes out white for every brand colour, so it's white here too.)
    val voicemailButtonColor = BrandHighlight.primary(coreContext.context)
    val voicemailButtonIconColor = MutableLiveData(BrandColors.WHITE)
    private val callHistoryService = CallHistoryService.getInstance(coreContext.context)

    // The History or Missed list's rows, as iOS's (WI 36610): calls by day, a day's calls with the
    // same number grouped under the newest, and the voicemails no call went to
    val callLogs = MutableLiveData<List<HistoryListItem>>()

    val message = MutableLiveData<String>("Loading call history...")

    val filter = MutableLiveData<CallLogsFilter>()

    val showConferencesFilter = MutableLiveData<Boolean>()

    val isContextMenuOpen = MutableLiveData<Boolean>()

    val contactsUpdatedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val makeCallEvent: MutableLiveData<Event<CallHistoryItemViewModel>> by lazy {
        MutableLiveData<Event<CallHistoryItemViewModel>>()
    }

    val playRecordingEvent: MutableLiveData<Event<CallHistoryItemViewModel>> by lazy {
        MutableLiveData<Event<CallHistoryItemViewModel>>()
    }

    val viewDetailsEvent: MutableLiveData<Event<CallHistoryItemViewModel>> by lazy {
        MutableLiveData<Event<CallHistoryItemViewModel>>()
    }

    val addContactEvent: MutableLiveData<Event<CallHistoryItemViewModel>> by lazy {
        MutableLiveData<Event<CallHistoryItemViewModel>>()
    }

    // Whether the context menu's call can be added as a contact (checked as the menu opens)
    val canAddContact = MutableLiveData(false)

    // The context menu's row, as iOS's: its title, and whether it can be called back and has a
    // voicemail (played in the Voicemail tab). A voicemail with no call has no call to show.
    val menuTitle = MutableLiveData("")
    val menuCanCallBack = MutableLiveData(false)
    val menuHasVoicemail = MutableLiveData(false)
    private var menuEntry: HistoryEntry? = null

    // A number to call, for a voicemail with no call
    val callNumberEvent = MutableLiveData<Event<String>>()

    // The voicemail to play in the Voicemail tab: box id and message
    val playVoicemailEvent = MutableLiveData<Event<Pair<String, VoicemailMessage>>>()

    // The entries newest first, and the groups open, by key (see HistoryListBuilder). Declared
    // before init, whose subscriptions can emit straight away.
    @Volatile
    private var entries: List<HistoryEntry> = emptyList()
    private val expandedGroups = mutableSetOf<String>()

    private val texts: HistoryTexts by lazy {
        val context = coreContext.context
        HistoryTexts(
            outgoing = context.getString(R.string.history_direction_outgoing),
            incoming = context.getString(R.string.history_direction_incoming),
            missed = context.getString(R.string.history_direction_missed),
            unanswered = context.getString(R.string.history_direction_unanswered),
            voicemail = context.getString(R.string.history_direction_voicemail),
            conference = context.getString(R.string.history_direction_conference),
            leftVoicemail = context.getString(R.string.history_left_voicemail),
            mobile = context.getString(R.string.history_location_mobile),
            nonGeographic = context.getString(R.string.history_location_non_geographic)
        )
    }

    // Adding a contact saves into Contacts, so it's offered only when the user can use Contacts
    private var contactsAllowed = false

    val transferState = TransferService.getInstance().transferState

    val hasPlaybackPermission = MutableLiveData<Boolean>(false)

    // The Voicemail tab: shown when licensed, permitted and the user has an enabled mailbox
    val showVoicemailTab = MutableLiveData(false)
    val newVoicemailCount = MutableLiveData(0)
    val isRefreshingVoicemail = MutableLiveData(false)

    val destroy = AsyncSubject.create<Unit>()

    private val listener: CoreListenerStub = object : CoreListenerStub() {
        override fun onCallLogUpdated(core: Core, log: CallLog) {
            updateCallLogs()
        }
    }

    private val contactsUpdatedListener = object : ContactsUpdatedListenerStub() {
        override fun onContactsUpdated() {
            Log.i("[Call Logs] Contacts have changed")
            contactsUpdatedEvent.value = Event(true)
        }
    }

    init {
        filter.value = CallLogsFilter.ALL
        updateCallLogs()

        showConferencesFilter.value = LinphoneUtils.isRemoteConferencingAvailable()

        coreContext.core.addListener(listener)
        coreContext.contactsManager.addListener(contactsUpdatedListener)

        // The calls, with the voicemails they left (and those with no call), and the contacts for
        // their pictures
        val voicemailBoxes = Observable.combineLatest(
            VoicemailBoxService.isAvailable,
            VoicemailBoxService.boxes
        ) { available, boxes -> if (available) boxes else emptyList() }
            .startWithItem(emptyList())
        Observable.combineLatest(
            callHistoryService.formattedHistory,
            voicemailBoxes,
            UserGroupService.getInstance(coreContext.context).directoryContacts.startWithItem(emptyList())
        ) { history, boxes, directories -> buildEntries(history, boxes, directories) }
            .takeUntil(destroy)
            .subscribe(
                { built ->
                    entries = built
                    updateCallLogs()
                },
                { error -> Log.e(error, "Failed to update call history.") }
            )

        val msgSubscription = callHistoryService.historyMessage
            .takeUntil(destroy)
            .subscribe(
                { msg -> message.postValue(msg) },
                { error -> Log.e(error, "Failed to update the call history message.") }
            )

        VoicemailBoxService.isAvailable
            .takeUntil(destroy)
            .subscribe(
                { available ->
                    showVoicemailTab.postValue(available)
                    // Voicemail can be taken away (licence, permission or mailbox) while it's shown
                    if (!available && filter.value == CallLogsFilter.VOICEMAIL) {
                        coreContext.handler.post { showAllCallLogs() }
                    }
                },
                { error -> Log.e(error, "Failed to check voicemail availability.") }
            )
        ContactsAccessService.isAvailable
            .takeUntil(destroy)
            .subscribe(
                { available -> contactsAllowed = available },
                { error -> Log.e(error, "Failed to check the Contacts licence and permission.") }
            )
        VoicemailBoxService.newCount
            .takeUntil(destroy)
            .subscribe(
                { count -> newVoicemailCount.postValue(count) },
                { error -> Log.e(error, "Failed to count new voicemails.") }
            )
        VoicemailBoxService.isRefreshing
            .takeUntil(destroy)
            .subscribe(
                { refreshing -> isRefreshingVoicemail.postValue(refreshing) },
                { error -> Log.e(error, "Failed to follow the voicemail refresh.") }
            )
    }

    override fun onCleared() {
        coreContext.contactsManager.removeListener(contactsUpdatedListener)
        coreContext.core.removeListener(listener)

        destroy.onNext(Unit)
        destroy.onComplete()

        super.onCleared()
    }

    // Whether the list holds only missed calls; it isn't rebuilt (which redraws every row) for a
    // tab that shows the same calls, e.g. coming back to History from Voicemail
    private var listIsMissedOnly: Boolean? = null

    fun showAllCallLogs() {
        filter.value = CallLogsFilter.ALL
        if (listIsMissedOnly != false) updateCallLogs()
    }

    fun showOnlyMissedCallLogs() {
        filter.value = CallLogsFilter.MISSED
        if (listIsMissedOnly != true) updateCallLogs()
    }

    fun showVoicemail() {
        filter.value = CallLogsFilter.VOICEMAIL
    }

    fun showOnlyConferenceCallLogs() {
        filter.value = CallLogsFilter.CONFERENCE
        updateCallLogs()
    }

    // Gateway calls can't be deleted from the app, as on the web client and iOS
    fun deleteCallLogGroups(listToDelete: List<HistoryListItem>) {
        Log.w("[Call Logs] ${listToDelete.size} row(s) can't be deleted")
    }


    /** Opens or closes a group of calls with the same number, as iOS's ToggleExpanded. */
    fun toggleGroup(row: HistoryListItem) {
        synchronized(expandedGroups) {
            if (!expandedGroups.remove(row.groupKey)) expandedGroups.add(row.groupKey)
        }
        updateCallLogs()
    }

    private fun updateCallLogs() {
        val currentFilter = filter.value ?: CallLogsFilter.ALL
        listIsMissedOnly = currentFilter == CallLogsFilter.MISSED
        val all = entries
        val (shown, list) = when (currentFilter) {
            // As the web client, only calls: a voicemail with no call isn't one
            CallLogsFilter.MISSED ->
                all.filter { it.call != null && HistoryTimelineRules.isMissed(it.direction) } to "missed"
            CallLogsFilter.CONFERENCE ->
                all.filter { it.direction == HistoryDirection.CONFERENCE } to "conference"
            else -> all to "all"
        }
        val expanded = synchronized(expandedGroups) { expandedGroups.toSet() }
        try {
            callLogs.postValue(
                HistoryListBuilder.build(
                    shown,
                    list,
                    expanded,
                    PhoneFormatterService.getInstance(coreContext.context).getPbxCountryCode(),
                    texts
                )
            )
        } catch (e: Exception) {
            Log.e(e, "[Call Logs] Failed to build the history list")
        }
    }

    /**
     * The calls, each with the voicemail it left, and the voicemails no call went to (but not those
     * from before the oldest call held, as they'd stretch the history back months), newest first,
     * as iOS's HistoryPageViewModel.Entries.
     */
    private fun buildEntries(
        history: List<CallHistoryItemViewModel>,
        boxes: List<VoicemailBoxWithMessages>,
        directories: List<Pair<ContactDirectoryModel, List<ContactItemModel>>>
    ): List<HistoryEntry> {
        val context = coreContext.context
        val formatter = PhoneFormatterService.getInstance(context)
        val countryCode = formatter.getPbxCountryCode()
        val users = DirectoriesService.getInstance(context).currentUsers()
        val unknown = context.getString(R.string.unknown_formatted_number)
        val voicemailName = context.getString(R.string.contact_match_voicemail)

        // The picture of the contact (or internal user) with the number
        val avatars = HashMap<String, String?>()
        fun avatarOf(number: String?): String? {
            if (number.isNullOrBlank()) return null
            return avatars.getOrPut(number) {
                try {
                    ContactDirectoryRules.findContactAndDirectoryByPhone(directories, number, countryCode)
                        ?.let { CallAvatarRules.contactAvatarUrl(it.second) }
                        ?: CallAvatarRules.userAvatarUrl(CallAvatarRules.findUserByNumberOrEmail(users, number))
                } catch (e: Exception) {
                    Log.w("[Call Logs] Failed to find the picture for $number: $e")
                    null
                }
            }
        }

        // Each voicemail matched to the call that went to it
        val calls = history.map { it.call }
        val byCall = HashMap<String, Pair<String, VoicemailMessage>>()
        val unlinked = mutableListOf<Pair<String, VoicemailMessage>>()
        for (box in boxes) {
            for (message in box.messages) {
                val documentId = try {
                    VoicemailRules.matchCall(message, calls, countryCode)
                } catch (e: Exception) {
                    null
                }
                if (documentId != null) {
                    if (documentId !in byCall) byCall[documentId] = box.box.id to message
                } else {
                    unlinked.add(box.box.id to message)
                }
            }
        }

        val zone = ZoneId.systemDefault()
        val list = history.mapNotNull { item ->
            val time = item.call.startTime?.withZoneSameInstant(zone) ?: return@mapNotNull null
            val voicemail = byCall[item.call.documentId]
            val direction = HistoryTimelineRules.direction(item.call, voicemail != null)
            val conference = direction == HistoryDirection.CONFERENCE
            val name = item.fields.firstOrNull().orEmpty().ifEmpty { unknown }
            val route = CallHistoryFieldRules.route(item.call)
                ?.takeUnless { item.number.isNotEmpty() && it.contains(item.number) }
            HistoryEntry(
                time = time,
                // As the web client, conferences are never grouped
                key = if (conference) null else HistoryTimelineRules.numberKey(item.number, countryCode),
                direction = direction,
                name = name,
                subtitle = HistoryTimelineRules.subtitle(item.fields.drop(1)),
                via = route?.let { context.getString(R.string.call_history_via_route, it) },
                number = item.number.ifEmpty { null },
                talkSeconds = item.call.talkTime ?: 0.0,
                avatarUrl = if (conference) null else avatarOf(item.number),
                nameIsCaller = name != unknown && name != voicemailName,
                call = item,
                voicemailBoxId = voicemail?.first,
                voicemail = voicemail?.second
            )
        }.toMutableList()

        val oldest = list.minOfOrNull { it.time.toEpochSecond() } ?: Long.MIN_VALUE
        for ((boxId, message) in unlinked) {
            val time = message.timestamp?.withZoneSameInstant(zone) ?: continue
            if (time.toEpochSecond() < oldest) continue
            val number = VoicemailRules.callerNumber(message)
            val formatted = number?.let { formatter.formatDisplayNumber(it) }
            val name = voicemailCallerName(message, number, countryCode, directories)
            list.add(
                HistoryEntry(
                    time = time,
                    key = HistoryTimelineRules.numberKey(number, countryCode),
                    // A voicemail means a call nobody answered, so it's shown as a missed call that
                    // left one
                    direction = HistoryDirection.MISSED_VOICEMAIL,
                    name = name ?: formatted ?: unknown,
                    subtitle = if (name != null) formatted.orEmpty() else "",
                    number = number,
                    avatarUrl = avatarOf(number),
                    nameIsCaller = name != null,
                    voicemailBoxId = boxId,
                    voicemail = message
                )
            )
        }
        return list.sortedByDescending { it.time.toEpochSecond() }
    }

    /**
     * The caller of a voicemail with no call: the directory contact with their number, else the
     * caller ID name when it isn't just the number.
     */
    private fun voicemailCallerName(
        message: VoicemailMessage,
        number: String?,
        countryCode: String,
        directories: List<Pair<ContactDirectoryModel, List<ContactItemModel>>>
    ): String? {
        if (number != null) {
            try {
                ContactDirectoryRules.findContactAndDirectoryByPhone(directories, number, countryCode)
                    ?.let { (directory, contact) ->
                        ContactDirectoryRules.toContactMatch(directory, contact).displayName
                    }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            } catch (e: Exception) {
                Log.w("[Call Logs] Failed to match voicemail ${message.mediaId} to a contact: $e")
            }
        }
        val callerName = message.callerIdName
        if (callerName.isNullOrBlank() || ContactDirectoryRules.toMatchable(callerName) != null) return null
        return callerName
    }

    val contextMenuTranslateY = MutableLiveData<Float>()
    private val contextMenuAnimator: ValueAnimator by lazy {
        ValueAnimator.ofFloat(
            AppUtils.getDimension(R.dimen.voip_call_extra_buttons_translate_y),
            0f
        ).apply {
            addUpdateListener {
                val value = it.animatedValue as Float
                contextMenuTranslateY.value = value
            }
            duration = if (corePreferences.enableAnimations) 500 else 0
        }
    }

    /** Offers what can be done with the row's call (or voicemail), as iOS's ShowContextMenu. */
    fun showContextMenu(entry: HistoryEntry) {
        val call = entry.call
        menuEntry = entry
        menuTitle.value = entry.name
        menuCanCallBack.value = !entry.number.isNullOrEmpty() && call?.call?.isConference != true &&
            call?.call?.pbxType != PbxType.Teams
        menuHasVoicemail.value = entry.voicemail != null && entry.voicemailBoxId != null
        canAddContact.value = call != null && canAddContact(call)
        // Every call has its details, as on the web client
        if (menuCanCallBack.value == true || menuHasVoicemail.value == true || canAddContact.value == true ||
            call?.call?.documentId?.isNotEmpty() == true
        ) {
            contextMenuAnimator.start()
            isContextMenuOpen.value = true
        }
    }

    fun callBack() {
        val entry = menuEntry ?: return
        val call = entry.call
        if (call != null) {
            makeCall(call)
        } else {
            hideContextMenu(false)
            entry.number?.let { callNumberEvent.value = Event(it) }
        }
    }

    fun playVoicemail() {
        val entry = menuEntry ?: return
        hideContextMenu(false)
        val boxId = entry.voicemailBoxId ?: return
        val message = entry.voicemail ?: return
        playVoicemailEvent.value = Event(boxId to message)
    }

    // As on the web client: an unmatched external number, and a directory to add it to. Calls are
    // matched to directory contacts as the history is formatted (CallHistoryContactMatcher).
    private fun canAddContact(call: CallHistoryItemViewModel): Boolean =
        contactsAllowed &&
            call.canAddContact &&
            DirectoriesService.getInstance(coreContext.context).contributorDirectories().isNotEmpty()

    fun hideContextMenu(skipAnimation: Boolean) {
        // Animation must be skipped when called from Fragment's onPause() !
        if (skipAnimation) {
            contextMenuTranslateY.value = AppUtils.getDimension(
                R.dimen.voip_call_extra_buttons_translate_y
            )
        } else {
            contextMenuAnimator.reverse()
        }
        isContextMenuOpen.value = false
    }

    fun makeCall(call: CallHistoryItemViewModel) {
        hideContextMenu(false)
        makeCallEvent.value = Event(call)
    }

    fun playRecording(call: CallHistoryItemViewModel) {
        hideContextMenu(false)
        playRecordingEvent.value = Event(call)
    }

    fun viewDetails(call: CallHistoryItemViewModel) {
        hideContextMenu(false)
        viewDetailsEvent.value = Event(call)
    }

    fun addContact(call: CallHistoryItemViewModel) {
        hideContextMenu(false)
        addContactEvent.value = Event(call)
    }
}

enum class CallLogsFilter {
    ALL,
    MISSED,
    CONFERENCE,
    VOICEMAIL
}
