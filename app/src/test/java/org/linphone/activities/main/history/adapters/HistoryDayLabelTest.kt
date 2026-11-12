package org.linphone.activities.main.history.adapters

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

// Same cases as iOS HistoryTimelineRulesTests.DaysAreTodayYesterdayThenTheWeekdayAndDate (WI 36939).
class HistoryDayLabelTest {
    private val zone = TimeZone.getTimeZone("Europe/London")

    // Tuesday 10 November 2026, 15:00
    private val now = millis(2026, Calendar.NOVEMBER, 10, 15)

    private fun millis(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis

    private fun label(timestampMillis: Long): String =
        historyDayLabel(timestampMillis / 1000, now, "Today", "Yesterday", Locale.UK, zone)

    private fun daysAgo(days: Int): Long = now - days * 24L * 60 * 60 * 1000

    @Test
    fun `days are Today, Yesterday, then the weekday and full date with the year`() {
        assertEquals("Today", label(now))
        assertEquals("Yesterday", label(daysAgo(1)))
        assertEquals("Sunday 8 November 2026", label(daysAgo(2)))
        assertEquals("Thursday 5 November 2026", label(daysAgo(5)))
        assertEquals("Tuesday 3 November 2026", label(daysAgo(7)))
        assertEquals("Sunday 14 December 2025", label(millis(2025, Calendar.DECEMBER, 14, 12)))
    }

    @Test
    fun `day boundaries are local midnight`() {
        assertEquals("Today", label(millis(2026, Calendar.NOVEMBER, 10, 0)))
        assertEquals("Yesterday", label(millis(2026, Calendar.NOVEMBER, 10, 0) - 1000))
    }

    @Test
    fun `yesterday on the 1st of the month is the last day of the previous month`() {
        val firstOfDec = millis(2026, Calendar.DECEMBER, 1, 9)
        val lastOfNov = millis(2026, Calendar.NOVEMBER, 30, 18) / 1000
        assertEquals("Yesterday", historyDayLabel(lastOfNov, firstOfDec, "Today", "Yesterday", Locale.UK, zone))
    }
}
