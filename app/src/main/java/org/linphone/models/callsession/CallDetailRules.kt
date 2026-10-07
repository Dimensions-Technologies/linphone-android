package org.linphone.models.callsession

import androidx.annotation.StringRes
import java.util.Locale
import org.linphone.R
import org.linphone.models.CustomerLicence
import org.linphone.models.LicenceDisplayState
import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.contact.ContactItemModel
import org.threeten.bp.Instant
import org.threeten.bp.ZoneId
import org.threeten.bp.ZoneOffset
import org.threeten.bp.format.DateTimeFormatter
import org.threeten.bp.format.FormatStyle

/** Text for the screen: literal, or a string resource with its arguments. */
sealed class UiText {
    data class Literal(val text: String) : UiText()
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText()
}

enum class CallDetailTab { OVERVIEW, TRANSCRIPTION, DATA, TAGS, INFO }

enum class TextStyle { NORMAL, ERROR, MUTED }

/** A row of a tab of the call detail page. */
sealed class DetailRow {
    /** A card's title, with the text its copy button copies (if it has one). */
    data class Card(val title: UiText, val copyText: String? = null, val greyed: Boolean = false) : DetailRow()
    data class SubHeader(val title: UiText) : DetailRow()
    data class Text(
        val text: UiText,
        val style: TextStyle = TextStyle.NORMAL,
        val greyed: Boolean = false
    ) : DetailRow()
    data class Field(
        val label: UiText,
        val value: UiText,
        val link: String? = null,
        val note: String? = null
    ) : DetailRow()
    object Loading : DetailRow()
    data class Transcript(val line: TranscriptLine) : DetailRow()
}

/** A piece of transcript text: plain, or personal information masked out under its category. */
data class TextPart(val text: String, val piiCategory: UiText? = null)

enum class Sentiment { POSITIVE, NEUTRAL, NEGATIVE }

/** One paragraph of the transcript; the first of a speaker's run carries their label and start. */
data class TranscriptLine(
    val channel: Int,
    val label: String?,
    val start: Double?,
    val parts: List<TextPart>,
    val sentiment: Sentiment,
    val sentimentLabel: UiText?,
    val sentimentScore: Double?,
    val isLowConfidence: Boolean,
    val index: Int?
) {
    val isUser: Boolean get() = channel == 0
    val plainText: String get() = parts.joinToString("") { it.text }
}

/** A coloured stretch of a channel's row in the timeline, as fractions of the whole. */
data class TimelineFragment(
    val offset: Float,
    val width: Float,
    val sentiment: Sentiment,
    val text: String?
)

data class TimelineRow(val label: String, val fragments: List<TimelineFragment>)

/**
 * The call detail page's rules, ported from the web client's conversation components
 * (conversation, conversation-summary, conversation-transcription, conversation-timeline,
 * call-details and call-session-tag-data).
 */
object CallDetailRules {
    const val RECORDING_PERMISSION = "recording.play"

    // Below this the paragraph is marked as likely to be wrong
    private const val LOW_CONFIDENCE = 0.4

    // Session and segments

    /** The segment shown: the first with a transcription, else the first answered, else the first. */
    fun defaultSegmentIndex(session: CallSession): Int {
        val calls = session.calls.orEmpty()
        var index = calls.indexOfFirst { call -> call.conns.orEmpty().any { it.analytic?.hasTrans == 1 } }
        if (index == -1) index = calls.indexOfFirst { it.answered }
        return index.coerceAtLeast(0)
    }

    /** The recording to analyse for a segment: the first of its answered connections. */
    fun bestRecordingId(segment: CallSegment?): String? = segment?.conns.orEmpty()
        .filter { it.answered }
        .flatMap { it.recording?.recordingIds.orEmpty() }
        .firstOrNull()

    /** Whether any recording in the session has been transcribed. */
    fun hasTranscription(session: CallSession): Boolean = session.calls.orEmpty().any { call ->
        call.conns.orEmpty().any { !it.recording?.recordingIds.isNullOrEmpty() && it.analytic?.hasTrans == 1 }
    }

    // Tabs

    /** Tags are shown unless both call tagging and dispositions are hidden by the licence. */
    fun showTags(licence: CustomerLicence?): Boolean = licence != null && !(
        licence.displayState(CustomerLicence.CALL_TAGGING) == LicenceDisplayState.HIDDEN &&
            licence.displayState(CustomerLicence.DISPOSITIONS) == LicenceDisplayState.HIDDEN
        )

    fun visibleTabs(
        canPlayRecordings: Boolean,
        hasTranscription: Boolean,
        transcriptionLicensed: Boolean,
        showTags: Boolean,
        hasContact: Boolean
    ): List<CallDetailTab> {
        val transcriptionFeatures = canPlayRecordings && hasTranscription
        return listOfNotNull(
            CallDetailTab.OVERVIEW.takeIf { transcriptionFeatures },
            CallDetailTab.TRANSCRIPTION.takeIf { transcriptionFeatures && transcriptionLicensed },
            CallDetailTab.DATA,
            CallDetailTab.TAGS.takeIf { showTags },
            CallDetailTab.INFO.takeIf { hasContact }
        )
    }

    // Overview

    fun analysisStateText(state: String?): UiText? = when (state) {
        "Credit", "NoCredit" -> UiText.Res(R.string.call_detail_analysis_credit)
        "Failed" -> UiText.Res(R.string.call_detail_analysis_failed)
        "Running" -> UiText.Res(R.string.call_detail_analysis_running)
        else -> null
    }

    fun overviewRows(
        conversation: Conversation?,
        isLoading: Boolean,
        transcriptionLicensed: Boolean,
        analyticsState: LicenceDisplayState
    ): List<DetailRow> {
        val rows = mutableListOf<DetailRow>()
        val summary = conversation?.summary

        if (transcriptionLicensed) {
            val summaries = summary?.summaryAnalysis?.summaries.orEmpty()
            rows.add(
                DetailRow.Card(
                    UiText.Res(R.string.call_detail_call_summary),
                    summaries.joinToString("\n\n").ifEmpty { null }
                )
            )
            val analysis = summary?.summaryAnalysis
            when {
                isLoading -> rows.add(DetailRow.Loading)
                analysis != null && analysis.state != "Succeeded" ->
                    rows.add(
                        DetailRow.Text(
                            analysisStateText(analysis.state) ?: UiText.Literal(""),
                            TextStyle.ERROR
                        )
                    )
                summaries.isNotEmpty() -> summaries.forEach {
                    rows.add(
                        DetailRow.Text(UiText.Literal(it))
                    )
                }
                conversation?.error != null -> rows.add(
                    DetailRow.Text(UiText.Literal(conversation.error), TextStyle.ERROR)
                )
                else -> rows.add(
                    DetailRow.Text(UiText.Res(R.string.call_detail_no_summary), TextStyle.MUTED)
                )
            }
        }

        if (analyticsState == LicenceDisplayState.HIDDEN) return rows
        val greyed = analyticsState == LicenceDisplayState.DISABLED
        val aspects = summary?.language?.aspects.orEmpty()

        fun single(type: String, @StringRes title: Int) {
            val aspect = aspects.firstOrNull { it.type == type } ?: return
            if (greyed) {
                rows.add(DetailRow.Card(UiText.Res(title), greyed = true))
                rows.add(
                    DetailRow.Text(
                        UiText.Res(R.string.call_detail_analytics_licence),
                        TextStyle.MUTED,
                        greyed = true
                    )
                )
            } else {
                rows.add(DetailRow.Card(UiText.Res(title), aspect.value))
                rows.add(DetailRow.Text(UiText.Literal(aspect.value.orEmpty())))
            }
        }

        single("issue", R.string.call_detail_issue)

        val followUps = aspects.filter { it.type == "followUp" }
        if (followUps.isNotEmpty()) {
            val map = summary?.speakers?.map.orEmpty()
            val userIndex = speakerIndex(map, "user")
            val customerIndex = speakerIndex(map, "customer")
            val user = followUps.filter { it.speaker == userIndex }
            val customer = followUps.filter { it.speaker == customerIndex }
            rows.add(
                DetailRow.Card(
                    UiText.Res(R.string.call_detail_follow_up),
                    followUps.mapNotNull { it.value }.joinToString("\n"),
                    greyed
                )
            )
            if (user.isNotEmpty()) {
                rows.add(DetailRow.SubHeader(UiText.Res(R.string.call_detail_speaker_user)))
                user.forEach {
                    rows.add(
                        DetailRow.Text(UiText.Literal(it.value.orEmpty()), greyed = greyed)
                    )
                }
            }
            if (customer.isNotEmpty()) {
                rows.add(DetailRow.SubHeader(UiText.Res(R.string.call_detail_speaker_customer)))
                customer.forEach {
                    rows.add(
                        DetailRow.Text(UiText.Literal(it.value.orEmpty()), greyed = greyed)
                    )
                }
            }
        }

        single("resolution", R.string.call_detail_resolution)
        return rows
    }

    // The channel the speaker map gives a role, or 0
    private fun speakerIndex(map: Map<String, String>, type: String): Int =
        map.entries.firstOrNull { it.value == type }?.key?.toIntOrNull() ?: 0

    // Transcription

    /** Each channel's label: the segment's origin and destination, placed by the speaker map. */
    fun channelLabels(segment: CallSegment?, speakers: Map<String, String>?, countryCode: String): List<String> {
        if (segment == null) return emptyList()
        val origin = CallLabelFormatter.originLabel(segment, countryCode)
        val dest = CallLabelFormatter.destinationLabel(segment, countryCode)
        if (speakers.isNullOrEmpty()) return listOf(origin, dest)

        val outgoing = segment.dir == SessionCallDirections.OUTGOING
        val labels = mapOf(
            "user" to if (outgoing) origin else dest,
            "customer" to if (outgoing) dest else origin
        )
        return listOf(labels[speakers["0"]].orEmpty(), labels[speakers["1"]].orEmpty())
    }

    /**
     * The transcript, a paragraph per speaker segment, with each speaker's run headed by their label
     * and start time. A silence where the speaker changes ends the run.
     */
    fun transcriptLines(
        transcription: ConversationTranscription?,
        channelLabels: List<String>,
        unknownLabel: String = "Unknown"
    ): List<TranscriptLine> {
        val lines = mutableListOf<TranscriptLine>()
        var currentSpeaker: Int? = null
        for (item in transcription?.timeline.orEmpty()) {
            val isSpeech = item.type == "SpeakerSegment"
            if (item.speaker != currentSpeaker) {
                if (!isSpeech) {
                    currentSpeaker = null
                    continue
                }
                currentSpeaker = item.speaker
                lines.add(
                    line(
                        item,
                        channelLabels.getOrNull(item.speaker)?.takeIf { it.isNotEmpty() } ?: unknownLabel,
                        item.start
                    )
                )
            } else if (isSpeech) {
                // A silence within a run adds nothing to show
                lines.add(line(item, null, null))
            }
        }
        return lines
    }

    private fun line(item: TranscriptionTimelineItem, label: String?, start: Double?): TranscriptLine {
        val sentiment = sentimentOf(item.sentiment?.scoreLabel)
        return TranscriptLine(
            channel = item.speaker,
            label = label,
            start = start,
            parts = maskPii(item.text.orEmpty(), item.pii?.piiItems.orEmpty()),
            sentiment = sentiment,
            sentimentLabel = item.sentiment?.scoreLabel?.let { sentimentText(sentiment) },
            sentimentScore = item.sentiment?.score,
            isLowConfidence = (item.confidence ?: 0.0) < LOW_CONFIDENCE,
            index = item.index
        )
    }

    fun sentimentOf(label: String?): Sentiment = when (label) {
        "Positive" -> Sentiment.POSITIVE
        "Negative" -> Sentiment.NEGATIVE
        else -> Sentiment.NEUTRAL
    }

    private fun sentimentText(sentiment: Sentiment) = UiText.Res(
        when (sentiment) {
            Sentiment.POSITIVE -> R.string.call_detail_sentiment_positive
            Sentiment.NEGATIVE -> R.string.call_detail_sentiment_negative
            Sentiment.NEUTRAL -> R.string.call_detail_sentiment_neutral
        }
    )

    /** The text with each piece of personal information replaced by asterisks, overlaps dropped. */
    fun maskPii(text: String, items: List<PiiItem>): List<TextPart> {
        val kept = mutableListOf<PiiItem>()
        for (item in items.sortedBy { it.start }) {
            if (kept.none { item.start < it.end }) kept.add(item)
        }
        val parts = mutableListOf<TextPart>()
        var position = 0
        for (item in kept) {
            val start = item.start.coerceIn(position, text.length)
            val end = item.end.coerceIn(start, text.length)
            if (start > position) parts.add(TextPart(text.substring(position, start)))
            parts.add(TextPart("*".repeat(end - start), piiCategoryText(item.category)))
            position = end
        }
        if (position < text.length) parts.add(TextPart(text.substring(position)))
        return parts
    }

    fun piiCategoryText(category: String?): UiText = when (category) {
        "CREDIT_CARD" -> UiText.Res(R.string.call_detail_pii_credit_card)
        "CRYPTO" -> UiText.Res(R.string.call_detail_pii_crypto)
        "DATE_TIME" -> UiText.Res(R.string.call_detail_pii_date_time)
        "EMAIL_ADDRESS" -> UiText.Res(R.string.call_detail_pii_email)
        "IBAN_CODE" -> UiText.Res(R.string.call_detail_pii_iban)
        "IP_ADDRESS" -> UiText.Res(R.string.call_detail_pii_ip_address)
        "LOCATION" -> UiText.Res(R.string.call_detail_pii_location)
        "PERSON" -> UiText.Res(R.string.call_detail_pii_person)
        "PHONE_NUMBER" -> UiText.Res(R.string.call_detail_pii_phone_number)
        "MEDICAL_LICENSE" -> UiText.Res(R.string.call_detail_pii_medical_license)
        "URL" -> UiText.Res(R.string.call_detail_pii_url)
        "US_BANK_NUMBER" -> UiText.Res(R.string.call_detail_pii_bank_number)
        "US_DRIVER_LICENSE" -> UiText.Res(R.string.call_detail_pii_driver_license)
        "US_ITIN" -> UiText.Res(R.string.call_detail_pii_itin)
        "US_PASSPORT" -> UiText.Res(R.string.call_detail_pii_passport)
        "US_SSN" -> UiText.Res(R.string.call_detail_pii_ssn)
        "UK_NHS" -> UiText.Res(R.string.call_detail_pii_nhs)
        else -> UiText.Literal(category.orEmpty())
    }

    /** Rows of the Transcription tab, the text of its copy button, and why there's no transcript. */
    fun transcriptionRows(
        conversation: Conversation?,
        isLoading: Boolean,
        lines: List<TranscriptLine>
    ): List<DetailRow> {
        if (isLoading) return listOf(DetailRow.Loading)
        val stats = conversation?.summary?.transcriptionStats
        if (stats?.state != null && stats.state != "Succeeded") {
            return listOf(
                DetailRow.Text(
                    analysisStateText(stats.state) ?: UiText.Literal(""),
                    TextStyle.ERROR
                )
            )
        }
        if (conversation?.transcription == null) {
            return listOf(
                conversation?.error?.let { DetailRow.Text(UiText.Literal(it), TextStyle.ERROR) }
                    ?: DetailRow.Text(
                        UiText.Res(R.string.call_detail_no_transcription),
                        TextStyle.MUTED
                    )
            )
        }
        return lines.map { DetailRow.Transcript(it) }
    }

    /** The transcript as plain text, for the clipboard. */
    fun transcriptText(lines: List<TranscriptLine>, title: String, lowConfidence: String): String {
        val sb = StringBuilder(title)
        for (line in lines) {
            if (line.label != null) {
                sb.append("\n\n").append(line.label).append(" ").append(
                    formatDuration(line.start ?: 0.0)
                )
            }
            sb.append("\n").append(line.plainText)
            if (line.isLowConfidence) sb.append(" ").append(lowConfidence)
        }
        return sb.toString()
    }

    /**
     * The index (the segment's idx) of the paragraph being played at [position] seconds, or -1. As
     * on the web client, the timeline's array index is compared with each paragraph's idx.
     */
    fun highlightedIndex(transcription: ConversationTranscription?, position: Double): Int =
        transcription?.timeline.orEmpty().indexOfFirst { it.start <= position && position <= it.end }

    // Timeline

    /** A row per speaker, coloured by the sentiment of each thing they said. */
    fun timelineRows(transcription: ConversationTranscription?, channelLabels: List<String>): List<TimelineRow> {
        val timeline = transcription?.timeline.orEmpty()
        val speech = timeline.filter { it.type == "SpeakerSegment" }
        if (speech.isEmpty()) return emptyList()
        val duration = (timeline.maxOf { it.end } - timeline.minOf { it.start }).takeIf { it > 0 } ?: return emptyList()

        return speech.groupBy { it.speaker }.entries.sortedBy { it.key }.map { (speaker, items) ->
            TimelineRow(
                channelLabels.getOrNull(speaker).orEmpty(),
                items.map {
                    TimelineFragment(
                        (it.start / duration).toFloat(),
                        ((it.end - it.start) / duration).toFloat(),
                        sentimentOf(it.sentiment?.scoreLabel),
                        it.text
                    )
                }
            )
        }
    }

    // Call data

    fun callDataRows(
        segment: CallSegment?,
        sessionId: String?,
        countryCode: String,
        zone: ZoneId,
        locale: Locale
    ): List<DetailRow> {
        if (segment == null) return emptyList()
        val rows = mutableListOf<DetailRow>()
        fun field(@StringRes label: Int, value: String) = rows.add(
            DetailRow.Field(UiText.Res(label), UiText.Literal(value))
        )

        segment.start?.let {
            field(
                R.string.call_detail_start_time,
                formatDateTime(it, zone, locale)
            )
        }
        segment.answer?.let {
            field(
                R.string.call_detail_answer_time,
                formatDateTime(it, zone, locale)
            )
        }
        segment.end?.let { field(R.string.call_detail_end_time, formatDateTime(it, zone, locale)) }
        segment.ring?.let { field(R.string.call_detail_ring_time, formatClock(it)) }
        segment.talk?.let { field(R.string.call_detail_talk_time, formatClock(it)) }
        segment.hold?.let { field(R.string.call_detail_hold_time, formatClock(it)) }
        segment.duration?.let { field(R.string.call_detail_duration, formatClock(it)) }
        if (countryCode.isNotEmpty()) {
            segment.cli?.let {
                rows.add(
                    DetailRow.Field(
                        UiText.Res(R.string.call_detail_cli),
                        externalNumbers(it, countryCode)
                    )
                )
            }
            segment.ddi?.let {
                rows.add(
                    DetailRow.Field(
                        UiText.Res(R.string.call_detail_did),
                        externalNumbers(it, countryCode)
                    )
                )
            }
        }
        segment.hgName?.takeIf { it.isNotEmpty() }?.let { name ->
            field(
                R.string.call_detail_queue_name,
                if (segment.hgNum.isNullOrEmpty()) name else "$name (${segment.hgNum})"
            )
        }
        rows.add(
            DetailRow.Field(
                UiText.Res(R.string.call_detail_direction),
                directionText(segment)?.let { UiText.Res(it) } ?: UiText.Literal("")
            )
        )
        segment.hangupCause?.takeIf { it.isNotEmpty() }?.let {
            field(
                R.string.call_detail_hangup_cause,
                it
            )
        }
        segment.routePathName?.takeIf { it.isNotEmpty() }?.let {
            field(
                R.string.call_detail_route_path_name,
                it
            )
        }
        segment.routePathId?.takeIf { it.isNotEmpty() }?.let {
            field(
                R.string.call_detail_route_path_number,
                it
            )
        }
        sessionId?.takeIf { it.isNotEmpty() }?.let { field(R.string.call_detail_document_id, it) }
        segment.bill?.takeIf { it.isNotEmpty() }?.let { field(R.string.call_detail_billing_code, it) }
        return rows
    }

    /** The direction's label: Internal for internal calls, else by direction. */
    @StringRes
    fun directionText(segment: CallSegment): Int? = if (segment.type == SessionCallTypes.INTERNAL) {
        R.string.call_detail_direction_internal
    } else {
        when (segment.dir) {
            SessionCallDirections.INCOMING -> R.string.call_detail_direction_incoming
            SessionCallDirections.OUTGOING -> R.string.call_detail_direction_outgoing
            SessionCallDirections.BOTH -> R.string.call_detail_direction_both
            else -> null
        }
    }

    /** "name (number)", the name or the number, for each; Unknown if there are none. */
    fun externalNumbers(numbers: List<ExternalNumber>, countryCode: String): UiText {
        if (numbers.isEmpty()) return UiText.Res(R.string.call_detail_unknown)
        return UiText.Literal(externalNumbersText(numbers, countryCode))
    }

    fun externalNumbersText(numbers: List<ExternalNumber>, countryCode: String): String {
        return numbers.joinToString(", ") {
            val number = it.num?.takeIf { n -> n.isNotEmpty() }?.let { n ->
                PhoneFormat.format(
                    n,
                    countryCode
                )
            }
            val name = it.name?.takeIf { n -> n.isNotEmpty() }
            when {
                name != null && number != null -> "$name ($number)"
                else -> name ?: number.orEmpty()
            }
        }
    }

    /** A date and time (seconds from the epoch) in the medium style; blank for 0. */
    fun formatDateTime(seconds: Double, zone: ZoneId, locale: Locale): String {
        if (seconds == 0.0) return ""
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(zone))
    }

    fun formatTime(seconds: Double, zone: ZoneId, locale: Locale): String {
        if (seconds == 0.0) return ""
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(zone))
    }

    /** A duration in seconds as HH:mm:ss. */
    fun formatClock(seconds: Double): String {
        if (seconds <= 0) return "00:00:00"
        return DateTimeFormatter.ofPattern("HH:mm:ss")
            .format(Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(ZoneOffset.UTC))
    }

    /** A position in seconds as [h:]mm:ss, as the web client's duration pipe. */
    fun formatDuration(seconds: Double): String {
        var s = seconds.toInt().coerceAtLeast(0)
        val days = s / 86400
        s %= 86400
        val hours = s / 3600
        s %= 3600
        val pad = { v: Int -> v.toString().padStart(2, '0') }
        val dayString = if (days > 0) "${days}d " else ""
        val hourString = if (hours > 0) "${pad(hours)}:" else ""
        return "$dayString$hourString${pad(s / 60)}:${pad(s % 60)}"
    }

    // Tags

    fun tagRows(
        tags: Map<String, InteractionTagItem>?,
        definitions: List<InteractionTag>,
        zone: ZoneId,
        locale: Locale
    ): List<DetailRow> = tags.orEmpty().values.sortedBy { it.ts ?: 0.0 }.map { tag ->
        val definition = definitions.firstOrNull { it.id == tag.id }
        val value = when (tag.`val`) {
            "true" -> UiText.Res(R.string.call_detail_yes)
            "false" -> UiText.Res(R.string.call_detail_no)
            else -> UiText.Literal(tag.`val`.orEmpty())
        }
        val link = definition?.tagType?.takeIf { it.type == "LinkTagValueType" }?.url
            ?.replace("{value}", tag.`val`.orEmpty())
        DetailRow.Field(
            UiText.Literal(definition?.name?.takeIf { it.isNotEmpty() } ?: tag.name.orEmpty()),
            value,
            link = link,
            note = tag.ts?.let { formatTime(it, zone, locale) }?.takeIf { it.isNotEmpty() }
        )
    }

    // Info

    /**
     * The directory contact for the other party on an incoming or outgoing call: the caller of the
     * invoked connection, or the called number of the invoking one.
     */
    fun contactFor(
        session: CallSession,
        segment: CallSegment?,
        directories: List<Pair<ContactDirectoryModel, List<ContactItemModel>>>,
        countryCode: String
    ): Pair<ContactDirectoryModel, ContactItemModel>? {
        val number = when (session.dir) {
            SessionCallDirections.INCOMING ->
                segment?.conns?.firstOrNull { it.dir == ConnectionDirections.INVOKED }?.caller
            SessionCallDirections.OUTGOING ->
                segment?.conns?.firstOrNull { it.dir == ConnectionDirections.INVOKING }?.called
            else -> null
        }
        if (number.isNullOrEmpty()) return null
        return ContactDirectoryRules.findContactAndDirectoryByPhone(
            directories,
            PhoneFormat.toE164(number, countryCode),
            countryCode
        )
    }

    /** The contact's filled-in fields, labelled with the directory's field names. */
    fun infoRows(directory: ContactDirectoryModel, contact: ContactItemModel): List<DetailRow> =
        contact.fields.filter { it.value.isNotEmpty() && it.id != "avatar" }.map { field ->
            val label = if (directory.id == "internal" || directory.id == "external") {
                field.id
            } else {
                directory.fields.firstOrNull { it.id == field.id }?.name.orEmpty()
            }
            DetailRow.Field(UiText.Literal(label), UiText.Literal(field.value))
        }
}
