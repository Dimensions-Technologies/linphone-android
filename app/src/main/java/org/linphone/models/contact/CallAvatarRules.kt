package org.linphone.models.contact

import org.linphone.models.UserInfo

/**
 * The picture shown for a live call, as the web client's Call.avatar: the matched contact's avatar
 * when exactly one contact matches, else the internal user's profile image. The web client finds the
 * user from the call's CTI data (otherUserIds); without it, the user is the one whose extension the
 * call is with.
 */
object CallAvatarRules {
    fun contactAvatarUrl(contact: ContactItemModel?): String? =
        contact?.fields
            ?.firstOrNull { it.id == ContactItemModel.AVATAR_URL }
            ?.value
            ?.takeIf { it.isNotBlank() }

    /** The internal user on the other end of the call, by extension. */
    fun findUser(users: List<UserInfo>, number: String): UserInfo? =
        if (number.isBlank()) null else users.firstOrNull { it.presenceId == number }

    /**
     * The internal user a History call was with, as iOS's ContactAvatarLookup and the web client's
     * userImages: by extension, or by email, which Teams calls give as the number.
     */
    fun findUserByNumberOrEmail(users: List<UserInfo>, number: String): UserInfo? {
        val value = number.trim()
        if (value.isEmpty()) return null
        if (value.contains('@')) return users.firstOrNull { it.email.trim().equals(value, ignoreCase = true) }
        return findUser(users, value)
    }

    /**
     * A user's picture from the profile image path the user groups give, as iOS's ContactAvatarLookup
     * and the web client's userProfileImageUrl: a full URL as it is, else the 64px image under the
     * environment's resources ("{resources}/images/{tenant}/profile-images/{id}_64.png").
     */
    fun profileImageUrl(path: String?, resourcesUrl: String?): String? {
        val value = path?.trim()
        if (value.isNullOrEmpty()) return null
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(value)) return value
        if (resourcesUrl.isNullOrBlank()) return null
        return "${resourcesUrl.trimEnd('/')}/images/${value.trimStart('/').replace(".png", "_64.png")}"
    }

    fun userAvatarUrl(user: UserInfo?): String? = user?.profileImageUrl?.takeIf { it.isNotBlank() }

    /** [singleMatch] is the contact when exactly one matches the call, else null. */
    fun avatarUrl(singleMatch: ContactItemModel?, user: UserInfo?): String? =
        contactAvatarUrl(singleMatch) ?: userAvatarUrl(user)
}
