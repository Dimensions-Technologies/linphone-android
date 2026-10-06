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
