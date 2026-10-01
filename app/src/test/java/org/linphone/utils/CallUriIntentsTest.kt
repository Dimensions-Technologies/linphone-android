package org.linphone.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks which intents MainActivity treats as a link to call when it's already running. Browsers
 * send tel: links as VIEW, which used to be ignored until the app was restarted (WI #28994).
 */
class CallUriIntentsTest {

    private val view = "android.intent.action.VIEW"
    private val dial = "android.intent.action.DIAL"

    @Test
    fun `VIEW and DIAL with a call scheme are call links`() {
        for (action in listOf(view, dial)) {
            for (scheme in listOf("tel", "sip", "sips", "linphone", "sip-linphone")) {
                assertTrue("$action $scheme:", CallUriIntents.isCallUri(action, scheme))
            }
        }
    }

    @Test
    fun `schemes are matched regardless of case`() {
        assertTrue(CallUriIntents.isCallUri(view, "TEL"))
        assertTrue(CallUriIntents.isCallUri(dial, "Sip"))
    }

    @Test
    fun `other schemes are not call links`() {
        // plum.oauth2 is the login redirect, which must still reach the auth handling
        for (scheme in listOf("plum.oauth2", "linphone-config", "https", "content", "sms")) {
            assertFalse("VIEW $scheme:", CallUriIntents.isCallUri(view, scheme))
        }
    }

    @Test
    fun `intents without a link are not call links`() {
        assertFalse(CallUriIntents.isCallUri(view, null))
        assertFalse(CallUriIntents.isCallUri(dial, null))
    }

    @Test
    fun `other actions are not call links`() {
        val actions = listOf(
            "android.intent.action.MAIN",
            "android.intent.action.CALL",
            "android.intent.action.CALL_BUTTON",
            "android.intent.action.SENDTO",
            null
        )
        for (action in actions) {
            assertFalse("$action tel:", CallUriIntents.isCallUri(action, "tel"))
        }
    }
}
