package org.linphone.models.realtime

import androidx.annotation.Keep

// callerIdEvent: the user's outgoing caller ID, and the ones they can choose from
@Keep
class RealtimeEventCallerId(
    val data: CallerIdEventData?,
    val id: String?,
    val tenantId: String?,
    val userId: String?
)

@Keep
class CallerIdEventData(
    val name: String?,
    val callerId: CallerId?,
    val callerIds: List<CallerId>?
)

/** An outgoing caller ID. A blank [callerNumber] is the account's own (the "Default" choice). */
@Keep
data class CallerId(
    val callerName: String?,
    val callerNumber: String?
)

// Sent to the user hub as setUserCliRequest to change the outgoing caller ID
@Keep
class SetUserCliRequest(
    val callerNumber: String
)
