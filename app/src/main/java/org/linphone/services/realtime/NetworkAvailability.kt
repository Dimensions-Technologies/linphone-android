package org.linphone.services.realtime

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject

/**
 * Tracks whether any network with internet access is available.
 *
 * ConnectivityManager reports each network on its own, so losing one (e.g. Wi-Fi turning off, or
 * mobile data being torn down once Wi-Fi connects) doesn't mean the device is offline. Callers pass
 * the network they were told about; any object with a stable equals/hashCode will do.
 */
class NetworkAvailability {
    private val networks = mutableSetOf<Any>()
    private val isOnlineSubject = BehaviorSubject.createDefault(false)

    /** Emits true each time a network becomes available, and false once none are left */
    val isOnline: Observable<Boolean> = isOnlineSubject.map { x -> x }

    val networkCount: Int
        @Synchronized get() = networks.size

    @Synchronized
    fun onAvailable(network: Any) {
        networks.add(network)
        isOnlineSubject.onNext(true)
    }

    @Synchronized
    fun onLost(network: Any) {
        networks.remove(network)
        if (networks.isEmpty()) isOnlineSubject.onNext(false)
    }
}
