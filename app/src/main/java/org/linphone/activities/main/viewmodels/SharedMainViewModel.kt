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
package org.linphone.activities.main.viewmodels

import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.activities.main.history.data.GroupedCallLogData
import org.linphone.core.*
import org.linphone.models.callhistory.CallHistoryItemViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.Log

class SharedMainViewModel : ViewModel() {
    companion object {
        // Long enough for a cold start to refresh its token, fetch devices and register
        private const val PENDING_CALL_TIMEOUT_MILLIS = 20_000L
    }

    val toggleDrawerEvent = MutableLiveData<Event<Boolean>>()
    val togglePresenceDrawerEvent = MutableLiveData<Event<Boolean>>()

    val layoutChangedEvent = MutableLiveData<Event<Boolean>>()
    var isSlidingPaneSlideable = MutableLiveData<Boolean>()

    /* Call history */
    val selectedHistoryItem = MutableLiveData<CallHistoryItemViewModel>()
    val selectedCallLogGroup = MutableLiveData<GroupedCallLogData>()

    /* Chat */

    val selectedChatRoom = MutableLiveData<ChatRoom>()
    var destructionPendingChatRoom: ChatRoom? = null

    val selectedGroupChatRoom = MutableLiveData<ChatRoom>()

    val filesToShare = MutableLiveData<ArrayList<String>>()

    val textToShare = MutableLiveData<String>()

    val messageToForwardEvent: MutableLiveData<Event<ChatMessage>> by lazy {
        MutableLiveData<Event<ChatMessage>>()
    }

    val isPendingMessageForward = MutableLiveData<Boolean>()

    val contentToOpen = MutableLiveData<Content>()

    var createEncryptedChatRoom: Boolean = corePreferences.forceEndToEndEncryptedChat

    val chatRoomParticipants = MutableLiveData<ArrayList<Address>>()

    var chatRoomSubject: String = ""

    // When using keyboard to share gif or other, see RichContentReceiver & RichEditText classes
    val richContentUri = MutableLiveData<Event<Uri>>()

    val refreshChatRoomInListEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    /* Contacts */

    val selectedContact = MutableLiveData<Friend>()

    // For correct animations directions
    val updateContactsAnimationsBasedOnDestination: MutableLiveData<Event<Int>> by lazy {
        MutableLiveData<Event<Int>>()
    }

    /* Accounts */

    val defaultAccountChanged: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>()
    }

    val accountRemoved: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>()
    }

    val publishPresenceToggled: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>()
    }

    val accountSettingsFragmentOpenedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    /* Call */

    // FIXME: OBSOLETE - remove
    var pendingCallTransfer: Boolean = false

    /* Conference */

    val addressOfConferenceInfoToEdit: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    val participantsListForNextScheduledMeeting: MutableLiveData<Event<ArrayList<Address>>> by lazy {
        MutableLiveData<Event<ArrayList<Address>>>()
    }

    /* Dialer */

    var dialerUri: String = ""

    // Fired with the address when a call that was waiting for registration starts, so a visible
    // dialer can clear it from the dial pad
    val pendingCallStartedEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    private var pendingCallJob: Job? = null

    // Any outgoing call started while waiting means the user has dialled by hand
    private val pendingCallListener = object : CoreListenerStub() {
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            if (state == Call.State.OutgoingInit && pendingCallJob != null) {
                Log.i("[Shared Main] Outgoing call started while waiting, dropping pending call")
                cancelPendingCall()
            }
        }
    }

    // Calls straight away if the account is registered. Otherwise puts the number in the dial pad
    // and calls once registration completes, as long as MainActivity is still alive
    fun callWhenRegistered(to: String) {
        cancelPendingCall()

        if (coreContext.isDefaultAccountReady()) {
            coreContext.startCall(to)
            return
        }

        Log.i("[Shared Main] Account isn't registered yet, waiting to call [$to]")
        dialerUri = to
        coreContext.core.addListener(pendingCallListener)
        pendingCallJob = viewModelScope.launch {
            try {
                if (coreContext.awaitDefaultAccountReady(PENDING_CALL_TIMEOUT_MILLIS)) {
                    Log.i("[Shared Main] Account is registered, calling [$to]")
                    // Remove the listener first so our own call doesn't cancel this job
                    coreContext.core.removeListener(pendingCallListener)
                    if (dialerUri == to) dialerUri = ""
                    pendingCallStartedEvent.value = Event(to)
                    coreContext.startCall(to)
                } else {
                    Log.w("[Shared Main] Account didn't register, leaving [$to] in the dial pad")
                    coreContext.callErrorMessageResourceId.value =
                        Event(AppUtils.getString(R.string.dialer_call_link_not_registered))
                }
            } finally {
                // A cancelled job finishes after its replacement has started, so leave that alone
                if (pendingCallJob == coroutineContext.job) {
                    coreContext.core.removeListener(pendingCallListener)
                    pendingCallJob = null
                }
            }
        }
    }

    fun cancelPendingCall() {
        pendingCallJob?.cancel()
        pendingCallJob = null
        coreContext.core.removeListener(pendingCallListener)
    }

    override fun onCleared() {
        cancelPendingCall()
        super.onCleared()
    }

    // For correct animations directions
    val updateDialerAnimationsBasedOnDestination: MutableLiveData<Event<Int>> by lazy {
        MutableLiveData<Event<Int>>()
    }
}
