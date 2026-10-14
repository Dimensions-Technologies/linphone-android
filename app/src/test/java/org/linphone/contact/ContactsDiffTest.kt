package org.linphone.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks which native contacts the contacts loader writes to the friend list. Replacing the whole
 * list on every load blocked the main thread for ~30 s with 3,000 contacts (Sentry 1D).
 */
class ContactsDiffTest {

    private fun contact(refKey: String, name: String = "Contact $refKey", phone: String = "+4477009$refKey") =
        ContactSnapshot(
            refKey = refKey,
            name = name,
            organization = null,
            starred = false,
            photo = null,
            phoneNumbers = listOf("mobile|$phone"),
            addresses = emptyList()
        )

    private fun incoming(vararg contacts: ContactSnapshot) = contacts.associateBy { it.refKey }

    @Test
    fun `unchanged address book needs no changes`() {
        val contacts = (1..100).map { contact("$it") }

        val diff = ContactsDiff.compute(contacts, incoming(*contacts.toTypedArray()))

        assertTrue(diff.isEmpty)
    }

    @Test
    fun `first load adds every contact`() {
        val diff = ContactsDiff.compute(emptyList(), incoming(contact("1"), contact("2")))

        assertEquals(setOf("1", "2"), diff.toAdd)
        assertTrue(diff.toRemove.isEmpty())
    }

    @Test
    fun `deleted contact is removed`() {
        val diff = ContactsDiff.compute(listOf(contact("1"), contact("2")), incoming(contact("1")))

        assertEquals(setOf("2"), diff.toRemove)
        assertTrue(diff.toAdd.isEmpty())
    }

    @Test
    fun `edited contact is removed and re-added`() {
        val diff = ContactsDiff.compute(
            listOf(contact("1"), contact("2")),
            incoming(contact("1"), contact("2", name = "Renamed"))
        )

        assertEquals(setOf("2"), diff.toRemove)
        assertEquals(setOf("2"), diff.toAdd)
    }

    @Test
    fun `changed phone number counts as an edit`() {
        val diff = ContactsDiff.compute(listOf(contact("1")), incoming(contact("1", phone = "+441234")))

        assertEquals(setOf("1"), diff.toRemove)
        assertEquals(setOf("1"), diff.toAdd)
    }

    @Test
    fun `duplicate friends for one contact are replaced by a single one`() {
        val diff = ContactsDiff.compute(listOf(contact("1"), contact("1")), incoming(contact("1")))

        assertEquals(setOf("1"), diff.toRemove)
        assertEquals(setOf("1"), diff.toAdd)
    }

    @Test
    fun `duplicate friends for a deleted contact are removed`() {
        val diff = ContactsDiff.compute(listOf(contact("1"), contact("1")), incoming())

        assertEquals(setOf("1"), diff.toRemove)
        assertTrue(diff.toAdd.isEmpty())
    }
}
