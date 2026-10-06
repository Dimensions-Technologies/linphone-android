package org.linphone.models.blf

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogInfoParserTest {

    private fun dialogInfo(vararg dialogs: String) = """
        <?xml version="1.0"?>
        <dialog-info xmlns="urn:ietf:params:xml:ns:dialog-info" version="1" state="full" entity="sip:1024@pbx.example.com">
        ${dialogs.joinToString("")}
        </dialog-info>
    """.trimIndent()

    private fun dialog(state: String, remoteDisplay: String? = null) = """
        <dialog id="d-$state" call-id="c1" direction="initiator">
          <state>$state</state>
          ${if (remoteDisplay != null) "<remote><identity display=\"$remoteDisplay\">sip:2048@pbx.example.com</identity></remote>" else ""}
        </dialog>
    """

    @Test
    fun `no dialogs is idle`() {
        val result = DialogInfoParser.parse(dialogInfo())
        assertEquals(BlfKeyStatus.IDLE, result.status)
        assertEquals("", result.dialogState)
    }

    @Test
    fun `states map like the web client`() {
        assertEquals(
            BlfKeyStatus.RINGING,
            DialogInfoParser.parse(dialogInfo(dialog("early"))).status
        )
        assertEquals(
            BlfKeyStatus.RINGING,
            DialogInfoParser.parse(dialogInfo(dialog("Trying"))).status
        )
        assertEquals(
            BlfKeyStatus.INCALL,
            DialogInfoParser.parse(dialogInfo(dialog("confirmed"))).status
        )
        assertEquals(
            BlfKeyStatus.UNKNOWN,
            DialogInfoParser.parse(dialogInfo(dialog("proceeding"))).status
        )
    }

    @Test
    fun `all dialogs terminated is idle with no dialog state`() {
        val result = DialogInfoParser.parse(dialogInfo(dialog("terminated", "Chris")))
        assertEquals(BlfKeyStatus.IDLE, result.status)
        assertEquals("", result.dialogState)
        assertEquals("", result.remoteDisplay)
    }

    @Test
    fun `the most active dialog wins and gives the remote display`() {
        val result = DialogInfoParser.parse(
            dialogInfo(
                dialog("early", "Ringing caller"),
                dialog("confirmed", "*76"),
                dialog("terminated")
            )
        )
        assertEquals(BlfKeyStatus.INCALL, result.status)
        assertEquals("confirmed", result.dialogState)
        assertEquals("*76", result.remoteDisplay)
    }

    @Test
    fun `a body that isn't dialog-info is unknown`() {
        assertEquals(BlfKeyStatus.UNKNOWN, DialogInfoParser.parse("<presence/>").status)
        assertEquals(BlfKeyStatus.UNKNOWN, DialogInfoParser.parse("not xml").status)
    }
}
