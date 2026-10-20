package org.linphone.models.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.linphone.models.UserInfo

class CallAvatarRulesTest {

    private val noel = UserInfo(
        id = "user-1034",
        displayName = "Noel Ramsay",
        presenceId = "1034",
        profileImageUrl = "https://images.example/users/noel.png"
    )
    private val bob = UserInfo(id = "user-1033", displayName = "Bob", presenceId = "1033")
    private val users = listOf(bob, noel)

    private fun contact(avatar: String?) = ContactItemModel(
        id = "contact-1",
        directoryId = "dir-1",
        fields = listOfNotNull(
            FieldItemModel(ContactItemModel.FULL_NAME, "WI36047 C2 Shared"),
            FieldItemModel(ContactItemModel.PHONE1, "+441617864355"),
            avatar?.let { FieldItemModel(ContactItemModel.AVATAR_URL, it) }
        )
    )

    @Test
    fun `the matched contact's avatar is the call's picture`() {
        val url = "https://images.example/contacts/c2.png"
        assertEquals(url, CallAvatarRules.avatarUrl(contact(url), null))
    }

    @Test
    fun `the matched contact's avatar wins over the user's profile image`() {
        val url = "https://images.example/contacts/c2.png"
        assertEquals(url, CallAvatarRules.avatarUrl(contact(url), noel))
    }

    @Test
    fun `the user's profile image is used when the contact has no avatar`() {
        assertEquals(noel.profileImageUrl, CallAvatarRules.avatarUrl(contact(null), noel))
        assertEquals(noel.profileImageUrl, CallAvatarRules.avatarUrl(contact(" "), noel))
    }

    @Test
    fun `the user's profile image is used when no single contact matches`() {
        assertEquals(noel.profileImageUrl, CallAvatarRules.avatarUrl(null, noel))
    }

    @Test
    fun `no picture without an avatar or profile image`() {
        assertNull(CallAvatarRules.avatarUrl(null, null))
        assertNull(CallAvatarRules.avatarUrl(contact(null), bob))
    }

    @Test
    fun `the user is found by extension`() {
        assertEquals(noel, CallAvatarRules.findUser(users, "1034"))
        assertNull(CallAvatarRules.findUser(users, "1035"))
        assertNull(CallAvatarRules.findUser(users, "+441617864355"))
    }

    @Test
    fun `no user for a call without a number`() {
        val noExtension = UserInfo(id = "user-x", presenceId = "")
        assertNull(CallAvatarRules.findUser(listOf(noExtension), ""))
    }
}
