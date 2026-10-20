package org.linphone.utils

object AvatarLabel {
    // A dialled or presented number, as written or formatted: "01617864357", "+44 161 786 4357",
    // "(0161) 786-4357", "*3101"
    private val numberPattern = Regex("""^[0-9+*#().\-/\s]+$""")

    /**
     * Whether a contact avatar shows the generic person icon rather than initials. As the web
     * client, a caller with no contact match (named only by their number) gets the icon, not the
     * number's first digit (WI 36369).
     */
    fun usesPlaceholder(displayName: String?): Boolean =
        displayName.isNullOrBlank() || numberPattern.matches(displayName.trim())
}
