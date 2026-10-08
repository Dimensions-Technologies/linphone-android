package org.linphone

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.utils.CallUriIntents
import org.w3c.dom.Element

/**
 * Checks the tel: and sip: intent filters (WI #28994, #29001). Call links go to LoginActivity, so
 * a user who isn't signed in is signed in before the call is made. The app takes them from
 * browsers (VIEW), other apps' call buttons (DIAL) and contacts apps (CALL), but mustn't claim
 * CALL_BUTTON, the hardware or headset call button, which makes it act like the default phone app.
 */
class CallIntentFilterTest {

    private val appDir: File = listOf(File(System.getProperty("user.dir")!!), File("app"))
        .map { it.absoluteFile }
        .first { File(it, "src/main/AndroidManifest.xml").exists() }

    private val callSchemes = setOf("tel", "sip", "sips")
    private val callActions = listOf(
        "android.intent.action.VIEW",
        "android.intent.action.DIAL",
        "android.intent.action.CALL"
    )
    private val forbiddenActions = setOf("android.intent.action.CALL_BUTTON")
    private val loginActivity = ".activities.main.LoginActivity"

    private data class Filter(
        val manifest: String,
        val activity: String,
        val actions: Set<String>,
        val schemes: Set<String>
    )

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun filtersIn(manifest: File): List<Filter> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(manifest)
        val name = manifest.relativeTo(appDir).path
        return document.documentElement.children("intent-filter").map { filter ->
            Filter(
                manifest = name,
                activity = (filter.parentNode as Element).getAttribute("android:name"),
                actions = filter.children("action").map { it.getAttribute("android:name") }.toSet(),
                schemes = filter.children("data").map { it.getAttribute("android:scheme") }
                    .filter { it.isNotEmpty() }
                    .toSet()
            )
        }
    }

    private val mainFilters by lazy { filtersIn(File(appDir, "src/main/AndroidManifest.xml")) }

    private val allFilters: List<Filter> by lazy {
        File(appDir, "src").listFiles()!!
            .map { File(it, "AndroidManifest.xml") }
            .filter { it.exists() }
            .flatMap { filtersIn(it) }
    }

    private fun Filter.handlesCallLinks() = schemes.any { it in callSchemes }

    @Test
    fun `LoginActivity takes tel and sip links from VIEW, DIAL and CALL`() {
        val loginFilters = mainFilters.filter { it.activity == loginActivity }
        for (scheme in callSchemes) {
            for (action in callActions) {
                assertTrue(
                    "LoginActivity doesn't handle $action for $scheme: links",
                    loginFilters.any { action in it.actions && scheme in it.schemes }
                )
            }
        }
    }

    // LoginActivity only holds a link that CallUriIntents accepts, so a scheme in the filter but
    // not in the code would open the app and then silently drop the number
    @Test
    fun `every call link LoginActivity receives is recognised as one`() {
        val unrecognised = mainFilters
            .filter { it.activity == loginActivity && it.handlesCallLinks() }
            .flatMap { filter ->
                filter.actions.flatMap { action -> filter.schemes.map { action to it } }
            }
            .filterNot { (action, scheme) -> CallUriIntents.isCallUri(action, scheme) }
        assertEquals(emptyList<Pair<String, String>>(), unrecognised)
    }

    @Test
    fun `every scheme recognised as a call link reaches LoginActivity`() {
        val loginSchemes = mainFilters
            .filter { it.activity == loginActivity }
            .flatMap { it.schemes }
            .toSet()
        assertEquals(emptySet<String>(), CallUriIntents.CALL_URI_SCHEMES - loginSchemes)
    }

    @Test
    fun `only LoginActivity takes call links, so sign-in can't be skipped`() {
        val elsewhere = allFilters
            .filter { it.handlesCallLinks() && it.activity != loginActivity }
            .map { "${it.manifest}: ${it.activity}" }
        assertEquals(emptyList<String>(), elsewhere)
    }

    @Test
    fun `no manifest claims CALL_BUTTON for call links`() {
        val offending = allFilters
            .filter { it.handlesCallLinks() || it.schemes.isEmpty() }
            .flatMap { filter ->
                filter.actions.intersect(forbiddenActions).map { "${filter.manifest}: $it" }
            }
        assertEquals(emptyList<String>(), offending)
    }
}
