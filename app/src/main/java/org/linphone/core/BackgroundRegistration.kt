package org.linphone.core

import org.linphone.utils.Log

// Unregisters SIP while the app is in the background, as the iOS app does, so the PBX stops
// showing the device as registered and reaches it through push instead (WI #36133). Calls in
// progress keep the registration until the last one ends. Registration comes back when the app
// returns to the foreground or a push arrives.
//
// The app starts out treated as backgrounded, so a cold start from a push still unregisters when
// its call ends. It isn't unregistered until something sends it to the background, though, as
// the push that started it needs the registration to receive its call.
class BackgroundRegistration(private val registrar: Registrar) {
    interface Registrar {
        val callCount: Int
        fun setRegistered(registered: Boolean)
    }

    var isInForeground = false
        private set

    // True while the accounts have been unregistered for the background. Accounts built in the
    // meantime should be built unregistered too.
    var isUnregistered = false
        private set

    fun onForeground() {
        isInForeground = true
        register("app is in the foreground")
    }

    fun onBackground() {
        isInForeground = false
        unregisterIfIdle("app is in the background")
    }

    fun onLastCallEnded() {
        if (!isInForeground) unregisterIfIdle("last call ended in the background")
    }

    fun onPushReceived() {
        register("push received")
    }

    private fun register(reason: String) {
        if (!isUnregistered) return

        isUnregistered = false
        Log.i("[Background Registration] Registering, $reason")
        registrar.setRegistered(true)
    }

    private fun unregisterIfIdle(reason: String) {
        if (isUnregistered) return

        val calls = registrar.callCount
        if (calls > 0) {
            Log.i("[Background Registration] Staying registered for $calls call(s), $reason")
            return
        }

        isUnregistered = true
        Log.i("[Background Registration] Unregistering, $reason")
        registrar.setRegistered(false)
    }
}
