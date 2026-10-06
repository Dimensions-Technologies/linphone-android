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
    fun `reads the display name from a P-Asserted-Identity`() {
        assertEquals(
            "Chris R",
            SipIdentity.displayName("\"Chris R\" <sip:+447921910119@pbx.example.com>")
        )
        assertEquals("Reception", SipIdentity.displayName("Reception <sips:1024@pbx.example.com>"))
        assertNull(SipIdentity.displayName("<sip:1024@pbx.example.com;user=phone>"))
        assertNull(SipIdentity.displayName("\"\" <sip:1024@pbx.example.com>"))
    }

    @Test
    fun `anything else isn't an identity`() {
        assertNull(SipIdentity.userPart(null))
        assertNull(SipIdentity.userPart(""))
        assertNull(SipIdentity.userPart("tel:+447921910119"))
        assertNull(SipIdentity.displayName(null))
        assertNull(SipIdentity.displayName("tel:+447921910119"))
    }
}
