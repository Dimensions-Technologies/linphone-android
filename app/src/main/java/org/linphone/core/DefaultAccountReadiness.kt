package org.linphone.core

// On start-up the accounts left over from the last session are cleared and rebuilt once the
// gateway's device list arrives, so nothing about the accounts counts until that has happened
object DefaultAccountReadiness {
    fun isReady(devicesReceived: Boolean, defaultAccountState: RegistrationState?): Boolean {
        return devicesReceived && defaultAccountState == RegistrationState.Ok
    }

    fun hasNoAccounts(devicesReceived: Boolean, hasAccounts: Boolean): Boolean {
        return devicesReceived && !hasAccounts
    }

    fun isDefaultAccountFailure(
        devicesReceived: Boolean,
        isDefaultAccount: Boolean,
        state: RegistrationState?
    ): Boolean {
        return devicesReceived && isDefaultAccount && state == RegistrationState.Failed
    }
}
