package org.linphone.models.contact

import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import java.text.Collator
import org.linphone.models.realtime.ContactMatch

/**
 * Rules for reading and editing contact directories, ported from the Connect web client
 * (directories.service.ts and contact-directory-add-edit.component.ts) so both clients save
 * contacts the same way.
 */
object ContactDirectoryRules {
    // The Personal directory is identified by name, as on the web client.
    const val PERSONAL_DIRECTORY_NAME = "Personal"
    const val CONTRIBUTOR_ROLE = "customer.directories.contributor"

    private const val ALL_USERS = "*"
    private const val BLF_ENABLED = "true"

    // Never shown as a group (the web client leaves these out entirely)
    private val excludedDirectoryTypes = setOf("HubspotContactDirectory")

    // Not shown as groups besides the Personal directory itself
    private val unlistedDirectoryTypes = setOf("PersonalContactDirectory", "UserContactDirectory")

    fun findPersonalDirectory(directories: List<ContactDirectoryModel>): ContactDirectoryModel? =
        directories.firstOrNull { it.name == PERSONAL_DIRECTORY_NAME && it.type !in excludedDirectoryTypes }

    // The directories, other than Personal, listed as groups in the contacts drop-down, as on the web client.
    fun otherListedDirectories(directories: List<ContactDirectoryModel>): List<ContactDirectoryModel> =
        directories.filter {
            it.name != PERSONAL_DIRECTORY_NAME &&
                it.type !in excludedDirectoryTypes &&
                it.type !in unlistedDirectoryTypes
        }

    /**
     * The directories numbers are matched against (calls, call history, voicemail, parking): every
     * directory but the excluded types, in the gateway's order, as the web client's
     * directories.service.ts `directories`. This includes the unlisted ones, e.g. the users directory.
     */
    fun matchedDirectories(directories: List<ContactDirectoryModel>): List<ContactDirectoryModel> =
        directories.filter { it.type !in excludedDirectoryTypes }

    // Whether the user can add, edit and delete contacts in the directory.
    fun canContribute(directory: ContactDirectoryModel, userId: String?): Boolean {
        // Gson ignores Kotlin defaults, so a missing userRoleAssociations arrives as null.
        val roles: Map<String, String>? = directory.userRoleAssociations
        if (roles == null) return false
        return (userId != null && roles[userId] == CONTRIBUTOR_ROLE) ||
            roles[ALL_USERS] == CONTRIBUTOR_ROLE
    }

    fun nameField(directory: ContactDirectoryModel) = fieldOfType(
        directory,
        DirectoryFieldTypes.CONTACT_NAME
    )

    fun companyNameField(directory: ContactDirectoryModel) = fieldOfType(
        directory,
        DirectoryFieldTypes.COMPANY_NAME
    )

    fun primaryPhoneField(directory: ContactDirectoryModel) = fieldOfType(
        directory,
        DirectoryFieldTypes.PHONE
    )

    fun blfField(directory: ContactDirectoryModel) = fieldOfType(directory, DirectoryFieldTypes.BLF)

    // Everything shown under "Additional fields" on the form: all but the name, primary phone,
    // avatar, id and BLF fields, in the directory's order.
    fun additionalFields(directory: ContactDirectoryModel): List<FieldDefinitionModel> {
        val primaryPhone = primaryPhoneField(directory)
        return directory.fields.filter {
            it.definitionType != DirectoryFieldTypes.CONTACT_NAME &&
                it.definitionType != DirectoryFieldTypes.ID &&
                it.definitionType != DirectoryFieldTypes.AVATAR &&
                it.definitionType != DirectoryFieldTypes.BLF &&
                it !== primaryPhone
        }
    }

    // The number shown and dialled for a contact in lists, as on the web client.
    fun primaryPhoneNumber(contact: ContactItemModel): String? {
        val values = contact.fields.associate { it.id to it.value }
        return listOf(
            ContactItemModel.PHONE1,
            ContactItemModel.PHONE2,
            ContactItemModel.PHONE3,
            ContactItemModel.PHONE4
        )
            .firstNotNullOfOrNull { id -> values[id]?.takeIf { it.isNotBlank() } }
    }

    /**
     * The line shown under the number in contact lists: the first of the directory's display fields
     * after the first (the name) that has a value other than the primary number. Mirrors the web
     * client's contact-item fallback line.
     */
    fun secondaryDisplayField(directory: ContactDirectoryModel, contact: ContactItemModel): FieldItemModel? {
        val primaryPhone = primaryPhoneNumber(contact)
        val values = contact.fields.associateBy { it.id }
        return directory.displayFields.orEmpty().drop(1)
            .mapNotNull { values[it] }
            .firstOrNull { it.value.isNotBlank() && it.value != primaryPhone }
    }

    fun isPhoneFieldId(id: String) = id.startsWith("phone")

    /**
     * The cached contacts with any field containing the search text, ignoring case, as the web
     * client's searchDirectoryLocally. Shown straight away while the gateway search runs.
     */
    fun searchCachedContacts(contacts: List<ContactItemModel>, searchText: String): List<ContactItemModel> {
        val lower = searchText.lowercase()
        return contacts.filter { contact -> contact.fields.any { it.value.lowercase().contains(lower) } }
    }

    /**
     * A directory's cached matches followed by the gateway's matches that aren't among them (by
     * contact id), as the web client's mergeSearchResults. The cache only holds the first 100
     * contacts of each directory, so the gateway can find more.
     */
    /**
     * The directories in the contacts selector's order (UserGroupService.mergeUserGroups): Personal
     * first, then the other listed directories by name. Search results are grouped by directory in
     * this order; directories the selector doesn't list (e.g. the users directory) go at the end, by name.
     */
    fun selectorOrder(directories: List<ContactDirectoryModel>): List<ContactDirectoryModel> {
        val collator = Collator.getInstance()
        val byName = Comparator<ContactDirectoryModel> { a, b -> collator.compare(a.name, b.name) }
        val personal = findPersonalDirectory(directories)
        val listed = otherListedDirectories(directories).sortedWith(byName)
        val unlisted = directories.filter { it !== personal && it !in listed }.sortedWith(byName)
        return listOfNotNull(personal) + listed + unlisted
    }

    /**
     * Where a search result is listed: its directory's place in [order], then users, then device
     * contacts last (as in the selector). A directory not in [order] goes after the known ones.
     */
    fun searchSectionRank(order: List<ContactDirectoryModel>, directoryId: String?, isUser: Boolean): Int =
        when {
            directoryId != null -> order.indexOfFirst { it.id == directoryId }.takeIf { it >= 0 } ?: order.size
            isUser -> order.size + 1
            else -> order.size + 2
        }

    fun mergeSearchResults(
        local: List<ContactItemModel>,
        server: List<ContactItemModel>
    ): List<ContactItemModel> {
        val localIds = local.map { it.id }.toSet()
        return local + server.filter { it.id !in localIds }
    }

    /**
     * The first contact with a phone field matching the number, compared in E.164 form using the PBX
     * country, as the web client matches call history to contacts (findContactAndDirByPhone).
     */
    fun findContactByPhone(
        directories: List<Pair<ContactDirectoryModel, List<ContactItemModel>>>,
        number: String,
        countryCode: String
    ): ContactItemModel? = findContactAndDirectoryByPhone(directories, number, countryCode)?.second

    fun findContactAndDirectoryByPhone(
        directories: List<Pair<ContactDirectoryModel, List<ContactItemModel>>>,
        number: String,
        countryCode: String
    ): Pair<ContactDirectoryModel, ContactItemModel>? {
        // A name (e.g. a parked caller's BLF display "Draper 2") isn't a number and matches nothing
        val target = toMatchable(number)?.let { toE164(it, countryCode) } ?: return null
        for ((directory, contacts) in directories) {
            val phoneFieldIds = directory.fields
                .filter { it.definitionType == DirectoryFieldTypes.PHONE }
                .map { it.id }
                .toSet()
            val match = contacts.firstOrNull { contact ->
                contact.fields.any { field ->
                    field.id in phoneFieldIds &&
                        toMatchable(field.value)?.let { toE164(it, countryCode) } == target
                }
            }
            if (match != null) return directory to match
        }
        return null
    }

    /**
     * A directory contact as a live call match, as the web client builds one from a local lookup
     * (findContactMatchByPhone): its name, company and phone numbers by tag name.
     */
    fun toContactMatch(directory: ContactDirectoryModel, contact: ContactItemModel): ContactMatch {
        val values = contact.fields.associate { it.id to it.value }
        val phones = directory.fields
            .filter { it.definitionType == DirectoryFieldTypes.PHONE }
            .mapNotNull { values[it.id] }
        val tagFields = mutableMapOf<String, String?>(
            "fullName" to nameField(directory)?.let { values[it.id] }.orEmpty(),
            "companyName" to companyNameField(directory)?.let { values[it.id] }.orEmpty()
        )
        for (i in 0 until 4) tagFields["phone${i + 1}"] = phones.getOrNull(i).orEmpty()
        return ContactMatch(directory.name, directory.id, false, contact.id, tagFields)
    }

    /**
     * Whether matches from a directory must be ignored: one the user's directory list doesn't include
     * (e.g. a server-side matching source), or an excluded type. As the web client's isDirectoryDenied.
     */
    fun isDirectoryDenied(directories: List<ContactDirectoryModel>, directoryId: String?): Boolean {
        val directory = directories.firstOrNull { it.id == directoryId } ?: return true
        return directory.type in excludedDirectoryTypes
    }

    fun filterDeniedMatches(
        directories: List<ContactDirectoryModel>,
        matches: List<ContactMatch>
    ): List<ContactMatch> = matches.filter { !isDirectoryDenied(directories, it.directoryId) }

    /**
     * As filterDeniedMatches, once the directory list has loaded: until then every directory reads as
     * denied, so matches that arrive early (e.g. a call soon after the app starts) would be lost. As
     * the web client's filterDeniedMatches, which waits for the directory metadata first.
     */
    fun filterDeniedMatches(
        directories: Observable<List<ContactDirectoryModel>>,
        matches: List<ContactMatch>
    ): Single<List<ContactMatch>> =
        if (matches.isEmpty()) {
            Single.just(matches)
        } else {
            directories.firstOrError().map { filterDeniedMatches(it, matches) }
        }

    /**
     * Contact id to number for the contacts whose line status (BLF) is watched, as on the web client
     * (blf-subscription-manager.service.ts): favourites in directories the user can contribute to,
     * marked as an internal number, with a primary number that isn't in E.164 (external) form.
     */
    fun blfSubscriptions(
        directories: List<Pair<ContactDirectoryModel, List<ContactItemModel>>>,
        favouriteContactIds: Set<String>,
        userId: String?
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((directory, contacts) in directories) {
            if (!canContribute(directory, userId) || blfField(directory) == null) continue
            for (contact in contacts) {
                if (contact.id !in favouriteContactIds) continue
                if (!isInternalNumber(directory, contact)) continue
                val phone = primaryPhoneNumber(contact) ?: continue
                if (phone.startsWith("+")) continue
                result[contact.id] = phone
            }
        }
        return result
    }

    fun isInternalNumber(directory: ContactDirectoryModel, contact: ContactItemModel): Boolean {
        val blf = blfField(directory) ?: return false
        return contact.fields.any { it.id == blf.id && it.value == BLF_ENABLED }
    }

    // A name or a company is required when the directory has both fields; a name when it only has that.
    fun isNameOrCompanyValid(directory: ContactDirectoryModel, values: Map<String, String>): Boolean {
        val name = nameField(directory)?.let { values[it.id]?.trim() }.orEmpty()
        val company = companyNameField(directory)?.let { values[it.id]?.trim() }.orEmpty()
        return when {
            nameField(directory) != null && companyNameField(directory) != null -> name.isNotEmpty() || company.isNotEmpty()
            nameField(directory) != null -> name.isNotEmpty()
            else -> true
        }
    }

    fun isPrimaryPhoneValid(directory: ContactDirectoryModel, values: Map<String, String>): Boolean {
        val phone = primaryPhoneField(directory) ?: return true
        return values[phone.id]?.isNotBlank() == true
    }

    /**
     * Builds the create/update body from the form values (field id to value). Empty fields are left
     * out, phone numbers are reduced to dialable characters and, unless the contact is an internal
     * number, converted to E.164 using the PBX country.
     */
    fun buildRequest(
        directory: ContactDirectoryModel,
        contactId: String,
        values: Map<String, String>,
        isInternalNumber: Boolean,
        countryCode: String
    ): ContactItemRequest {
        val blf = blfField(directory)
        val phoneFieldIds = directory.fields
            .filter { it.definitionType == DirectoryFieldTypes.PHONE }
            .map { it.id }
            .toSet()

        val fields = values
            .filter { (id, value) -> value.isNotBlank() && id != blf?.id }
            .map { (id, value) ->
                val formatted = if (id in phoneFieldIds) {
                    val dialable = toDialable(value)
                    if (isInternalNumber) dialable else toE164(dialable, countryCode)
                } else {
                    value.trim()
                }
                ContactItemRequestField(id, formatted)
            }
            .toMutableList()

        if (isInternalNumber && blf != null) {
            fields.add(ContactItemRequestField(blf.id, BLF_ENABLED))
        }

        return ContactItemRequest(
            id = contactId,
            tenantId = directory.tenantId,
            directoryId = directory.id,
            fields = fields
        )
    }

    fun toDialable(number: String): String = number.replace(Regex("[^0-9+#*]"), "")

    /**
     * The number to compare when matching, or null if it isn't one. Only formatting (spaces, dashes,
     * dots and brackets) is dropped, as the web client's formatPhoneNumberToE164 cleans it; any
     * other text means it's a name, which must not be reduced to its digits ("Draper 2" isn't "2").
     */
    fun toMatchable(number: String): String? =
        number.replace(Regex("[\\s\\-().]"), "").takeIf { Regex("^[+*#0-9]*[0-9][+*#0-9]*$").matches(it) }

    // Mirrors formatPhoneNumberToE164 on the web client: feature codes and short numbers are kept as is.
    fun toE164(number: String, countryCode: String): String {
        if (number.startsWith("*") || !Regex("^[+*#0-9]{5,}$").matches(number)) return number
        return try {
            val util = PhoneNumberUtil.getInstance()
            util.format(util.parse(number, countryCode), PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (e: Exception) {
            number
        }
    }

    private fun fieldOfType(directory: ContactDirectoryModel, type: String) =
        directory.fields.firstOrNull { it.definitionType == type }
}
