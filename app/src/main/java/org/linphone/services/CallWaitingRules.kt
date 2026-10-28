package org.linphone.services

import org.linphone.models.UserDevice

/**
 * Call waiting, as on the web client (softphone.service.ts handleCallWaiting): with it off, a call
 * that rings while another call is in progress is rejected.
 */
object CallWaitingRules {
    /** The device the app registers as its default account: the first one it can register. */
    fun primaryDevice(devices: List<UserDevice>?): UserDevice? =
        devices?.firstOrNull { it.hasCredentials() }

    /** A device that doesn't say counts as off, as on the web client. */
    fun isEnabled(device: UserDevice?): Boolean = device?.callWaitingEnabled == true

    fun shouldReject(otherCallsInProgress: Int, device: UserDevice?): Boolean =
        otherCallsInProgress > 0 && !isEnabled(device)
}
