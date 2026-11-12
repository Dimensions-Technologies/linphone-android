package org.linphone.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrandColorsTest {
    @Test
    fun `css hex colours are read in each length`() {
        assertEquals(0xFFD4FF38.toInt(), BrandColors.parseCssColor("#d4ff38"))
        assertEquals(0xFFD4FF38.toInt(), BrandColors.parseCssColor(" #D4FF38 "))
        assertEquals(0xFFAABBCC.toInt(), BrandColors.parseCssColor("#abc"))
        assertEquals(0x80112233.toInt(), BrandColors.parseCssColor("#11223380"))
        assertEquals(0x88AABBCC.toInt(), BrandColors.parseCssColor("#abc8"))
    }

    @Test
    fun `hsl colours are read, as tenant brands hold them`() {
        // The test tenant's accent, which is iOS's default lime
        assertEquals(0xFFD4FF38.toInt(), BrandColors.parseCssColor("hsl(73, 100%, 61%)"))
        assertEquals(0xFF00A0B5.toInt(), BrandColors.parseCssColor("hsl(187, 100%, 35.5%)"))
        assertEquals(0xFFFF0000.toInt(), BrandColors.parseCssColor("hsl(360, 100%, 50%)"))
        assertEquals(0xFFFFFFFF.toInt(), BrandColors.parseCssColor("hsl(0, 0%, 100%)"))
        assertEquals(0x80FF0000.toInt(), BrandColors.parseCssColor("hsla(0, 100%, 50%, 0.5)"))
        assertEquals(0x80FF0000.toInt(), BrandColors.parseCssColor("hsl(0deg 100% 50% / 50%)"))
    }

    @Test
    fun `rgb colours are read`() {
        assertEquals(0xFF0102FF.toInt(), BrandColors.parseCssColor("rgb(1, 2, 255)"))
        assertEquals(0xFFFF0000.toInt(), BrandColors.parseCssColor("RGB(100%, 0%, 0%)"))
        assertEquals(0x40010203, BrandColors.parseCssColor("rgba(1, 2, 3, 0.25)"))
        assertEquals(0xFF010203.toInt(), BrandColors.parseCssColor("rgb(1 2 3)"))
    }

    @Test
    fun `icons on the highlight are the brand's dark on light colours, else white, as on iOS`() {
        assertEquals(BrandColors.DEFAULT_DARK, BrandColors.contrastOf(0xFFD4FF38.toInt()))
        assertEquals(BrandColors.DEFAULT_DARK, BrandColors.contrastOf(0xFFFFFFFF.toInt()))
        assertEquals(BrandColors.WHITE, BrandColors.contrastOf(0xFF1C3440.toInt()))
        assertEquals(BrandColors.WHITE, BrandColors.contrastOf(0xFF000000.toInt()))
        // A light blue (Voyager's) reads dark
        assertEquals(BrandColors.DEFAULT_DARK, BrandColors.contrastOf(0xFF00C8FF.toInt()))
        // The brand's own dark colour
        assertEquals(0xFF112233.toInt(), BrandColors.contrastOf(0xFFD4FF38.toInt(), 0xFF112233.toInt()))
    }

    @Test
    fun `the primary colour is the brand's, else iOS's default`() {
        // The test tenant's primary, a deeper lime than its accent
        assertEquals(0xFFC9FF05.toInt(), BrandColors.primaryOf(mapOf("primary" to "hsl(73, 100%, 51%)")))
        assertEquals(BrandColors.DEFAULT_PRIMARY, BrandColors.primaryOf(mapOf("accent" to "#d4ff38")))
        assertEquals(BrandColors.DEFAULT_PRIMARY, BrandColors.primaryOf(null))
    }

    @Test
    fun `the navbar colour is the brand's, else iOS's default`() {
        assertEquals(0xFFDEEBED.toInt(), BrandColors.navbarColorOf(null))
        // The test tenant's
        assertEquals(0xFFE1E9EA.toInt(), BrandColors.navbarColorOf(mapOf("navbar-color" to "hsl(187, 18%, 90%)")))
    }

    @Test
    fun `the dark colour is the brand's, else iOS's default`() {
        // The test tenant's: hsl(200, 39%, 18%)
        assertEquals(0xFF1C3440.toInt(), BrandColors.darkOf(mapOf("dark" to "hsl(200, 39%, 18%)")))
        assertEquals(BrandColors.DEFAULT_DARK, BrandColors.darkOf(mapOf("accent" to "#d4ff38")))
        assertEquals(BrandColors.DEFAULT_DARK, BrandColors.darkOf(null))
    }

    @Test
    fun `anything else isn't a colour`() {
        assertNull(BrandColors.parseCssColor(null))
        assertNull(BrandColors.parseCssColor(""))
        assertNull(BrandColors.parseCssColor("#"))
        assertNull(BrandColors.parseCssColor("#12345"))
        assertNull(BrandColors.parseCssColor("#ggffff"))
        assertNull(BrandColors.parseCssColor("d4ff38"))
        assertNull(BrandColors.parseCssColor("lime"))
        assertNull(BrandColors.parseCssColor("rgb(1, 2)"))
        assertNull(BrandColors.parseCssColor("hsl(73, 100, 61)"))
    }

    @Test
    fun `the highlight is the brand's accent, else iOS's default`() {
        // As iOS: Colors.accent from the brand's cssVariables, #d4ff38 by default
        assertEquals(0xFF00A0B5.toInt(), BrandColors.accentOf(mapOf("accent" to "#00a0b5")))
        assertEquals(BrandColors.DEFAULT_ACCENT, BrandColors.accentOf(mapOf("primary" to "#00a0b5")))
        assertEquals(BrandColors.DEFAULT_ACCENT, BrandColors.accentOf(mapOf("accent" to "lime")))
        assertEquals(BrandColors.DEFAULT_ACCENT, BrandColors.accentOf(null))
        assertEquals(0xFFD4FF38.toInt(), BrandColors.DEFAULT_ACCENT)
    }
}
