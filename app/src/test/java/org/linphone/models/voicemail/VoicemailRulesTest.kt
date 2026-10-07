package org.linphone.models.voicemail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.models.CustomerLicence
import org.linphone.models.callhistory.CallHistoryItem
import org.linphone.models.callhistory.CallTypes
import org.linphone.models.callhistory.PbxType
import org.threeten.bp.ZoneOffset
import org.threeten.bp.ZonedDateTime

class VoicemailRulesTest {

    private fun at(hour: Int) = ZonedDateTime.of(2026, 8, 14, hour, 0, 0, 0, ZoneOffset.UTC)

    private fun message(
        id: String,
        folder: String? = "new",
        hour: Int = 9,
        transcription: VoicemailTranscription? = null
    ) =
        VoicemailMessage(
            mediaId = id,
            folder = folder,
            timestamp = at(hour),
            callerIdNumber = "1076",
            transcription = transcription
        )

    private fun box(
        id: String = "box1",
        new: Int = 0,
        saved: Int = 0,
        enabled: Boolean = true,
        messages: List<VoicemailMessage> = emptyList(),
        collapsed: Boolean = false,
        collapsedFolders: List<String> = emptyList()
    ) = VoicemailBoxWithMessages(
        VoicemailBox(
            id = id,
            name = "Box $id",
            totalMessages = new + saved,
            new = new,
            saved = saved,
            enabled = enabled
        ),
        messages,
        null,
        collapsed,
        collapsedFolders
    )

    @Test
    fun `new count sums each box's own count, not the messages held`() {
        val boxes = listOf(box("a", new = 3), box("b", new = 60, messages = listOf(message("m1"))))
        assertEquals(63, VoicemailRules.newCount(boxes))
        assertEquals(0, VoicemailRules.newCount(emptyList()))
    }

    @Test
    fun `voicemail is available only when licensed, permitted and a box is enabled`() {
        val enabled = listOf(box(enabled = false), box("b", enabled = true))
        assertTrue(VoicemailRules.isAvailable(true, true, enabled))
        assertFalse(VoicemailRules.isAvailable(false, true, enabled))
        assertFalse(VoicemailRules.isAvailable(true, false, enabled))
        assertFalse("no mailbox", VoicemailRules.isAvailable(true, true, emptyList()))
        assertFalse(
            "only disabled mailboxes",
            VoicemailRules.isAvailable(true, true, listOf(box(enabled = false)))
        )
    }

    @Test
    fun `a missing folder counts as new`() {
        assertEquals("new", VoicemailRules.folderOf(message("m", folder = null)))
        assertEquals("new", VoicemailRules.folderOf(message("m", folder = "")))
        assertTrue(VoicemailRules.isSaved(message("m", folder = "saved")))
        assertFalse(VoicemailRules.isSaved(message("m", folder = null)))
    }

    @Test
    fun `the caller is the caller id number, else from`() {
        assertEquals(
            "1076",
            VoicemailRules.callerNumber(VoicemailMessage(callerIdNumber = "1076", from = "x@y"))
        )
        assertEquals(
            "x@y",
            VoicemailRules.callerNumber(VoicemailMessage(callerIdNumber = "", from = "x@y"))
        )
        assertNull(VoicemailRules.callerNumber(VoicemailMessage()))
    }

    @Test
    fun `folders are new then saved then others, always showing new and saved, newest first`() {
        val groups = VoicemailRules.groupByFolder(
            listOf(
                message("old", hour = 8),
                message("archived", folder = "archive"),
                message("deleted", folder = "deleted"),
                message("newest", hour = 11)
            )
        )
        assertEquals(listOf("new", "saved", "deleted", "archive"), groups.map { it.first })
        assertEquals(listOf("newest", "old"), groups[0].second.map { it.mediaId })
        assertTrue("saved is shown even when empty", groups[1].second.isEmpty())
    }

    @Test
    fun `one box has no name and can't be collapsed`() {
        val rows = VoicemailRules.rows(
            listOf(box(collapsed = true, messages = listOf(message("m1"))))
        )

        val header = rows[0] as VoicemailRow.BoxHeader
        assertFalse(header.showName)
        assertTrue(header.expanded)
        assertEquals(
            listOf(
                VoicemailRow.FolderHeader("box1", "new", true),
                VoicemailRow.Message("box1", message("m1")),
                VoicemailRow.FolderHeader("box1", "saved", true),
                VoicemailRow.EmptyFolder("box1", "saved")
            ),
            rows.drop(1)
        )
    }

    @Test
    fun `collapsed boxes and folders hide their contents`() {
        val rows = VoicemailRules.rows(
            listOf(
                box("a", collapsed = true, messages = listOf(message("m1"))),
                box("b", collapsedFolders = listOf("new"), messages = listOf(message("m2")))
            )
        )

        assertEquals(
            listOf(
                VoicemailRow.BoxHeader(
                    box("a", collapsed = true, messages = listOf(message("m1"))),
                    true,
                    false
                ),
                VoicemailRow.BoxHeader(
                    box("b", collapsedFolders = listOf("new"), messages = listOf(message("m2"))),
                    true,
                    true
                ),
                VoicemailRow.FolderHeader("b", "new", false),
                VoicemailRow.FolderHeader("b", "saved", true),
                VoicemailRow.EmptyFolder("b", "saved")
            ),
            rows
        )
    }

    @Test
    fun `a refresh keeps transcriptions already fetched`() {
        val transcription = VoicemailTranscription("success", "Call me back")
        val previous = box(
            messages = listOf(message("m1", transcription = transcription), message("gone"))
        )

        val refreshed = VoicemailRules.withCachedTranscriptions(
            listOf(message("m1"), message("m2")),
            previous
        )

        assertEquals(transcription, refreshed[0].transcription)
        assertNull(refreshed[1].transcription)
        assertEquals(
            listOf(message("m1")),
            VoicemailRules.withCachedTranscriptions(listOf(message("m1")), null)
        )
    }

    @Test
    fun `removing a message moves its folder count, floored at zero`() {
        val start = box(
            new = 1,
            saved = 0,
            messages = listOf(message("n1"), message("s1", folder = "saved"))
        )

        val withoutNew = VoicemailRules.withoutMessage(start, "n1")
        assertEquals(listOf("s1"), withoutNew.messages.map { it.mediaId })
        assertEquals(0, withoutNew.box.new)
        assertEquals(0, withoutNew.box.totalMessages)

        val withoutSaved = VoicemailRules.withoutMessage(withoutNew, "s1")
        assertEquals("server counts already 0", 0, withoutSaved.box.saved)
        assertEquals(0, withoutSaved.box.totalMessages)

        assertSame("unknown message", start, VoicemailRules.withoutMessage(start, "missing"))
    }

    @Test
    fun `a failed delete puts the message back where it was, once`() {
        val removed = message("n2")
        val start = box(new = 2, messages = listOf(message("n1"), removed, message("n3")))
        val without = VoicemailRules.withoutMessage(start, "n2")

        val restored = VoicemailRules.withMessage(without, removed, 1)
        assertEquals(listOf("n1", "n2", "n3"), restored.messages.map { it.mediaId })
        assertEquals(2, restored.box.new)

        assertSame("already back", restored, VoicemailRules.withMessage(restored, removed, 1))
        assertEquals(
            "index past the end of a shorter list",
            listOf("n2"),
            VoicemailRules.withMessage(box(), removed, 5).messages.map { it.mediaId }
        )
    }

    @Test
    fun `a fetched transcription is set on its message only`() {
        val transcription = VoicemailTranscription(text = "Hello")
        val boxes = listOf(
            box("a", messages = listOf(message("m1"), message("m2"))),
            box("b", messages = listOf(message("m1")))
        )

        val updated = VoicemailRules.withTranscription(boxes, "a", "m1", transcription)

        assertEquals(transcription, updated[0].messages[0].transcription)
        assertNull(updated[0].messages[1].transcription)
        assertNull("same media id in another box", updated[1].messages[0].transcription)
    }

    @Test
    fun `boxes and folders toggle collapsed`() {
        val boxes = listOf(box("a"), box("b"))
        assertEquals(
            listOf(true, false),
            VoicemailRules.toggledBox(boxes, "a").map { it.collapsed }
        )

        val folded = VoicemailRules.toggledFolder(boxes, "b", "saved")
        assertEquals(listOf("saved"), folded[1].collapsedFolders)
        assertEquals(
            emptyList<String>(),
            VoicemailRules.toggledFolder(folded, "b", "saved")[1].collapsedFolders
        )
    }

    @Test
    fun `length is shown as minutes and seconds, with hours when needed`() {
        assertEquals("00:07", VoicemailRules.formatLength(6900))
        assertEquals("00:36", VoicemailRules.formatLength(35920))
        assertEquals("01:05", VoicemailRules.formatLength(65_000))
        assertEquals("1:00:01", VoicemailRules.formatLength(3_601_000))
        assertEquals("00:00", VoicemailRules.formatLength(-5))
    }

    @Test
    fun `audio is saved with the extension of its type`() {
        assertEquals("mp3", VoicemailRules.audioExtension("audio/mpeg"))
        assertEquals("wav", VoicemailRules.audioExtension("audio/x-wav"))
        assertEquals("m4a", VoicemailRules.audioExtension("audio/mp4"))
        assertEquals("mp3", VoicemailRules.audioExtension(null))
    }

    @Test
    fun `the licence counts any non-zero value, as the web client does`() {
        val licence = CustomerLicence(features = mapOf("a" to 1, "b" to 0, "c" to -1))
        assertTrue(licence.hasFeature("a"))
        assertFalse(licence.hasFeature("b"))
        assertTrue(
            "disabled (-1) still counts, as hasFeature on the web client",
            licence.hasFeature("c")
        )
        assertFalse(licence.hasFeature("missing"))
        assertFalse(CustomerLicence().hasFeature("a"))
    }

    private fun call(
        id: String,
        number: String,
        secondsBefore: Long,
        type: CallTypes = CallTypes.External,
        direction: Int = 2
    ) =
        CallHistoryItem(
            missedCall = false,
            answered = true,
            hasRecording = false,
            startTime = at(9).minusSeconds(secondsBefore),
            connectionId = id,
            callType = type,
            callDirection = direction,
            contactName = null,
            contactMatchType = null,
            hasContactMatch = false,
            calledUserName = null,
            calledUserNumber = null,
            callingUserName = null,
            callingUserNumber = if (type == CallTypes.Internal) number else null,
            routePathName = null,
            groupName = null,
            huntgroupName = null,
            documentId = id,
            isConference = false,
            pbxType = PbxType.Kazoo,
            interactionTags = emptyList(),
            cli = if (type == CallTypes.External) number else null
        )

    @Test
    fun `a voicemail matches the closest call from its number in the 90 seconds before it`() {
        val voicemail = VoicemailMessage(
            mediaId = "m",
            timestamp = at(9),
            callerIdNumber = "01332362900"
        )
        val history = listOf(
            call("too-early", "+441332362900", 91),
            call("closest", "+441332362900", 20),
            call("further", "+441332362900", 60),
            call("other-number", "+441332362901", 5),
            call("after", "+441332362900", -5)
        )
        assertEquals("closest", VoicemailRules.matchCall(voicemail, history, "GB"))
    }

    @Test
    fun `internal calls match on the calling user's number, if incoming`() {
        val voicemail = VoicemailMessage(mediaId = "m", timestamp = at(9), callerIdNumber = "1076")
        assertEquals(
            "in",
            VoicemailRules.matchCall(
                voicemail,
                listOf(call("in", "1076", 10, CallTypes.Internal, 2)),
                "GB"
            )
        )
        assertNull(
            VoicemailRules.matchCall(
                voicemail,
                listOf(call("out", "1076", 10, CallTypes.Internal, 3)),
                "GB"
            )
        )
        assertNull(
            "no caller",
            VoicemailRules.matchCall(
                VoicemailMessage(timestamp = at(9)),
                listOf(call("in", "1076", 10)),
                "GB"
            )
        )
    }
}
