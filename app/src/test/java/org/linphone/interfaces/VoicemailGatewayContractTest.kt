package org.linphone.interfaces

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.linphone.models.CustomerLicence
import org.linphone.utils.GsonUtils
import org.threeten.bp.ZoneOffset
import org.threeten.bp.ZonedDateTime
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The voicemail and licence endpoints, as the web client calls them (voicemail-box.service.ts,
 * licence.service.ts). Like CTGatewayServiceContractTest, the fixtures are written from the
 * models, not captured from the gateway.
 */
class VoicemailGatewayContractTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: CTGatewayService

    private val boxId = "3ed4e3dcf69f145218a10f9fb1c316c7"
    private val mediaId = "202608-c939f8108a3b2c95b8c42f473d557e68"

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
    fun `getLicence maps feature values`() = runBlocking {
        enqueueFixture("licence.json")

        val licence = gateway.getLicence().body()!!

        assertRequest("GET", "/api/v1.0/licence")
        assertTrue(licence.hasFeature(CustomerLicence.VOICEMAIL_ENABLED))
        assertFalse("0 is hidden", licence.hasFeature("customer.ucwebclient.cti.enabled"))
        assertFalse("absent", licence.hasFeature("customer.ucwebclient.presence.enabled"))
    }

    @Test
    fun `getVoicemailBoxes maps boxes and their counts`() = runBlocking {
        enqueueFixture("voicemailbox.json")

        val boxes = gateway.getVoicemailBoxes().body()!!

        assertRequest("GET", "/api/v1.0/voicemailbox")
        assertEquals(2, boxes.size)
        assertEquals(boxId, boxes[0].id)
        assertEquals("Test User 1", boxes[0].name)
        assertEquals("1031", boxes[0].number)
        assertEquals(3, boxes[0].totalMessages)
        assertEquals(1, boxes[0].saved)
        assertEquals(2, boxes[0].new)
        assertTrue(boxes[0].enabled)
        assertFalse(boxes[1].enabled)
    }

    @Test
    fun `getVoicemailBoxes treats null as no mailbox`() = runBlocking {
        server.enqueue(MockResponse().setBody("null").setHeader("Content-Type", "application/json"))

        val response = gateway.getVoicemailBoxes()

        assertTrue(response.isSuccessful)
        assertNull(response.body())
    }

    @Test
    fun `getVoicemailMessages asks for the first page and maps messages`() = runBlocking {
        enqueueFixture("voicemailbox-messages.json")

        val page = gateway.getVoicemailMessages(boxId).body()!!

        assertRequest(
            "GET",
            "/api/v1.0/voicemailbox/$boxId/messages?pageSize=50&includeDeleted=false"
        )
        assertEquals("opaque-cursor", page.nextStartKey)
        val message = page.messages!![0]
        assertEquals(mediaId, message.mediaId)
        assertEquals("new", message.folder)
        assertEquals(6900L, message.length)
        assertEquals("1076", message.callerIdNumber)
        assertEquals("1076@hjy0gw4.uk.sip.xarios.cloud", message.from)
        assertEquals(
            ZonedDateTime.of(2026, 8, 14, 10, 21, 13, 0, ZoneOffset.UTC).toInstant(),
            message.timestamp!!.toInstant()
        )
        assertNull(message.transcription)
        assertEquals("saved", page.messages!![1].folder)
    }

    @Test
    fun `getVoicemailMessages passes the cursor for the next page`() = runBlocking {
        enqueueFixture("voicemailbox-messages.json")

        gateway.getVoicemailMessages(boxId, startKey = "opaque-cursor")

        assertRequest(
            "GET",
            "/api/v1.0/voicemailbox/$boxId/messages?pageSize=50&includeDeleted=false&startKey=opaque-cursor"
        )
    }

    @Test
    fun `getVoicemailTranscription maps the text`() = runBlocking {
        enqueueFixture("voicemailbox-transcription.json")

        val transcription = gateway.getVoicemailTranscription(boxId, mediaId).body()!!

        assertRequest("GET", "/api/v1.0/voicemailbox/$boxId/messages/$mediaId/transcription")
        assertEquals("success", transcription.result)
        assertEquals(
            "Hi, it's Test User 2. Can you call me back about the order?",
            transcription.text
        )
    }

    @Test
    fun `getVoicemailAudio returns the audio and its type`() = runBlocking {
        server.enqueue(MockResponse().setBody("ID3audio").setHeader("Content-Type", "audio/mpeg"))

        val response = gateway.getVoicemailAudio(boxId, mediaId)

        assertRequest("GET", "/api/v1.0/voicemailbox/$boxId/messages/$mediaId/audio")
        assertEquals("audio/mpeg", response.body()!!.contentType().toString())
        assertEquals("ID3audio", response.body()!!.string())
    }

    @Test
    fun `saveVoicemailMessage puts to save`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val response = gateway.saveVoicemailMessage(boxId, mediaId)

        assertRequest("PUT", "/api/v1.0/voicemailbox/$boxId/messages/$mediaId/save")
        assertTrue(response.isSuccessful)
    }

    @Test
    fun `deleteVoicemailMessage deletes the message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val response = gateway.deleteVoicemailMessage(boxId, mediaId)

        assertRequest("DELETE", "/api/v1.0/voicemailbox/$boxId/messages/$mediaId")
        assertTrue(response.isSuccessful)
    }
}
