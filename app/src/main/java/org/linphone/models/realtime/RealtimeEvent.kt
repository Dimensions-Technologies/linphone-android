package org.linphone.models.realtime

class RealtimeEvent<T : RealtimeEventData> (
    val data: T,
    val id: String,
    val tenantId: String,
    val userId: String
)

typealias RealtimeEventData = Any
