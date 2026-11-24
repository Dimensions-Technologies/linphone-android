package org.linphone.branding

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Text must stay readable in light and dark theme, for the main app and every brand: every
 * layout's text colour is checked against the solid colour behind it (WI 37133: About's
 * Diagnostics heading used the brand's dark colour, which vanished on the dark background, and
 * Parking's hint used an inverse colour that vanished on both).
 *
 * Colours resolve as Android does for a brand build: the brand's `values` override main's, and in
 * dark theme `values-night` wins over `values`. Theme attributes come from main's BaseAppTheme,
 * which no brand overrides. Text over a drawable background, or with a colour set only from a
 * binding expression, can't be resolved here and is skipped.
 */
class TextContrastTest {

    private val appDir: File = listOf(File(System.getProperty("user.dir")!!), File("app"))
        .map { it.absoluteFile }
        .first { File(it, "src/main/AndroidManifest.xml").exists() }

    private val notBrands = setOf("main", "test", "androidTest", "debug")

    private val sourceSets: List<String> =
        listOf("main") + File(appDir, "src").listFiles { f -> f.isDirectory }!!
            .map { it.name }
            .filterNot { it in notBrands }
            .sorted()

    /**
     * Text that is knowingly below [MIN_CONTRAST], as "layout/file.xml: <text> on <background>".
     * None of these is a light/dark theme mix-up; they're brand colour choices or upstream
     * screens the app doesn't show. Remove an entry when fixing it.
     */
    private val knownLowContrast = setOf(
        // The brand's accent (teal, green, light blue) on the light grey title bar or white, in
        // light theme. A branding choice, worst for Voyager's light blue.
        "layout-land/about_fragment.xml: ?attr/accentColor on ?attr/backgroundColor",
        "layout-land/about_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/about_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/assistant_top_bar_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/contact_detail_cell.xml: ?attr/accentColor on ?attr/backgroundColor",
        "layout/contact_detail_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/contact_list_cell.xml: ?attr/accentColor on ?attr/backgroundColor",
        "layout/directory_contact_editor_fragment.xml: ?attr/accentColor on ?attr/backgroundColor",
        "layout/fragment_presence_editor.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/fragment_recording_playback.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/generic_list_header.xml: ?attr/accentColor on ?attr/backgroundColor",
        "layout/numpad_new.xml: @color/primary_color on ?attr/backgroundColor",
        "layout/parking_slots_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/recordings_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/settings_account_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/settings_advanced_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/settings_audio_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/settings_call_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/settings_chat_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/settings_conferences_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/settings_contacts_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/settings_fragment.xml: ?attr/accentColor on ?attr/backgroundColor",
        "layout/settings_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/settings_ldap_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/settings_network_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/settings_tunnel_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        "layout/settings_video_fragment.xml: ?attr/accentColor on ?attr/lightToolbarBackgroundColor",
        // White on the brand's accent, for brands with a light accent
        "layout/call_overlay.xml: @color/white_color on @color/primary_color",
        "layout/voip_status_fragment.xml: ?attr/accentTextColor on ?attr/accentColor",
        // BryteCall only: its pale grey_color (#bac2d6) and white navbar_color
        "layout/about_fragment.xml: ?attr/primarySubtextDarkColor on ?attr/backgroundColor",
        "layout/contact_detail_cell.xml: ?attr/primarySubtextDarkColor on ?attr/backgroundColor",
        "layout/contact_detail_fragment.xml: ?attr/primarySubtextDarkColor on ?attr/lightToolbarBackgroundColor",
        "layout/fragment_presence_editor.xml: ?attr/primarySubtextDarkColor on ?attr/backgroundColor",
        "layout/side_menu_fragment.xml: ?attr/accentTextColor on ?attr/navbarColor",
        // Pale grey text in light theme
        "layout/fragment_recording_playback.xml: @color/voip_gray on ?attr/backgroundColor",
        // Upstream chat and conference scheduling screens, which the app doesn't show
        "layout/chat_bubble_activity.xml: ?attr/primarySubtextDarkColor on ?attr/backgroundColor",
        "layout/chat_room_detail_fragment.xml: ?attr/primarySubtextDarkColor on ?attr/backgroundColor",
        "layout/chat_room_list_cell.xml: ?attr/primarySubtextDarkColor on ?attr/backgroundColor",
        "layout/chat_room_list_cell.xml: @color/primary_color on ?attr/backgroundColor",
        "layout/chat_room_sending.xml: @color/primary_color on ?attr/lightToolbarBackgroundColor",
        "layout/chat_unread_messages_list_header.xml: @color/light_grey_color on ?attr/backgroundColor",
        "layout/conference_schedule_cell.xml: @color/voip_conf_address_text_color on @color/white_color",
        "layout/conference_scheduling_participant_cell.xml: ?attr/accentColor on ?attr/backgroundColor",
        "layout/conference_scheduling_summary_fragment.xml: @color/light_grey_color on ?attr/voipBackgroundBWColor",
        "layout/conferences_scheduled_fragment.xml: ?attr/accentColor on ?attr/voipToolbarBackgroundColor"
    )

    private val allText by lazy { layoutFiles().flatMap { textOnSolidBackgrounds(it) } }

    /** Where [item] is below [MIN_CONTRAST], as "<brand> <theme> <ratio>" for each. */
    private fun lowContrast(item: TextOnBackground): List<String> =
        sourceSets.flatMap { sourceSet ->
            listOf(false, true).mapNotNull { night ->
                val bg = resolve(sourceSet, night, item.background)
                    ?.takeIf { it ushr 24 == 0xFF } // a see-through background: unknown
                    ?: return@mapNotNull null
                val fg = resolve(sourceSet, night, item.text)?.let { blend(it, bg) }
                    ?: return@mapNotNull null
                val ratio = contrast(fg, bg)
                if (ratio >= MIN_CONTRAST) {
                    null
                } else {
                    "$sourceSet ${if (night) "dark" else "light"} " +
                        "#%06X on #%06X %.2f:1".format(fg and 0xFFFFFF, bg and 0xFFFFFF, ratio)
                }
            }
        }

    @Test
    fun `text is readable on the colour behind it in light and dark theme`() {
        assertTrue("The scan should find text to check", allText.size > 100)
        val failures = allText.filterNot { it.key in knownLowContrast }
            .groupBy { it.key }
            .mapNotNull { (key, items) ->
                lowContrast(items.first()).takeIf { it.isNotEmpty() }?.let {
                    "$key (${items.joinToString { i -> i.label }})\n    ${it.joinToString("\n    ")}"
                }
            }
            .sorted()
        assertEquals("Text below $MIN_CONTRAST:1 contrast", "", failures.joinToString("\n"))
    }

    @Test
    fun `known low contrast entries still exist and still fail`() {
        val byKey = allText.associateBy { it.key }
        val stale = knownLowContrast.filter { byKey[it]?.let(::lowContrast).isNullOrEmpty() }
        assertEquals("Fixed or gone: remove from knownLowContrast", emptyList<String>(), stale)
    }

    @Test
    fun `the about page diagnostics heading and the parking hint are checked`() {
        val labels = allText.map { "${it.key.substringBefore(':')}:${it.label}" }
        assertTrue(labels.contains("layout/about_fragment.xml:@string/about_diagnostics_title"))
        assertTrue(labels.contains("layout/parking_slots_fragment.xml:@+id/hint"))
        // Included into the settings pages and About
        assertTrue(labels.any { it.startsWith("layout/settings_widget_switch.xml:") })
    }

    @Test
    fun `contrast is measured as WCAG defines it`() {
        assertEquals(21.0, contrast(0xFFFFFF, 0x000000), 0.01)
        assertEquals(1.0, contrast(0x2D3841, 0x2D3841), 0.01)
        // The old Diagnostics heading in dark theme: the brand's dark colour on the dark background
        assertTrue(contrast(0x252E35, 0x2D3841) < MIN_CONTRAST)
        // Half-transparent white over black is mid grey
        assertEquals(0x808080, blend(0x80FFFFFF.toInt(), 0x000000) and 0xFFFFFF)
    }

    // ---- Layout scan ----

    /** [key] is "layout/file.xml: <text colour> on <background colour>", as [knownLowContrast] lists it. */
    private data class TextOnBackground(
        val key: String,
        val label: String,
        val text: String,
        val background: String
    )

    private fun layoutFiles(): List<File> =
        File(appDir, "src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("layout") }!!
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }!!.toList() }
            .sortedBy { it.path }

    /**
     * Text in [file] over a solid colour. An `<include>`d layout is scanned too, over the colour
     * where it's included, so shared widgets are checked against each page that uses them.
     */
    private fun textOnSolidBackgrounds(
        file: File,
        rootBackground: String? = null,
        including: Set<File> = emptySet()
    ): List<TextOnBackground> {
        if (file in including || !file.isFile) return emptyList()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val name = "${file.parentFile!!.name}/${file.name}"
        val found = mutableListOf<TextOnBackground>()
        fun walk(e: Element, inheritedBackground: String?) {
            if (e.tagName == "data") return
            if (e.getAttribute("android:visibility") == "gone") return
            if (e.tagName == "include") {
                val included = e.getAttribute("layout").removePrefix("@layout/")
                if (inheritedBackground != null && !included.startsWith("@")) {
                    val target = listOf(file.parentFile!!, File(file.parentFile!!.parentFile, "layout"))
                        .map { File(it, "$included.xml") }
                        .firstOrNull { it.isFile }
                    if (target != null) {
                        found += textOnSolidBackgrounds(target, inheritedBackground, including + file)
                    }
                }
                return
            }
            val own = e.getAttribute("android:background").takeIf { it.isNotEmpty() }
            val background = when {
                own == null -> inheritedBackground
                own.startsWith("@{") -> null // set from a binding: unknown
                isTransparent(own) -> inheritedBackground
                own.startsWith("@color/") || own.startsWith("?attr/") || own.startsWith("#") -> own
                else -> null // a drawable: unknown
            }
            val text = e.getAttribute("android:textColor").takeIf { it.isNotEmpty() }
                ?: styleTextColor(e.getAttribute("style"))
            // Buttons and tinted views draw their own background
            val drawsOwnBackground = e.tagName.endsWith("Button") ||
                e.hasAttribute("android:backgroundTint") || e.hasAttribute("app:backgroundTint")
            if (background != null && text != null && !text.startsWith("@{") && !drawsOwnBackground) {
                val label = e.getAttribute("android:id").takeIf { it.isNotEmpty() }
                    ?: e.getAttribute("android:text").takeIf { it.startsWith("@string/") }
                    ?: e.tagName.substringAfterLast('.')
                found += TextOnBackground("$name: $text on $background", label, text, background)
            }
            val children = e.childNodes
            for (i in 0 until children.length) {
                (children.item(i) as? Element)?.let { walk(it, background) }
            }
        }
        walk(doc.documentElement, rootBackground)
        return found
    }

    private fun isTransparent(value: String) =
        value == "@color/transparent_color" || value == "@android:color/transparent" ||
            value == "#00000000"

    // ---- Resources ----

    private fun xml(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private val colorCache = mutableMapOf<String, Map<String, String>>()

    private fun colors(sourceSet: String, folder: String): Map<String, String> =
        colorCache.getOrPut("$sourceSet/$folder") {
            val dir = File(appDir, "src/$sourceSet/res/$folder")
            dir.listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { file ->
                val nodes = xml(file).getElementsByTagName("color")
                (0 until nodes.length).map {
                    val e = nodes.item(it) as Element
                    e.getAttribute("name") to e.textContent.trim()
                }
            }.toMap()
        }

    /** Text colours of the app's own styles, following parents. */
    private val styles: Map<String, Pair<String?, String?>> by lazy {
        val root = xml(File(appDir, "src/main/res/values/styles.xml"))
        val nodes = root.getElementsByTagName("style")
        (0 until nodes.length).associate {
            val e = nodes.item(it) as Element
            val items = e.getElementsByTagName("item")
            val textColor = (0 until items.length).map { i -> items.item(i) as Element }
                .firstOrNull { item -> item.getAttribute("name") == "android:textColor" }
                ?.textContent?.trim()
            val name = e.getAttribute("name")
            val parent = e.getAttribute("parent").takeIf { p -> p.isNotEmpty() }
                ?: name.substringBeforeLast('.', "").takeIf { p -> p.isNotEmpty() }
            name to (textColor to parent)
        }
    }

    private fun styleTextColor(style: String, depth: Int = 0): String? {
        if (!style.startsWith("@style/") || depth > 10) return null
        val (color, parent) = styles[style.removePrefix("@style/")] ?: return null
        return color ?: parent?.let { styleTextColor("@style/" + it.removePrefix("@style/"), depth + 1) }
    }

    private val themeCache = mutableMapOf<Boolean, Map<String, String>>()

    /** BaseAppTheme's attributes for light or dark theme. */
    private fun theme(night: Boolean): Map<String, String> = themeCache.getOrPut(night) {
        val file = File(appDir, "src/main/res/${if (night) "values-night" else "values"}/styles.xml")
        val nodes = xml(file).getElementsByTagName("style")
        val base = (0 until nodes.length).map { nodes.item(it) as Element }
            .first { it.getAttribute("name") == "BaseAppTheme" }
        val items = base.getElementsByTagName("item")
        (0 until items.length).associate {
            val e = items.item(it) as Element
            e.getAttribute("name") to e.textContent.trim()
        }
    }

    /** Resolves a colour, @color reference or ?attr to 0xAARRGGBB, or null if it can't be. */
    private fun resolve(sourceSet: String, night: Boolean, value: String, depth: Int = 0): Int? {
        check(depth < 15) { "Colour reference loop at $value" }
        return when {
            value.startsWith("#") -> parseHex(value)
            value.startsWith("?attr/") ->
                theme(night)[value.removePrefix("?attr/")]?.let { resolve(sourceSet, night, it, depth + 1) }
            value.startsWith("@color/") -> {
                val name = value.removePrefix("@color/")
                val lookups = buildList {
                    if (night) {
                        add(colors(sourceSet, "values-night"))
                        add(colors("main", "values-night"))
                    }
                    add(colors(sourceSet, "values"))
                    add(colors("main", "values"))
                }
                val found = lookups.firstNotNullOfOrNull { it[name] }
                    ?: colorStateListDefault(sourceSet, night, name)
                found?.let { resolve(sourceSet, night, it, depth + 1) }
            }
            else -> null // @android:color/…, drawables: not resolved here
        }
    }

    /** The default (last, stateless) item of a colour state list, if [name] is one. */
    private fun colorStateListDefault(sourceSet: String, night: Boolean, name: String): String? {
        val folders = if (night) listOf("color-night", "color") else listOf("color")
        val file = folders.flatMap { folder -> listOf(sourceSet, "main").map { File(appDir, "src/$it/res/$folder/$name.xml") } }
            .firstOrNull { it.isFile } ?: return null
        val items = xml(file).getElementsByTagName("item")
        val last = (0 until items.length).map { items.item(it) as Element }.lastOrNull() ?: return null
        val color = last.getAttribute("android:color")
        val alpha = last.getAttribute("android:alpha").toDoubleOrNull()
        if (alpha == null || color.isEmpty()) return color.ifEmpty { null }
        // Fold the item's alpha into a literal colour
        val base = resolve(sourceSet, night, color) ?: return null
        val a = (((base ushr 24) * alpha).toInt() and 0xFF)
        return "#%08X".format((a shl 24) or (base and 0xFFFFFF))
    }

    private fun parseHex(hex: String): Int {
        val d = hex.removePrefix("#")
        return when (d.length) {
            3 -> (0xFF000000 or d.map { "$it$it" }.joinToString("").toLong(16)).toInt()
            6 -> (0xFF000000 or d.toLong(16)).toInt()
            8 -> d.toLong(16).toInt()
            else -> throw AssertionError("Unsupported colour $hex")
        }
    }

    // ---- Contrast ----

    /** [fg] drawn over the opaque colour [bg]. */
    private fun blend(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255.0
        fun ch(shift: Int) =
            (((fg shr shift) and 0xFF) * a + ((bg shr shift) and 0xFF) * (1 - a)).let { Math.round(it).toInt() }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun luminance(rgb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(rgb shr 16 and 0xFF) +
            0.7152 * channel(rgb shr 8 and 0xFF) +
            0.0722 * channel(rgb and 0xFF)
    }

    /** WCAG contrast ratio. */
    private fun contrast(a: Int, b: Int): Double {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private companion object {
        // WCAG's minimum for large text and UI parts. Below this, text is hard to read for anyone.
        const val MIN_CONTRAST = 3.0
    }
}
