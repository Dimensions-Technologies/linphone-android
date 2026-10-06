package org.linphone.models.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDirectoryRulesTest {

    private val personal = directory(
        name = "Personal",
        roles = mapOf("user-1" to ContactDirectoryRules.CONTRIBUTOR_ROLE)
    )

    private fun directory(
        name: String = "Personal",
        roles: Map<String, String> = emptyMap(),
        fields: List<FieldDefinitionModel> = standardFields()
    ) = ContactDirectoryModel(
        id = "dir-$name",
        tenantId = "tenant-1",
        name = name,
        fields = fields,
        userRoleAssociations = roles
    )

    private fun standardFields() = listOf(
        FieldDefinitionModel("id", "Id", "", DirectoryFieldTypes.ID),
        FieldDefinitionModel("fullName", "Full Name", "", DirectoryFieldTypes.CONTACT_NAME),
        FieldDefinitionModel("phone1", "Phone number", "", DirectoryFieldTypes.PHONE),
        FieldDefinitionModel("title", "Title", "", DirectoryFieldTypes.TEXT),
        FieldDefinitionModel("companyName", "Company Name", "", DirectoryFieldTypes.COMPANY_NAME),
        FieldDefinitionModel("email", "Email address", "", DirectoryFieldTypes.EMAIL),
        FieldDefinitionModel("phone2", "Phone 2", "", DirectoryFieldTypes.PHONE),
        FieldDefinitionModel("avatar", "Avatar", "", DirectoryFieldTypes.AVATAR),
        FieldDefinitionModel("blf", "BLF", "", DirectoryFieldTypes.BLF)
    )

    @Test
    fun `finds the Personal directory by name`() {
        val other = directory(name = "Suppliers")
        assertEquals(personal, ContactDirectoryRules.findPersonalDirectory(listOf(other, personal)))
        assertNull(ContactDirectoryRules.findPersonalDirectory(listOf(other)))
    }

    @Test
    fun `other listed directories leave out Personal, user, personal-type and HubSpot directories`() {
        val shared = directory(name = "Suppliers").copy(type = "TenantContactDirectory")
        val userDirectory = directory(name = "Users").copy(type = "UserContactDirectory")
        val otherPersonal = directory(name = "Someone's").copy(type = "PersonalContactDirectory")
        val hubspot = directory(name = "HubSpot").copy(type = "HubspotContactDirectory")
        val personalTyped = personal.copy(type = "PersonalContactDirectory")

        val listed = ContactDirectoryRules.otherListedDirectories(
            listOf(personalTyped, shared, userDirectory, otherPersonal, hubspot)
        )

        assertEquals(listOf(shared), listed)
        assertEquals(
            personalTyped,
            ContactDirectoryRules.findPersonalDirectory(listOf(hubspot, personalTyped))
        )
    }

    @Test
    fun `contributor role for the user or for everyone allows editing`() {
        assertTrue(ContactDirectoryRules.canContribute(personal, "user-1"))
        assertFalse(ContactDirectoryRules.canContribute(personal, "user-2"))
        assertFalse(ContactDirectoryRules.canContribute(personal, null))

        val everyone = directory(roles = mapOf("*" to ContactDirectoryRules.CONTRIBUTOR_ROLE))
        assertTrue(ContactDirectoryRules.canContribute(everyone, "user-2"))

        val readOnly = directory(roles = mapOf("user-1" to "customer.directories.reader"))
        assertFalse(ContactDirectoryRules.canContribute(readOnly, "user-1"))
    }

    @Test
    fun `missing role associations from the gateway mean no editing`() {
        val json = """{"id":"d1","name":"Personal"}"""
        val parsed = org.linphone.utils.GsonUtils.create().fromJson(
            json,
            ContactDirectoryModel::class.java
        )
        assertFalse(ContactDirectoryRules.canContribute(parsed, "user-1"))
    }

    @Test
    fun `additional fields leave out name, primary phone, avatar, id and BLF`() {
        val ids = ContactDirectoryRules.additionalFields(personal).map { it.id }
        assertEquals(listOf("title", "companyName", "email", "phone2"), ids)
    }

    @Test
    fun `name or company is required when the directory has both`() {
        assertFalse(ContactDirectoryRules.isNameOrCompanyValid(personal, mapOf("fullName" to "  ")))
        assertTrue(
            ContactDirectoryRules.isNameOrCompanyValid(personal, mapOf("fullName" to "Chris"))
        )
        assertTrue(
            ContactDirectoryRules.isNameOrCompanyValid(personal, mapOf("companyName" to "Xarios"))
        )
    }

    @Test
    fun `name is required when the directory has no company field`() {
        val noCompany = directory(fields = standardFields().filter { it.id != "companyName" })
        assertFalse(
            ContactDirectoryRules.isNameOrCompanyValid(noCompany, mapOf("companyName" to "Xarios"))
        )
        assertTrue(
            ContactDirectoryRules.isNameOrCompanyValid(noCompany, mapOf("fullName" to "Chris"))
        )
    }

    @Test
    fun `primary phone is required`() {
        assertFalse(ContactDirectoryRules.isPrimaryPhoneValid(personal, mapOf("phone1" to " ")))
        assertTrue(ContactDirectoryRules.isPrimaryPhoneValid(personal, mapOf("phone1" to "1024")))
    }

    @Test
    fun `request drops empty fields and converts external numbers to E164`() {
        val request = ContactDirectoryRules.buildRequest(
            personal,
            "",
            mapOf(
                "fullName" to " Chris Rawlinson ",
                "phone1" to "07968 543537",
                "phone2" to "(0161) 496-0000",
                "email" to "",
                "title" to "Mr"
            ),
            isInternalNumber = false,
            countryCode = "GB"
        )

        assertEquals("IContactItem", request.dtype)
        assertEquals("ContactItem", request.type)
        assertEquals("", request.id)
        assertEquals("tenant-1", request.tenantId)
        assertEquals("dir-Personal", request.directoryId)
        assertEquals(
            mapOf(
                "fullName" to "Chris Rawlinson",
                "phone1" to "+447968543537",
                "phone2" to "+441614960000",
                "title" to "Mr"
            ),
            request.fields.associate { it.id to it.value }
        )
    }

    @Test
    fun `internal numbers are kept as dialled and set the BLF field`() {
        val request = ContactDirectoryRules.buildRequest(
            personal,
            "contact-1",
            mapOf("fullName" to "Chris", "phone1" to "10 24", "blf" to "true"),
            isInternalNumber = true,
            countryCode = "GB"
        )

        assertEquals("contact-1", request.id)
        assertEquals(
            mapOf("fullName" to "Chris", "phone1" to "1024", "blf" to "true"),
            request.fields.associate { it.id to it.value }
        )
    }

    @Test
    fun `turning internal number off removes the BLF field`() {
        val request = ContactDirectoryRules.buildRequest(
            personal,
            "contact-1",
            mapOf("fullName" to "Chris", "phone1" to "01614960000", "blf" to "true"),
            isInternalNumber = false,
            countryCode = "GB"
        )

        assertFalse(request.fields.any { it.id == "blf" })
    }

    @Test
    fun `E164 keeps feature codes, short numbers and unparseable input`() {
        assertEquals("*72", ContactDirectoryRules.toE164("*72", "GB"))
        assertEquals("1024", ContactDirectoryRules.toE164("1024", "GB"))
        assertEquals("+14155550100", ContactDirectoryRules.toE164("+14155550100", "GB"))
        assertEquals("12345", ContactDirectoryRules.toE164("12345", "ZZ"))
    }

    @Test
    fun `internal number is read from the BLF field`() {
        val contact = ContactItemModel("c1", "dir-Personal", listOf(FieldItemModel("blf", "true")))
        assertTrue(ContactDirectoryRules.isInternalNumber(personal, contact))
        assertFalse(
            ContactDirectoryRules.isInternalNumber(personal, ContactItemModel("c2", "dir-Personal"))
        )
    }

    @Test
    fun `primary number is the first phone field with a value`() {
        val contact = ContactItemModel(
            "c1",
            "dir-Personal",
            listOf(
                FieldItemModel("phone1", " "),
                FieldItemModel("phone3", "1024"),
                FieldItemModel("phone2", "2048")
            )
        )
        assertEquals("2048", ContactDirectoryRules.primaryPhoneNumber(contact))
        assertNull(ContactDirectoryRules.primaryPhoneNumber(ContactItemModel("c2", "dir-Personal")))
    }

    @Test
    fun `secondary field is the first later display field with a value other than the number`() {
        val directory = personal.copy(
            displayFields = listOf("fullName", "phone1", "email", "companyName")
        )
        val contact = ContactItemModel(
            "c1",
            "dir-Personal",
            listOf(
                FieldItemModel("fullName", "Chris"),
                FieldItemModel("phone1", "1024"),
                FieldItemModel("email", ""),
                FieldItemModel("companyName", "Xarios")
            )
        )

        assertEquals(
            "companyName",
            ContactDirectoryRules.secondaryDisplayField(directory, contact)?.id
        )
        assertNull(
            ContactDirectoryRules.secondaryDisplayField(
                directory,
                ContactItemModel(
                    "c2",
                    "dir-Personal",
                    listOf(FieldItemModel("fullName", "Chris"), FieldItemModel("phone1", "1024"))
                )
            )
        )
    }

    @Test
    fun `line status is watched for favourite internal contacts in contributor directories`() {
        fun contact(id: String, phone: String, blf: Boolean) = ContactItemModel(
            id,
            "dir-Personal",
            listOfNotNull(
                FieldItemModel("phone1", phone),
                if (blf) FieldItemModel("blf", "true") else null
            )
        )
        val readOnly = directory(name = "Shared")

        val subscriptions = ContactDirectoryRules.blfSubscriptions(
            listOf(
                personal to listOf(
                    contact("fav-internal", "1024", true),
                    contact("fav-external", "+447968543537", true),
                    contact("fav-not-internal", "2048", false),
                    contact("not-fav", "3072", true)
                ),
                readOnly to listOf(contact("read-only", "4096", true))
            ),
            favouriteContactIds = setOf(
                "fav-internal",
                "fav-external",
                "fav-not-internal",
                "read-only"
            ),
            userId = "user-1"
        )

        assertEquals(mapOf("fav-internal" to "1024"), subscriptions)
    }

    @Test
    fun `call history numbers match contacts in E164 form`() {
        val mobile = ContactItemModel(
            "c1",
            "dir-Personal",
            listOf(FieldItemModel("phone2", "07921 910119"))
        )
        val extension = ContactItemModel(
            "c2",
            "dir-Personal",
            listOf(FieldItemModel("phone1", "1024"))
        )
        val notAPhoneField = ContactItemModel(
            "c3",
            "dir-Personal",
            listOf(FieldItemModel("title", "+441614960000"))
        )
        val directories = listOf(personal to listOf(notAPhoneField, mobile, extension))

        assertEquals(
            mobile,
            ContactDirectoryRules.findContactByPhone(directories, "+447921910119", "GB")
        )
        assertEquals(extension, ContactDirectoryRules.findContactByPhone(directories, "1024", "GB"))
        assertNull(ContactDirectoryRules.findContactByPhone(directories, "+441614960000", "GB"))
        assertNull(ContactDirectoryRules.findContactByPhone(directories, "", "GB"))
    }
}
