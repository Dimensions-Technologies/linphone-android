package org.linphone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.linphone.R

/**
 * Checks what the user is told when a call ends in error: nothing for an incoming call that was
 * cancelled or rejected (as on the web client), a specific message where there is one, and
 * otherwise "Call failed" - never the raw SIP code, which shows as "0 / null" with no response.
 */
class CallErrorPolicyTest {

    @Test
    fun `an incoming call with no reason says nothing`() {
        // The caller hung up but the cancel never arrived (seen as "Error: 0 / null")
        assertNull(CallErrorPolicy.message(Call.Dir.Incoming, Reason.None))
        assertNull(CallErrorPolicy.message(Call.Dir.Incoming, null))
    }

    @Test
    fun `an outgoing call with no reason is just Call failed`() {
        assertEquals(R.string.call_error_failed, CallErrorPolicy.message(Call.Dir.Outgoing, Reason.None))
        assertEquals(R.string.call_error_failed, CallErrorPolicy.message(Call.Dir.Outgoing, null))
        assertEquals(R.string.call_error_failed, CallErrorPolicy.message(null, null))
    }

    @Test
    fun `a rejected call says nothing`() {
        assertNull(CallErrorPolicy.message(Call.Dir.Incoming, Reason.Declined))
        assertNull(CallErrorPolicy.message(Call.Dir.Outgoing, Reason.Declined))
    }

    @Test
    fun `known reasons keep their own message in both directions`() {
        val expected = mapOf(
            Reason.Busy to R.string.call_error_user_busy,
            Reason.IOError to R.string.call_error_io_error,
            Reason.NotAcceptable to R.string.call_error_incompatible_media_params,
            Reason.NotFound to R.string.call_error_user_not_found,
            Reason.ServerTimeout to R.string.call_error_server_timeout,
            Reason.TemporarilyUnavailable to R.string.call_error_temporarily_unavailable
        )
        for (dir in listOf(Call.Dir.Incoming, Call.Dir.Outgoing)) {
            expected.forEach { (reason, message) ->
                assertEquals("$dir $reason", message, CallErrorPolicy.message(dir, reason))
            }
        }
    }

    @Test
    fun `any other reason is Call failed in both directions`() {
        val specific = setOf(
            Reason.None, Reason.Declined, Reason.Busy, Reason.IOError, Reason.NotAcceptable,
            Reason.NotFound, Reason.ServerTimeout, Reason.TemporarilyUnavailable
        )
        for (dir in listOf(Call.Dir.Incoming, Call.Dir.Outgoing)) {
            Reason.values().filterNot { it in specific }.forEach { reason ->
                assertEquals("$dir $reason", R.string.call_error_failed, CallErrorPolicy.message(dir, reason))
            }
        }
    }
}
