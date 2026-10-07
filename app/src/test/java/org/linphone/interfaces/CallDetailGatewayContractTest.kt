package org.linphone.interfaces

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.linphone.models.callsession.ConnectionDirections
import org.linphone.models.callsession.ConnectionTypes
import org.linphone.utils.GsonUtils
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The call detail page's endpoints, as the web client calls them (conversation-data.service.ts,
 * disposition-manager.service.ts). The fixtures are written from the web client's models, not
 * captured from the gateway.
 */
class CallDetailGatewayContractTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: CTGatewayService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(GsonUtils.create()))
            .build()
            .create(CTGatewayService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueFixture(name: String) {
        val body = javaClass.getResource("/gateway/$name")!!.readText()
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
    }

    private fun assertRequest(method: String, path: String) {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.path)
    }

    @Test
    fun `getCallSession unwraps the session and maps its segments`() = runBlocking {
        enqueueFixture("session.json")

        val session = gateway.getCallSession("sess-1").body()!!.data!!

        assertRequest("GET", "/api/v1.0/session/sess-1")
        assertEquals("sess-1", session.id)
        assertEquals("Kazoo", session.pbxinfo?.type)
        assertEquals("1234", session.tags!!["t2"]!!.`val`)
        assertEquals(1723627390.0, session.tags!!["t2"]!!.ts!!, 0.0)

        val call = session.calls!!.single()
        assertEquals(2, call.type)
        assertEquals("Acme Ltd", call.cli!!.single().name)
        assertEquals(7.0, call.ring!!, 0.0)
        assertTrue(call.answered)

        val user = call.conns!![1]
        assertEquals(ConnectionTypes.USER, user.type)
        assertEquals(ConnectionDirections.INVOKED, user.dir)
        assertEquals(listOf("rec-1"), user.recording!!.recordingIds)
        assertEquals(1, user.analytic!!.hasTrans)
        assertEquals(2, user.events!!.size)
    }

    @Test
    fun `getConversationSummary maps the short wire names`() = runBlocking {
        enqueueFixture("calltranscriptionsummaries.json")

        val summary = gateway.getConversationSummary("rec-1").body()!!

        assertRequest("GET", "/api/v1.0/calltranscriptionsummaries?recordingId=rec-1")
        assertEquals("trans-1", summary.transcriptionId)
        assertEquals("c2", summary.connectionId)
        assertEquals("Succeeded", summary.summaryAnalysis!!.state)
        assertEquals(2, summary.summaryAnalysis!!.summaries!!.size)
        assertEquals("Positive", summary.sentiment!!.sentiment!!.scoreLabel)
        assertEquals(0.91, summary.transcriptionStats!!.confidence!!, 0.0)
        assertEquals("Order arrived damaged", summary.language!!.aspects!![0].value)
        assertEquals(1, summary.language!!.aspects!![0].speaker)
        assertEquals("customer", summary.speakers!!.map!!["1"])
    }

    @Test
    fun `getConversationSummary encodes the recording id`() = runBlocking {
        enqueueFixture("calltranscriptionsummaries.json")

        gateway.getConversationSummary("rec 1/2")

        assertRequest("GET", "/api/v1.0/calltranscriptionsummaries?recordingId=rec%201%2F2")
    }

    @Test
    fun `getConversationTranscription maps the timeline`() = runBlocking {
        enqueueFixture("calltranscriptions.json")

        val transcription = gateway.getConversationTranscription("trans-1").body()!!

        assertRequest("GET", "/api/v1.0/calltranscriptions/trans-1")
        val timeline = transcription.timeline!!
        assertEquals(4, timeline.size)
        assertEquals("SpeakerSegment", timeline[1].type)
        assertEquals(1, timeline[1].speaker)
        assertEquals(0.3, timeline[1].confidence!!, 0.0)
        assertEquals("Negative", timeline[1].sentiment!!.scoreLabel)
        val pii = timeline[1].pii!!.piiItems!!.single()
        assertEquals("CREDIT_CARD", pii.category)
        assertEquals(8, pii.start)
        assertEquals(17, pii.end)
        assertEquals(1, timeline[1].index)
        assertEquals("SilenceSegment", timeline[2].type)
    }

    @Test
    fun `getInteractionTags maps tag types`() = runBlocking {
        enqueueFixture("interactiontags.json")

        val tags = gateway.getInteractionTags().body()!!

        assertRequest("GET", "/api/v1.0/interactiontags")
        assertEquals("LinkTagValueType", tags[0].tagType!!.type)
        assertEquals("https://tickets.example.com/{value}", tags[0].tagType!!.url)
        assertEquals("Resolved", tags[1].name)
    }
}
