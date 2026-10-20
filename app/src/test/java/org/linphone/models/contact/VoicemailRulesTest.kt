package org.linphone.models.contact

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicemailRulesTest {

    @Test
    fun `an outgoing call to the user's own extension is their voicemail`() {
        assertTrue(VoicemailRules.isOwnVoicemailCall(outgoing = true, number = "1035", presenceId = "1035"))
    }

    @Test
    fun `an incoming call from the user's own extension isn't their voicemail`() {
        assertFalse(VoicemailRules.isOwnVoicemailCall(outgoing = false, number = "1035", presenceId = "1035"))
    }

    @Test
    fun `a call to another extension isn't voicemail`() {
        assertFalse(VoicemailRules.isOwnVoicemailCall(outgoing = true, number = "1034", presenceId = "1035"))
    }

    @Test
    fun `nothing is voicemail before the user's extension is known`() {
        assertFalse(VoicemailRules.isOwnVoicemailCall(outgoing = true, number = "", presenceId = ""))
        assertFalse(VoicemailRules.isOwnVoicemailCall(outgoing = true, number = "1035", presenceId = null))
        assertFalse(VoicemailRules.isOwnNumber(null, null))
    }

    @Test
    fun `a history row with the user's own extension is voicemail in either direction`() {
        assertTrue(VoicemailRules.isOwnNumber("1035", "1035"))
        assertFalse(VoicemailRules.isOwnNumber("1034", "1035"))
        assertFalse(VoicemailRules.isOwnNumber(null, "1035"))
    }
}
