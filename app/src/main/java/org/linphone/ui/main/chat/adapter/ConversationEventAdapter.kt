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
package org.linphone.ui.main.chat.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R
import org.linphone.databinding.ChatBubbleIncomingBinding
import org.linphone.databinding.ChatBubbleOutgoingBinding
import org.linphone.databinding.ChatEventBinding
import org.linphone.ui.main.chat.model.ChatMessageModel
import org.linphone.ui.main.chat.model.EventLogModel
import org.linphone.ui.main.chat.model.EventModel
import org.linphone.utils.Event

class ConversationEventAdapter(
    private val viewLifecycleOwner: LifecycleOwner
) : ListAdapter<EventLogModel, RecyclerView.ViewHolder>(EventLogDiffCallback()) {
    companion object {
        const val INCOMING_CHAT_MESSAGE = 1
        const val OUTGOING_CHAT_MESSAGE = 2
        const val EVENT = 3
    }

    val chatMessageLongPressEvent = MutableLiveData<Event<ChatMessageModel>>()

    val showDeliveryForChatMessageModelEvent: MutableLiveData<Event<ChatMessageModel>> by lazy {
        MutableLiveData<Event<ChatMessageModel>>()
    }
    val showReactionForChatMessageModelEvent: MutableLiveData<Event<ChatMessageModel>> by lazy {
        MutableLiveData<Event<ChatMessageModel>>()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            INCOMING_CHAT_MESSAGE -> createIncomingChatBubble(parent)
            OUTGOING_CHAT_MESSAGE -> createOutgoingChatBubble(parent)
            else -> createEvent(parent)
        }
    }

    override fun getItemViewType(position: Int): Int {
        val data = getItem(position)
        if (data.isEvent) return EVENT

        if ((data.model as ChatMessageModel).isOutgoing) {
            return OUTGOING_CHAT_MESSAGE
        }
        return INCOMING_CHAT_MESSAGE
    }

    private fun createIncomingChatBubble(parent: ViewGroup): IncomingBubbleViewHolder {
        val binding: ChatBubbleIncomingBinding = DataBindingUtil.inflate(
            LayoutInflater.from(parent.context),
            R.layout.chat_bubble_incoming,
            parent,
            false
        )
        return IncomingBubbleViewHolder(binding)
    }

    private fun createOutgoingChatBubble(parent: ViewGroup): OutgoingBubbleViewHolder {
        val binding: ChatBubbleOutgoingBinding = DataBindingUtil.inflate(
            LayoutInflater.from(parent.context),
            R.layout.chat_bubble_outgoing,
            parent,
            false
        )
        return OutgoingBubbleViewHolder(binding)
    }

    private fun createEvent(parent: ViewGroup): EventViewHolder {
        val binding: ChatEventBinding = DataBindingUtil.inflate(
            LayoutInflater.from(parent.context),
            R.layout.chat_event,
            parent,
            false
        )
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val eventLog = getItem(position)
        when (holder) {
            is IncomingBubbleViewHolder -> holder.bind(eventLog.model as ChatMessageModel)
            is OutgoingBubbleViewHolder -> holder.bind(eventLog.model as ChatMessageModel)
            is EventViewHolder -> holder.bind(eventLog.model as EventModel)
        }
    }

    inner class IncomingBubbleViewHolder(
        val binding: ChatBubbleIncomingBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessageModel) {
            with(binding) {
                model = message

                setOnLongClickListener {
                    chatMessageLongPressEvent.value = Event(message)
                    true
                }

                setShowDeliveryInfoClickListener {
                    showDeliveryForChatMessageModelEvent.value = Event(message)
                }
                setShowReactionInfoClickListener {
                    showReactionForChatMessageModelEvent.value = Event(message)
                }

                lifecycleOwner = viewLifecycleOwner
                executePendingBindings()
            }
        }
    }

    inner class OutgoingBubbleViewHolder(
        val binding: ChatBubbleOutgoingBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessageModel) {
            with(binding) {
                model = message

                setShowDeliveryInfoClickListener {
                    showDeliveryForChatMessageModelEvent.value = Event(message)
                }
                setShowReactionInfoClickListener {
                    showReactionForChatMessageModelEvent.value = Event(message)
                }

                lifecycleOwner = viewLifecycleOwner
                executePendingBindings()
            }
        }
    }
    inner class EventViewHolder(
        val binding: ChatEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(event: EventModel) {
            with(binding) {
                model = event

                lifecycleOwner = viewLifecycleOwner
                executePendingBindings()
            }
        }
    }

    private class EventLogDiffCallback : DiffUtil.ItemCallback<EventLogModel>() {
        override fun areItemsTheSame(oldItem: EventLogModel, newItem: EventLogModel): Boolean {
            return if (!oldItem.isEvent && !newItem.isEvent) {
                val oldData = (oldItem.model as ChatMessageModel)
                val newData = (newItem.model as ChatMessageModel)
                oldData.id == newData.id &&
                    oldData.timestamp == newData.timestamp &&
                    oldData.isOutgoing == newData.isOutgoing
            } else {
                oldItem.notifyId == newItem.notifyId
            }
        }

        override fun areContentsTheSame(oldItem: EventLogModel, newItem: EventLogModel): Boolean {
            return if (oldItem.isEvent && newItem.isEvent) {
                true
            } else if (!oldItem.isEvent && !newItem.isEvent) {
                val oldModel = (oldItem.model as ChatMessageModel)
                val newModel = (newItem.model as ChatMessageModel)
                oldModel.statusIcon.value == newModel.statusIcon.value
            } else {
                false
            }
        }
    }
}