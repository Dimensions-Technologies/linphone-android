package org.linphone.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.linphone.models.DimensionsEnvironment

class DefaultEnvironmentTest {
    private fun env(id: String, isDefault: Boolean = false, locales: List<String> = emptyList()) =
        DimensionsEnvironment(
            id = id,
            name = id,
            isDefault = isDefault,
            identityServerUri = "",
            gatewayApiUri = "",
            realtimeApiUri = "",
            documentationUri = "",
            diagnosticsBlobConnectionString = "",
            resourcesBlobUrl = "",
            locales = locales
        )

    private val list = listOf(
        env("NA", locales = listOf("en-US")),
        env("EU", locales = listOf("en-IE")),
        env("UK", isDefault = true, locales = listOf("en-GB"))
    )

    @Test
    fun `brand default beats a locale match`() {
        assertEquals("NA", resolveDefaultEnvironment(list, "NA", "en-IE")?.id)
        assertEquals("NA", resolveDefaultEnvironment(list, "NA", "en-GB")?.id)
    }

    @Test
    fun `without a brand default the locale match wins`() {
        assertEquals("EU", resolveDefaultEnvironment(list, null, "en-IE")?.id)
        assertEquals("NA", resolveDefaultEnvironment(list, null, "en-US")?.id)
    }

    @Test
    fun `falls back to isDefault when nothing matches the locale`() {
        assertEquals("UK", resolveDefaultEnvironment(list, null, "fr-FR")?.id)
    }

    @Test
    fun `unknown brand default id falls through to locale matching`() {
        assertEquals("EU", resolveDefaultEnvironment(list, "Nope", "en-IE")?.id)
    }

    @Test
    fun `empty list gives null`() {
        assertNull(resolveDefaultEnvironment(emptyList(), "NA", "en-US"))
    }
}
