package org.linphone.models.realtime

import androidx.annotation.Keep

// callEvent: the PBX's (CTI) view of all the user's calls, on any of their devices
@Keep
class RealtimeEventCall(
    val data: CallEventData?,
    val id: String?,
    val tenantId: String?,
    val userId: String?
)

@Keep
class CallEventData(
    // By PBX call id
    val calls: Map<String, CtiCallData>?
)

@Keep
data class CtiCallData(
    val callId: String? = null,
    val userId: String? = null,
    // held, connected, cleared, offhook, ringing, queued or unknown
    val state: String? = null,
    // PbxOffered, PbxEstablished, PbxTransferred, PbxBridged, PbxCleared or PbxDeviceSnapshot
    val eventType: String? = null,
    val deviceNumber: String? = null,
    val sessionId: String? = null,
    val direction: String? = null,
    val type: String? = null,
    val callerName: String? = null,
    val callerNumber: String? = null,
    val calledName: String? = null,
    val calledNumber: String? = null,
    val startTime: String? = null,
    val answerTime: String? = null,
    // The queue (hunt group) and route path the call came in on, for an incoming call
    val groupName: String? = null,
    val groupNumber: String? = null,
    val routePathName: String? = null,
    val routePathId: String? = null,
    // The PBX legs of the call; a softphone's leg has its SIP Call-ID
    val relatedCalls: List<CtiRelatedCall>? = null,
    // The internal users the call is with, the other party first
    val otherUserIds: List<String>? = null
)

@Keep
data class CtiRelatedCall(
    val callId: String? = null,
    val userId: String? = null,
    val state: String? = null
)
