package org.linphone.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks which FCM messages are passed to the linphone SDK and take the push wake lock (WI #35628).
 */
class PushPayloadFilterTest {

    private fun extras(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = mapOf(*pairs)
        return { map[it] }
    }

    // Shape of the Kazoo call push seen in the WI #35628 logs
    private val callPush = extras(
        "call-id" to "8e237a47-064c-4968-b439-e30612675664",
        "caller-id-name" to "Jens Heilemann",
        "caller-id-number" to "201",
        "alert" to "{\"loc-key\":\"IC_SIL\",\"loc-args\":[\"201 - Jens Heilemann\"]}",
        "sound" to "ring.caf"
    )

    @Test
    fun `a call push is handled`() {
        assertTrue(PushPayloadFilter.shouldHandle(callPush))
    }

    @Test
    fun `a push with no extras is handled`() {
        assertTrue(PushPayloadFilter.shouldHandle(extras()))
    }

    @Test
    fun `a push with a title or body is a display notification and is skipped`() {
        assertFalse(PushPayloadFilter.shouldHandle(extras("title" to "Hello")))
        assertFalse(PushPayloadFilter.shouldHandle(extras("body" to "Hello")))
    }

    @Test
    fun `blank title and body don't count as display content`() {
        assertTrue(PushPayloadFilter.shouldHandle(extras("title" to " ", "body" to "")))
    }

    @Test
    fun `a NOUI notification body is skipped`() {
        assertFalse(PushPayloadFilter.shouldHandle(extras("gcm.notification.body" to "NOUI")))
    }

    @Test
    fun `a NOUI body is skipped`() {
        assertFalse(PushPayloadFilter.shouldHandle(extras("body" to "NOUI")))
    }

    @Test
    fun `any other notification body is handled`() {
        assertTrue(
            PushPayloadFilter.shouldHandle(extras("gcm.notification.body" to "Incoming call"))
        )
    }

    @Test
    fun `only received messages take the wake lock, not token refreshes`() {
        assertTrue(PushPayloadFilter.isMessage("com.google.android.c2dm.intent.RECEIVE"))
        assertFalse(PushPayloadFilter.isMessage("com.google.firebase.messaging.NEW_TOKEN"))
        assertFalse(PushPayloadFilter.isMessage(null))
    }
}
