package org.linphone.models.blf

import org.junit.Assert.assertEquals
import org.junit.Test

class BlfDisplayTest {

    private val parkCode = "*3"
    private val dndCode = "*76"

    private fun key(dialogState: String, displayNumber: String = "") =
        BlfKey("1024", BlfKeyStatus.INCALL, dialogState, displayNumber)

    private fun text(key: BlfKey, phone: String = "1024") = BlfDisplay.text(
        key,
        phone,
        parkCode,
        dndCode
    )

    @Test
    fun `extension states come from the dialog state`() {
        assertEquals(BlfDisplay.Text.Available, text(key("")))
        assertEquals(BlfDisplay.Text.Available, text(key("terminated")))
        assertEquals(BlfDisplay.Text.Ringing, text(key("early")))
        assertEquals(BlfDisplay.Text.Ringing, text(key("proceeding")))
        assertEquals(BlfDisplay.Text.OnACall, text(key("confirmed", "Chris")))
    }

    @Test
    fun `a confirmed dialog to the DND code is do not disturb`() {
        assertEquals(BlfDisplay.Text.DoNotDisturb, text(key("confirmed", "*76")))
        assertEquals(
            BlfDisplay.Text.OnACall,
            BlfDisplay.text(key("confirmed", "*76"), "1024", parkCode, null)
        )
    }

    @Test
    fun `feature code numbers show enabled or disabled`() {
        assertEquals(BlfDisplay.Text.Disabled, text(key(""), "*72"))
        assertEquals(BlfDisplay.Text.Enabled, text(key("confirmed", "*76"), "*72"))
        assertEquals(BlfDisplay.Text.Enabled, text(key("early"), "*72"))
    }

    @Test
    fun `park slots show free or the parked caller`() {
        assertEquals(BlfDisplay.Text.Free, text(key("terminated"), "*31"))
        assertEquals(
            BlfDisplay.Text.CallParked("07968543537"),
            text(key("confirmed", "07968543537"), "*31")
        )
        assertEquals(BlfDisplay.Text.CallParked(""), text(key("early"), "*31"))
    }
}
