package org.linphone.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks when the app counts as in the foreground. SIP is unregistered when it goes to the
 * background, so the app must stay in the foreground while any of it is visible (WI #36133).
 */
class ForegroundTrackerTest {

    private class FakeScheduler : ForegroundTracker.Scheduler {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        var cancelled = 0

        override fun schedule(delayMs: Long, task: () -> Unit): ForegroundTracker.Cancellable {
            val entry = delayMs to task
            tasks.add(entry)
            return ForegroundTracker.Cancellable {
                if (tasks.remove(entry)) cancelled++
            }
        }

        fun runAll() {
            val due = tasks.toList()
            tasks.clear()
            due.forEach { it.second() }
        }
    }

    private val scheduler = FakeScheduler()
    private val events = mutableListOf<String>()
    private val tracker = ForegroundTracker(
        scheduler,
        onForeground = { events.add("foreground") },
        onBackground = { events.add("background") }
    )

    @Test
    fun `the first visible activity brings the app to the foreground`() {
        tracker.onActivityVisible()

        assertTrue(tracker.isInForeground)
        assertEquals(listOf("foreground"), events)
    }

    @Test
    fun `the app goes to the background after the delay once nothing is visible`() {
        tracker.onActivityVisible()
        tracker.onActivityHidden()

        assertTrue(tracker.isInForeground)
        assertEquals(ForegroundTracker.BACKGROUND_DELAY_MS, scheduler.tasks.single().first)

        scheduler.runAll()

        assertFalse(tracker.isInForeground)
        assertEquals(listOf("foreground", "background"), events)
    }

    @Test
    fun `moving between activities doesn't go to the background`() {
        tracker.onActivityVisible()
        tracker.onActivityVisible()
        tracker.onActivityHidden()

        assertTrue(scheduler.tasks.isEmpty())
        assertEquals(listOf("foreground"), events)
    }

    @Test
    fun `an activity becoming visible within the delay cancels going to the background`() {
        // e.g. rotation, where the activity stops and a new one starts
        tracker.onActivityVisible()
        tracker.onActivityHidden()
        tracker.onActivityVisible()
        scheduler.runAll()

        assertTrue(tracker.isInForeground)
        assertEquals(1, scheduler.cancelled)
        assertEquals(listOf("foreground"), events)
    }

    @Test
    fun `coming back after going to the background is reported again`() {
        tracker.onActivityVisible()
        tracker.onActivityHidden()
        scheduler.runAll()
        tracker.onActivityVisible()

        assertEquals(listOf("foreground", "background", "foreground"), events)
    }

    @Test
    fun `a hidden activity that was never counted as visible is ignored`() {
        tracker.onActivityHidden()
        tracker.onActivityVisible()
        tracker.onActivityHidden()
        scheduler.runAll()

        assertEquals(listOf("foreground", "background"), events)
    }

    @Test
    fun `the app starts out not in the foreground and reports nothing`() {
        assertFalse(tracker.isInForeground)
        assertTrue(events.isEmpty())
    }
}
