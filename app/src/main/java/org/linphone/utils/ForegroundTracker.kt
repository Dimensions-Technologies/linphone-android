package org.linphone.utils

// Works out whether the app is in the foreground from how many of its activities are visible
// (started), not resumed. An activity is only paused behind the notification shade, a permission
// dialog or another app in split-screen, and the app is still in use then (WI #36133).
// The app only counts as backgrounded once nothing has been visible for BACKGROUND_DELAY_MS, so
// moving between activities or rotating doesn't flip it.
class ForegroundTracker(
    private val scheduler: Scheduler,
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit
) {
    fun interface Scheduler {
        fun schedule(delayMs: Long, task: () -> Unit): Cancellable
    }

    fun interface Cancellable {
        fun cancel()
    }

    private var visibleActivities = 0
    private var pendingBackground: Cancellable? = null

    var isInForeground = false
        private set

    fun onActivityVisible() {
        visibleActivities++
        cancelPendingBackground()

        if (!isInForeground) {
            isInForeground = true
            onForeground()
        }
    }

    fun onActivityHidden() {
        if (visibleActivities == 0) return

        visibleActivities--
        if (visibleActivities == 0 && isInForeground) {
            cancelPendingBackground()
            pendingBackground = scheduler.schedule(BACKGROUND_DELAY_MS) {
                pendingBackground = null
                if (visibleActivities == 0 && isInForeground) {
                    isInForeground = false
                    onBackground()
                }
            }
        }
    }

    private fun cancelPendingBackground() {
        pendingBackground?.cancel()
        pendingBackground = null
    }

    companion object {
        const val BACKGROUND_DELAY_MS = 2_000L
    }
}
