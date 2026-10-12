package org.linphone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks when SIP is unregistered for the background and registered again. Without it the PBX
 * kept showing a backgrounded Android device as registered, unlike iOS (WI #36133).
 */
class BackgroundRegistrationTest {

    private class FakeRegistrar : BackgroundRegistration.Registrar {
        override var callCount = 0
        val changes = mutableListOf<Boolean>()

        override fun setRegistered(registered: Boolean) {
            changes.add(registered)
        }
    }

    private val registrar = FakeRegistrar()
    private val registration = BackgroundRegistration(registrar)

    @Test
    fun `going to the background with no calls unregisters`() {
        registration.onForeground()
        registration.onBackground()

        assertEquals(listOf(false), registrar.changes)
        assertTrue(registration.isUnregistered)
    }

    @Test
    fun `coming back to the foreground registers again`() {
        registration.onForeground()
        registration.onBackground()
        registration.onForeground()

        assertEquals(listOf(false, true), registrar.changes)
        assertFalse(registration.isUnregistered)
    }

    @Test
    fun `going to the background during a call stays registered`() {
        registration.onForeground()
        registrar.callCount = 1
        registration.onBackground()

        assertTrue(registrar.changes.isEmpty())
        assertFalse(registration.isUnregistered)
    }

    @Test
    fun `the last call ending in the background unregisters`() {
        registration.onForeground()
        registrar.callCount = 1
        registration.onBackground()
        registrar.callCount = 0
        registration.onLastCallEnded()

        assertEquals(listOf(false), registrar.changes)
    }

    @Test
    fun `the last call ending in the foreground stays registered`() {
        registration.onForeground()
        registration.onLastCallEnded()

        assertTrue(registrar.changes.isEmpty())
    }

    @Test
    fun `a push in the background registers to take the call`() {
        registration.onForeground()
        registration.onBackground()
        registration.onPushReceived()

        assertEquals(listOf(false, true), registrar.changes)
        assertFalse(registration.isUnregistered)
    }

    @Test
    fun `a call from a push unregisters again when it ends`() {
        registration.onForeground()
        registration.onBackground()
        registration.onPushReceived()
        registration.onLastCallEnded()

        assertEquals(listOf(false, true, false), registrar.changes)
    }

    @Test
    fun `a cold start from a push stays registered until its call ends`() {
        registration.onPushReceived()

        assertTrue(registrar.changes.isEmpty())
        assertFalse(registration.isInForeground)

        registration.onLastCallEnded()

        assertEquals(listOf(false), registrar.changes)
    }

    @Test
    fun `registration isn't changed again when it's already in that state`() {
        registration.onForeground()
        registration.onForeground()
        registration.onPushReceived()
        registration.onBackground()
        registration.onBackground()
        registration.onLastCallEnded()

        assertEquals(listOf(false), registrar.changes)
    }
}
