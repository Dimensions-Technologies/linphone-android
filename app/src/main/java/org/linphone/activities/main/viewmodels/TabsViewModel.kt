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
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.*
import org.linphone.services.BrandHighlight
import org.linphone.services.CallHistoryService
import org.linphone.services.ContactsAccessService
import org.linphone.services.ParkingSlotService
import org.linphone.services.VoicemailBoxService
import org.linphone.utils.AppUtils
import org.linphone.utils.Log

class TabsViewModel : ViewModel() {
    private val callHistoryService = CallHistoryService.getInstance(coreContext.context)

    // The selected item's colour: the brand's highlight, as on iOS
    val highlightColor = BrandHighlight.color(coreContext.context)

    // The other items' colour: the brand's navbar colour, as on iOS
    val unselectedColor = BrandHighlight.navbarColor(coreContext.context)

    val unreadMessagesCount = MutableLiveData<Int>()

    // Voicemail is a switch inside History (as on iOS), so History's badge counts the missed calls
    // and the new voicemails
    val historyBadgeCount = MutableLiveData<Int>()

    // Boundaries between the tabs: four, or five with Parking (after anchor4)
    val anchor1 = MutableLiveData<Float>()
    val anchor2 = MutableLiveData<Float>()
    val anchor3 = MutableLiveData<Float>()
    val anchor4 = MutableLiveData<Float>()

    // The Parking tab is shown only when the user has parking slots. It's hidden by giving it no
    // width rather than by visibility, which the tabs' MotionLayout would reset; TabsFragment moves
    // the boundaries (see tabAnchors).
    val showParking = MutableLiveData(false)
    val parkingOccupiedCount = MutableLiveData(0)

    // The Favourites and Contacts tabs are shown only when the user can use Contacts (licence and
    // permission, as the web client's nav menu), hidden the same way as Parking. No value (hidden)
    // until it's known.
    val showContacts = MutableLiveData<Boolean>()

    val historyMissedCountTranslateY = MutableLiveData<Float>()
    val chatUnreadCountTranslateY = MutableLiveData<Float>()

    // New voicemails count only while History has its Voicemail switch (licence, permission and an
    // enabled mailbox). Without it there's nothing in History to clear them; the status bar's
    // voicemail icon still shows the SIP MWI count.
    private val historyBadgeSubscription: Disposable = Observable.combineLatest(
        callHistoryService.missedCallCount.startWithItem(0),
        // Missed calls show straight away, before the user and mailboxes have loaded
        VoicemailBoxService.isAvailable.startWithItem(false),
        VoicemailBoxService.newCount.startWithItem(0)
    ) { missed, voicemailAvailable, newVoicemails ->
        historyBadgeCount(missed, voicemailAvailable, newVoicemails)
    }
        .subscribe(
            { count -> historyBadgeCount.postValue(count) },
            { e -> Log.e("[Tabs] History badge failed", e) }
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

    private val contactsSubscription: Disposable = ContactsAccessService.isAvailable
        .subscribe(
            { available -> showContacts.postValue(available) },
            { e -> Log.e("[Tabs] Contacts access failed", e) }
        )

    companion object {
        // The boundaries between the tabs, anchor1 to anchor4: Favourites, Contacts, History, Dialer,
        // Parking. A hidden tab has no width - Favourites and Contacts (before anchor1 and between
        // anchor1 and anchor2) without Contacts, Parking (after anchor4) without slots.
        fun tabAnchors(withParking: Boolean, withContacts: Boolean): List<Float> {
            val widths = listOf(
                if (withContacts) 1F else 0F, // Favourites
                if (withContacts) 1F else 0F, // Contacts
                1F, // History
                1F, // Dialer
                if (withParking) 1F else 0F // Parking
            )
            val tabCount = widths.sum()
            var edge = 0F
            // The last boundary is the end of the bar
            return widths.dropLast(1).map { width ->
                edge += width
                edge / tabCount
            }
        }

        fun historyBadgeCount(missedCalls: Int, voicemailAvailable: Boolean, newVoicemails: Int): Int =
            missedCalls + if (voicemailAvailable) newVoicemails else 0
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
    }

    init {
        coreContext.core.addListener(listener)

        anchor1.value = 0.2F
        anchor2.value = 0.4F
        anchor3.value = 0.6F
        anchor4.value = 0.8F

        updateUnreadChatCount()
        updateMissedCallCount()

        if (corePreferences.enableAnimations) bounceAnimator.start()
    }

    override fun onCleared() {
        parkingSubscription.dispose()
        contactsSubscription.dispose()
        historyBadgeSubscription.dispose()
        coreContext.core.removeListener(listener)
        super.onCleared()
    }

    fun updateMissedCallCount() {
        // missedCallsCount.value = coreContext.core.missedCallsCount //NOTE - now handled by MissedCallCountSubscription
    }

    fun updateUnreadChatCount() {
        unreadMessagesCount.value = if (corePreferences.disableChat) 0 else coreContext.core.unreadChatMessageCountFromActiveLocals
    }
}
