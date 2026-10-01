package org.linphone.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks when a call from a tel: link may start. On start-up the last session's accounts can report
 * a registration before they're cleared and rebuilt from the gateway's device list, so nothing
 * counts until that list has arrived (WI #28994).
 */
class DefaultAccountReadinessTest {

    @Test
    fun `ready once devices have arrived and the default account is registered`() {
        assertTrue(DefaultAccountReadiness.isReady(true, RegistrationState.Ok))
    }

    @Test
    fun `a registration before the device list arrives is from a stale account`() {
        assertFalse(DefaultAccountReadiness.isReady(false, RegistrationState.Ok))
    }

    @Test
    fun `not ready while the default account is still registering`() {
        val states = listOf(
            RegistrationState.None,
            RegistrationState.Progress,
            RegistrationState.Refreshing,
            RegistrationState.Failed,
            RegistrationState.Cleared,
            null
        )
        for (state in states) {
            assertFalse("$state", DefaultAccountReadiness.isReady(true, state))
        }
    }

    @Test
    fun `a failure on the default account after devices arrive ends the wait`() {
        assertTrue(
            DefaultAccountReadiness.isDefaultAccountFailure(true, true, RegistrationState.Failed)
        )
    }

    @Test
    fun `a failure before the device list arrives is from a stale account`() {
        assertFalse(
            DefaultAccountReadiness.isDefaultAccountFailure(false, true, RegistrationState.Failed)
        )
    }

    @Test
    fun `a failure on another account doesn't end the wait`() {
        assertFalse(
            DefaultAccountReadiness.isDefaultAccountFailure(true, false, RegistrationState.Failed)
        )
    }

    @Test
    fun `other states on the default account don't end the wait`() {
        for (state in listOf(RegistrationState.Progress, RegistrationState.Cleared, null)) {
            assertFalse(
                "$state",
                DefaultAccountReadiness.isDefaultAccountFailure(true, true, state)
            )
        }
    }

    @Test
    fun `no accounts after the device list arrives means there's nothing to register`() {
        assertTrue(DefaultAccountReadiness.hasNoAccounts(true, false))
        assertFalse(DefaultAccountReadiness.hasNoAccounts(true, true))
    }

    @Test
    fun `no accounts before the device list arrives is expected`() {
        assertFalse(DefaultAccountReadiness.hasNoAccounts(false, false))
    }
}
