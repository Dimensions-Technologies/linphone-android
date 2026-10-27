package org.linphone.models.realtime

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CtiCallRulesTest {

    private fun call(
        state: String,
        startTime: String? = "2026-10-06T09:00:00Z",
        eventType: String? = "PbxEstablished",
        deviceNumber: String? = null,
        direction: String? = "outgoing",
        callerNumber: String? = "2001",
        calledNumber: String? = "01234567890"
    ) = CtiCallData(
        state = state,
        startTime = startTime,
        eventType = eventType,
        deviceNumber = deviceNumber,
        direction = direction,
        type = "external",
        callerNumber = callerNumber,
        calledNumber = calledNumber
    )

    @Test
    fun `callEvent from the server is read`() {
        // SignalR's Java client reads events with plain Gson
        val json = """{"data":{"calls":{
            "c1":{"callId":"c1","userId":"u1","state":"connected","eventType":"PbxEstablished",
                  "deviceNumber":"2001","direction":"outgoing","type":"external",
                  "callerNumber":"2001","calledNumber":"01234567890",
                  "startTime":"2026-10-06T09:00:00Z","relatedCalls":[],"otherUserIds":[]}}},
            "timestamp":"2026-10-06T09:00:01Z","userId":"u1","id":"e1","tenantId":"t1"}"""

        val event = Gson().fromJson(json, RealtimeEventCall::class.java)

        val call = event.data!!.calls!!.getValue("c1")
        assertEquals("connected", call.state)
        assertEquals("PbxEstablished", call.eventType)
        assertEquals("01234567890", call.calledNumber)
        assertEquals("c1", CtiCallRules.activeCallId(CtiCallRules.currentCalls(event.data!!.calls)))
    }

    @Test
    fun `an incoming callEvent's route, queue and legs are read`() {
        val json = """{"data":{"calls":{
            "c1":{"callId":"c1","userId":"u1","state":"ringing","eventType":"PbxOffered",
                  "direction":"incoming","type":"external","callerNumber":"+441617864355",
                  "calledNumber":"03301274143","groupName":"Sales","groupNumber":"600",
                  "routePathName":"Noel CallFlow 1","routePathId":"rp1",
                  "startTime":"2026-10-19T09:00:00Z",
                  "relatedCalls":[{"callId":"abc123@10.0.0.1","userId":"u1","state":"ringing"}],
                  "otherUserIds":[]}}},
            "timestamp":"2026-10-19T09:00:01Z","userId":"u1","id":"e1","tenantId":"t1"}"""

        val call = Gson().fromJson(json, RealtimeEventCall::class.java).data!!.calls!!.getValue("c1")

        assertEquals("Noel CallFlow 1", call.routePathName)
        assertEquals("rp1", call.routePathId)
        assertEquals("Sales", call.groupName)
        assertEquals("abc123@10.0.0.1", call.relatedCalls!!.single().callId)
    }

    @Test
    fun `a SIP call is the CTI call that has its Call-ID among its legs`() {
        val other = call("ringing").copy(relatedCalls = listOf(CtiRelatedCall(callId = "other")))
        val mine = call("ringing").copy(
            routePathName = "Main",
            relatedCalls = listOf(CtiRelatedCall(callId = "desk"), CtiRelatedCall(callId = "sip-1"))
        )
        val calls = mapOf("c1" to other, "c2" to mine)

        assertEquals(mine, CtiCallRules.callForSipCall(calls, "sip-1"))
        assertNull(CtiCallRules.callForSipCall(calls, "sip-2"))
        assertNull(CtiCallRules.callForSipCall(calls, ""))
        assertNull(CtiCallRules.callForSipCall(mapOf("c1" to call("ringing")), "sip-1"))
    }

    @Test
    fun `a server call id is for the SIP call that's a leg of the CTI call it names`() {
        // Bug 36366: callMatchEvent names the PBX call (4a1a2103-…), the softphone's INVITE has its
        // own Call-ID (a45aa719-…); only the callEvent links the two
        val pbxCallId = "4a1a2103-499a-1240-f1aa-005056a5e8f1"
        val sipCallId = "a45aa719-0150-4a68-ba46-d66473be96aa"
        val calls = mapOf(
            pbxCallId to call("ringing").copy(
                callId = pbxCallId,
                relatedCalls = listOf(CtiRelatedCall(callId = "desk-leg"), CtiRelatedCall(callId = sipCallId))
            ),
            "other" to call("ringing").copy(callId = "other", relatedCalls = listOf(CtiRelatedCall(callId = "x")))
        )
        val sipCalls = listOf(sipCallId)

        assertEquals(sipCallId, CtiCallRules.sipCallIdForServerId(calls, sipCalls, pbxCallId))
        // Another leg of the same call
        assertEquals(sipCallId, CtiCallRules.sipCallIdForServerId(calls, sipCalls, "desk-leg"))
        assertNull(CtiCallRules.sipCallIdForServerId(calls, sipCalls, "other"))
        assertNull(CtiCallRules.sipCallIdForServerId(calls, sipCalls, "x"))
    }

    @Test
    fun `a server call id is for the SIP call with that Call-ID, even without a callEvent`() {
        assertEquals("sip-1", CtiCallRules.sipCallIdForServerId(emptyMap(), listOf("sip-1"), "sip-1"))
        assertNull(CtiCallRules.sipCallIdForServerId(emptyMap(), listOf("sip-1"), "c1"))
        assertNull(CtiCallRules.sipCallIdForServerId(emptyMap(), listOf(""), ""))
        assertNull(CtiCallRules.sipCallIdForServerId(emptyMap(), emptyList(), "sip-1"))
    }

    @Test
    fun `a server call id can be the CTI call's callId rather than its key`() {
        val calls = mapOf(
            "key-1" to call("ringing").copy(callId = "c1", relatedCalls = listOf(CtiRelatedCall(callId = "sip-1")))
        )

        assertEquals("sip-1", CtiCallRules.sipCallIdForServerId(calls, listOf("sip-1"), "c1"))
        assertEquals("sip-1", CtiCallRules.sipCallIdForServerId(calls, listOf("sip-1"), "key-1"))
        assertNull(CtiCallRules.sipCallIdForServerId(calls, listOf("sip-2"), "c1"))
    }

    @Test
    fun `a server call id is matched rule by rule across all calls, as the web client's findCall`() {
        // sip-b's Call-ID is also a leg of sip-a's CTI call, and c2's id is a leg of sip-a's too;
        // sip-a comes first, but an earlier rule matching a later call wins
        val calls = mapOf(
            "c1" to call("ringing").copy(
                callId = "c1",
                relatedCalls = listOf(CtiRelatedCall(callId = "sip-a"), CtiRelatedCall(callId = "sip-b"), CtiRelatedCall(callId = "c2"))
            ),
            "c2" to call("ringing").copy(callId = "c2", relatedCalls = listOf(CtiRelatedCall(callId = "sip-c")))
        )
        val sipCalls = listOf("sip-a", "sip-c", "sip-b")

        // Own Call-ID beats being another leg of sip-a's CTI call
        assertEquals("sip-b", CtiCallRules.sipCallIdForServerId(calls, sipCalls, "sip-b"))
        // The CTI call's id beats being another leg of sip-a's CTI call
        assertEquals("sip-c", CtiCallRules.sipCallIdForServerId(calls, sipCalls, "c2"))
    }

    @Test
    fun `cleared calls are left out`() {
        val calls = CtiCallRules.currentCalls(mapOf("c1" to call("cleared")))

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `calls in an unknown state are kept only for an event other than PbxCleared`() {
        val calls = CtiCallRules.currentCalls(
            mapOf(
                "c1" to call("unknown", eventType = "PbxCleared"),
                "c2" to call("unknown", eventType = null),
                "c3" to call("unknown", eventType = "PbxOffered")
            )
        )

        assertEquals(listOf("c3"), calls.keys.toList())
    }

    @Test
    fun `calls without a start time need a device and an event other than a snapshot`() {
        val calls = CtiCallRules.currentCalls(
            mapOf(
                "c1" to call(
                    "ringing",
                    startTime = null,
                    deviceNumber = "2001",
                    eventType = "PbxDeviceSnapshot"
                ),
                "c2" to call("ringing", startTime = "", deviceNumber = "", eventType = "PbxOffered"),
                "c3" to call(
                    "ringing",
                    startTime = null,
                    deviceNumber = "2001",
                    eventType = "PbxOffered"
                )
            )
        )

        assertEquals(listOf("c3"), calls.keys.toList())
    }

    @Test
    fun `quickcall legs for one call collapse to the first by call id`() {
        val calls = CtiCallRules.currentCalls(
            mapOf(
                "b-quickcall" to call("offhook"),
                "a-quickcall" to call("offhook"),
                // A different call, so kept
                "c-quickcall" to call("offhook", calledNumber = "09876543210"),
                // Not a quickcall leg, so never collapsed
                "d" to call("offhook")
            )
        )

        assertEquals(listOf("a-quickcall", "c-quickcall", "d"), calls.keys.toList())
    }

    @Test
    fun `a missing number matches an empty one when collapsing quickcall legs`() {
        val calls = CtiCallRules.dedupeQuickCallLegs(
            mapOf(
                "a-QuickCall" to call("offhook", callerNumber = null),
                "b-quickcall" to call("offhook", callerNumber = "")
            )
        )

        assertEquals(listOf("a-QuickCall"), calls.keys.toList())
    }

    @Test
    fun `the active call is the first connected one`() {
        val calls = CtiCallRules.currentCalls(
            mapOf(
                "a" to call("held"),
                "c" to call("connected"),
                "b" to call("connected", calledNumber = "09876543210")
            )
        )

        assertEquals("b", CtiCallRules.activeCallId(calls))
    }

    @Test
    fun `there is no active call while calls are only ringing or held`() {
        val calls = CtiCallRules.currentCalls(
            mapOf("a" to call("ringing"), "b" to call("held"))
        )

        assertNull(CtiCallRules.activeCallId(calls))
        assertNull(CtiCallRules.activeCallId(CtiCallRules.currentCalls(null)))
    }
}
