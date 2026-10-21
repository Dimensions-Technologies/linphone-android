package org.linphone.models.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.linphone.models.contact.ContactDirectoryRules

// ADO 36252: an attended transfer leaves the call talking to someone else
class CallPartyRulesTest {
    private val remoteIdentity = "\"Draper 2\" <sip:1041@pbx.example.com>"

    // A CTI update to the call, as CallRouteService.apply makes it
    private fun update(
        previous: CallParty,
        previousRoute: CallRoute,
        cti: CtiCallData?,
        pai: String? = null,
        identity: String = remoteIdentity
    ): Pair<CallParty, CallRoute> {
        val route = CallRouteRules.update(previousRoute, cti, incoming = true)
        val party = CallPartyRules.update(
            previous,
            previousRoute,
            route,
            cti != null,
            pai,
            "1041",
            identity
        ) { ContactDirectoryRules.toE164(it, "GB") }
        return party to route
    }

    private fun callFrom(number: String, name: String = "", userId: String? = null) =
        CtiCallData(callerNumber = number, callerName = name, otherUserIds = userId?.let { listOf(it) })

    @Test
    fun `the first CTI number isn't a change of party`() {
        val (party, _) = update(CallParty(), CallRoute(), callFrom("+441617864355"))

        assertEquals(0, party.changes)
        assertEquals("+441617864355", party.shownNumber)
    }

    @Test
    fun `a transfer to another number changes the party`() {
        val (first, route) = update(CallParty(), CallRoute(), callFrom("+441617864355"))

        val (party, _) = update(first, route, callFrom("+441619999999"))

        assertEquals(1, party.changes)
        assertEquals("+441619999999", party.shownNumber)
        assertEquals(remoteIdentity, party.staleRemoteIdentity)
    }

    @Test
    fun `the same number in another form isn't a change`() {
        val (first, route) = update(CallParty(), CallRoute(), callFrom("+441617864355"))

        val (party, _) = update(first, route, callFrom("01617864355"))

        assertEquals(0, party.changes)
    }

    @Test
    fun `an event that leaves the number out isn't a change`() {
        val (first, route) = update(CallParty(), CallRoute(), callFrom("+441617864355"))

        val (party, _) = update(first, route, callFrom(""))

        assertEquals(0, party.changes)
        assertEquals("+441617864355", party.shownNumber)
    }

    @Test
    fun `the old number is kept through a gap with no CTI call, so the new one is a change`() {
        // During a transfer the leg briefly matches no CTI call
        val (first, route) = update(CallParty(), CallRoute(), callFrom("+441617864355"))
        val (gap, gapRoute) = update(first, route, null)
        assertEquals(first, gap)

        val (party, _) = update(gap, gapRoute, callFrom("+441619999999"))

        assertEquals(1, party.changes)
    }

    @Test
    fun `a transfer to another internal user with the same number changes the party`() {
        val (first, route) = update(CallParty(), CallRoute(), callFrom("1040", userId = "u1"))

        val (party, _) = update(first, route, callFrom("1040", userId = "u2"))

        assertEquals(1, party.changes)
    }

    @Test
    fun `a missing internal user isn't a change`() {
        val (first, route) = update(CallParty(), CallRoute(), callFrom("1040", userId = "u1"))

        val (party, _) = update(first, route, callFrom("1040"))

        assertEquals(0, party.changes)
    }

    @Test
    fun `an asserted number goes stale when the CTI number moves on to someone else`() {
        // Retrieved from park: the PBX asserted the caller, which the CTI number agrees with
        val (first, route) = update(CallParty(), CallRoute(), callFrom("+441617864355"), pai = "01617864355")
        assertEquals("01617864355", CallPartyRules.paiNumber("01617864355", first))

        val (party, newRoute) = update(first, route, callFrom("+441619999999"), pai = "01617864355")

        assertEquals("01617864355", party.stalePaiNumber)
        assertNull(CallPartyRules.paiNumber("01617864355", party))
        assertEquals(1, party.changes)
        assertEquals(
            "+441619999999",
            CallRouteRules.phoneNumber(CallPartyRules.paiNumber("01617864355", party), newRoute, "1041")
        )
    }

    @Test
    fun `an asserted number the CTI number changes to isn't stale`() {
        val (first, route) = update(CallParty(), CallRoute(), callFrom("1040"), pai = "+441619999999")

        val (party, _) = update(first, route, callFrom("01619999999"), pai = "+441619999999")

        assertEquals("", party.stalePaiNumber)
        assertEquals(0, party.changes)
    }

    @Test
    fun `an asserted number with no earlier CTI number isn't stale`() {
        val (party, _) = update(CallParty(), CallRoute(), callFrom("+441619999999"), pai = "01617864355")

        assertEquals("", party.stalePaiNumber)
        assertEquals("01617864355", party.shownNumber)
    }

    @Test
    fun `a new asserted number is trusted again`() {
        val stale = CallParty(shownNumber = "+441619999999", stalePaiNumber = "01617864355")

        val party = CallPartyRules.update(
            stale,
            CallRoute(ctiNumber = "+441619999999"),
            CallRoute(ctiNumber = "+441619999999"),
            hasCti = false,
            pai = "01618888888",
            remoteNumber = "1041",
            remoteIdentity = remoteIdentity
        ) { it }

        assertEquals("", party.stalePaiNumber)
        assertEquals("01618888888", CallPartyRules.paiNumber("01618888888", party))
    }

    @Test
    fun `after a change, the stale SIP identity gives way to the CTI name, else the number`() {
        val (first, route) = update(CallParty(), CallRoute(), callFrom("+441617864355", "Old Caller"))
        val (party, newRoute) = update(first, route, callFrom("+441619999999", "New Caller"))

        assertEquals(
            "New Caller",
            CallPartyRules.staleIdentityName(party, remoteIdentity, null, newRoute, "+441619999999")
        )
        assertEquals(
            "+441619999999",
            CallPartyRules.staleIdentityName(party, remoteIdentity, null, newRoute.copy(ctiName = ""), "+441619999999")
        )
    }

    @Test
    fun `the CTI name isn't kept from an earlier event`() {
        // It would name the old party
        val (_, route) = update(CallParty(), CallRoute(), callFrom("+441617864355", "Old Caller"))

        assertEquals("", CallRouteRules.update(route, callFrom("+441619999999"), incoming = true).ctiName)
    }

    @Test
    fun `the SIP identity is used once it changes, or while nothing has`() {
        val (first, route) = update(CallParty(), CallRoute(), callFrom("+441617864355"))
        assertNull(CallPartyRules.staleIdentityName(first, remoteIdentity, null, route, "+441617864355"))

        val (party, newRoute) = update(first, route, callFrom("+441619999999", "New Caller"))
        assertNull(
            CallPartyRules.staleIdentityName(party, "<sip:1050@pbx.example.com>", null, newRoute, "+441619999999")
        )
    }

    @Test
    fun `an asserted number names the call instead of the stale identity`() {
        val (first, route) = update(CallParty(), CallRoute(), callFrom("+441617864355"))
        val (party, newRoute) = update(first, route, callFrom("+441619999999", "New Caller"))

        assertNull(CallPartyRules.staleIdentityName(party, remoteIdentity, "01618888888", newRoute, "01618888888"))
    }
}
