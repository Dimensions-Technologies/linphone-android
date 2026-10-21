package org.linphone.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftphoneDeviceRulesTest {

    private fun device(
        sipUsername: String = "1040",
        sipPassword: String = "secret",
        sipRealm: String = "realm.example",
        sipOutboundProxy: String = ""
    ) = UserDevice(
        deviceId = "device",
        pbxId = "pbx",
        sipUsername = sipUsername,
        sipUserPassword = sipPassword,
        sipRealm = sipRealm,
        sipPort = 0,
        sipOutboundProxy = sipOutboundProxy,
        remoteSipHost = "",
        sipOutboundProxyPort = 0,
        sipTransport = "tls",
        primaryDns = "",
        secondaryDns = ""
    )

    @Test
    fun `no device is configured when the devices API returns an empty list`() {
        assertTrue(SoftphoneDeviceRules.noDeviceConfigured(emptyList()))
    }

    @Test
    fun `no device is configured when none of the devices can register`() {
        assertTrue(
            SoftphoneDeviceRules.noDeviceConfigured(
                listOf(device(sipPassword = ""), device(sipRealm = "", sipOutboundProxy = ""))
            )
        )
    }

    @Test
    fun `a device is configured when one of the devices can register`() {
        assertFalse(
            SoftphoneDeviceRules.noDeviceConfigured(
                listOf(device(sipPassword = ""), device(sipRealm = "", sipOutboundProxy = "proxy"))
            )
        )
    }

    @Test
    fun `nothing is reported before the devices load or after signing out`() {
        assertFalse(SoftphoneDeviceRules.noDeviceConfigured(null))
    }
}
