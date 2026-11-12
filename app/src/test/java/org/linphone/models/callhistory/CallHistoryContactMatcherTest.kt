package org.linphone.models.callhistory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactItemModel
import org.linphone.models.contact.DirectoryFieldTypes
import org.linphone.models.contact.FieldDefinitionModel
import org.linphone.models.contact.FieldItemModel

class CallHistoryContactMatcherTest {

    private val personal = ContactDirectoryModel(
        id = "dir-personal",
        name = "Personal",
        fields = listOf(
            FieldDefinitionModel("fullName", "Full Name", "", DirectoryFieldTypes.CONTACT_NAME),
            FieldDefinitionModel("companyName", "Company", "", DirectoryFieldTypes.COMPANY_NAME),
            FieldDefinitionModel("phone1", "Phone", "", DirectoryFieldTypes.PHONE)
        ),
        userRoleAssociations = emptyMap()
    )

    private val directories = listOf(
        personal to listOf(
            ContactItemModel(
                "c1",
                "dir-personal",
                listOf(
                    FieldItemModel("fullName", "El Cabasa Grande"),
                    FieldItemModel("phone1", "+447921910119")
                )
            ),
            ContactItemModel(
                "c2",
                "dir-personal",
                listOf(
                    FieldItemModel("companyName", "Xarios"),
                    FieldItemModel("phone1", "0161 496 0000")
                )
            )
        )
    )

    private fun call(
        direction: CallDirections,
        number: String,
        callType: CallTypes = CallTypes.External,
        hasContactMatch: Boolean = false
    ) = CallHistoryItem(
        missedCall = false,
        answered = true,
        hasRecording = false,
        startTime = null,
        connectionId = "connection",
        callType = callType,
        callDirection = direction.value,
        contactName = "New Company - 07921 910119",
        contactMatchType = null,
        hasContactMatch = hasContactMatch,
        calledUserName = null,
        calledUserNumber = if (direction == CallDirections.Outgoing) number else null,
        callingUserName = null,
        callingUserNumber = if (direction == CallDirections.Outgoing) null else number,
        routePathName = null,
        groupName = null,
        huntgroupName = null,
        documentId = "doc",
        isConference = false,
        pbxType = PbxType.Kazoo,
        interactionTags = listOf()
    )

    private fun enrich(item: CallHistoryItem) =
        CallHistoryContactMatcher.enrich(listOf(item), directories, "GB").single()

    @Test
    fun `external calls take the matching contact's name`() {
        val incoming = enrich(call(CallDirections.Incoming, "+447921910119"))
        assertEquals("El Cabasa Grande", incoming.contactName)
        assertTrue(incoming.hasContactMatch)

        val outgoing = enrich(call(CallDirections.Outgoing, "+441614960000"))
        assertEquals("Xarios", outgoing.contactName)
        assertTrue(outgoing.hasContactMatch)
    }

    @Test
    fun `unmatched, internal and already matched calls are left alone`() {
        val unknown = enrich(call(CallDirections.Incoming, "+441234567890"))
        assertEquals("New Company - 07921 910119", unknown.contactName)
        assertFalse(unknown.hasContactMatch)

        val internal = enrich(
            call(CallDirections.Incoming, "+447921910119", callType = CallTypes.Internal)
        )
        assertFalse(internal.hasContactMatch)

        val gatewayMatched = call(CallDirections.Incoming, "+447921910119", hasContactMatch = true)
        assertEquals("New Company - 07921 910119", enrich(gatewayMatched).contactName)
    }

    @Test
    fun `calls missing fields from the gateway can still be named`() {
        // No interactionTags or pbxType: Gson leaves them null despite their non-null Kotlin types
        val json = """{"documentId":"d1","connectionId":"c1","callType":2,"callDirection":2,
            "callingUserNumber":"+447921910119","contactName":"New Company","answered":1}"""
        val item = org.linphone.utils.GsonUtils.create().fromJson(json, CallHistoryItem::class.java)

        val enriched = CallHistoryContactMatcher.enrich(listOf(item), directories, "GB").single()

        assertEquals("El Cabasa Grande", enriched.contactName)
        assertTrue(enriched.hasContactMatch)
        assertEquals("d1", enriched.documentId)
        assertTrue(enriched.answered)
    }

    @Test
    fun `an outgoing leg with only a cli is still the caller's, as on iOS and the web client`() {
        // The gateway's outgoing leg of a forwarded external call: no called number, just the cli
        val json = """{"documentId":"d2","connectionId":"c2","callType":2,"callDirection":3,
            "cli":"+447921910119","ddi":"+443301274140","answered":1,"contactName":""}"""
        val item = org.linphone.utils.GsonUtils.create().fromJson(json, CallHistoryItem::class.java)

        assertEquals("+447921910119", CallHistoryContactMatcher.otherPartyNumber(item))
        assertEquals("El Cabasa Grande", CallHistoryContactMatcher.enrich(listOf(item), directories, "GB").single().contactName)
    }

    @Test
    fun `external calls are the cli, internal calls the extension, each falling back to the other`() {
        val gson = org.linphone.utils.GsonUtils.create()
        fun item(json: String) = gson.fromJson(json, CallHistoryItem::class.java)

        // As the web client: an external call's number is its cli
        assertEquals(
            "+447921910119",
            CallHistoryContactMatcher.otherPartyNumber(
                item("""{"callType":2,"callDirection":2,"callingUserNumber":"07921910119","cli":"+447921910119"}""")
            )
        )
        assertEquals(
            "07921910119",
            CallHistoryContactMatcher.otherPartyNumber(item("""{"callType":2,"callDirection":2,"callingUserNumber":"07921910119","cli":""}"""))
        )
        assertEquals(
            "1041",
            CallHistoryContactMatcher.otherPartyNumber(item("""{"callType":1,"callDirection":3,"calledUserNumber":"1041","cli":"+441111"}"""))
        )
        assertEquals(
            "1041",
            CallHistoryContactMatcher.otherPartyNumber(item("""{"callType":1,"callDirection":2,"callingUserNumber":"","cli":"1041"}"""))
        )
        assertEquals(null, CallHistoryContactMatcher.otherPartyNumber(item("""{"callType":1,"callDirection":2}""")))
    }
}
