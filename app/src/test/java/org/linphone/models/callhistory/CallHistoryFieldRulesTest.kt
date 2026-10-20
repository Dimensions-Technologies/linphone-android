package org.linphone.models.callhistory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallHistoryFieldRulesTest {

    private fun fields(name: String?, formattedNumber: String, number: String, route: String?) =
        CallHistoryFieldRules.fields(name, formattedNumber, number, route) { "via $it" }

    @Test
    fun `a call has its name, number and route`() {
        assertEquals(
            listOf("Beth Ramsay", "0161 786 4355", "via Noel CallFlow 1"),
            fields("Beth Ramsay", "0161 786 4355", "+441617864355", "Noel CallFlow 1")
        )
    }

    @Test
    fun `a call with no name starts with its number`() {
        assertEquals(
            listOf("0161 786 4355", "via Noel CallFlow 1"),
            fields(null, "0161 786 4355", "+441617864355", "Noel CallFlow 1")
        )
        assertEquals(
            listOf("0161 786 4355"),
            fields("", "0161 786 4355", "+441617864355", null)
        )
    }

    @Test
    fun `a name that is the number isn't repeated`() {
        assertEquals(listOf("2001"), fields("2001", "2001", "2001", null))
    }

    @Test
    fun `a route named after the number isn't shown`() {
        assertEquals(
            listOf("Beth Ramsay", "0161 786 4355"),
            fields("Beth Ramsay", "0161 786 4355", "+441617864355", "DDI +441617864355")
        )
    }

    @Test
    fun `a call with no number shows no route`() {
        assertEquals(listOf("Unknown"), fields(null, "Unknown", "", "Main"))
    }

    @Test
    fun `the route is the route path, else the group, else the hunt group`() {
        assertEquals("Main", CallHistoryFieldRules.route(item("Main", "Sales", "Hunt")))
        assertEquals("Sales", CallHistoryFieldRules.route(item("", "Sales", "Hunt")))
        assertEquals("Hunt", CallHistoryFieldRules.route(item(null, null, "Hunt")))
        assertNull(CallHistoryFieldRules.route(item(null, "", null)))
    }

    private fun item(routePathName: String?, groupName: String?, huntgroupName: String?) =
        CallHistoryItem(
            missedCall = false,
            answered = true,
            hasRecording = false,
            startTime = null,
            connectionId = "connection",
            callType = CallTypes.External,
            callDirection = CallDirections.Incoming.value,
            contactName = null,
            contactMatchType = null,
            hasContactMatch = false,
            calledUserName = null,
            calledUserNumber = "1035",
            callingUserName = null,
            callingUserNumber = "+441617864355",
            routePathName = routePathName,
            groupName = groupName,
            huntgroupName = huntgroupName,
            documentId = "doc",
            isConference = false,
            pbxType = PbxType.Kazoo,
            interactionTags = listOf()
        )
}
