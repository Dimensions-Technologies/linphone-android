package org.linphone.telecom

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the phone account icon stays under Telecom's size check. An oversized icon left the phone
 * account unregistered, so every incoming call was refused as busy (WI #36796).
 */
class PhoneAccountIconRulesTest {

    /** What Telecom builds: `Icon.writeToStream` output encoded with Android's `Base64.DEFAULT`. */
    private fun telecomBase64Length(pngBytes: Int): Int {
        val stream = ByteArray(pngBytes + 5)
        val encoded = Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(stream)
        // Android also ends the last line with '\n'; the MIME encoder doesn't
        return encoded.length + 1
    }

    @Test
    fun `serialised length matches how Telecom encodes the icon`() {
        for (pngBytes in listOf(0, 1, 2, 3, 52, 53, 54, 55, 1_000, 20_578, 48_000, 49_000, 60_000)) {
            assertEquals(
                "PNG of $pngBytes bytes",
                telecomBase64Length(pngBytes),
                PhoneAccountIconRules.serialisedLength(pngBytes)
            )
        }
    }

    @Test
    fun `limit is applied at exactly 65535 characters`() {
        val largestFitting = (0..60_000).last { PhoneAccountIconRules.fitsTelecomLimit(it) }

        assertTrue(PhoneAccountIconRules.serialisedLength(largestFitting) <= 65_535)
        assertTrue(PhoneAccountIconRules.serialisedLength(largestFitting + 1) > 65_535)
    }

    @Test
    fun `a PNG of the size an upscaled app logo produces is rejected`() {
        // The 380px xhdpi logo drawn at xxxhdpi is 760px, well over 50KB as a PNG
        assertFalse(PhoneAccountIconRules.fitsTelecomLimit(60_000))
    }

    @Test
    fun `an incompressible icon still fits once shrunk to the second size`() {
        // Raw ARGB plus generous PNG overhead: the worst a logo can compress to
        fun worstCasePngBytes(side: Int) = side * side * 4 + 1_000
        val sizes = PhoneAccountIconRules.candidateSizes()

        assertFalse(PhoneAccountIconRules.fitsTelecomLimit(worstCasePngBytes(sizes[0])))
        assertTrue(PhoneAccountIconRules.fitsTelecomLimit(worstCasePngBytes(sizes[1])))
    }

    @Test
    fun `candidate sizes halve from the largest down to the smallest`() {
        assertEquals(listOf(192, 96, 48, 24), PhoneAccountIconRules.candidateSizes())
    }

    @Test
    fun `square logo is scaled down to the max side`() {
        assertEquals(Pair(192, 192), PhoneAccountIconRules.scaledSize(760, 760, 192))
    }

    @Test
    fun `aspect ratio is kept when scaling down`() {
        assertEquals(Pair(192, 96), PhoneAccountIconRules.scaledSize(800, 400, 192))
        assertEquals(Pair(48, 96), PhoneAccountIconRules.scaledSize(200, 400, 96))
    }

    @Test
    fun `small images keep their size`() {
        assertEquals(Pair(64, 32), PhoneAccountIconRules.scaledSize(64, 32, 192))
    }

    @Test
    fun `unknown intrinsic size becomes a square`() {
        assertEquals(Pair(96, 96), PhoneAccountIconRules.scaledSize(-1, -1, 96))
        assertEquals(Pair(96, 96), PhoneAccountIconRules.scaledSize(0, 50, 96))
    }
}
