package org.linphone.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class InviteHeadersTest {
    // Stands in for the core: what an INVITE's User-Agent and Supported headers end up as
    private class FakeCore(supported: List<String>) : InviteHeaders.Target {
        var userAgent = "Dimensions Connect Mobile UCM Android/5.2.5"
        val supported = supported.toMutableList()

        override fun setUserAgent(name: String, version: String) {
            userAgent = "$name/$version"
        }

        override fun removeSupportedTag(tag: String) {
            supported.remove(tag)
        }

        override fun addSupportedTag(tag: String) {
            if (tag !in supported) supported.add(tag)
        }
    }

    @Test
    fun `INVITEs look like the web client's, which the PBX sends the identity UPDATE to`() {
        // As our INVITE was without the alignment (WI 36120: no P-Asserted-Identity on a retrieve)
        val core = FakeCore(listOf("replaces", "outbound", "gruu", "path", "record-aware"))

        InviteHeaders.apply(core)

        // As the INVITE the PBX did send the P-Asserted-Identity UPDATE to
        assertEquals("Dimensions PlumUCW/v1.0.26278-2", core.userAgent)
        assertEquals("replaces, outbound, gruu, ice", core.supported.joinToString(", "))
    }

    @Test
    fun `ice is advertised even when the core already has it`() {
        val core = FakeCore(listOf("replaces", "outbound", "gruu", "ice", "record-aware"))

        InviteHeaders.apply(core)

        assertEquals("replaces, outbound, gruu, ice", core.supported.joinToString(", "))
    }
}
