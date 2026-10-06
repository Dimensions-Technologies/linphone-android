package org.linphone.models.realtime

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.contact.ContactItemModel
import org.linphone.models.contact.DirectoryFieldTypes
import org.linphone.models.contact.FieldDefinitionModel
import org.linphone.models.contact.FieldItemModel

class ContactMatchTest {

    @Test
    fun `a match is named after the full name, else the company`() {
        assertEquals(
            "Chris",
            ContactMatch(tagFields = mapOf("fullName" to "Chris", "companyName" to "Xarios")).displayName
        )
        assertEquals(
            "Xarios",
            ContactMatch(tagFields = mapOf("fullName" to " ", "companyName" to "Xarios")).displayName
        )
        assertNull(ContactMatch(tagFields = mapOf("fullName" to "")).displayName)
        assertNull(ContactMatch().displayName)
    }

    @Test
    fun `callMatchEvent from the server is read`() {
        // SignalR's Java client reads events with plain Gson
        val json = """{"data":{"callId":"abc@pbx","sessionId":"s1","connectionId":"c1","matches":[
            {"directoryName":"Salesforce","directoryId":"d1","hasAvatar":false,"contactId":"k1",
             "tagFields":{"fullName":"Chris Rawlinson","companyName":"Xarios","crmId":"0031"}}]},
            "id":"e1","tenantId":"t1","userId":"u1"}"""

        val event = Gson().fromJson(json, RealtimeEventCallMatch::class.java)

        assertEquals("abc@pbx", event.data!!.callId)
        val match = event.data!!.matches!!.single()
        assertEquals("d1", match.directoryId)
        assertEquals("k1", match.contactId)
        assertEquals("Chris Rawlinson", match.displayName)
        assertEquals("0031", match.tagFields!!["crmId"])
    }

    @Test
    fun `a directory contact becomes a match with its name, company and numbers`() {
        val directory = ContactDirectoryModel(
            id = "dir-personal",
            name = "Personal",
            fields = listOf(
                FieldDefinitionModel("fullName", "Full Name", "", DirectoryFieldTypes.CONTACT_NAME),
                FieldDefinitionModel("companyName", "Company", "", DirectoryFieldTypes.COMPANY_NAME),
                FieldDefinitionModel("phone1", "Phone", "", DirectoryFieldTypes.PHONE),
                FieldDefinitionModel("phone2", "Mobile", "", DirectoryFieldTypes.PHONE)
            ),
            userRoleAssociations = emptyMap()
        )
        val contact = ContactItemModel(
            "c1",
            "dir-personal",
            listOf(
                FieldItemModel("fullName", "El Cabasa Grande"),
                FieldItemModel("phone1", "+447921910119"),
                FieldItemModel("phone2", "1024")
            )
        )

        val found = ContactDirectoryRules.findContactAndDirectoryByPhone(
            listOf(directory to listOf(contact)),
            "07921 910119",
            "GB"
        )!!
        val match = ContactDirectoryRules.toContactMatch(found.first, found.second)

        assertEquals("dir-personal", match.directoryId)
        assertEquals("Personal", match.directoryName)
        assertEquals("c1", match.contactId)
        assertEquals("El Cabasa Grande", match.displayName)
        assertEquals("", match.tagFields!!["companyName"])
        assertEquals("+447921910119", match.tagFields!!["phone1"])
        assertEquals("1024", match.tagFields!!["phone2"])
        assertEquals("", match.tagFields!!["phone3"])
    }
}
