package org.linphone.models.realtime

/**
 * The route path and queue a live call came in on, and the other party's number, name and internal
 * user, from the PBX's callEvent.
 */
data class CallRoute(
    val routePathName: String = "",
    val routePathId: String = "",
    val queueName: String = "",
    val ctiNumber: String = "",
    val ctiName: String = "",
    val otherUserId: String = ""
)

/**
 * How a live call's route is shown, ported from the web client: its CallSummary keeps the route
 * from the call's CTI data, the call view lists it, and browser-notification.service.ts titles the
 * notification "Incoming call via {route}".
 */
object CallRouteRules {
    /**
     * The route once [cti] is applied. As the web client's CallSummary.update, a value the PBX
     * leaves out of a later event keeps the one it sent before. Except the name, which the web
     * client reads from the latest event only - after a transfer, the old party's would stick.
     */
    fun update(previous: CallRoute, cti: CtiCallData?, incoming: Boolean): CallRoute {
        if (cti == null) return previous
        val number = if (incoming) cti.callerNumber else cti.calledNumber
        val name = if (incoming) cti.callerName else cti.calledName
        return CallRoute(
            routePathName = cti.routePathName.orEmpty().ifEmpty { previous.routePathName },
            routePathId = cti.routePathId.orEmpty().ifEmpty { previous.routePathId },
            queueName = cti.groupName.orEmpty().ifEmpty { previous.queueName },
            ctiNumber = number.orEmpty().ifEmpty { previous.ctiNumber },
            ctiName = name.orEmpty(),
            otherUserId = cti.otherUserIds?.firstOrNull().orEmpty().ifEmpty { previous.otherUserId }
        )
    }

    /**
     * The other party's number, as the web client's CallSummary.getPhoneNumber: the number the PBX
     * asserted (P-Asserted-Identity), else the call's CTI number, else the SIP remote address's.
     */
    fun phoneNumber(paiNumber: String?, route: CallRoute, remoteNumber: String?): String {
        return paiNumber.takeUnless { it.isNullOrEmpty() }
            ?: route.ctiNumber.takeUnless { it.isEmpty() }
            ?: remoteNumber.orEmpty()
    }

    /**
     * The lines the call view shows under the caller for an incoming call (call-view.component.ts
     * getCallInfoFields): the route path name and id, then the queue. None for an outgoing call.
     */
    fun callViewInfo(route: CallRoute, incoming: Boolean): List<String> {
        if (!incoming) return emptyList()
        val info = mutableListOf<String>()
        val routePath = "${route.routePathName} ${route.routePathId}".trim()
        if (routePath.isNotEmpty()) info.add(routePath)
        if (route.queueName.isNotEmpty()) info.add(route.queueName)
        return info
    }

    /**
     * The route to name in a call's notification: the route path, else the queue. None when it
     * contains the caller's number, as some routes are named after it.
     */
    fun notificationRoute(route: CallRoute, phoneNumber: String): String? {
        val name = route.routePathName.ifEmpty { route.queueName }.trim()
        if (name.isEmpty() || name.contains(phoneNumber)) return null
        return name
    }
}
