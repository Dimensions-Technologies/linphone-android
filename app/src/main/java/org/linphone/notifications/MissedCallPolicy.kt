package org.linphone.notifications

import org.linphone.core.Call

// Decides which finished calls get the local missed call notification. The server's missed call
// push reaches Android as "NOUI" and is dropped, so this notification is the only one Android
// shows (WI #28567). Calls answered or declined on another device end as AcceptedElsewhere or
// DeclinedElsewhere and aren't counted as missed.
object MissedCallPolicy {
    private val missedStatuses = setOf(
        Call.Status.Missed,
        Call.Status.Aborted,
        Call.Status.EarlyAborted
    )

    fun isMissed(dir: Call.Dir?, status: Call.Status?): Boolean {
        return dir == Call.Dir.Incoming && status in missedStatuses
    }
}
