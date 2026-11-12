package org.linphone.activities.main.history.data

import org.linphone.models.callhistory.CallHistoryItemViewModel
import org.linphone.models.callhistory.HistoryDirection
import org.linphone.models.callhistory.HistoryTimelineRules
import org.linphone.models.callhistory.PhoneLocationKind
import org.linphone.models.callhistory.PhoneLocationRules
import org.linphone.models.voicemail.VoicemailMessage
import org.threeten.bp.LocalDate
import org.threeten.bp.ZonedDateTime
import org.threeten.bp.format.DateTimeFormatter
import org.threeten.bp.format.FormatStyle

/** A call, or a voicemail with no call, as the History list holds it (iOS's HistoryEntry). */
data class HistoryEntry(
    // In the device's time zone
    val time: ZonedDateTime,
    // The number calls group on; none for a conference or a withheld caller
    val key: String?,
    val direction: HistoryDirection,
    val name: String,
    // Under the name: the number when it isn't the name, and the route ("via Sales")
    val subtitle: String = "",
    val via: String? = null,
    // The number to locate and call back
    val number: String? = null,
    val talkSeconds: Double = 0.0,
    val avatarUrl: String? = null,
    // Whether the name is a person's or business's, so the avatar can show its initials
    val nameIsCaller: Boolean = true,
    // The call, unless this is a voicemail with no call
    val call: CallHistoryItemViewModel? = null,
    // The voicemail the call left, or this voicemail
    val voicemailBoxId: String? = null,
    val voicemail: VoicemailMessage? = null
)

/**
 * A row of the History list, as iOS's HistoryRowItem: a call, or a group of calls with the same
 * number on the same day showing the newest, or (a sub row) one of a group's calls when it's open.
 */
data class HistoryListItem(
    val id: String,
    val groupKey: String,
    val isSubRow: Boolean,
    // The group's entries, newest first; a sub row has only its own
    val entries: List<HistoryEntry>,
    val name: String = "",
    val subtitle: String = "",
    // A place, Mobile or Non-geographic, with the country code when abroad
    val location: String = "",
    val countryCode: String = "",
    val time: String = "",
    val expanded: Boolean = false,
    val avatarUrl: String? = null,
    val avatarName: String? = null,
    val isConference: Boolean = false,
    val direction: HistoryDirection = HistoryDirection.OUTGOING,
    // Sub rows: how the call went
    val label: String = "",
    val description: String = ""
) {
    val entry: HistoryEntry get() = entries[0]
    val count: Int get() = entries.size
    val hasCount: Boolean get() = !isSubRow && count > 1
    val hasSubtitle: Boolean get() = subtitle.isNotEmpty()
    val hasLocation: Boolean get() = location.isNotEmpty()
    val hasCountryCode: Boolean get() = countryCode.isNotEmpty()

    /** The start, in seconds, for the day headings. */
    val startSecs: Long get() = entry.time.toEpochSecond()

    // The text shown in full by the row's long-press tooltip, as each line can be cut short
    val fullText: String get() =
        listOf(name, subtitle, location).filter { it.isNotEmpty() }.joinToString("\n")
}

/** The words the History list is built with, from the app's strings. */
data class HistoryTexts(
    val outgoing: String,
    val incoming: String,
    val missed: String,
    val unanswered: String,
    val voicemail: String,
    val conference: String,
    val leftVoicemail: String,
    val mobile: String,
    val nonGeographic: String
) {
    fun directionName(direction: HistoryDirection): String = when (direction) {
        HistoryDirection.OUTGOING -> outgoing
        HistoryDirection.INCOMING -> incoming
        HistoryDirection.MISSED, HistoryDirection.MISSED_VOICEMAIL -> missed
        HistoryDirection.UNANSWERED -> unanswered
        HistoryDirection.VOICEMAIL -> voicemail
        HistoryDirection.CONFERENCE -> conference
    }
}

/** Builds the History list's rows from its entries, as iOS's HistoryPageViewModel.Sections. */
object HistoryListBuilder {
    private val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    /**
     * [entries] newest first; [list] tells the History and Missed lists' groups apart; [expanded]
     * the groups open; [countryCode] the PBX's, to place numbers.
     */
    fun build(
        entries: List<HistoryEntry>,
        list: String,
        expanded: Set<String>,
        countryCode: String,
        texts: HistoryTexts
    ): List<HistoryListItem> {
        val rows = mutableListOf<HistoryListItem>()
        for ((day, groups) in HistoryTimelineRules.groupByDay(entries, { it.time.toLocalDate() }, { it.key })) {
            for (group in groups) {
                val key = groupKey(list, day, group[0])
                val row = mainRow(group, key, key in expanded, countryCode, texts)
                rows.add(row)
                if (row.expanded) rows.addAll(subRows(row, texts))
            }
        }
        return rows
    }

    private fun groupKey(list: String, day: LocalDate, first: HistoryEntry): String =
        "$list|$day|${first.key ?: first.time.toEpochSecond().toString()}"

    private fun mainRow(
        group: List<HistoryEntry>,
        key: String,
        isExpanded: Boolean,
        countryCode: String,
        texts: HistoryTexts
    ): HistoryListItem {
        val entry = group[0]
        val conference = entry.direction == HistoryDirection.CONFERENCE
        val location = if (conference) null else PhoneLocationRules.describe(entry.number, countryCode)
        val time = timeOf(entry)
        return HistoryListItem(
            id = key,
            groupKey = key,
            isSubRow = false,
            entries = group,
            name = entry.name,
            subtitle = entry.subtitle,
            location = when (location?.kind) {
                null -> ""
                PhoneLocationKind.MOBILE -> texts.mobile
                PhoneLocationKind.NON_GEOGRAPHIC -> texts.nonGeographic
                PhoneLocationKind.PLACE -> location.place.orEmpty()
            },
            countryCode = location?.countryCode.orEmpty(),
            time = time,
            expanded = group.size > 1 && isExpanded,
            avatarUrl = entry.avatarUrl,
            avatarName = entry.name.takeIf { entry.nameIsCaller },
            isConference = conference,
            direction = entry.direction,
            description = listOfNotNull(
                texts.directionName(entry.direction),
                entry.name,
                entry.subtitle.takeIf { it.isNotEmpty() },
                time,
                group.size.takeIf { it > 1 }?.toString()
            ).joinToString(", ")
        )
    }

    private fun subRows(row: HistoryListItem, texts: HistoryTexts): List<HistoryListItem> =
        row.entries.mapIndexed { index, entry ->
            val label = HistoryTimelineRules.subLabel(
                texts.directionName(entry.direction),
                entry.via,
                entry.direction,
                entry.talkSeconds,
                texts.leftVoicemail
            )
            val time = timeOf(entry)
            HistoryListItem(
                id = "${row.groupKey}|$index",
                groupKey = row.groupKey,
                isSubRow = true,
                entries = listOf(entry),
                name = entry.name,
                time = time,
                direction = entry.direction,
                label = label,
                description = "$label, $time"
            )
        }

    private fun timeOf(entry: HistoryEntry): String = entry.time.format(timeFormatter)
}
