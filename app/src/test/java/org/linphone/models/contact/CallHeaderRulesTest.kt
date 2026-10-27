package org.linphone.models.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHeaderRulesTest {

    @Test
    fun `an unmatched call shows its number once, as the name`() {
        assertFalse(CallHeaderRules.showsNumber(true, false, "0161 786 4357", "0161 786 4357"))
        assertFalse(CallHeaderRules.showsNumber(false, false, "0161 786 4357", "0161 786 4357"))
    }

    @Test
    fun `a matched outgoing call shows the number under the name`() {
        assertTrue(CallHeaderRules.showsNumber(true, false, "Beth Ramsay", "0161 786 4355"))
    }

    @Test
    fun `an incoming call never shows the number under the name`() {
        assertFalse(CallHeaderRules.showsNumber(false, false, "Beth Ramsay", "0161 786 4355"))
    }

    @Test
    fun `a call to the user's own voicemail shows no number`() {
        assertFalse(CallHeaderRules.showsNumber(true, true, "Voicemail", "1035"))
    }

    @Test
    fun `a call without a number shows none`() {
        assertFalse(CallHeaderRules.showsNumber(true, false, "Beth Ramsay", ""))
        assertFalse(CallHeaderRules.showsNumber(true, false, "Beth Ramsay", null))
    }

    @Test
    fun `an unmatched caller's notification shows the number once`() {
        assertNull(CallHeaderRules.notificationNumber("0161 786 4357", "0161 786 4357"))
    }

    @Test
    fun `a named caller's notification shows the number under the name`() {
        assertEquals("0161 786 4355", CallHeaderRules.notificationNumber("Beth Ramsay", "0161 786 4355"))
    }

    @Test
    fun `a notification for a call without a number shows none`() {
        assertNull(CallHeaderRules.notificationNumber("Beth Ramsay", ""))
        assertNull(CallHeaderRules.notificationNumber("Beth Ramsay", null))
    }
}
