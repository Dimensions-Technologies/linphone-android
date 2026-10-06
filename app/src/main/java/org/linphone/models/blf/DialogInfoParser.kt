package org.linphone.models.blf

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Reads an application/dialog-info+xml NOTIFY body into a BLF status. Ported from the web client's
 * libwebphone lwpBLF._parseDialogInfo so both clients show the same state.
 */
object DialogInfoParser {
    data class Result(val status: BlfKeyStatus, val dialogState: String, val remoteDisplay: String)

    private val priority = mapOf(
        BlfKeyStatus.INCALL to 3,
        BlfKeyStatus.RINGING to 2,
        BlfKeyStatus.UNKNOWN to 1,
        BlfKeyStatus.IDLE to 0
    )

    fun parse(xml: String): Result {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            return Result(BlfKeyStatus.UNKNOWN, "", "")
        }

        if (document.getElementsByTagNameNS("*", "dialog-info").length == 0) {
            return Result(BlfKeyStatus.UNKNOWN, "", "")
        }

        val dialogs = document.getElementsByTagNameNS("*", "dialog")
        var best = Result(BlfKeyStatus.IDLE, "", "")
        var bestPriority = priority.getValue(BlfKeyStatus.IDLE)
        for (i in 0 until dialogs.length) {
            val dialog = dialogs.item(i) as Element
            val state = childText(dialog, "state").lowercase()
            val status = when (state) {
                "trying", "early" -> BlfKeyStatus.RINGING
                "confirmed" -> BlfKeyStatus.INCALL
                "terminated" -> BlfKeyStatus.IDLE
                else -> BlfKeyStatus.UNKNOWN
            }

            // The most active dialog wins; ties keep the first
            val dialogPriority = priority.getValue(status)
            if (dialogPriority > bestPriority) {
                bestPriority = dialogPriority
                best = Result(status, state, remoteDisplay(dialog))
            }
        }
        return best
    }

    private fun childText(parent: Element, name: String): String {
        val nodes = parent.getElementsByTagNameNS("*", name)
        return if (nodes.length > 0) nodes.item(0).textContent.trim() else ""
    }

    private fun remoteDisplay(dialog: Element): String {
        val remote = dialog.getElementsByTagNameNS("*", "remote")
        if (remote.length == 0) return ""
        val identity = (remote.item(0) as Element).getElementsByTagNameNS("*", "identity")
        if (identity.length == 0) return ""
        return (identity.item(0) as Element).getAttribute("display")
    }
}
