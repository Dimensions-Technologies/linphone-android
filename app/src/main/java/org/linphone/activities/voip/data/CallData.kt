/*
 * Copyright (c) 2010-2021 Belledonne Communications SARL.
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
package org.linphone.activities.voip.data

import android.view.View
import android.widget.Toast
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import io.reactivex.rxjava3.disposables.Disposable
import java.util.*
import kotlinx.coroutines.*
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.activities.main.contact.viewmodels.UserGroupViewModel
import org.linphone.activities.voip.TransferState
import org.linphone.compatibility.Compatibility
import org.linphone.contact.GenericContactData
import org.linphone.core.*
import org.linphone.models.realtime.ContactMatch
import org.linphone.services.CallContactMatchService
import org.linphone.services.DirectoriesService
import org.linphone.services.TransferService
import org.linphone.utils.AppUtils
import org.linphone.utils.LinphoneUtils
import org.linphone.utils.Log

open class CallData(val call: Call) : GenericContactData(call.remoteAddress) {
    interface CallContextMenuClickListener {
        fun onShowContextMenu(anchor: View, callData: CallData)
    }

    val displayableAddress = MutableLiveData<String>()

    val isPaused = MutableLiveData<Boolean>()
    val isRemotelyPaused = MutableLiveData<Boolean>()
    val canBePaused = MutableLiveData<Boolean>()

    val isRecording = MutableLiveData<Boolean>()
    val isRemotelyRecorded = MutableLiveData<Boolean>()

    val isInRemoteConference = MutableLiveData<Boolean>()
    val remoteConferenceSubject = MutableLiveData<String>()
    val isConferenceCall = MediatorLiveData<Boolean>()
    val conferenceParticipants = MutableLiveData<List<ConferenceInfoParticipantData>>()
    val conferenceParticipantsCountLabel = MutableLiveData<String>()

    val isOutgoing = MutableLiveData<Boolean>()
    val isIncoming = MutableLiveData<Boolean>()

    var chatRoom: ChatRoom? = null

    var contextMenuClickListener: CallContextMenuClickListener? = null

    private var timer: Timer? = null

    private var matchSubscription: Disposable? = null

    private val listener = object : CallListenerStub() {
        override fun onStateChanged(call: Call, state: Call.State, message: String) {
            if (call != this@CallData.call) return
            Log.i("[Call] State changed: $state")

            update()

            if (call.state == Call.State.UpdatedByRemote) {
                val remoteVideo = call.remoteParams?.isVideoEnabled ?: false
                val localVideo = call.currentParams.isVideoEnabled
                if (remoteVideo && !localVideo) {
                    // User has 30 secs to accept or decline call update
                    startVideoUpdateAcceptanceTimer()
                }
            } else if (state == Call.State.End || state == Call.State.Released || state == Call.State.Error) {
                timer?.cancel()
            } else if (state == Call.State.StreamsRunning) {
                // Stop call update timer once user has accepted or declined call update
                timer?.cancel()
            }
        }

        override fun onRemoteRecording(call: Call, recording: Boolean) {
            Log.i("[Call] Remote recording changed: $recording")
            isRemotelyRecorded.value = recording
        }

        override fun onSnapshotTaken(call: Call, filePath: String) {
            Log.i("[Call] Snapshot taken: $filePath")
            val content = Factory.instance().createContent()
            content.filePath = filePath
            content.type = "image"
            content.subtype = "jpeg"
            content.name = filePath.substring(filePath.lastIndexOf("/") + 1)

            scope.launch {
                if (Compatibility.addImageToMediaStore(coreContext.context, content)) {
                    Log.i("[Call] Added snapshot ${content.name} to Media Store")
                    val message = String.format(
                        AppUtils.getString(R.string.call_screenshot_taken),
                        content.name
                    )
                    Toast.makeText(coreContext.context, message, Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("[Call] Something went wrong while copying file to Media Store...")
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        call.addListener(listener)
        isRemotelyRecorded.value = call.remoteParams?.isRecording
        displayableAddress.value = LinphoneUtils.getDisplayableAddress(call.remoteAddress)

        isConferenceCall.addSource(remoteConferenceSubject) {
            isConferenceCall.value = remoteConferenceSubject.value.orEmpty().isNotEmpty() || conferenceParticipants.value.orEmpty().isNotEmpty()
        }
        isConferenceCall.addSource(conferenceParticipants) {
            isConferenceCall.value = remoteConferenceSubject.value.orEmpty().isNotEmpty() || conferenceParticipants.value.orEmpty().isNotEmpty()
        }

        update()

        matchSubscription = CallContactMatchService.matches(call).subscribe(
            { matches -> scope.launch { applyContactMatches(matches) } },
            { e -> Log.e("[Call] Contact matches failed", e) }
        )
    }

    /**
     * Names the call after its contact matches, as the web client's call view does: the first match's
     * name, and its full contact (e.g. for the avatar) when exactly one contact matches.
     */
    private suspend fun applyContactMatches(matches: List<ContactMatch>) {
        CallContactMatchService.displayName(call)?.let { displayName.value = it }
        if (matches.isEmpty()) return

        if (matches.size == 1) {
            val match = matches.single()
            val directoryId = match.directoryId ?: return
            val contactId = match.contactId ?: return
            val item = DirectoriesService.getInstance(coreContext.context).getContact(
                directoryId,
                contactId
            )
            // A device contact for the number would otherwise name the call instead
            contact.value = item?.let { UserGroupViewModel.createFriendFromContactItemModel(it) }
        } else {
            contact.value = null
        }
    }

    override fun destroy() {
        matchSubscription?.dispose()
        call.removeListener(listener)
        timer?.cancel()
        scope.cancel()

        super.destroy()
    }

    fun togglePause() {
        if (isCallPaused()) {
            resume()
        } else {
            pause()
        }
    }

    fun pause() {
        call.pause()
    }

    fun resume() {
        call.resume()
        TransferService.getInstance().transferState.value = TransferState.NONE
    }

    fun accept() {
        call.accept()
    }

    fun terminate() {
        call.terminate()
    }

    fun toggleRecording() {
        if (call.params.isRecording) {
            call.stopRecording()
        } else {
            call.startRecording()
        }
        isRecording.value = call.params.isRecording
    }

    fun showContextMenu(anchor: View) {
        contextMenuClickListener?.onShowContextMenu(anchor, this)
    }

    fun isActiveAndNotInConference(): Boolean {
        return isPaused.value == false && isRemotelyPaused.value == false && call.conference?.call != null && isInRemoteConference.value == false
    }

    private fun isCallPaused(): Boolean {
        return when (call.state) {
            Call.State.Paused, Call.State.Pausing -> true
            else -> false
        }
    }

    private fun isCallRemotelyPaused(): Boolean {
        return when (call.state) {
            Call.State.PausedByRemote -> {
                val conference = call.conference
                if (conference != null && conference.me.isFocus) {
                    Log.w(
                        "[Call] State is paused by remote but we are the focus of the conference, so considering call as active"
                    )
                    false
                } else {
                    true
                }
            }
            else -> false
        }
    }

    private fun canCallBePaused(): Boolean {
        return !call.mediaInProgress() && when (call.state) {
            Call.State.StreamsRunning, Call.State.PausedByRemote -> true
            else -> false
        }
    }

    private fun update() {
        isRecording.value = call.params.isRecording
        isPaused.value = isCallPaused()
        isRemotelyPaused.value = isCallRemotelyPaused()
        canBePaused.value = canCallBePaused()

        updateConferenceInfo()

        isOutgoing.value = when (call.state) {
            Call.State.OutgoingInit, Call.State.OutgoingEarlyMedia, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> true
            else -> false
        }
        isIncoming.value = when (call.state) {
            Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> true
            else -> false
        }

        // Check periodically until mediaInProgress is false
        if (call.mediaInProgress()) {
            scope.launch {
                delay(1000)
                update()
            }
        }
    }

    private fun updateConferenceInfo() {
        val conference = call.conference
        isInRemoteConference.value = conference != null && conference.call != null
        if (conference != null) {
            Log.d("[Call] Found conference attached to call")
            remoteConferenceSubject.value = LinphoneUtils.getConferenceSubject(conference)
            Log.d(
                "[Call] Found conference related to this call with subject [${remoteConferenceSubject.value}]"
            )

            val participantsList = arrayListOf<ConferenceInfoParticipantData>()
            for (participant in conference.participantList) {
                val participantData = ConferenceInfoParticipantData(participant.address)
                participantsList.add(participantData)
            }

            conferenceParticipants.value = participantsList
            conferenceParticipantsCountLabel.value = coreContext.context.getString(
                R.string.conference_participants_title,
                participantsList.size
            )
        } else {
            val conferenceAddress = LinphoneUtils.getConferenceAddress(call)
            val conferenceInfo = if (conferenceAddress != null) {
                coreContext.core.findConferenceInformationFromUri(
                    conferenceAddress
                )
            } else {
                null
            }
            if (conferenceInfo != null) {
                Log.d(
                    "[Call] Found matching conference info with subject: ${conferenceInfo.subject}"
                )
                remoteConferenceSubject.value = conferenceInfo.subject

                val participantsList = arrayListOf<ConferenceInfoParticipantData>()
                for (participant in conferenceInfo.participants) {
                    val participantData = ConferenceInfoParticipantData(participant)
                    participantsList.add(participantData)
                }

                // Add organizer if not in participants list
                val organizer = conferenceInfo.organizer
                if (organizer != null) {
                    val found = participantsList.find { it.participant.weakEqual(organizer) }
                    if (found == null) {
                        val participantData = ConferenceInfoParticipantData(organizer)
                        participantsList.add(0, participantData)
                    }
                }

                conferenceParticipants.value = participantsList
                conferenceParticipantsCountLabel.value = coreContext.context.getString(
                    R.string.conference_participants_title,
                    participantsList.size
                )
            }
        }
    }

    private fun startVideoUpdateAcceptanceTimer() {
        timer?.cancel()

        timer = Timer("Call update timeout")
        timer?.schedule(
            object : TimerTask() {
                override fun run() {
                    // Decline call update
                    coreContext.videoUpdateRequestTimedOut(call)
                }
            },
            30000
        )
        Log.i("[Call] Starting 30 seconds timer to automatically decline video request")
    }
}
