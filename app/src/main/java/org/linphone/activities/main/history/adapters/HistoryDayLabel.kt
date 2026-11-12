package org.linphone.activities.main.history.adapters

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The History tab's day header, matching iOS: "Today", "Yesterday", then the weekday and full date,
 * always with the year (e.g. "Sunday 14 December 2025").
 *
 * @param timestampSecs the call's start, in seconds.
 * @param nowMillis the current time, in milliseconds.
 */
fun historyDayLabel(
    timestampSecs: Long,
    nowMillis: Long,
    todayText: String,
    yesterdayText: String,
    locale: Locale,
    timeZone: TimeZone = TimeZone.getDefault()
): String {
    val day = Calendar.getInstance(timeZone, locale).apply { timeInMillis = timestampSecs * 1000 }
    val today = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMillis }
    if (isSameDay(day, today)) return todayText

    // add, not roll: rolling the day of the month back from the 1st stays in the same month.
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (isSameDay(day, yesterday)) return yesterdayText

    val format = SimpleDateFormat("EEEE d MMMM yyyy", locale)
    format.timeZone = timeZone
    return format.format(Date(timestampSecs * 1000))
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a[Calendar.ERA] == b[Calendar.ERA] &&
        a[Calendar.YEAR] == b[Calendar.YEAR] &&
        a[Calendar.DAY_OF_YEAR] == b[Calendar.DAY_OF_YEAR]
