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
package org.linphone.ui.main.chat.model

import androidx.annotation.WorkerThread
import org.linphone.core.EventLog
import org.linphone.ui.main.contacts.model.ContactAvatarModel

class EventLogModel @WorkerThread constructor(
    val eventLog: EventLog,
    avatarModel: ContactAvatarModel,
    isFromGroup: Boolean,
    isGroupedWithPreviousOne: Boolean,
    isGroupedWithNextOne: Boolean
) {
    val type: EventLog.Type = eventLog.type

    val isEvent = type != EventLog.Type.ConferenceChatMessage

    val model = if (isEvent) {
        EventModel(eventLog)
    } else {
        ChatMessageModel(
            eventLog.chatMessage!!,
            avatarModel,
            isFromGroup,
            isGroupedWithPreviousOne,
            isGroupedWithNextOne
        )
    }

    val notifyId = eventLog.notifyId

    @WorkerThread
    fun destroy() {
        if (model is ChatMessageModel) {
            model.destroy()
        }
    }
}