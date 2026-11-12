package org.linphone.activities.main.history.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.models.callhistory.HistoryDirection
import org.threeten.bp.ZoneOffset
import org.threeten.bp.ZonedDateTime

class HistoryListBuilderTest {
    private val texts = HistoryTexts(
        outgoing = "Outgoing",
        incoming = "Incoming",
        missed = "Missed",
        unanswered = "Unanswered",
        voicemail = "Voicemail",
        conference = "Conference",
        leftVoicemail = "left voicemail",
        mobile = "Mobile",
        nonGeographic = "Non-geographic"
    )

    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, ZoneOffset.UTC)

    private fun entry(
        day: Int,
        hour: Int,
        key: String?,
        direction: HistoryDirection = HistoryDirection.INCOMING,
        number: String? = "07400 123456"
    ) = HistoryEntry(
        time = at(day, hour),
        key = key,
        direction = direction,
        name = "El Cabasa Grande",
        subtitle = "07400 123456",
        via = "via Sales",
        number = number,
        talkSeconds = 252.0
    )

    @Test
    fun `a day's calls with the same number are one row, opening to each`() {
        val entries = listOf(entry(10, 12, "a"), entry(10, 11, "b"), entry(10, 10, "a", HistoryDirection.MISSED_VOICEMAIL))

        val closed = HistoryListBuilder.build(entries, "all", emptySet(), "GB", texts)
        assertEquals(2, closed.size)
        assertTrue(closed[0].hasCount)
        assertEquals(2, closed[0].count)
        assertFalse(closed[1].hasCount)
        // As iOS, the newest call's details, with where the number is
        assertEquals("Mobile", closed[0].location)

        val open = HistoryListBuilder.build(entries, "all", setOf(closed[0].groupKey), "GB", texts)
        assertEquals(listOf(false, true, true, false), open.map { it.isSubRow })
        assertTrue(open[0].expanded)
        assertEquals("Incoming via Sales · 4m 12s", open[1].label)
        assertEquals("Missed via Sales · left voicemail", open[2].label)
    }

    @Test
    fun `groups don't span days, and conferences and withheld callers aren't grouped`() {
        val entries = listOf(
            entry(10, 12, "a"),
            entry(9, 12, "a"),
            entry(9, 11, null, HistoryDirection.CONFERENCE, number = null),
            entry(9, 10, null, HistoryDirection.CONFERENCE, number = null)
        )

        val rows = HistoryListBuilder.build(entries, "all", emptySet(), "GB", texts)

        assertEquals(4, rows.size)
        assertTrue(rows.none { it.hasCount })
        assertTrue(rows[2].isConference)
        assertEquals("", rows[2].location)
    }

    @Test
    fun `History and Missed keep their own open groups`() {
        val entries = listOf(entry(10, 12, "a"), entry(10, 11, "a"))
        val history = HistoryListBuilder.build(entries, "all", emptySet(), "GB", texts)
        val missed = HistoryListBuilder.build(entries, "missed", setOf(history[0].groupKey), "GB", texts)
        assertEquals(1, missed.size)
    }
}
