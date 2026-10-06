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
    val answerTime: String? = null
)
