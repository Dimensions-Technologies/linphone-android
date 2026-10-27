package org.linphone.models.callhistory

/**
 * The text of a call history row, ported from the web client's call-history-item-view.ts
 * getFields: the other party's name, their number when it isn't the name, and the route the call
 * came in on ("via {route}"). The row shows the first field, then the rest on the line below.
 */
object CallHistoryFieldRules {
    private val phoneNumber = Regex("^\\+?[0-9][0-9 ()\\-.]*$")

    /**
     * [name] is the other party's name, already "Voicemail" for the user's own voicemail;
     * [formattedNumber] is their number as shown ("Unknown" when there is none), formatted by
     * [formatNumber].
     */
    fun fields(
        name: String?,
        formattedNumber: String,
        number: String,
        route: String?,
        formatNumber: (String) -> String,
        viaRoute: (String) -> String
    ): List<String> {
        val fields = mutableListOf<String>()
        // The gateway names an outgoing call to an unmatched number after the number as dialled,
        // unformatted: shown once, as the number
        val shownName = name.takeUnless { it.isNullOrEmpty() || isSameNumber(it, formattedNumber, formatNumber) }
        if (shownName != null) fields.add(shownName)
        if (formattedNumber.isNotEmpty() && formattedNumber != shownName) fields.add(formattedNumber)
        // Some routes are named after the number they're for
        if (!route.isNullOrEmpty() && !route.contains(number)) fields.add(viaRoute(route))
        return fields
    }

    private fun isSameNumber(name: String, formattedNumber: String, formatNumber: (String) -> String) =
        phoneNumber.matches(name) && formatNumber(name) == formattedNumber

    /** The route the call came in on: its route path, else its group, else its hunt group. */
    fun route(item: CallHistoryItem): String? {
        return item.routePathName.takeUnless { it.isNullOrEmpty() }
            ?: item.groupName.takeUnless { it.isNullOrEmpty() }
            ?: item.huntgroupName.takeUnless { it.isNullOrEmpty() }
    }
}
