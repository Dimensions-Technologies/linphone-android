package org.linphone.activities.main.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.R
import org.linphone.services.SendToSupportState

class SendToSupportButtonStateTest {

    @Test
    fun `idle shows the plain enabled button`() {
        val state = SendToSupportButtonState.of(SendToSupportState.IDLE)
        assertEquals(R.string.about_send_to_support, state.label)
        assertTrue(state.enabled)
        assertFalse(state.sent)
    }

    @Test
    fun `no state yet is treated as idle`() {
        assertEquals(
            SendToSupportButtonState.of(SendToSupportState.IDLE),
            SendToSupportButtonState.of(null)
        )
    }

    @Test
    fun `sending disables the button without the sent look`() {
        val state = SendToSupportButtonState.of(SendToSupportState.SENDING)
        assertEquals(R.string.about_send_to_support_sending, state.label)
        assertFalse(state.enabled)
        assertFalse(state.sent)
    }

    @Test
    fun `sent shows the green tick and stays tappable`() {
        val state = SendToSupportButtonState.of(SendToSupportState.SENT)
        assertEquals(R.string.about_send_to_support_sent, state.label)
        assertTrue(state.enabled)
        assertTrue(state.sent)
    }

    @Test
    fun `failed offers a retry without the sent look`() {
        val state = SendToSupportButtonState.of(SendToSupportState.FAILED)
        assertEquals(R.string.about_send_to_support_failed, state.label)
        assertTrue(state.enabled)
        assertFalse(state.sent)
    }

    @Test
    fun `tick shows when label and tick fit on one line`() {
        assertTrue(SendToSupportButtonState.tickFits(300f, buttonWidth = 400, horizontalPadding = 60, tickSpace = 40))
    }

    @Test
    fun `tick is left off when it would wrap the label`() {
        assertFalse(SendToSupportButtonState.tickFits(320f, buttonWidth = 400, horizontalPadding = 60, tickSpace = 40))
    }

    @Test
    fun `tick is left off before the button is laid out`() {
        assertFalse(SendToSupportButtonState.tickFits(0f, buttonWidth = 0, horizontalPadding = 0, tickSpace = 0))
    }
}
