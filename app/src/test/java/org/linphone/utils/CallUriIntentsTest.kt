package org.linphone.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `LoginActivity holds the link it was opened with`() {
        assertEquals("tel:03332420042", CallUriIntents.callUriToHold(view, "tel:03332420042", null))
        assertEquals(
            "sip:1040@example.com",
            CallUriIntents.callUriToHold(dial, "sip:1040@example.com", null)
        )
    }

    @Test
    fun `the link it was opened with wins over a carried one`() {
        assertEquals(
            "tel:0111",
            CallUriIntents.callUriToHold(view, "tel:0111", "tel:0222")
        )
    }

    @Test
    fun `LoginActivity holds a link carried back from a cancelled sign-in`() {
        // The cancel intent targets LoginActivity directly, with no action or data
        assertEquals("tel:0222", CallUriIntents.callUriToHold(null, null, "tel:0222"))
    }

    @Test
    fun `a non-call link isn't held as one`() {
        assertNull(CallUriIntents.callUriToHold(view, "plum.oauth2:/oauth2redirect?code=x", null))
        assertNull(CallUriIntents.callUriToHold(view, "https://example.com", null))
        assertNull(CallUriIntents.callUriToHold(view, "03332420042", null))
    }

    @Test
    fun `a launcher start holds nothing`() {
        assertNull(CallUriIntents.callUriToHold("android.intent.action.MAIN", null, null))
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
