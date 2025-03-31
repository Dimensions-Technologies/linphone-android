package org.linphone.utils

import android.annotation.SuppressLint
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class DateUtils {
    companion object {
        private val _todaysDate = BehaviorSubject.createDefault(getToday())
        val todaysDate: Observable<Date> = _todaysDate.hide()

        private fun getToday(): Date {
            val today = Calendar.getInstance()
            return Calendar.getInstance().apply {
                set(Calendar.YEAR, today.get(Calendar.YEAR))
                set(Calendar.MONTH, today.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, today.get(Calendar.DAY_OF_MONTH))
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time
        }

        @SuppressLint("CheckResult")
        private fun checkDate() {
            // If necessary, update today's date.
            val date = getToday()
            if (date != _todaysDate.value) _todaysDate.onNext(date)

            // Set a timer for when the hour next changes to re-check the current date.
            val delay = (60 - Calendar.getInstance().get(Calendar.MINUTE)) * 60000L
            Log.d("Check date in $delay ms")
            Observable.timer(delay, TimeUnit.MILLISECONDS).subscribe { checkDate() }
        }

        /** Formats the date string as a user-friendly string. */
        fun formatFriendlyDate(dateTime: Date?, todaysDate: Date?, useLastWeek: Boolean = false): String {
            if (dateTime == null || todaysDate == null) return ""

            val midnightTodaysDate = getMidnight(todaysDate)
            val midnightDate = getMidnight(dateTime)

            if (midnightDate == midnightTodaysDate) return ""

            val yesterday = Calendar.getInstance().apply {
                time = midnightTodaysDate
                add(Calendar.DAY_OF_MONTH, -1)
            }.time

            val aWeekAgo = Calendar.getInstance().apply {
                time = midnightTodaysDate
                add(Calendar.DAY_OF_MONTH, -7)
            }.time

            val twoWeeksAgo = Calendar.getInstance().apply {
                time = midnightTodaysDate
                add(Calendar.DAY_OF_MONTH, -14)
            }.time

            return when {
                midnightDate == todaysDate -> ""
                midnightDate == yesterday -> "Yesterday"
                midnightDate.after(aWeekAgo) -> getDayName(dateTime.day)
                useLastWeek && midnightDate.after(twoWeeksAgo) -> "Last week"
                else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(dateTime)
            }
        }

        fun toLocaleHMString(dateTime: Date?): String {
            val hmsString = dateTime?.let {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(
                    it
                )
            }
            if (hmsString != null) {
                return hmsString
                // return hmsString.replace(Regex("(:[0-9]{2})($| )"), " ").trimEnd()
            }
            return ""
        }

        private fun getDayName(dayIndex: Int): String {
            return when (dayIndex) {
                0 -> "Sunday"
                1 -> "Monday"
                2 -> "Tuesday"
                3 -> "Wednesday"
                4 -> "Thursday"
                5 -> "Friday"
                6 -> "Saturday"
                else -> throw IllegalArgumentException("Invalid day index")
            }
        }

        fun secondsToHms(totalSeconds: Int, alwaysIncludeHours: Boolean = true): String {
            var seconds = totalSeconds
            val days = seconds / 86400
            seconds %= 86400
            val hours = seconds / 3600
            seconds %= 3600
            val minutes = seconds / 60
            seconds %= 60

            val dayString = if (days > 0) "${days}d " else ""
            val hoursString = if (alwaysIncludeHours || hours > 0) "${padLeft(hours)}:" else ""

            return "$dayString$hoursString${padLeft(minutes)}:${padLeft(seconds)}"
        }

        private fun padLeft(value: Int): String {
            return value.toString().padStart(2, '0')
        }

        fun getMidnight(date: Date): Date {
            val calendar = Calendar.getInstance()
            calendar.time = date
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return calendar.time
        }
    }
}
