package org.linphone.utils

import org.linphone.core.Core

/**
 * The User-Agent and Supported tags our INVITEs carry. They match the web client's because the PBX
 * only sends the P-Asserted-Identity UPDATE that names the other party after answer (e.g. a caller
 * retrieved from a parking slot) to INVITEs that look like the web client's (WI 36043, WI 36120).
 * Which header it keys on isn't known, so all of them are matched.
 */
object InviteHeaders {
    const val USER_AGENT_NAME = "Dimensions PlumUCW"
    const val USER_AGENT_VERSION = "v1.0.26278-2"

    val removedSupportedTags = listOf("path", "record-aware")
    val addedSupportedTags = listOf("ice")

    /** The core settings applied, so they can be checked without a native Core. */
    interface Target {
        fun setUserAgent(name: String, version: String)
        fun removeSupportedTag(tag: String)
        fun addSupportedTag(tag: String)
    }

    fun apply(target: Target) {
        target.setUserAgent(USER_AGENT_NAME, USER_AGENT_VERSION)
        removedSupportedTags.forEach { target.removeSupportedTag(it) }
        addedSupportedTags.forEach { target.addSupportedTag(it) }
    }

    fun apply(core: Core) = apply(
        object : Target {
            override fun setUserAgent(name: String, version: String) = core.setUserAgent(name, version)
            override fun removeSupportedTag(tag: String) = core.removeSupportedTag(tag)
            override fun addSupportedTag(tag: String) = core.addSupportedTag(tag)
        }
    )
}
