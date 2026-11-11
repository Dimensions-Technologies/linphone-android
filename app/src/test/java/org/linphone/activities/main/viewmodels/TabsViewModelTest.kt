package org.linphone.activities.main.viewmodels

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The tab bar: Favourites, Contacts, History, Dialer and Parking. Voicemail is a switch inside
 * History (History / Missed / Voicemail, as on iOS), so it has no tab and History's badge counts the
 * new voicemails as well as the missed calls.
 */
class TabsViewModelTest {

    private val appDir: File = listOf(File(System.getProperty("user.dir")!!), File("app"))
        .map { it.absoluteFile }
        .first { File(it, "src/main/AndroidManifest.xml").exists() }

    private fun assertAnchors(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 0.0001F) }
    }

    @Test
    fun `all five tabs share the bar equally`() {
        assertAnchors(
            listOf(0.2F, 0.4F, 0.6F, 0.8F),
            TabsViewModel.tabAnchors(withParking = true, withContacts = true)
        )
    }

    @Test
    fun `without parking slots Dialer reaches the end of the bar`() {
        assertAnchors(
            listOf(0.25F, 0.5F, 0.75F, 1F),
            TabsViewModel.tabAnchors(withParking = false, withContacts = true)
        )
    }

    @Test
    fun `without Contacts Favourites and Contacts have no width`() {
        assertAnchors(
            listOf(0F, 0F, 1F / 3, 2F / 3),
            TabsViewModel.tabAnchors(withParking = true, withContacts = false)
        )
    }

    @Test
    fun `History and Dialer alone take half each`() {
        assertAnchors(
            listOf(0F, 0F, 0.5F, 1F),
            TabsViewModel.tabAnchors(withParking = false, withContacts = false)
        )
    }

    @Test
    fun `History badge adds new voicemails to missed calls`() {
        assertEquals(5, TabsViewModel.historyBadgeCount(2, voicemailAvailable = true, newVoicemails = 3))
        assertEquals(3, TabsViewModel.historyBadgeCount(0, voicemailAvailable = true, newVoicemails = 3))
        assertEquals(0, TabsViewModel.historyBadgeCount(0, voicemailAvailable = true, newVoicemails = 0))
    }

    @Test
    fun `History badge leaves out voicemails when History has no Voicemail switch`() {
        assertEquals(2, TabsViewModel.historyBadgeCount(2, voicemailAvailable = false, newVoicemails = 3))
    }

    @Test
    fun `tab bars have no Voicemail tab and one boundary per tab after the first`() {
        for (layout in listOf("layout", "layout-land")) {
            val xml = File(appDir, "src/main/res/$layout/tabs_fragment.xml").readText()
            assertFalse("$layout has a Voicemail tab", xml.contains("@+id/voicemail"))
            val guidelines = Regex("@\\+id/guideline(\\d+)").findAll(xml).map { it.groupValues[1] }.toList()
            assertEquals(
                "$layout guidelines",
                (1..TabsViewModel.tabAnchors(withParking = true, withContacts = true).size).map { "$it" },
                guidelines
            )
        }
    }
}
