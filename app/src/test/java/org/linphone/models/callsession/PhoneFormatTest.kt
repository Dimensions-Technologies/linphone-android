package org.linphone.models.callsession

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneFormatTest {

    @Test
    fun `a number in the PBX's country is shown in national form, with spaces`() {
        assertEquals("0161 786 4357", PhoneFormat.formatDisplay("01617864357", "GB"))
        assertEquals("0161 786 4357", PhoneFormat.formatDisplay("+441617864357", "GB"))
    }

    @Test
    fun `a number from another country is shown in international form`() {
        assertEquals("+1 212-555-0123", PhoneFormat.formatDisplay("+12125550123", "GB"))
    }

    @Test
    fun `extensions and short numbers are shown as they are`() {
        assertEquals("1035", PhoneFormat.formatDisplay("1035", "GB"))
    }

    @Test
    fun `feature codes keep their star`() {
        assertEquals("*3101", PhoneFormat.formatDisplay("*3101", "GB"))
        assertEquals("*97", PhoneFormat.formatDisplay("*97", "GB"))
    }

    @Test
    fun `names are shown as they are`() {
        assertEquals("Beth Ramsay", PhoneFormat.formatDisplay("Beth Ramsay", "GB"))
        assertEquals("Voicemail", PhoneFormat.formatDisplay("Voicemail", "GB"))
    }
}
