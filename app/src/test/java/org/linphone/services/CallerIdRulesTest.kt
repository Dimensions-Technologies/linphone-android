package org.linphone.services

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.models.ExternalCallerId
import org.linphone.models.realtime.CallerId
import org.linphone.models.realtime.RealtimeEventCallerId

class CallerIdRulesTest {

    private fun choice(
        server: List<CallerId>? = emptyList(),
        policy: List<ExternalCallerId>? = emptyList(),
        defaultEnabled: Boolean = true,
        current: CallerId? = CallerId("Default", "")
    ) = CallerIdRules.choice(server, policy, defaultEnabled, "Account Default", current)

    @Test
    fun `the account default is always listed first and selected when there's no caller ID`() {
        val result = choice()

        assertEquals(listOf(CallerIdOption("Account Default", "", false)), result.options)
        assertEquals(0, result.selectedIndex)
        assertEquals(0, choice(current = null).selectedIndex)
    }

    @Test
    fun `the account default can't be chosen when the profile doesn't allow it`() {
        assertTrue(choice(defaultEnabled = false).options.single().disabled)
    }

    @Test
    fun `server and policy caller IDs are listed once each, with the policy's name winning`() {
        val result = choice(
            server = listOf(CallerId("Sales (server)", "441111"), CallerId("Support", "442222")),
            policy = listOf(ExternalCallerId("Sales", "441111"), ExternalCallerId("Marketing", "443333"))
        )

        assertEquals(
            listOf(
                CallerIdOption("Account Default", "", false),
                CallerIdOption("Sales", "441111", false),
                CallerIdOption("Support", "442222", false),
                CallerIdOption("Marketing", "443333", false)
            ),
            result.options
        )
    }

    @Test
    fun `entries without a number are left out`() {
        val result = choice(
            server = listOf(CallerId("Blank", ""), CallerId("Missing", null)),
            policy = listOf(ExternalCallerId("Blank policy", ""), ExternalCallerId("Missing policy", null))
        )

        assertEquals(1, result.options.size)
    }

    @Test
    fun `the current caller ID is selected by number`() {
        val result = choice(
            policy = listOf(ExternalCallerId("Sales", "441111"), ExternalCallerId("Support", "442222")),
            current = CallerId("anything", "442222")
        )

        assertEquals(2, result.selectedIndex)
    }

    @Test
    fun `a current caller ID that's no longer offered is shown but can't be chosen`() {
        val result = choice(
            policy = listOf(ExternalCallerId("Sales", "441111")),
            current = CallerId("Old", "449999")
        )

        assertEquals(CallerIdOption("Old", "449999", true), result.options.last())
        assertEquals(2, result.selectedIndex)
        assertFalse(result.options[1].disabled)
    }

    @Test
    fun `callerIdEvent is read with the web client's field names`() {
        val json = """
            {
              "id": "event-1", "tenantId": "tenant-1", "userId": "user-1",
              "data": {
                "name": "callerIdEvent",
                "callerId": { "callerName": "Sales", "callerNumber": "441111" },
                "callerIds": [ { "callerName": "Sales", "callerNumber": "441111" } ]
              }
            }
        """.trimIndent()

        val event = Gson().fromJson(json, RealtimeEventCallerId::class.java)

        assertEquals(CallerId("Sales", "441111"), event.data!!.callerId)
        assertEquals(listOf(CallerId("Sales", "441111")), event.data!!.callerIds)
    }
}
