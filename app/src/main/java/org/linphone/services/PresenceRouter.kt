package org.linphone.services

import PresenceEventData
import PresenceObservable
import java.util.concurrent.ConcurrentHashMap

/**
 * Each user's presence stream, by user id, and the delivery of presence events to them. Events come
 * from the realtime connection's thread while streams are asked for on the main thread, so the map
 * is concurrent.
 */
class PresenceRouter {
    private val streams = ConcurrentHashMap<String, PresenceObservable>()

    /** The user's stream, made by [create] the first time it's asked for. */
    fun stream(userId: String, create: () -> PresenceObservable): PresenceObservable =
        streams.getOrPut(userId, create)

    /** The user's latest presence, if they have a stream and it has had one. */
    fun current(userId: String): PresenceEventData? = streams[userId]?.subject?.value

    /** Passes an event to its user's stream; a user nobody watches has none, so it's dropped. */
    fun deliver(userId: String?, data: PresenceEventData) {
        streams[userId ?: return]?.subject?.onNext(data)
    }
}
