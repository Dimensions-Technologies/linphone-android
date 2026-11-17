package org.linphone.models.voicemail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.models.callhistory.CallHistoryItem
import org.linphone.models.callhistory.CallTypes
import org.linphone.models.callhistory.PbxType
import org.threeten.bp.ZoneOffset
import org.threeten.bp.ZonedDateTime

/** WI 37000: a voicemail keeps the call it was left on after that call ages out of the history. */
class VoicemailCallLinksTest {

    private val nine = ZonedDateTime.of(2026, 11, 5, 9, 0, 0, 0, ZoneOffset.UTC)

    private fun message(id: String, number: String = "01332362900") =
        VoicemailMessage(mediaId = id, timestamp = nine, callerIdNumber = number)

    private fun box(id: String, vararg messages: VoicemailMessage, nextStartKey: String? = null) =
        VoicemailBoxWithMessages(VoicemailBox(id = id), messages.toList(), nextStartKey)

    private fun call(id: String, number: String = "+441332362900", secondsBefore: Long = 20) =
        CallHistoryItem(
            missedCall = true,
            answered = false,
            hasRecording = false,
            startTime = nine.minusSeconds(secondsBefore),
            connectionId = id,
            callType = CallTypes.External,
            callDirection = 2,
            contactName = null,
            contactMatchType = null,
            hasContactMatch = false,
            calledUserName = null,
            calledUserNumber = null,
            callingUserName = null,
            callingUserNumber = null,
            routePathName = null,
            groupName = null,
            huntgroupName = null,
            documentId = id,
            isConference = false,
            pbxType = PbxType.Kazoo,
            interactionTags = emptyList(),
            cli = number
        )

    @Test
    fun `voicemails match the loaded calls they were left on`() {
        val boxes = listOf(box("b", message("m1"), message("m2", number = "0800123456")))
        assertEquals(mapOf("m1" to "c1"), VoicemailCallLinks.match(boxes, listOf(call("c1")), "GB"))
    }

    @Test
    fun `a match is remembered with its box`() {
        val boxes = listOf(box("b", message("m1")))
        assertEquals(
            mapOf("m1" to VoicemailCallLink("b", "c1")),
            VoicemailCallLinks.updated(emptyMap(), boxes, mapOf("m1" to "c1"))
        )
    }

    @Test
    fun `a remembered link is kept after its call ages out of the history`() {
        val stored = mapOf("m1" to VoicemailCallLink("b", "c1"))
        val boxes = listOf(box("b", message("m1")))
        assertEquals(stored, VoicemailCallLinks.updated(stored, boxes, emptyMap()))
    }

    @Test
    fun `a deleted voicemail's link is dropped`() {
        val stored = mapOf(
            "gone" to VoicemailCallLink("b", "c1"),
            "kept" to VoicemailCallLink("b", "c2")
        )
        val boxes = listOf(box("b", message("kept")))
        assertEquals(
            mapOf("kept" to VoicemailCallLink("b", "c2")),
            VoicemailCallLinks.updated(stored, boxes, emptyMap())
        )
    }

    @Test
    fun `links are kept for boxes not fully loaded, empty or missing`() {
        val stored = mapOf(
            "paged" to VoicemailCallLink("paged", "c1"),
            "failed" to VoicemailCallLink("failed", "c2"),
            "missing" to VoicemailCallLink("missing", "c3")
        )
        val boxes = listOf(
            box("paged", message("other"), nextStartKey = "next"),
            // A box whose messages failed to load has none
            box("failed")
        )
        assertEquals(stored, VoicemailCallLinks.updated(stored, boxes, emptyMap()))
    }

    @Test
    fun `a new match replaces the remembered call`() {
        val stored = mapOf("m1" to VoicemailCallLink("b", "old"))
        val boxes = listOf(box("b", message("m1")))
        assertEquals(
            mapOf("m1" to VoicemailCallLink("b", "new")),
            VoicemailCallLinks.updated(stored, boxes, mapOf("m1" to "new"))
        )
    }

    @Test
    fun `the oldest links are dropped past the limit`() {
        val stored = (0 until VoicemailCallLinks.MAX_LINKS).associate {
            "m$it" to VoicemailCallLink("paged", "c$it")
        }
        val boxes = listOf(box("b", message("fresh")))
        val updated = VoicemailCallLinks.updated(stored, boxes, mapOf("fresh" to "cf"))
        assertEquals(VoicemailCallLinks.MAX_LINKS, updated.size)
        assertTrue("m0" !in updated)
        assertEquals(VoicemailCallLink("b", "cf"), updated["fresh"])
    }

    @Test
    fun `a voicemail's call is the loaded match, else the remembered one`() {
        val boxes = listOf(box("b", message("matched"), message("remembered"), message("unknown")))
        assertEquals(
            mapOf("matched" to "live", "remembered" to "stored"),
            VoicemailCallLinks.withLinks(
                boxes,
                mapOf("matched" to "live"),
                mapOf("matched" to "older", "remembered" to "stored", "deleted" to "x")
            )
        )
    }

    @Test
    fun `in History a voicemail goes with its listed call, else in a row with the call remembered`() {
        val withCall = message("withCall")
        val remembered = message("remembered")
        val unknown = message("unknown")
        val boxes = listOf(box("b", withCall, remembered, unknown))
        val placed = VoicemailCallLinks.forHistory(
            boxes,
            mapOf("withCall" to "listed", "remembered" to "aged-out"),
            setOf("listed")
        )
        assertEquals(mapOf("listed" to ("b" to withCall)), placed.byCall)
        assertEquals(
            listOf(
                UnlistedVoicemail("b", remembered, "aged-out"),
                UnlistedVoicemail("b", unknown, null)
            ),
            placed.unlisted
        )
    }

    @Test
    fun `a call's second voicemail isn't listed twice`() {
        val first = message("first")
        val boxes = listOf(box("b", first, message("second")))
        val placed = VoicemailCallLinks.forHistory(
            boxes,
            mapOf("first" to "c1", "second" to "c1"),
            setOf("c1")
        )
        assertEquals(mapOf("c1" to ("b" to first)), placed.byCall)
        assertTrue(placed.unlisted.isEmpty())
    }
}
