package org.linphone.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.models.UserDevice

class CallWaitingRulesTest {

    private fun device(sipUsername: String = "user1", callWaitingEnabled: Boolean? = null) = UserDevice(
        deviceId = "device-$sipUsername",
        pbxId = "pbx",
        sipUsername = sipUsername,
        sipUserPassword = if (sipUsername.isBlank()) "" else "secret",
        sipRealm = "realm.example.com",
        sipPort = 5061,
        sipOutboundProxy = "",
        remoteSipHost = "sip.example.com",
        sipOutboundProxyPort = 0,
        sipTransport = "tls",
        primaryDns = "",
        secondaryDns = "",
        callWaitingEnabled = callWaitingEnabled
    )

    @Test
    fun `the primary device is the first one the app can register`() {
        val unregistrable = device(sipUsername = "")
        val first = device("user1")
        val second = device("user2")

        assertEquals(first, CallWaitingRules.primaryDevice(listOf(unregistrable, first, second)))
        assertNull(CallWaitingRules.primaryDevice(listOf(unregistrable)))
        assertNull(CallWaitingRules.primaryDevice(null))
    }

    @Test
    fun `a call is rejected while another is in progress when call waiting is off`() {
        assertTrue(CallWaitingRules.shouldReject(1, device(callWaitingEnabled = false)))
        assertTrue(CallWaitingRules.shouldReject(2, device(callWaitingEnabled = false)))
    }

    @Test
    fun `a call rings while another is in progress when call waiting is on`() {
        assertFalse(CallWaitingRules.shouldReject(1, device(callWaitingEnabled = true)))
    }

    @Test
    fun `the only call always rings`() {
        assertFalse(CallWaitingRules.shouldReject(0, device(callWaitingEnabled = false)))
        assertFalse(CallWaitingRules.shouldReject(0, null))
    }

    @Test
    fun `a device that doesn't say counts as off, as on the web client`() {
        assertFalse(CallWaitingRules.isEnabled(device(callWaitingEnabled = null)))
        assertFalse(CallWaitingRules.isEnabled(null))
        assertTrue(CallWaitingRules.shouldReject(1, device(callWaitingEnabled = null)))
        assertTrue(CallWaitingRules.shouldReject(1, null))
    }
}
