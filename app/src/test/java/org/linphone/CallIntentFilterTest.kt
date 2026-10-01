package org.linphone

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Checks the tel: and sip: intent filters. The app takes tel: links from browsers (VIEW) and other
 * apps' call buttons (DIAL), but mustn't claim CALL or CALL_BUTTON, which make it act like the
 * default phone app on some models (WI #28994).
 */
class CallIntentFilterTest {

    private val appDir: File = listOf(File(System.getProperty("user.dir")!!), File("app"))
        .map { it.absoluteFile }
        .first { File(it, "src/main/AndroidManifest.xml").exists() }

    private val callSchemes = setOf("tel", "sip", "sips")
    private val forbiddenActions = setOf(
        "android.intent.action.CALL",
        "android.intent.action.CALL_BUTTON"
    )

    private data class Filter(
        val manifest: String,
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
                actions = filter.children("action").map { it.getAttribute("android:name") }.toSet(),
                schemes = filter.children("data").map { it.getAttribute("android:scheme") }
                    .filter { it.isNotEmpty() }
                    .toSet()
            )
        }
    }

    private val allManifests: List<File> by lazy {
        File(appDir, "src").listFiles()!!
            .map { File(it, "AndroidManifest.xml") }
            .filter { it.exists() }
    }

    @Test
    fun `main manifest takes tel and sip links from VIEW and DIAL`() {
        val filters = filtersIn(File(appDir, "src/main/AndroidManifest.xml"))
        for (scheme in callSchemes) {
            for (action in listOf("android.intent.action.VIEW", "android.intent.action.DIAL")) {
                assertTrue(
                    "No intent filter handles $action for $scheme: links",
                    filters.any { action in it.actions && scheme in it.schemes }
                )
            }
        }
    }

    @Test
    fun `no manifest claims CALL or CALL_BUTTON for call links`() {
        val offending = allManifests.flatMap { filtersIn(it) }
            .filter { it.schemes.any { scheme -> scheme in callSchemes } || it.schemes.isEmpty() }
            .flatMap { filter ->
                filter.actions.intersect(forbiddenActions).map { "${filter.manifest}: $it" }
            }
        assertEquals(emptyList<String>(), offending)
    }
}
