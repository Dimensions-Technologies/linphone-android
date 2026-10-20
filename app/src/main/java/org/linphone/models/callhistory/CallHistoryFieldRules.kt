package org.linphone.models.callhistory

/**
 * The text of a call history row, ported from the web client's call-history-item-view.ts
 * getFields: the other party's name, their number when it isn't the name, and the route the call
 * came in on ("via {route}"). The row shows the first field, then the rest on the line below.
 */
object CallHistoryFieldRules {
    /**
     * [name] is the other party's name, already "Voicemail" for the user's own voicemail;
     * [formattedNumber] is their number as shown ("Unknown" when there is none).
     */
    fun fields(
        name: String?,
        formattedNumber: String,
        number: String,
        route: String?,
        viaRoute: (String) -> String
    ): List<String> {
        val fields = mutableListOf<String>()
        if (!name.isNullOrEmpty()) fields.add(name)
        if (formattedNumber.isNotEmpty() && formattedNumber != name) fields.add(formattedNumber)
        // Some routes are named after the number they're for
        if (!route.isNullOrEmpty() && !route.contains(number)) fields.add(viaRoute(route))
        return fields
    }

    /** The route the call came in on: its route path, else its group, else its hunt group. */
    fun route(item: CallHistoryItem): String? {
        return item.routePathName.takeUnless { it.isNullOrEmpty() }
            ?: item.groupName.takeUnless { it.isNullOrEmpty() }
            ?: item.huntgroupName.takeUnless { it.isNullOrEmpty() }
    }
}
