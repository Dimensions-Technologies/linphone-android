package org.linphone.models.callhistory

import kotlin.math.roundToInt
import org.linphone.models.contact.ContactDirectoryRules

/**
 * The icon a History row shows on its right, each with its own colour, as the web client's
 * CallDirectionKind and iOS's HistoryDirection. A call that left a voicemail shows the voicemail
 * icon: MISSED_VOICEMAIL for a missed call (and a voicemail with no call), VOICEMAIL otherwise.
 */
enum class HistoryDirection { OUTGOING, INCOMING, MISSED, UNANSWERED, CONFERENCE, VOICEMAIL, MISSED_VOICEMAIL }

/**
 * The History list as iOS's (WI 36610, HistoryTimelineRules): calls under day headings, and calls
 * with the same number on the same day grouped into one row that shows the latest call and the
 * count, expandable to show each.
 */
object HistoryTimelineRules {
    fun direction(item: CallHistoryItem, leftVoicemail: Boolean): HistoryDirection = when {
        item.isConference -> HistoryDirection.CONFERENCE
        leftVoicemail -> if (item.missedCall) HistoryDirection.MISSED_VOICEMAIL else HistoryDirection.VOICEMAIL
        CallDirections.fromValue(item.callDirection) != CallDirections.Incoming -> HistoryDirection.OUTGOING
        item.missedCall -> HistoryDirection.MISSED
        item.answered -> HistoryDirection.INCOMING
        else -> HistoryDirection.UNANSWERED
    }

    /** Shown in the Missed list: missed calls, with or without a voicemail. */
    fun isMissed(direction: HistoryDirection): Boolean =
        direction == HistoryDirection.MISSED || direction == HistoryDirection.MISSED_VOICEMAIL

    /** A call's talk time as "45s", "4m 12s" or "1h 02m", or empty when it had none. */
    fun formatTalkTime(seconds: Double): String {
        val s = seconds.roundToInt()
        return when {
            s <= 0 -> ""
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
            else -> "${s / 3600}h ${(s % 3600 / 60).toString().padStart(2, '0')}m"
        }
    }

    /**
     * What calls group on, as the web client's partyOf: a phone number in E.164 form, else the
     * number as it is (Teams calls give the user's email). None for a withheld caller.
     */
    fun numberKey(number: String?, countryCode: String): String? {
        if (number.isNullOrBlank()) return null
        val matchable = ContactDirectoryRules.toMatchable(number)
            ?: return number.trim().lowercase()
        return ContactDirectoryRules.toE164(matchable, countryCode)
    }

    /**
     * The line under a row's name from the call's details after its name (the number, then "via"
     * the route): as iOS, without an email, which Teams calls give as the number.
     */
    fun subtitle(details: List<String>): String =
        details.filterNot { it.contains('@') }.joinToString(" ")

    /**
     * The entries (newest first) by day, and within each day grouped by key in the order each key was
     * first seen, so each group starts with its newest entry. Entries without a key are never grouped.
     */
    fun <T, D> groupByDay(newestFirst: List<T>, day: (T) -> D, key: (T) -> String?): List<Pair<D, List<List<T>>>> {
        val days = mutableListOf<Pair<D, MutableList<MutableList<T>>>>()
        var byKey = mutableMapOf<String, MutableList<T>>()
        for (entry in newestFirst) {
            val d = day(entry)
            if (days.isEmpty() || days.last().first != d) {
                days.add(d to mutableListOf())
                byKey = mutableMapOf()
            }
            val k = key(entry)
            val existing = if (k.isNullOrEmpty()) null else byKey[k]
            if (existing != null) {
                existing.add(entry)
                continue
            }
            val group = mutableListOf(entry)
            days.last().second.add(group)
            if (!k.isNullOrEmpty()) byKey[k] = group
        }
        return days
    }

    /**
     * A grouped call's line when its group is opened, as iOS's SubLabel: e.g. "Incoming via Sales ·
     * 4m 12s", "Missed · left voicemail".
     */
    fun subLabel(
        directionName: String,
        via: String?,
        direction: HistoryDirection,
        talkSeconds: Double,
        leftVoicemailText: String
    ): String {
        val label = if (via.isNullOrEmpty()) directionName else "$directionName $via"
        if (direction == HistoryDirection.MISSED_VOICEMAIL) return "$label · $leftVoicemailText"
        val talk = formatTalkTime(talkSeconds)
        return if (talk.isEmpty()) label else "$label · $talk"
    }
}
