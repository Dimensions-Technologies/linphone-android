package org.linphone.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    @Test
    fun `Yiddish ligatures change to letter pairs`() {
        assertEquals("וו", SearchText.from("װ")) // װ
        assertEquals("וי", SearchText.from("ױ")) // ױ
        assertEquals("יי", SearchText.from("ײ")) // ײ
    }

    @Test
    fun `Hebrew points are removed`() {
        assertEquals("א", SearchText.from("אַ")) // אַ
        assertEquals("פ", SearchText.from("פֿ")) // פֿ
        assertEquals("ש", SearchText.from("שּׁ")) // שּׁ
    }

    @Test
    fun `presentation forms change to plain letters`() {
        assertEquals("א", SearchText.from("אַ")) // אַ as one character
        assertEquals("פ", SearchText.from("פֿ")) // פֿ as one character
        assertEquals("יי", SearchText.from("ײַ")) // ײַ as one character
    }

    @Test
    fun `each form of a Yiddish name gives the same text`() {
        val ligatures = "טעסט װײַס" // טעסט װײַס
        val letters = "טעסט ווייס" // טעסט ווייס
        assertEquals(SearchText.from(letters), SearchText.from(ligatures))
        assertTrue(SearchText.from(ligatures).contains(SearchText.from("ײס"))) // ײס
    }

    @Test
    fun `the Hebrew maqaf stays`() {
        assertEquals("א־ב", SearchText.from("א־ב"))
    }

    @Test
    fun `Latin text changes to lower case`() {
        assertEquals("andrew smith", SearchText.from("Andrew SMITH"))
        assertEquals(SearchText.from("zoë"), SearchText.from("Zoë"))
    }

    @Test
    fun `no text gives an empty string`() {
        assertEquals("", SearchText.from(""))
        assertEquals("", SearchText.from(null))
    }
}
