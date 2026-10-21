package org.linphone.models.realtime

/**
 * What a live call knows about who it's with, kept across its CTI and SIP updates, so that it can
 * tell when an attended transfer leaves it talking to someone else (ADO 36252).
 */
data class CallParty(
    // The number the call showed at its last CTI update, kept through a gap with none
    val shownNumber: String = "",
    // A P-Asserted-Identity number that's gone stale, so is ignored (see CallPartyRules.update)
    val stalePaiNumber: String = "",
    // The SIP remote identity the call had when its other party changed (see staleIdentityName)
    val staleRemoteIdentity: String = "",
    // How many times the other party has changed, so the old one's contact can be dropped
    val changes: Int = 0
)

/**
 * When a live call's other party changes, ported from the web client's Call model (ADO 36252): an
 * attended transfer can leave the call talking to someone else, and the contact matches and name
 * found for the old party would otherwise stick, as only the number follows.
 */
object CallPartyRules {
    /** The P-Asserted-Identity number to use, if the call has one: none while it's stale. */
    fun paiNumber(pai: String?, party: CallParty): String? =
        pai?.takeUnless { it.isEmpty() || it == party.stalePaiNumber }

    /**
     * The party once the call's route has gone from [previousRoute] to [route]. [hasCti] is whether
     * the call matched a CTI call this time (the route is kept through a gap with none). Numbers are
     * compared in E.164 form ([e164]), as the PBX can give the same number in different forms.
     *
     * The P-Asserted-Identity is preferred over the CTI number, so if it goes stale the call never
     * looks like it changed party. The PBX doesn't necessarily send a new one after a transfer, so
     * it's taken as stale once the CTI number changes to something other than it - and trusted
     * again once the PBX sends a different one.
     *
     * The other party has changed when the number the call shows does, or its internal user does.
     * Only checked when there's CTI data, as the web client does. A side missing from either isn't
     * a change.
     */
    fun update(
        previous: CallParty,
        previousRoute: CallRoute,
        route: CallRoute,
        hasCti: Boolean,
        pai: String?,
        remoteNumber: String?,
        remoteIdentity: String,
        e164: (String) -> String
    ): CallParty {
        var stalePai = previous.stalePaiNumber.takeIf { it == pai.orEmpty() }.orEmpty()
        if (!hasCti) return previous.copy(stalePaiNumber = stalePai)

        val ctiNumber = e164(route.ctiNumber)
        if (!pai.isNullOrEmpty() && previousRoute.ctiNumber.isNotEmpty() && route.ctiNumber.isNotEmpty() &&
            ctiNumber != e164(previousRoute.ctiNumber) && ctiNumber != e164(pai)
        ) {
            stalePai = pai
        }

        val number = CallRouteRules.phoneNumber(
            paiNumber(pai, previous.copy(stalePaiNumber = stalePai)),
            route,
            remoteNumber
        )
        val numberChanged = previous.shownNumber.isNotEmpty() && number.isNotEmpty() &&
            e164(previous.shownNumber) != e164(number)
        val userChanged = previousRoute.otherUserId.isNotEmpty() && route.otherUserId.isNotEmpty() &&
            previousRoute.otherUserId != route.otherUserId
        val changed = numberChanged || userChanged

        return CallParty(
            shownNumber = number.ifEmpty { previous.shownNumber },
            stalePaiNumber = stalePai,
            staleRemoteIdentity = if (changed) remoteIdentity else previous.staleRemoteIdentity,
            changes = if (changed) previous.changes + 1 else previous.changes
        )
    }

    /**
     * The name to show for a call no contact matches while its SIP remote identity still names the
     * party it had before a change: the PBX doesn't necessarily update it after a transfer, so the
     * CTI name is used instead, else the number. Null when the identity can be trusted, or a
     * P-Asserted-Identity names the other party.
     */
    fun staleIdentityName(
        party: CallParty,
        remoteIdentity: String,
        paiNumber: String?,
        route: CallRoute,
        phoneNumber: String
    ): String? {
        if (party.staleRemoteIdentity.isEmpty() || remoteIdentity != party.staleRemoteIdentity) return null
        if (!paiNumber.isNullOrEmpty()) return null
        return route.ctiName.ifEmpty { phoneNumber }.ifEmpty { null }
    }
}
