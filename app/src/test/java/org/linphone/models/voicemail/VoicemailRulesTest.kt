package org.linphone.models.voicemail

import java.util.Locale
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
import org.threeten.bp.LocalDate
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
    fun `folders are new then saved then others, newest first`() {
        val groups = VoicemailRules.groupByFolder(
            listOf(
                message("old", hour = 8),
                message("archived", folder = "archive"),
                message("deleted", folder = "deleted"),
                message("kept", folder = "saved"),
                message("newest", hour = 11)
            )
        )
        assertEquals(listOf("new", "saved", "deleted", "archive"), groups.map { it.first })
        assertEquals(listOf("newest", "old"), groups[0].second.map { it.mediaId })
    }

    @Test
    fun `new is shown even when empty, saved only with messages in it`() {
        assertEquals(listOf("new"), VoicemailRules.groupByFolder(emptyList()).map { it.first })
        assertEquals(
            listOf("new"),
            VoicemailRules.groupByFolder(listOf(message("m1"))).map { it.first }
        )
        assertEquals(
            listOf("new", "saved"),
            VoicemailRules.groupByFolder(listOf(message("m1", folder = "saved"))).map { it.first }
        )
    }

    @Test
    fun `the list is the shown box's folders and messages, with no mailbox header`() {
        // As iOS (WI 36610): the mailbox is named in the bar above the list, with a picker of the others
        val rows = VoicemailRules.rows(box(messages = listOf(message("m1"))))

        assertEquals(
            listOf(
                VoicemailRow.FolderHeader("box1", "new", true, 1),
                VoicemailRow.Message("box1", message("m1"))
            ),
            rows
        )
        assertEquals(emptyList<VoicemailRow>(), VoicemailRules.rows(null))
    }

    @Test
    fun `collapsed folders hide their contents`() {
        val rows = VoicemailRules.rows(
            box("b", collapsedFolders = listOf("new"), messages = listOf(message("m2")))
        )

        assertEquals(listOf(VoicemailRow.FolderHeader("b", "new", false, 1)), rows)
    }

    @Test
    fun `the box shown is the one chosen while it's still there, else the first`() {
        val boxes = listOf(box("a"), box("b"))
        assertEquals("b", VoicemailRules.selectedBox(boxes, "b")?.box?.id)
        assertEquals("nothing chosen", "a", VoicemailRules.selectedBox(boxes, null)?.box?.id)
        assertEquals("chosen box gone", "a", VoicemailRules.selectedBox(boxes, "gone")?.box?.id)
        assertNull("no mailbox", VoicemailRules.selectedBox(emptyList(), "a"))
    }

    @Test
    fun `the mailbox bar names the box and number, then its new or message count`() {
        assertEquals("Sales · 2001", VoicemailRules.boxTitle(VoicemailBox(name = "Sales", number = "2001")))
        assertEquals("Sales", VoicemailRules.boxTitle(VoicemailBox(name = "Sales", number = " ")))
        assertEquals("2001", VoicemailRules.boxTitle(VoicemailBox(number = "2001")))

        val badge = { b: VoicemailBox -> VoicemailRules.boxBadge(b, { "$it new" }, { "$it messages" }) }
        assertEquals("2 new", badge(VoicemailBox(new = 2, totalMessages = 5)))
        assertEquals("5 messages", badge(VoicemailBox(new = 0, totalMessages = 5)))
        assertEquals("0 messages", badge(VoicemailBox(totalMessages = -1)))
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
    fun `folders toggle collapsed`() {
        val boxes = listOf(box("a"), box("b"))
        val folded = VoicemailRules.toggledFolder(boxes, "b", "saved")
        assertEquals(listOf("saved"), folded[1].collapsedFolders)
        assertEquals(
            emptyList<String>(),
            VoicemailRules.toggledFolder(folded, "b", "saved")[1].collapsedFolders
        )
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

    @Test
    fun `length is shown as minutes and seconds, with hours when needed`() {
        assertEquals("00:07", VoicemailRules.formatLength(6900))
        assertEquals("00:36", VoicemailRules.formatLength(35920))
        assertEquals("01:05", VoicemailRules.formatLength(65_000))
        assertEquals("1:00:01", VoicemailRules.formatLength(3_601_000))
        assertEquals("00:00", VoicemailRules.formatLength(-5))
    }

    @Test
    fun `the last line is the number then the length, as on iOS`() {
        assertEquals("07921 910119 · 00:10", VoicemailRules.messageSubtitle("07921 910119", 10_000))
        // No number to show
        assertEquals("00:10", VoicemailRules.messageSubtitle("", 10_000))
    }

    @Test
    fun `folder headings carry their message count, as on iOS`() {
        assertEquals("New · 2", VoicemailRules.folderHeading("New", 2))
        assertEquals("New · 0", VoicemailRules.folderHeading("New", 0))
        val rows = VoicemailRules.rows(
            box(messages = listOf(message("m1"), message("m2"), message("m3", folder = "saved")))
        )
        assertEquals(
            listOf(2, 1),
            rows.filterIsInstance<VoicemailRow.FolderHeader>().map { it.count }
        )
    }

    @Test
    fun `transcriptions are fetched newest first, each once`() {
        val fetched = VoicemailTranscription(text = "Call me back")
        val boxes = listOf(
            box("a", messages = listOf(message("old", hour = 8), message("done", hour = 12, transcription = fetched))),
            box("b", messages = listOf(message("newest", hour = 11), message("mid", hour = 10)))
        )

        assertEquals("b" to "newest", VoicemailRules.nextTranscriptionToFetch(boxes, emptySet())?.let { it.first to it.second.mediaId })
        assertEquals("b" to "mid", VoicemailRules.nextTranscriptionToFetch(boxes, setOf("newest"))?.let { it.first to it.second.mediaId })
        assertEquals("a" to "old", VoicemailRules.nextTranscriptionToFetch(boxes, setOf("newest", "mid"))?.let { it.first to it.second.mediaId })
        // An empty transcription has been fetched: there isn't one
        assertNull(VoicemailRules.nextTranscriptionToFetch(boxes, setOf("newest", "mid", "old")))
    }

    @Test
    fun `a message's day is Today, Yesterday, then the short date`() {
        // As the web client's voicemailDayOf: Today, Yesterday, then "5 Nov", with no time.
        val today = LocalDate.of(2026, 11, 10)
        val day = { y: Int, m: Int, d: Int ->
            VoicemailRules.formatDay(
                ZonedDateTime.of(y, m, d, 9, 30, 0, 0, ZoneOffset.UTC),
                today,
                "Today",
                "Yesterday",
                Locale.UK
            )
        }
        assertEquals("Today", day(2026, 11, 10))
        assertEquals("Yesterday", day(2026, 11, 9))
        assertEquals("8 Nov", day(2026, 11, 8))
        assertEquals("5 Nov", day(2026, 11, 5))
        assertEquals("30 Oct", day(2026, 10, 30))
        assertEquals("14 Dec", day(2025, 12, 14))
        assertEquals("", VoicemailRules.formatDay(null, today, "Today", "Yesterday", Locale.UK))
    }
}
