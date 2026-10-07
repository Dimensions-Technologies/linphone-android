package org.linphone.models.callsession

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.R
import org.linphone.models.CustomerLicence
import org.linphone.models.LicenceDisplayState
import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactItemModel
import org.linphone.models.contact.DirectoryFieldTypes
import org.linphone.models.contact.FieldDefinitionModel
import org.linphone.models.contact.FieldItemModel
import org.linphone.utils.GsonUtils
import org.threeten.bp.ZoneOffset

class CallDetailRulesTest {
    private val gson = GsonUtils.create()

    private fun <T> fixture(name: String, type: Class<T>): T =
        gson.fromJson(javaClass.getResource("/gateway/$name")!!.readText(), type)

    private val session = fixture("session.json", CallSessionResponse::class.java).data!!
    private val summary = fixture(
        "calltranscriptionsummaries.json",
        ConversationSummary::class.java
    )
    private val transcription = fixture(
        "calltranscriptions.json",
        ConversationTranscription::class.java
    )

    private fun segment(hasTrans: Int, answered: Boolean, recordingIds: List<String> = emptyList()) = CallSegment(
        answered = answered,
        conns = listOf(
            CallConnection(
                answered = answered,
                recording = RecordingIdList(recordingIds),
                analytic = CallSessionAnalytics(hasTrans)
            )
        )
    )

    @Test
    fun `the segment shown is the first transcribed, else the first answered, else the first`() {
        assertEquals(
            1,
            CallDetailRules.defaultSegmentIndex(
                CallSession(calls = listOf(segment(0, true), segment(1, true)))
            )
        )
        assertEquals(
            1,
            CallDetailRules.defaultSegmentIndex(
                CallSession(calls = listOf(segment(0, false), segment(0, true)))
            )
        )
        assertEquals(
            0,
            CallDetailRules.defaultSegmentIndex(CallSession(calls = listOf(segment(0, false))))
        )
        assertEquals(0, CallDetailRules.defaultSegmentIndex(CallSession()))
    }

    @Test
    fun `the recording analysed is the first of an answered connection`() {
        val call = CallSegment(
            conns = listOf(
                CallConnection(answered = false, recording = RecordingIdList(listOf("unanswered"))),
                CallConnection(answered = true, recording = RecordingIdList(listOf("a", "b")))
            )
        )
        assertEquals("a", CallDetailRules.bestRecordingId(call))
        assertNull(CallDetailRules.bestRecordingId(null))
        assertEquals("rec-1", CallDetailRules.bestRecordingId(session.calls!![0]))
    }

    @Test
    fun `a session is transcribed when a recorded connection has a transcription`() {
        assertTrue(CallDetailRules.hasTranscription(session))
        assertFalse(
            "transcribed but no recording",
            CallDetailRules.hasTranscription(CallSession(calls = listOf(segment(1, true))))
        )
    }

    @Test
    fun `tabs follow the permission, transcription, licences and contact`() {
        assertEquals(
            listOf(
                CallDetailTab.OVERVIEW,
                CallDetailTab.TRANSCRIPTION,
                CallDetailTab.DATA,
                CallDetailTab.TAGS,
                CallDetailTab.INFO
            ),
            CallDetailRules.visibleTabs(true, true, true, true, true)
        )
        assertEquals(
            listOf(CallDetailTab.OVERVIEW, CallDetailTab.DATA),
            CallDetailRules.visibleTabs(true, true, false, false, false)
        )
        assertEquals(
            "no permission to play",
            listOf(CallDetailTab.DATA),
            CallDetailRules.visibleTabs(false, true, true, false, false)
        )
        assertEquals(
            "not transcribed",
            listOf(CallDetailTab.DATA),
            CallDetailRules.visibleTabs(true, false, true, false, false)
        )
    }

    @Test
    fun `tags show unless both tagging and dispositions are hidden`() {
        assertFalse(CallDetailRules.showTags(null))
        assertFalse(
            CallDetailRules.showTags(
                CustomerLicence(features = mapOf(CustomerLicence.CALL_TAGGING to 0))
            )
        )
        assertTrue(
            CallDetailRules.showTags(
                CustomerLicence(features = mapOf(CustomerLicence.CALL_TAGGING to 1))
            )
        )
        assertTrue(
            "disabled still shows",
            CallDetailRules.showTags(
                CustomerLicence(features = mapOf(CustomerLicence.DISPOSITIONS to -1))
            )
        )
    }

    @Test
    fun `the licence's display states`() {
        val licence = CustomerLicence(features = mapOf("a" to 1, "b" to -1, "c" to 0))
        assertEquals(LicenceDisplayState.ENABLED, licence.displayState("a"))
        assertEquals(LicenceDisplayState.DISABLED, licence.displayState("b"))
        assertEquals(LicenceDisplayState.HIDDEN, licence.displayState("c"))
        assertEquals(LicenceDisplayState.HIDDEN, licence.displayState("missing"))
    }

    @Test
    fun `the overview shows the summary and the analysed aspects`() {
        val rows = CallDetailRules.overviewRows(
            Conversation(summary, transcription),
            false,
            true,
            LicenceDisplayState.ENABLED
        )

        assertEquals(
            listOf(
                DetailRow.Card(
                    UiText.Res(R.string.call_detail_call_summary),
                    "The customer asked about an order.\n\nA refund was agreed."
                ),
                DetailRow.Text(UiText.Literal("The customer asked about an order.")),
                DetailRow.Text(UiText.Literal("A refund was agreed.")),
                DetailRow.Card(UiText.Res(R.string.call_detail_issue), "Order arrived damaged"),
                DetailRow.Text(UiText.Literal("Order arrived damaged")),
                DetailRow.Card(
                    UiText.Res(R.string.call_detail_follow_up),
                    "Send a refund\nReturn the item"
                ),
                DetailRow.SubHeader(UiText.Res(R.string.call_detail_speaker_user)),
                DetailRow.Text(UiText.Literal("Send a refund")),
                DetailRow.SubHeader(UiText.Res(R.string.call_detail_speaker_customer)),
                DetailRow.Text(UiText.Literal("Return the item")),
                DetailRow.Card(UiText.Res(R.string.call_detail_resolution), "Refund agreed"),
                DetailRow.Text(UiText.Literal("Refund agreed"))
            ),
            rows
        )
    }

    @Test
    fun `the overview explains a missing or unfinished summary`() {
        assertEquals(
            DetailRow.Loading,
            CallDetailRules.overviewRows(null, true, true, LicenceDisplayState.HIDDEN)[1]
        )
        val running = ConversationSummary(summaryAnalysis = SummaryAnalysis(state = "Running"))
        assertEquals(
            DetailRow.Text(UiText.Res(R.string.call_detail_analysis_running), TextStyle.ERROR),
            CallDetailRules.overviewRows(
                Conversation(running),
                false,
                true,
                LicenceDisplayState.HIDDEN
            )[1]
        )
        assertEquals(
            DetailRow.Text(UiText.Literal("Call not transcribed"), TextStyle.ERROR),
            CallDetailRules.overviewRows(
                Conversation(error = "Call not transcribed"),
                false,
                true,
                LicenceDisplayState.HIDDEN
            )[1]
        )
        assertEquals(
            DetailRow.Text(UiText.Res(R.string.call_detail_no_summary), TextStyle.MUTED),
            CallDetailRules.overviewRows(Conversation(), false, true, LicenceDisplayState.HIDDEN)[1]
        )
        assertTrue(
            "no transcription licence",
            CallDetailRules.overviewRows(
                Conversation(summary),
                false,
                false,
                LicenceDisplayState.HIDDEN
            ).isEmpty()
        )
    }

    @Test
    fun `without the analytics licence the aspects are greyed out`() {
        val rows = CallDetailRules.overviewRows(
            Conversation(summary),
            false,
            false,
            LicenceDisplayState.DISABLED
        )
        assertEquals(DetailRow.Card(UiText.Res(R.string.call_detail_issue), greyed = true), rows[0])
        assertEquals(
            DetailRow.Text(
                UiText.Res(R.string.call_detail_analytics_licence),
                TextStyle.MUTED,
                greyed = true
            ),
            rows[1]
        )
    }

    @Test
    fun `transcript channels are labelled by the speaker map`() {
        val call = session.calls!![0]
        // Incoming: the user is the destination (the hunt group's name), the customer the origin
        assertEquals(
            listOf("Sales", "01332 362900"),
            CallDetailRules.channelLabels(call, summary.speakers!!.map, "GB")
        )
        assertEquals(
            "no map: origin first",
            listOf("01332 362900", "Sales"),
            CallDetailRules.channelLabels(call, null, "GB")
        )
    }

    @Test
    fun `the transcript groups each speaker's run and masks personal information`() {
        val lines = CallDetailRules.transcriptLines(transcription, listOf("Agent", "Caller"))

        assertEquals(3, lines.size)
        assertEquals("Agent", lines[0].label)
        assertEquals(Sentiment.POSITIVE, lines[0].sentiment)
        assertFalse(lines[0].isLowConfidence)

        assertEquals("Caller", lines[1].label)
        assertTrue(lines[1].isLowConfidence)
        assertEquals(
            listOf(
                TextPart("My card "),
                TextPart("*********", UiText.Res(R.string.call_detail_pii_credit_card)),
                TextPart(" was charged twice")
            ),
            lines[1].parts
        )

        assertEquals("after the silence, a new run with its own label", "Agent", lines[2].label)
        assertEquals(12.0, lines[2].start!!, 0.0)
        assertEquals(3, lines[2].index)
    }

    @Test
    fun `overlapping personal information is masked once`() {
        val parts = CallDetailRules.maskPii(
            "call 0123 456 789 now",
            listOf(
                PiiItem(category = "PHONE_NUMBER", start = 5, end = 17),
                PiiItem(category = "PERSON", start = 9, end = 12)
            )
        )
        assertEquals(
            listOf(
                TextPart("call "),
                TextPart("************", UiText.Res(R.string.call_detail_pii_phone_number)),
                TextPart(" now")
            ),
            parts
        )
        assertEquals(UiText.Literal("NEW_THING"), CallDetailRules.piiCategoryText("NEW_THING"))
    }

    @Test
    fun `the paragraph playing is found by position`() {
        assertEquals(0, CallDetailRules.highlightedIndex(transcription, 2.0))
        assertEquals(1, CallDetailRules.highlightedIndex(transcription, 4.5))
        assertEquals(3, CallDetailRules.highlightedIndex(transcription, 15.0))
        assertEquals(-1, CallDetailRules.highlightedIndex(transcription, 30.0))
        assertEquals(-1, CallDetailRules.highlightedIndex(null, 1.0))
    }

    @Test
    fun `the transcription tab explains a missing transcript`() {
        assertEquals(
            listOf(DetailRow.Loading),
            CallDetailRules.transcriptionRows(null, true, emptyList())
        )
        assertEquals(
            listOf(
                DetailRow.Text(UiText.Res(R.string.call_detail_analysis_failed), TextStyle.ERROR)
            ),
            CallDetailRules.transcriptionRows(
                Conversation(
                    ConversationSummary(transcriptionStats = TranscribeAction(state = "Failed"))
                ),
                false,
                emptyList()
            )
        )
        assertEquals(
            listOf(
                DetailRow.Text(UiText.Res(R.string.call_detail_no_transcription), TextStyle.MUTED)
            ),
            CallDetailRules.transcriptionRows(Conversation(summary), false, emptyList())
        )
    }

    @Test
    fun `the transcript copies as text`() {
        val lines = CallDetailRules.transcriptLines(transcription, listOf("Agent", "Caller"))
        val text = CallDetailRules.transcriptText(lines, "Call Transcription", "(low confidence)")
        assertTrue(text.startsWith("Call Transcription\n\nAgent 00:00\nHello, how can I help?"))
        assertTrue(
            text.contains("Caller 00:04\nMy card ********* was charged twice (low confidence)")
        )
    }

    @Test
    fun `the timeline has a row per speaker coloured by sentiment`() {
        val rows = CallDetailRules.timelineRows(transcription, listOf("Agent", "Caller"))

        assertEquals(listOf("Agent", "Caller"), rows.map { it.label })
        assertEquals(
            TimelineFragment(0f, 0.2f, Sentiment.POSITIVE, "Hello, how can I help?"),
            rows[0].fragments[0]
        )
        assertEquals(0.6f, rows[0].fragments[1].offset, 0.001f)
        assertEquals(Sentiment.NEGATIVE, rows[1].fragments.single().sentiment)
        assertTrue(CallDetailRules.timelineRows(null, emptyList()).isEmpty())
    }

    @Test
    fun `call data lists the segment's times, numbers and route`() {
        val rows = CallDetailRules.callDataRows(
            session.calls!![0],
            "sess-1",
            "GB",
            ZoneOffset.UTC,
            Locale.UK
        )
            .map { it as DetailRow.Field }
            .associate { (it.label as UiText.Res).id to it.value }

        assertEquals(UiText.Literal("00:00:07"), rows[R.string.call_detail_ring_time])
        assertEquals(UiText.Literal("00:00:00"), rows[R.string.call_detail_hold_time])
        assertEquals(UiText.Literal("00:02:07"), rows[R.string.call_detail_duration])
        assertEquals(UiText.Literal("Acme Ltd (01332 362900)"), rows[R.string.call_detail_cli])
        assertEquals(UiText.Literal("0330 088 9055"), rows[R.string.call_detail_did])
        assertEquals(UiText.Literal("Sales (500)"), rows[R.string.call_detail_queue_name])
        assertEquals(
            UiText.Res(R.string.call_detail_direction_incoming),
            rows[R.string.call_detail_direction]
        )
        assertEquals(UiText.Literal("sess-1"), rows[R.string.call_detail_document_id])
        assertEquals(UiText.Literal("NORMAL_CLEARING"), rows[R.string.call_detail_hangup_cause])
        assertFalse("no billing code", rows.containsKey(R.string.call_detail_billing_code))
        assertTrue((rows[R.string.call_detail_start_time] as UiText.Literal).text.contains("2024"))
    }

    @Test
    fun `numbers show the name and number, nationally in the PBX's country`() {
        assertEquals(
            "Acme (01332 362900), +1 212-555-0100",
            CallDetailRules.externalNumbersText(
                listOf(
                    ExternalNumber(num = "+441332362900", name = "Acme"),
                    ExternalNumber(num = "+12125550100")
                ),
                "GB"
            )
        )
        assertEquals(
            UiText.Res(R.string.call_detail_unknown),
            CallDetailRules.externalNumbers(emptyList(), "GB")
        )
    }

    @Test
    fun `internal calls are Internal whatever their direction`() {
        assertEquals(
            R.string.call_detail_direction_internal,
            CallDetailRules.directionText(CallSegment(type = 1, dir = 3))
        )
        assertEquals(
            R.string.call_detail_direction_outgoing,
            CallDetailRules.directionText(CallSegment(type = 2, dir = 3))
        )
        assertNull(CallDetailRules.directionText(CallSegment(type = 2, dir = 0)))
    }

    @Test
    fun `durations format as the web client's duration pipe`() {
        assertEquals("00:04", CallDetailRules.formatDuration(4.6))
        assertEquals("01:02:03", CallDetailRules.formatDuration(3723.0))
        assertEquals("00:00", CallDetailRules.formatDuration(-1.0))
    }

    @Test
    fun `tags are named by their definitions, in order, with links`() {
        val definitions = fixture("interactiontags.json", Array<InteractionTag>::class.java).toList()

        val rows = CallDetailRules.tagRows(session.tags, definitions, ZoneOffset.UTC, Locale.UK).map { it as DetailRow.Field }

        assertEquals(UiText.Literal("Resolved"), rows[0].label)
        assertEquals(UiText.Res(R.string.call_detail_yes), rows[0].value)
        assertNull(rows[0].link)
        assertEquals(UiText.Literal("Support ticket"), rows[1].label)
        assertEquals(UiText.Literal("1234"), rows[1].value)
        assertEquals("https://tickets.example.com/1234", rows[1].link)
        assertEquals("09:23:10", rows[1].note)
    }

    private val directory = ContactDirectoryModel(
        id = "dir-1",
        name = "Customers",
        fields = listOf(
            FieldDefinitionModel(
                id = "name",
                name = "Full name",
                definitionType = DirectoryFieldTypes.CONTACT_NAME
            ),
            FieldDefinitionModel(
                id = "phone1",
                name = "Phone",
                definitionType = DirectoryFieldTypes.PHONE
            ),
            FieldDefinitionModel(id = "avatar", name = "Avatar")
        ),
        userRoleAssociations = emptyMap()
    )
    private val contact = ContactItemModel(
        id = "c1",
        directoryId = "dir-1",
        fields = listOf(
            FieldItemModel("name", "Jane Customer"),
            FieldItemModel("phone1", "01332 362900"),
            FieldItemModel("avatar", "data"),
            FieldItemModel("email", "")
        )
    )

    @Test
    fun `the other party is found in the directories on an incoming call`() {
        val found = CallDetailRules.contactFor(
            session,
            session.calls!![0],
            listOf(directory to listOf(contact)),
            "GB"
        )
        assertEquals(contact, found?.second)
        assertNull(
            "internal sessions have no contact",
            CallDetailRules.contactFor(
                session.copy(dir = 1),
                session.calls!![0],
                listOf(directory to listOf(contact)),
                "GB"
            )
        )
    }

    @Test
    fun `contact info lists the filled-in fields under their names`() {
        assertEquals(
            listOf(
                DetailRow.Field(UiText.Literal("Full name"), UiText.Literal("Jane Customer")),
                DetailRow.Field(UiText.Literal("Phone"), UiText.Literal("01332 362900"))
            ),
            CallDetailRules.infoRows(directory, contact)
        )
    }
}
