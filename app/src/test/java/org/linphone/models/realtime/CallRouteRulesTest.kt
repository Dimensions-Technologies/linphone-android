package org.linphone.models.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CallRouteRulesTest {

    @Test
    fun `a CTI call's route path and group are its route and queue`() {
        val route = CallRouteRules.update(
            CallRoute(),
            CtiCallData(
                routePathName = "Noel CallFlow 1",
                routePathId = "4143",
                groupName = "Sales",
                callerNumber = "+441617864355",
                calledNumber = "03301274143"
            ),
            incoming = true
        )

        assertEquals(CallRoute("Noel CallFlow 1", "4143", "Sales", "+441617864355"), route)
    }

    @Test
    fun `an outgoing call's CTI number is the called number`() {
        val route = CallRouteRules.update(
            CallRoute(),
            CtiCallData(callerNumber = "1035", calledNumber = "01612345678"),
            incoming = false
        )

        assertEquals("01612345678", route.ctiNumber)
    }

    @Test
    fun `values a later event leaves out are kept`() {
        val previous = CallRoute("Noel CallFlow 1", "4143", "Sales", "+441617864355")

        val route = CallRouteRules.update(
            previous,
            CtiCallData(routePathName = "", groupName = null),
            incoming = true
        )

        assertEquals(previous, route)
    }

    @Test
    fun `no CTI call leaves the route as it was`() {
        val previous = CallRoute("Main")

        assertSame(previous, CallRouteRules.update(previous, null, incoming = true))
    }

    @Test
    fun `the phone number is the asserted one, else the CTI one, else the remote address's`() {
        val route = CallRoute(ctiNumber = "+441617864355")

        assertEquals("01619999999", CallRouteRules.phoneNumber("01619999999", route, "anonymous"))
        assertEquals("+441617864355", CallRouteRules.phoneNumber(null, route, "anonymous"))
        assertEquals("+441617864355", CallRouteRules.phoneNumber("", route, "anonymous"))
        assertEquals("anonymous", CallRouteRules.phoneNumber(null, CallRoute(), "anonymous"))
        assertEquals("", CallRouteRules.phoneNumber(null, CallRoute(), null))
    }

    @Test
    fun `the call view lists the route path then the queue for an incoming call`() {
        val info = CallRouteRules.callViewInfo(CallRoute("Main", "rp1", "Sales"), incoming = true)

        assertEquals(listOf("Main rp1", "Sales"), info)
    }

    @Test
    fun `the call view lists only what the route has`() {
        assertEquals(listOf("Sales"), CallRouteRules.callViewInfo(CallRoute(queueName = "Sales"), true))
        assertEquals(listOf("Main"), CallRouteRules.callViewInfo(CallRoute("Main"), true))
        assertEquals(emptyList<String>(), CallRouteRules.callViewInfo(CallRoute(), true))
    }

    @Test
    fun `the call view lists no route for an outgoing call`() {
        assertEquals(
            emptyList<String>(),
            CallRouteRules.callViewInfo(CallRoute("Main", "rp1", "Sales"), incoming = false)
        )
    }

    @Test
    fun `the notification names the route path, else the queue`() {
        assertEquals(
            "Noel CallFlow 1",
            CallRouteRules.notificationRoute(CallRoute("Noel CallFlow 1", queueName = "Sales"), "441617864355")
        )
        assertEquals(
            "Sales",
            CallRouteRules.notificationRoute(CallRoute(queueName = " Sales "), "441617864355")
        )
    }

    @Test
    fun `the notification names no route that contains the caller's number`() {
        assertNull(CallRouteRules.notificationRoute(CallRoute("DDI 441617864355"), "441617864355"))
    }

    @Test
    fun `the notification names no route when there is none`() {
        assertNull(CallRouteRules.notificationRoute(CallRoute(), "441617864355"))
        assertNull(CallRouteRules.notificationRoute(CallRoute(" "), "441617864355"))
    }

    @Test
    fun `as the web client, a caller with no number gets no route in the notification`() {
        assertNull(CallRouteRules.notificationRoute(CallRoute("Main"), ""))
    }
}
