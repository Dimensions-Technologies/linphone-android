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

    fun userAvatarUrl(user: UserInfo?): String? = user?.profileImageUrl?.takeIf { it.isNotBlank() }

    /** [singleMatch] is the contact when exactly one matches the call, else null. */
    fun avatarUrl(singleMatch: ContactItemModel?, user: UserInfo?): String? =
        contactAvatarUrl(singleMatch) ?: userAvatarUrl(user)
}
