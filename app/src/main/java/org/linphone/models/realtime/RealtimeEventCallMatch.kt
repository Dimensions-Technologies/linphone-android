package org.linphone.models.realtime

import androidx.annotation.Keep

// callMatchEvent: contacts the server matched to one of the user's calls
@Keep
class RealtimeEventCallMatch(
    val data: CallMatchEventData?,
    val id: String?,
    val tenantId: String?,
    val userId: String?
)

@Keep
class CallMatchEventData(
    val callId: String?,
    val sessionId: String?,
    val connectionId: String?,
    val matches: List<ContactMatch>?
)
