package org.linphone.models.callsession

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/*
 * A call session as the gateway reports it (GET session/{documentId}), ported from the web client's
 * models/reporting/call-session.ts. Times are seconds from the Unix epoch; durations are seconds.
 */

@Keep
data class CallSessionResponse(val data: CallSession? = null)

@Keep
data class CallSession(
    val id: String? = null,
    val calls: List<CallSegment>? = null,
    val pbxinfo: CallSessionPbxInfo? = null,
    val tags: Map<String, InteractionTagItem>? = null,
    // The session is itself a segment covering the whole call
    val type: Int = 0,
    val dir: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val duration: Double = 0.0
)

@Keep
data class CallSessionPbxInfo(
    val type: String? = null,
    val ver: String? = null,
    val name: String? = null,
    val siteId: String? = null,
    val siteName: String? = null
)

@Keep
data class CallSegment(
    val callId: String? = null,
    // CallTypes: 0 unknown, 1 internal, 2 external
    val type: Int = 0,
    // CallDirections: 0 unknown, 1 internal, 2 incoming, 3 outgoing, 4 both
    val dir: Int = 0,
    val cli: List<ExternalNumber>? = null,
    val ddi: List<ExternalNumber>? = null,
    val hgNum: String? = null,
    val hgName: String? = null,
    val accCode: String? = null,
    val conns: List<CallConnection>? = null,
    val routePathId: String? = null,
    val routePathName: String? = null,
    val bill: String? = null,
    val xfrToAgtName: String? = null,
    val xfrToAgtNum: String? = null,
    val xfrToDevName: String? = null,
    val xfrToDevNum: String? = null,
    val xfrToDevType: String? = null,
    val xfrToUsrName: String? = null,
    val xfrToUsrNum: String? = null,
    val ovfIn: Int = 0,
    val ovfOut: Int = 0,
    val xfrIn: Int = 0,
    val xfrOut: Int = 0,
    val enquiry: Int = 0,
    val hangupCause: String? = null,
    val conference: Int = 0,
    // CtiData
    val start: Double? = null,
    val answer: Double? = null,
    val end: Double? = null,
    val duration: Double? = null,
    val parked: Double? = null,
    val ring: Double? = null,
    val talk: Double? = null,
    val hold: Double? = null,
    val answered: Boolean = false,
    val agtName: String? = null,
    val agtNum: String? = null,
    val devName: String? = null,
    val devNum: String? = null,
    val userName: String? = null,
    val userNum: String? = null
)

@Keep
data class CallConnection(
    val connId: String? = null,
    val recording: RecordingIdList? = null,
    // ConnectionTypes
    val type: Int = 0,
    // ConnectionDirections: 0 unknown, 1 invoking, 2 invoked
    val dir: Int = 0,
    val caller: String? = null,
    val callerName: String? = null,
    val called: String? = null,
    val calledName: String? = null,
    val events: List<CallEvent>? = null,
    val intId: String? = null,
    val vmBoxNum: String? = null,
    val vmMsgState: String? = null,
    val tags: ContactTags? = null,
    val confParts: Int = 0,
    val confAtt: Int = 0,
    val analytic: CallSessionAnalytics? = null,
    // CtiData
    val start: Double? = null,
    val answer: Double? = null,
    val end: Double? = null,
    val duration: Double? = null,
    val parked: Double? = null,
    val ring: Double? = null,
    val talk: Double? = null,
    val hold: Double? = null,
    val answered: Boolean = false,
    val agtName: String? = null,
    val agtNum: String? = null,
    val devName: String? = null,
    val devNum: String? = null,
    val userName: String? = null,
    val userNum: String? = null
)

object ConnectionTypes {
    const val UNKNOWN = 0
    const val STATION = 1
    const val TRUNK = 2
    const val AGENT = 3
    const val GROUP = 4
    const val QUEUED = 5
    const val USER = 6
    const val CONFERENCE = 7
    const val EXTERNAL_STATION = 8
}

object ConnectionDirections {
    const val UNKNOWN = 0
    const val INVOKING = 1
    const val INVOKED = 2
}

object SessionCallTypes {
    const val UNKNOWN = 0
    const val INTERNAL = 1
    const val EXTERNAL = 2
}

object SessionCallDirections {
    const val UNKNOWN = 0
    const val INTERNAL = 1
    const val INCOMING = 2
    const val OUTGOING = 3
    const val BOTH = 4
}

@Keep
data class RecordingIdList(val recordingIds: List<String>? = null)

@Keep
data class ExternalNumber(
    val type: Int = 0,
    val num: String? = null,
    val name: String? = null,
    val loc: String? = null,
    val area: String? = null,
    val state: String? = null,
    val cntry: String? = null
)

@Keep
data class CallEvent(
    // 1 held, 2 retrieved, 3 account code entered
    val type: Int = 0,
    val eventTime: Double = 0.0
)

@Keep
data class ContactTags(val contName: String? = null, val compName: String? = null)

@Keep
data class CallSessionAnalytics(val hasTrans: Int = 0, val sent: CallSessionSentiment? = null)

@Keep
data class CallSessionSentiment(
    val usr: Double? = null,
    val cust: Double? = null,
    val usrTrd: Double? = null,
    val custTrd: Double? = null
)

/** A tag set on the session; ts is seconds from the Unix epoch. */
@Keep
data class InteractionTagItem(
    val id: String? = null,
    val name: String? = null,
    val `val`: String? = null,
    val userId: String? = null,
    val ts: Double? = null
)

/** A tag definition (GET interactiontags). */
@Keep
data class InteractionTag(
    val id: String? = null,
    val name: String? = null,
    val tagType: InteractionTagType? = null
)

@Keep
data class InteractionTagType(
    @SerializedName("_type") val type: String? = null,
    val name: String? = null,
    // LinkTagValueType: the link, with {value} for the tag's value
    val url: String? = null
)

/*
 * Transcription analysis (GET calltranscriptionsummaries, GET calltranscriptions/{id}), ported from
 * the web client's models/conversations. Wire names are the short ones the gateway sends.
 */

@Keep
data class ConversationSummary(
    val id: String? = null,
    @SerializedName("transId") val transcriptionId: String? = null,
    @SerializedName("tId") val tenantId: String? = null,
    val sessionId: String? = null,
    val callId: String? = null,
    val userId: String? = null,
    val recordingId: String? = null,
    @SerializedName("connId") val connectionId: String? = null,
    val state: String? = null,
    @SerializedName("sum") val summaryAnalysis: SummaryAnalysis? = null,
    @SerializedName("sent") val sentiment: SentimentAnalysis? = null,
    @SerializedName("trans") val transcriptionStats: TranscribeAction? = null,
    @SerializedName("langDet") val language: LanguageAction? = null,
    @SerializedName("spkrs") val speakers: SpeakersAction? = null
)

@Keep
data class SummaryAnalysis(
    val state: String? = null,
    @SerializedName("err") val error: String? = null,
    @SerializedName("sum") val summaries: List<String>? = null
)

@Keep
data class SentimentAnalysis(
    val state: String? = null,
    @SerializedName("err") val error: String? = null,
    @SerializedName("sent") val sentiment: SentimentScores? = null
)

@Keep
data class SentimentScores(
    @SerializedName("scoreLbl") val scoreLabel: String? = null,
    val score: Double? = null,
    @SerializedName("pos") val positive: Double? = null,
    @SerializedName("neut") val neutral: Double? = null,
    @SerializedName("neg") val negative: Double? = null
)

@Keep
data class TranscribeAction(
    val state: String? = null,
    @SerializedName("err") val error: String? = null,
    @SerializedName("conf") val confidence: Double? = null,
    @SerializedName("dur") val duration: Double? = null
)

@Keep
data class LanguageAction(
    val state: String? = null,
    @SerializedName("err") val error: String? = null,
    @SerializedName("asp") val aspects: List<Aspect>? = null
)

@Keep
data class Aspect(
    // resolution, recap, issue or followUp
    val type: String? = null,
    @SerializedName("val") val value: String? = null,
    @SerializedName("spk") val speaker: Int = 0
)

@Keep
data class SpeakersAction(
    val state: String? = null,
    @SerializedName("err") val error: String? = null,
    // Channel index to "customer" or "user"
    val map: Map<String, String>? = null
)

@Keep
data class ConversationTranscription(
    val id: String? = null,
    @SerializedName("transId") val transcriptionId: String? = null,
    @SerializedName("tId") val tenantId: String? = null,
    val timeline: List<TranscriptionTimelineItem>? = null,
    val duration: Double = 0.0
)

@Keep
data class TranscriptionTimelineItem(
    // SilenceSegment or SpeakerSegment
    @SerializedName("_type") val type: String? = null,
    // Seconds from the start of the recording
    val start: Double = 0.0,
    val end: Double = 0.0,
    val duration: Double = 0.0,
    @SerializedName("spk") val speaker: Int = 0,
    @SerializedName("conf") val confidence: Double? = null,
    @SerializedName("txt") val text: String? = null,
    @SerializedName("sent") val sentiment: SentimentScores? = null,
    @SerializedName("pii") val pii: PiiItemList? = null,
    @SerializedName("idx") val index: Int? = null
)

@Keep
data class PiiItemList(@SerializedName("items") val piiItems: List<PiiItem>? = null)

@Keep
data class PiiItem(
    @SerializedName("txt") val text: String? = null,
    @SerializedName("cat") val category: String? = null,
    @SerializedName("conf") val confidence: Double? = null,
    // Character positions in the segment's text
    @SerializedName("piiStart") val start: Int = 0,
    @SerializedName("piiEnd") val end: Int = 0
)

/** The summary and transcription of one recording, or why there isn't one. */
data class Conversation(
    val summary: ConversationSummary? = null,
    val transcription: ConversationTranscription? = null,
    val error: String? = null
)
