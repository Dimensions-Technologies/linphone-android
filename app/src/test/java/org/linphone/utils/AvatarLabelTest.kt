package org.linphone.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarLabelTest {
    @Test
    fun `an unmatched number gets the placeholder, not its first digit (WI 36369)`() {
        assertTrue(AvatarLabel.usesPlaceholder("01617864357"))
        assertTrue(AvatarLabel.usesPlaceholder("+441617864357"))
        assertTrue(AvatarLabel.usesPlaceholder("+44 161 786 4357"))
        assertTrue(AvatarLabel.usesPlaceholder("(0161) 786-4357"))
        assertTrue(AvatarLabel.usesPlaceholder("1035"))
        assertTrue(AvatarLabel.usesPlaceholder("*3101"))
        assertTrue(AvatarLabel.usesPlaceholder("+"))
    }

    @Test
    fun `no name gets the placeholder`() {
        assertTrue(AvatarLabel.usesPlaceholder(null))
        assertTrue(AvatarLabel.usesPlaceholder(""))
        assertTrue(AvatarLabel.usesPlaceholder("  "))
    }

    @Test
    fun `a name gets initials, even one with digits in it`() {
        assertFalse(AvatarLabel.usesPlaceholder("Beth Ramsay"))
        assertFalse(AvatarLabel.usesPlaceholder("3M Reception"))
        assertFalse(AvatarLabel.usesPlaceholder("Draper 2"))
        assertFalse(AvatarLabel.usesPlaceholder("Voicemail"))
    }
}
