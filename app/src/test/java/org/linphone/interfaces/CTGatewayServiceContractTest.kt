package org.linphone.interfaces

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.linphone.models.PbxFeatureCode
import org.linphone.models.PbxParkingSlot
import org.linphone.models.callhistory.CallTypes
import org.linphone.models.callhistory.PbxType
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.contact.ContactItemRequest
import org.linphone.models.contact.ContactItemRequestField
import org.linphone.utils.GsonUtils
import org.threeten.bp.ZoneOffset
import org.threeten.bp.ZonedDateTime
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Serves sample gateway responses (src/test/resources/gateway) to CTGatewayService through the
 * same Gson configuration the app uses, checking request paths and how the JSON maps onto the models.
 *
 * The fixtures are written from the model classes, not captured from the gateway, so they test our
 * reading of the API rather than what the server actually sends.
 */
class CTGatewayServiceContractTest {

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
    fun `getUserInfo maps the current user`() = runBlocking {
        enqueueFixture("users-me.json")

        val user = gateway.getUserInfo().body()!!

        assertRequest("GET", "/api/v1.0/users/me")
        assertEquals("5b0c4f7e-1a2b-4c3d-8e9f-000000000001", user.id)
        assertEquals("Test User", user.displayName)
        assertEquals("GB", user.pbxCountryCode)
        assertTrue("isEnabled sent as 1", user.isEnabled)
        assertTrue(user.hasClientPermission())
        assertEquals(2, user.deviceCount.toInt())
        assertTrue(user.clientProfileSettings.presenceSelectionEnabled)
        assertTrue(user.clientProfileSettings.queueControlEnabled)
        assertFalse(user.clientProfileSettings.agentControlDisplayed)
        assertTrue(user.clientProfileSettings.exposePersonalParkingSlotsEnabled)
        assertFalse(user.clientProfileSettings.exposeAllParkingSlotsEnabled)
        assertEquals(
            listOf(
                PbxParkingSlot("Reception", "101", true),
                PbxParkingSlot("Warehouse", "102", false)
            ),
            user.parkingSlotCollection
        )
    }

    @Test
    fun `getUserBranding maps tenant branding`() = runBlocking {
        enqueueFixture("users-me-branding.json")

        val branding = gateway.getUserBranding().body()!!

        assertRequest("GET", "/api/v1.0/users/me/branding")
        assertEquals("Example Brand", branding.brandName)
        assertEquals("https://docs.example.com", branding.documentationRootUrl)
        assertEquals("Example Identity", branding.identityPortal!!.name)
        assertNull(branding.identityPortal!!.clientStartupScript)
        assertEquals("Example Customer Portal", branding.customerPortal!!.name)
        assertNull("absent portals stay null", branding.systemPortal)
        assertEquals("#123456", branding.cssVariables["--primary-color"])
    }

    @Test
    fun `getUserBranding treats an empty body as no branding`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val response = gateway.getUserBranding()

        assertTrue(response.isSuccessful)
        assertNull(response.body())
    }

    @Test
    fun `getReportResult maps call history`() = runBlocking {
        enqueueFixture("usercallhistory-report.json")

        val report = gateway.getReportResult("request-1").body()!!

        assertRequest("GET", "/api/v1.0/usercallhistory/report?requestId=request-1")
        assertEquals(1, report.status)
        assertEquals(2, report.data!!.size)

        val answered = report.data!![0]
        assertTrue(answered.answered)
        assertFalse(answered.missedCall)
        assertEquals(ZonedDateTime.of(2025, 3, 1, 9, 0, 0, 0, ZoneOffset.UTC), answered.startTime)
        assertEquals(CallTypes.External, answered.callType)
        assertEquals(2, answered.callDirection)
        assertEquals(PbxType.DimensionsVoice, answered.pbxType)
        assertEquals("Sale", answered.interactionTags.single().value)

        val missed = report.data!![1]
        assertTrue("missedCall sent as 1", missed.missedCall)
        assertFalse("answered sent as 0", missed.answered)
        assertEquals("start time without a zone is still read", 8, missed.startTime!!.hour)
        assertEquals("callType sent as a number", CallTypes.Internal, missed.callType)
        assertEquals(PbxType.Kazoo, missed.pbxType)
        assertTrue(missed.interactionTags.isEmpty())
    }

    @Test
    fun `doGetContactDirectories maps the Personal directory`() {
        enqueueFixture("contactdirectories-users-me.json")

        val directories = gateway.doGetContactDirectories().execute().body()!!

        assertRequest("GET", "/api/v1.0/contactdirectories/users/me")
        val personal = ContactDirectoryRules.findPersonalDirectory(directories)!!
        assertEquals("PersonalContactDirectory", personal.type)
        assertEquals("BlfDefinition", ContactDirectoryRules.blfField(personal)!!.definitionType)
        assertTrue(
            ContactDirectoryRules.canContribute(personal, "5b0c4f7e-1a2b-4c3d-8e9f-000000000001")
        )
    }

    @Test
    fun `getDirectoryContacts maps contact fields`() = runBlocking {
        enqueueFixture("contactdirectories-items.json")

        val contacts = gateway.getDirectoryContacts("dir-personal").body()!!

        assertRequest("GET", "/api/v1.0/contactdirectories/dir-personal/items?maxrecords=100")
        val contact = contacts.single()
        assertEquals("contact-1", contact.id)
        assertEquals("Chris Rawlinson", contact.fields.first { it.id == "fullName" }.value)
    }

    private val request = ContactItemRequest(
        id = "",
        tenantId = "tenant-1",
        directoryId = "dir-personal",
        fields = listOf(ContactItemRequestField("fullName", "Chris"))
    )

    private fun contactItemPart() = GsonUtils.create().toJson(request).toRequestBody()

    @Test
    fun `createDirectoryContact posts the contact as multipart form data`() = runBlocking {
        // Create/update responses echo the fields as "val"
        server.enqueue(
            MockResponse()
                .setBody("""{"id":"contact-2","fields":[{"id":"fullName","val":"Chris"}]}""")
                .setHeader("Content-Type", "application/json")
        )
        val avatar = MultipartBody.Part.createFormData(
            "file",
            "avatar.png",
            byteArrayOf(1, 2, 3).toRequestBody("image/png".toMediaType())
        )

        val created = gateway.createDirectoryContact("dir-personal", contactItemPart(), avatar).body()!!

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1.0/contactdirectories/dir-personal/items", recorded.path)
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"contactItem\""))
        assertTrue(body.contains("\"dtype\":\"IContactItem\""))
        assertTrue(body.contains("\"_type\":\"ContactItem\""))
        assertTrue(body.contains("\"dId\":\"dir-personal\""))
        assertTrue(body.contains("{\"id\":\"fullName\",\"val\":\"Chris\"}"))
        assertTrue(body.contains("name=\"file\"; filename=\"avatar.png\""))

        assertEquals("contact-2", created.id)
        assertEquals("Chris", created.fields.single().value)
    }

    @Test
    fun `updateDirectoryContact puts to the contact and can remove the avatar`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"contact-1","fields":[]}"""))

        gateway.updateDirectoryContact("dir-personal", "contact-1", contactItemPart(), null, true)

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals(
            "/api/v1.0/contactdirectories/dir-personal/items/contact-1?removeAvatar=true",
            recorded.path
        )
        assertFalse(recorded.body.readUtf8().contains("name=\"file\""))
    }

    @Test
    fun `updateDirectoryContact leaves out removeAvatar unless asked`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"contact-1","fields":[]}"""))

        gateway.updateDirectoryContact("dir-personal", "contact-1", contactItemPart(), null)

        assertEquals(
            "/api/v1.0/contactdirectories/dir-personal/items/contact-1",
            server.takeRequest().path
        )
    }

    @Test
    fun `deleteDirectoryContact deletes the contact`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val response = gateway.deleteDirectoryContact("dir-personal", "contact-1")

        assertTrue(response.isSuccessful)
        assertRequest("DELETE", "/api/v1.0/contactdirectories/dir-personal/items/contact-1")
    }

    @Test
    fun `getUserFeatureCodes maps feature names to numbers`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(
                    """[{"number":"*3","name":"park_and_retrieve"},{"number":"*76","name":"DimensionsFeatureCode_DndToggle"}]"""
                )
                .setHeader("Content-Type", "application/json")
        )

        val codes = gateway.getUserFeatureCodes().body()!!

        assertRequest("GET", "/api/v1.0/userfeaturecodes")
        assertEquals("*3", codes.single { it.name == PbxFeatureCode.PARK_AND_RETRIEVE }.number)
        assertEquals("*76", codes.single { it.name == PbxFeatureCode.DIMENSIONS_DND_TOGGLE }.number)
    }

    @Test
    fun `getAllUserDevices lists every device, PBX handsets included`() = runBlocking {
        enqueueFixture("users-me-devices.json")

        val devices = gateway.getAllUserDevices().body()!!

        assertRequest("GET", "/api/v1.0/users/me/devices?includePbxDevices=true")
        assertEquals(listOf("dev-mobile", "dev-desk"), devices.map { it.deviceId })
        assertEquals("UCM", devices[0].model)
        assertEquals("Reception desk phone", devices[1].deviceName)
    }

    @Test
    fun `getUserFeatureCodes treats an empty body as no codes`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val response = gateway.getUserFeatureCodes()

        assertTrue(response.isSuccessful)
        assertNull(response.body())
    }

    @Test
    fun `getDirectoryContact fetches one contact`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"id":"k1","fields":[{"id":"fullName","value":"Chris"}]}""")
                .setHeader("Content-Type", "application/json")
        )

        val contact = gateway.getDirectoryContact("d1", "k1").body()!!

        assertRequest("GET", "/api/v1.0/contactdirectories/d1/items/k1")
        assertEquals("k1", contact.id)
        assertEquals("Chris", contact.fields.single().value)
    }
}
