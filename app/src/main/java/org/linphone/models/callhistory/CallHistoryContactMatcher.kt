package org.linphone.models.callhistory

import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.contact.ContactItemModel
import org.linphone.utils.GsonUtils
import org.linphone.utils.Log

/**
 * Names external calls after the directory contact with the other party's number, as the web client
 * does (call-history.service.ts enrichWithContactNames): the contact's name, or else its company.
 * A matched call is marked hasContactMatch, which also stops it offering "Add contact".
 */
object CallHistoryContactMatcher {
    fun enrich(
        items: List<CallHistoryItem>,
        directories: List<Pair<ContactDirectoryModel, List<ContactItemModel>>>,
        countryCode: String
    ): List<CallHistoryItem> {
        if (directories.isEmpty()) return items
        return items.map { item ->
            if (item.callType != CallTypes.External || item.hasContactMatch || item.isConference) {
                return@map item
            }
            val number = otherPartyNumber(item)
            if (number.isNullOrBlank()) return@map item

            val (directory, contact) = ContactDirectoryRules.findContactAndDirectoryByPhone(
                directories,
                number,
                countryCode
            ) ?: return@map item
            val name = contactName(directory, contact) ?: return@map item
            matched(item, name) ?: item
        }
    }

    /**
     * The call named after its matched contact. Not data class copy(): Gson leaves fields the gateway
     * omits (e.g. interactionTags) null whatever their Kotlin type, and copy() throws on those. A
     * Gson round trip, as the history cache does, keeps them as they came.
     */
    private fun matched(item: CallHistoryItem, name: String): CallHistoryItem? {
        return try {
            val json = gson.toJsonTree(item).asJsonObject
            json.addProperty("contactName", name)
            json.addProperty("hasContactMatch", true)
            gson.fromJson(json, CallHistoryItem::class.java)
        } catch (e: Exception) {
            Log.e(e, "Couldn't name call ${item.documentId} after its contact")
            null
        }
    }

    private val gson by lazy { GsonUtils.create() }

    fun otherPartyNumber(item: CallHistoryItem): String? =
        if (CallDirections.fromValue(item.callDirection) == CallDirections.Outgoing) {
            item.calledUserNumber
        } else {
            item.callingUserNumber
        }

    private fun contactName(directory: ContactDirectoryModel, contact: ContactItemModel): String? {
        val values = contact.fields.associate { it.id to it.value }
        val name = ContactDirectoryRules.nameField(directory)?.let { values[it.id] }
        val company = ContactDirectoryRules.companyNameField(directory)?.let { values[it.id] }
        return name?.takeIf { it.isNotBlank() } ?: company?.takeIf { it.isNotBlank() }
    }
}
