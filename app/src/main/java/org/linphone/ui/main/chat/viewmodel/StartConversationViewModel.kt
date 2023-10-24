/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
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
package org.linphone.ui.main.chat.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.Address
import org.linphone.core.ChatRoom
import org.linphone.core.ChatRoomListenerStub
import org.linphone.core.ChatRoomParams
import org.linphone.core.tools.Log
import org.linphone.ui.main.model.isInSecureMode
import org.linphone.ui.main.viewmodel.AddressSelectionViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

class StartConversationViewModel @UiThread constructor() : AddressSelectionViewModel() {
    companion object {
        private const val TAG = "[Start Conversation ViewModel]"
    }

    val hideGroupChatButton = MutableLiveData<Boolean>()

    val subject = MutableLiveData<String>()

    val groupChatRoomCreateButtonEnabled = MediatorLiveData<Boolean>()

    val operationInProgress = MutableLiveData<Boolean>()

    val chatRoomCreationErrorEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    val chatRoomCreatedEvent: MutableLiveData<Event<Pair<String, String>>> by lazy {
        MutableLiveData<Event<Pair<String, String>>>()
    }

    private val chatRoomListener = object : ChatRoomListenerStub() {
        @WorkerThread
        override fun onStateChanged(chatRoom: ChatRoom, newState: ChatRoom.State?) {
            val state = chatRoom.state
            val id = LinphoneUtils.getChatRoomId(chatRoom)
            Log.i("$TAG Chat room [$id] (${chatRoom.subject}) state changed: [$state]")

            if (state == ChatRoom.State.Created) {
                Log.i("$TAG Chat room [$id] successfully created")
                chatRoom.removeListener(this)
                operationInProgress.postValue(false)
                chatRoomCreatedEvent.postValue(
                    Event(
                        Pair(
                            chatRoom.localAddress.asStringUriOnly(),
                            chatRoom.peerAddress.asStringUriOnly()
                        )
                    )
                )
            } else if (state == ChatRoom.State.CreationFailed) {
                Log.e("$TAG Chat room [$id] creation has failed!")
                chatRoom.removeListener(this)
                operationInProgress.postValue(false)
                chatRoomCreationErrorEvent.postValue(Event("Error!")) // TODO FIXME: use translated string
            }
        }
    }

    init {
        groupChatRoomCreateButtonEnabled.postValue(false)
        groupChatRoomCreateButtonEnabled.addSource(selection) {
            groupChatRoomCreateButtonEnabled.postValue(
                subject.value.orEmpty().isNotEmpty() && selection.value.orEmpty().isNotEmpty()
            )
        }
        groupChatRoomCreateButtonEnabled.addSource(subject) {
            groupChatRoomCreateButtonEnabled.postValue(
                subject.value.orEmpty().isNotEmpty() && selection.value.orEmpty().isNotEmpty()
            )
        }

        updateGroupChatButtonVisibility()
    }

    @UiThread
    fun createGroupChatRoom() {
        coreContext.postOnCoreThread { core ->
            val account = core.defaultAccount
            if (account == null) {
                Log.e(
                    "$TAG No default account found, can't create group conversation!"
                )
                return@postOnCoreThread
            }

            operationInProgress.postValue(true)

            val groupChatRoomSubject = subject.value.orEmpty()
            val params: ChatRoomParams = coreContext.core.createDefaultChatRoomParams()
            params.isGroupEnabled = true
            params.subject = groupChatRoomSubject
            params.backend = ChatRoom.Backend.FlexisipChat
            params.isEncryptionEnabled = true

            val participants = arrayListOf<Address>()
            for (participant in selection.value.orEmpty()) {
                participants.add(participant.address)
            }
            val localAddress = account.params.identityAddress

            val participantsArray = arrayOf<Address>()
            val chatRoom = core.createChatRoom(
                params,
                localAddress,
                participants.toArray(participantsArray)
            )
            if (chatRoom != null) {
                if (params.backend == ChatRoom.Backend.FlexisipChat) {
                    if (chatRoom.state == ChatRoom.State.Created) {
                        val id = LinphoneUtils.getChatRoomId(chatRoom)
                        Log.i("$TAG Group chat room [$id] ($groupChatRoomSubject) has been created")
                        operationInProgress.postValue(false)
                        chatRoomCreatedEvent.postValue(
                            Event(
                                Pair(
                                    chatRoom.localAddress.asStringUriOnly(),
                                    chatRoom.peerAddress.asStringUriOnly()
                                )
                            )
                        )
                    } else {
                        Log.i(
                            "$TAG Chat room [$groupChatRoomSubject] isn't in Created state yet, wait for it"
                        )
                        chatRoom.addListener(chatRoomListener)
                    }
                } else {
                    val id = LinphoneUtils.getChatRoomId(chatRoom)
                    Log.i("$TAG Chat room successfully created [$id] ($groupChatRoomSubject)")
                    operationInProgress.postValue(false)
                    chatRoomCreatedEvent.postValue(
                        Event(
                            Pair(
                                chatRoom.localAddress.asStringUriOnly(),
                                chatRoom.peerAddress.asStringUriOnly()
                            )
                        )
                    )
                }
            } else {
                Log.e("$TAG Failed to create group chat room [$groupChatRoomSubject]!")
                operationInProgress.postValue(false)
                chatRoomCreationErrorEvent.postValue(Event("Error!")) // TODO FIXME: use translated string
            }
        }
    }

    @WorkerThread
    fun createOneToOneChatRoomWith(remote: Address) {
        val core = coreContext.core
        val account = core.defaultAccount
        if (account == null) {
            Log.e(
                "$TAG No default account found, can't create conversation with [${remote.asStringUriOnly()}]!"
            )
            return
        }

        operationInProgress.postValue(true)

        val params: ChatRoomParams = coreContext.core.createDefaultChatRoomParams()
        params.isGroupEnabled = false
        params.subject = AppUtils.getString(R.string.conversation_one_to_one_hidden_subject)

        val sameDomain = remote.domain == corePreferences.defaultDomain && remote.domain == account.params.domain
        if (account.isInSecureMode() && sameDomain) {
            Log.i("$TAG Account is in secure mode & domain matches, creating a E2E chat room")
            params.backend = ChatRoom.Backend.FlexisipChat
            params.isEncryptionEnabled = true
            params.ephemeralLifetime = 0 // Make sure ephemeral is disabled by default
        } else if (!account.isInSecureMode()) {
            Log.i("$TAG Account is in interop mode, creating a SIP simple chat room")
            params.backend = ChatRoom.Backend.Basic
            params.isEncryptionEnabled = false
        } else {
            Log.e(
                "$TAG Account is in secure mode, can't chat with SIP address of different domain [${remote.asStringUriOnly()}]"
            )
            operationInProgress.postValue(false)
            chatRoomCreationErrorEvent.postValue(Event("Error!")) // TODO FIXME: use translated string
            return
        }

        val participants = arrayOf(remote)
        val localAddress = account.params.identityAddress
        val existingChatRoom = core.searchChatRoom(params, localAddress, null, participants)
        if (existingChatRoom == null) {
            Log.i(
                "$TAG No existing 1-1 chat room between local account [${localAddress?.asStringUriOnly()}] and remote [${remote.asStringUriOnly()}] was found for given parameters, let's create it"
            )
            val chatRoom = core.createChatRoom(params, localAddress, participants)
            if (chatRoom != null) {
                if (params.backend == ChatRoom.Backend.FlexisipChat) {
                    if (chatRoom.state == ChatRoom.State.Created) {
                        val id = LinphoneUtils.getChatRoomId(chatRoom)
                        Log.i("$TAG 1-1 chat room [$id] has been created")
                        operationInProgress.postValue(false)
                        chatRoomCreatedEvent.postValue(
                            Event(
                                Pair(
                                    chatRoom.localAddress.asStringUriOnly(),
                                    chatRoom.peerAddress.asStringUriOnly()
                                )
                            )
                        )
                    } else {
                        Log.i("$TAG Chat room isn't in Created state yet, wait for it")
                        chatRoom.addListener(chatRoomListener)
                    }
                } else {
                    val id = LinphoneUtils.getChatRoomId(chatRoom)
                    Log.i("$TAG Chat room successfully created [$id]")
                    operationInProgress.postValue(false)
                    chatRoomCreatedEvent.postValue(
                        Event(
                            Pair(
                                chatRoom.localAddress.asStringUriOnly(),
                                chatRoom.peerAddress.asStringUriOnly()
                            )
                        )
                    )
                }
            } else {
                Log.e("$TAG Failed to create 1-1 chat room with [${remote.asStringUriOnly()}]!")
                operationInProgress.postValue(false)
                chatRoomCreationErrorEvent.postValue(Event("Error!")) // TODO FIXME: use translated string
            }
        } else {
            Log.w(
                "$TAG A 1-1 chat room between local account [${localAddress?.asStringUriOnly()}] and remote [${remote.asStringUriOnly()}] for given parameters already exists!"
            )
            operationInProgress.postValue(false)
            chatRoomCreatedEvent.postValue(
                Event(
                    Pair(
                        existingChatRoom.localAddress.asStringUriOnly(),
                        existingChatRoom.peerAddress.asStringUriOnly()
                    )
                )
            )
        }
    }

    @UiThread
    fun updateGroupChatButtonVisibility() {
        coreContext.postOnCoreThread { core ->
            val hideGroupChat = !LinphoneUtils.isGroupChatAvailable(core)
            hideGroupChatButton.postValue(hideGroupChat)
        }
    }
}