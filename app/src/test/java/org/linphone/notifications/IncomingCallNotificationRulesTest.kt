package org.linphone.notifications

import android.app.Notification
import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks how the incoming call notification is posted, so posting it again doesn't stop the call
 * screen opening over the lock screen (WI #37127).
 */
class IncomingCallNotificationRulesTest {
    private val flags = IncomingCallNotificationRules.PENDING_INTENT_FLAGS

    @Test
    fun `posting again doesn't cancel the full-screen intent Android already holds`() {
        assertEquals(0, flags and PendingIntent.FLAG_CANCEL_CURRENT)
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT, flags and PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Test
    fun `the intent is immutable, as Android 12 and above require`() {
        assertEquals(PendingIntent.FLAG_IMMUTABLE, flags and PendingIntent.FLAG_IMMUTABLE)
    }

    @Test
    fun `the intent is never one-shot or disabled`() {
        assertEquals(0, flags and PendingIntent.FLAG_ONE_SHOT)
        assertEquals(0, flags and PendingIntent.FLAG_NO_CREATE)
    }

    @Test
    fun `later posts don't alert again`() {
        val result = IncomingCallNotificationRules.withOnlyAlertOnce(0)
        assertTrue(result and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun `the notification's other flags are kept`() {
        val existing = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        val result = IncomingCallNotificationRules.withOnlyAlertOnce(existing)
        assertEquals(existing, result and existing)
        assertFalse(result == existing)
    }
}
