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

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.*
import org.linphone.services.CallHistoryService
import org.linphone.services.ParkingSlotService
import org.linphone.services.VoicemailBoxService
import org.linphone.utils.AppUtils
import org.linphone.utils.Log

class TabsViewModel : ViewModel() {
    private val callHistoryService = CallHistoryService.getInstance(coreContext.context)

    val unreadMessagesCount = MutableLiveData<Int>()
    val unreadVoicemailsCount = MutableLiveData<Int>()
    val missedCallsCount = MutableLiveData<Int>()

    // Boundaries between the tabs: five, or six with Parking (between anchor4 and anchor5)
    val anchor1 = MutableLiveData<Float>()
    val anchor2 = MutableLiveData<Float>()
    val anchor3 = MutableLiveData<Float>()
    val anchor4 = MutableLiveData<Float>()
    val anchor5 = MutableLiveData<Float>()

    // The Parking tab is shown only when the user has parking slots. It's hidden by giving it no
    // width rather than by visibility, which the tabs' MotionLayout would reset; TabsFragment moves
    // the boundaries (see tabAnchors).
    val showParking = MutableLiveData(false)
    val parkingOccupiedCount = MutableLiveData(0)

    val historyMissedCountTranslateY = MutableLiveData<Float>()
    val chatUnreadCountTranslateY = MutableLiveData<Float>()
    val voicemailUnreadCountTranslateY = MutableLiveData<Float>()

    private var missedCallCountSubscription: Disposable? = null

    // With visual voicemail the voicemail button opens the Voicemail tab, and its badge counts the
    // mailbox's new messages; without it, the button dials voicemail and the badge is SIP MWI's count
    var isVisualVoicemailAvailable = false
        private set
    private var mwiVoicemailCount = 0

    private val voicemailSubscription: Disposable = Observable.combineLatest(
        VoicemailBoxService.isAvailable,
        VoicemailBoxService.newCount
    ) { available, count -> Pair(available, count) }
        .subscribe(
            { (available, count) ->
                isVisualVoicemailAvailable = available
                unreadVoicemailsCount.postValue(if (available) count else mwiVoicemailCount)
            },
            { e -> Log.e("[Tabs] Voicemail failed", e) }
        )

    private val parkingSubscription: Disposable = Observable.combineLatest(
        ParkingSlotService.hasSlots,
        ParkingSlotService.occupiedCount.startWithItem(0)
    ) { hasSlots, occupied -> Pair(hasSlots, occupied) }
        .subscribe(
            { (hasSlots, occupied) ->
                showParking.postValue(hasSlots)
                parkingOccupiedCount.postValue(if (hasSlots) occupied else 0)
            },
            { e -> Log.e("[Tabs] Parking slots failed", e) }
        )

    init {
        missedCallCountSubscription = callHistoryService.missedCallCount.subscribe { c ->
            missedCallsCount.postValue(c)
        }
    }

    companion object {
        // The boundaries between the tabs, anchor1 to anchor5. Without Parking, its tab (between
        // anchor4 and anchor5) has no width.
        fun tabAnchors(withParking: Boolean): List<Float> = if (withParking) {
            listOf(1 / 6F, 2 / 6F, 3 / 6F, 4 / 6F, 5 / 6F)
        } else {
            listOf(0.2F, 0.4F, 0.6F, 0.8F, 0.8F)
        }
    }

    private val bounceAnimator: ValueAnimator by lazy {
        ValueAnimator.ofFloat(
            AppUtils.getDimension(R.dimen.tabs_fragment_unread_count_bounce_offset),
            0f
        ).apply {
            addUpdateListener {
                val value = it.animatedValue as Float
                historyMissedCountTranslateY.value = -value
                chatUnreadCountTranslateY.value = -value
                voicemailUnreadCountTranslateY.value = -value
            }
            interpolator = LinearInterpolator()
            duration = 250
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
    }

    private val listener: CoreListenerStub = object : CoreListenerStub() {
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String
        ) {
            if (state == Call.State.End || state == Call.State.Error) {
                updateMissedCallCount()
            }
        }

        override fun onChatRoomRead(core: Core, chatRoom: ChatRoom) {
            updateUnreadChatCount()
        }

        override fun onMessagesReceived(
            core: Core,
            chatRoom: ChatRoom,
            messages: Array<out ChatMessage>
        ) {
            updateUnreadChatCount()
        }

        override fun onChatRoomStateChanged(core: Core, chatRoom: ChatRoom, state: ChatRoom.State) {
            if (state == ChatRoom.State.Deleted) {
                updateUnreadChatCount()
            }
        }

        override fun onNotifyReceived(
            core: Core,
            event: Event,
            notifiedEvent: String,
            body: Content?
        ) {
            if (body?.type == "application" && body.subtype == "simple-message-summary" && body.size > 0) {
                val data = body.utf8Text?.lowercase(Locale.getDefault())
                val voiceMail = data?.split("voice-message: ")
                if ((voiceMail?.size ?: 0) >= 2) {
                    val toParse = voiceMail!![1].split("/", limit = 0)
                    try {
                        val unreadCount: Int = toParse[0].toInt()
                        mwiVoicemailCount = unreadCount
                        if (!isVisualVoicemailAvailable) unreadVoicemailsCount.value = unreadCount
                    } catch (nfe: NumberFormatException) {
                        Log.e("[Status Fragment] $nfe")
                    }
                }
            }
        }
    }

    init {
        coreContext.core.addListener(listener)

        anchor1.value = 0.2F
        anchor2.value = 0.4F
        anchor3.value = 0.6F
        anchor4.value = 0.8F
        anchor5.value = 0.8F

        updateUnreadChatCount()
        updateMissedCallCount()

        if (corePreferences.enableAnimations) bounceAnimator.start()
    }

    override fun onCleared() {
        parkingSubscription.dispose()
        voicemailSubscription.dispose()
        coreContext.core.removeListener(listener)
        super.onCleared()
    }

    fun updateMissedCallCount() {
        // missedCallsCount.value = coreContext.core.missedCallsCount //NOTE - now handled by MissedCallCountSubscription
    }

    fun updateUnreadChatCount() {
        unreadMessagesCount.value = if (corePreferences.disableChat) 0 else coreContext.core.unreadChatMessageCountFromActiveLocals
    }

    fun dialVoicemail() {
        coreContext.dialVoicemail()
    }
}
