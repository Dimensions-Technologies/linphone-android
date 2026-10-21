package org.linphone.models

/**
 * Whether the user has a softphone device to make and receive calls with, as the web client's
 * showNoDeviceConfiguredWarning (softphone.service.ts). Mobile has no CTI mode to fall back on, so
 * without a device the user can't call at all.
 */
object SoftphoneDeviceRules {
    /**
     * True once the devices have loaded and none of them can register, e.g. the user's mobile
     * softphone device is disabled in the portal. Null means not loaded yet or signed out.
     */
    fun noDeviceConfigured(devices: List<UserDevice>?): Boolean =
        devices != null && devices.none { it.hasCredentials() }
}
