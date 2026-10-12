package org.linphone.models.callhistory

import org.junit.Assert.assertEquals
import org.junit.Test

class CallHistoryAddressesTest {

    private val domain = "abc123.uk.sip.xarios.cloud"

    @Test
    fun `feature codes keep their star`() {
        assertEquals("sip:*31002@$domain", CallHistoryAddresses.sipUri("*31002", domain))
    }

    @Test
    fun `international numbers keep their plus`() {
        assertEquals("sip:+447700900123@$domain", CallHistoryAddresses.sipUri("+447700900123", domain))
    }

    @Test
    fun `spaces are dropped and other characters escaped`() {
        assertEquals("sip:0161%23496@$domain", CallHistoryAddresses.sipUri("0161 #496", domain))
        assertEquals("sip:a%40b@$domain", CallHistoryAddresses.sipUri("a@b", domain))
    }

    @Test
    fun `no number or no domain is anonymous`() {
        assertEquals(CallHistoryAddresses.ANONYMOUS, CallHistoryAddresses.sipUri(null, domain))
        assertEquals(CallHistoryAddresses.ANONYMOUS, CallHistoryAddresses.sipUri("  ", domain))
        assertEquals(CallHistoryAddresses.ANONYMOUS, CallHistoryAddresses.sipUri("1202", null))
        assertEquals(CallHistoryAddresses.ANONYMOUS, CallHistoryAddresses.sipUri("1202", ""))
    }
}
