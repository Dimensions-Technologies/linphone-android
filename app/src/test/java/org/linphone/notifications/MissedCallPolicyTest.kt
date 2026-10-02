package org.linphone.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.core.Call

/**
 * Checks which finished calls get the local missed call notification (WI #28567).
 */
class MissedCallPolicyTest {

    @Test
    fun `an unanswered incoming call is missed`() {
        assertTrue(MissedCallPolicy.isMissed(Call.Dir.Incoming, Call.Status.Missed))
    }

    @Test
    fun `an incoming call the caller hung up on is missed`() {
        assertTrue(MissedCallPolicy.isMissed(Call.Dir.Incoming, Call.Status.Aborted))
        assertTrue(MissedCallPolicy.isMissed(Call.Dir.Incoming, Call.Status.EarlyAborted))
    }

    @Test
    fun `an incoming call answered or declined on another device is not missed`() {
        assertFalse(MissedCallPolicy.isMissed(Call.Dir.Incoming, Call.Status.AcceptedElsewhere))
        assertFalse(MissedCallPolicy.isMissed(Call.Dir.Incoming, Call.Status.DeclinedElsewhere))
    }

    @Test
    fun `an answered or declined incoming call is not missed`() {
        assertFalse(MissedCallPolicy.isMissed(Call.Dir.Incoming, Call.Status.Success))
        assertFalse(MissedCallPolicy.isMissed(Call.Dir.Incoming, Call.Status.Declined))
    }

    @Test
    fun `an outgoing call is never missed`() {
        val missed = Call.Status.values().filter {
            MissedCallPolicy.isMissed(Call.Dir.Outgoing, it)
        }
        assertEquals(emptyList<Call.Status>(), missed)
    }

    @Test
    fun `a call log with no direction or status is not missed`() {
        assertFalse(MissedCallPolicy.isMissed(null, Call.Status.Missed))
        assertFalse(MissedCallPolicy.isMissed(Call.Dir.Incoming, null))
    }
}
