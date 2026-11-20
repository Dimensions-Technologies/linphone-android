package org.linphone.notifications

import android.app.Notification
import android.app.PendingIntent

/**
 * How the incoming call notification is posted.
 *
 * The notification is posted several times for one call: when the call arrives, when the
 * service starts, when it becomes the foreground service's notification, and again when a
 * contact match or call route names the caller. With FLAG_CANCEL_CURRENT every post cancelled the
 * previous post's full-screen intent, so when Android fired the intent from the first post it had
 * already been cancelled. The screen woke but the call screen never opened, and on Samsung phones
 * only a short heads-up showed (WI #37127).
 */
object IncomingCallNotificationRules {
    /**
     * Flags for the full-screen and content intent. FLAG_UPDATE_CURRENT keeps the intent Android
     * already holds valid when the notification is posted again.
     */
    const val PENDING_INTENT_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /**
     * Adds FLAG_ONLY_ALERT_ONCE to the notification's [flags]. The first post rings and shows
     * the heads-up. Later posts only update the text and picture, so they don't alert again or
     * bring the heads-up back.
     */
    fun withOnlyAlertOnce(flags: Int): Int {
        return flags or Notification.FLAG_ONLY_ALERT_ONCE
    }
}
