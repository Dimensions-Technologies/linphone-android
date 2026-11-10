package org.linphone.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SipIdentityTest {
    @Test
    fun `reads the number from a P-Asserted-Identity`() {
        assertEquals(
            "+447921910119",
            SipIdentity.userPart("\"Chris R\" <sip:+447921910119@pbx.example.com>")
        )
        assertEquals("1024", SipIdentity.userPart("<sip:1024@pbx.example.com;user=phone>"))
        assertEquals("1024", SipIdentity.userPart("Reception <sips:1024@pbx.example.com>"))
    }

    @Test
    fun `anything else isn't an identity`() {
        assertNull(SipIdentity.userPart(null))
        assertNull(SipIdentity.userPart(""))
        assertNull(SipIdentity.userPart("tel:+447921910119"))
    }

    @Test
    fun `the other party is the PAI's number when it isn't the remote address`() {
        // A parked caller retrieved with the park code: the PBX names them in the PAI
        assertEquals(
            "1041",
            SipIdentity.otherPartyNumber("\"Draper 2\" <sip:1041@pbx.example.com>", "*3101")
        )
    }

    @Test
    fun `the PAI's display name is never the other party (WI 36371)`() {
        // The PBX filled the name from a HubSpot contact, which the web client doesn't show
        assertEquals(
            "+441617864358",
            SipIdentity.otherPartyNumber(
                "\"New Contact - 0161 786 4358\" <sip:+441617864358@pbx.example.com>",
                "01617864358"
            )
        )
    }

    @Test
    fun `a withheld caller's number is never asserted (WI 36896)`() {
        // A withheld caller retrieved from park: the PBX hides the number only by the display name
        val pai = "\"anonymous\" <sip:+447866266719@192.168.200.121>"
        assertEquals("anonymous", SipIdentity.assertedNumber(pai))
        assertEquals("anonymous", SipIdentity.otherPartyNumber(pai, "*31034"))
        assertEquals(
            "Anonymous",
            SipIdentity.assertedNumber("Anonymous <sip:+447866266719@pbx.example.com>")
        )
    }

    @Test
    fun `an identified caller's asserted number is the PAI's user part`() {
        assertEquals(
            "+447921910119",
            SipIdentity.assertedNumber("\"Chris R\" <sip:+447921910119@pbx.example.com>")
        )
        assertEquals("1024", SipIdentity.assertedNumber("<sip:1024@pbx.example.com>"))
        assertNull(SipIdentity.assertedNumber(null))
        assertNull(SipIdentity.assertedNumber("tel:+447921910119"))
    }

    @Test
    fun `no other party when the PAI is the remote address or missing`() {
        assertNull(
            SipIdentity.otherPartyNumber("\"Chris R\" <sip:1024@pbx.example.com>", "1024")
        )
        assertNull(SipIdentity.otherPartyNumber(null, "1024"))
        assertNull(SipIdentity.otherPartyNumber("tel:+447921910119", "1024"))
    }
}
