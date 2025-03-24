package org.linphone.models.callhistory

import io.reactivex.rxjava3.core.Observable
import java.util.Date
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.services.PhoneFormatterService
import org.linphone.utils.DateUtils

class CallHistoryItemViewModel(val call: CallHistoryItem, val today: Date, val countryCode: String) {
    val callId: String = call.documentId
    val rowClass: String = if (call.missedCall) "missed" else ""
    val date: String = if (call.startTime != 0L) {
        DateUtils.formatFriendlyDate(
            Date(call.startTime),
            today
        )
    } else {
        ""
    }
    val time: String = if (call.startTime != 0L) DateUtils.toLocaleHMString(Date(call.startTime)) else ""
    var number: String = ""
    var formattedNumber: String = ""
    var fields: List<String> = buildFields()
    var icon: String = buildIcon()
    var contactIcon: String = buildContactMatchIcon()
    var contactLabel: String = buildContactMatchLabel()
    var isSelected: Boolean = false

    val canCall: Observable<Boolean> = Observable.defer {
        if (call.pbxType == PbxType.Teams) {
            Observable.just(!call.isConference)
        } else {
            Observable.just(coreContext.core.callsNb == 0)
        }
    }

    private fun retrieveFormattedNumber(number: String): String {
        return if (call.isConference && call.pbxType == PbxType.Teams) {
            "Microsoft Teams"
        } else {
            if (number.isNotEmpty()) {
                PhoneFormatterService.getInstance(coreContext.context).formatPhoneNumber(number)
            } else {
                "Unknown"
            }
        }
    }

    private fun buildFields(): List<String> {
        val name = call.contactName ?: if (call.callDirection == CallDirections.Incoming) call.callingUserName else call.calledUserName
        val route = call.routePathName ?: call.groupName ?: call.huntgroupName
        val fields = mutableListOf<String>()

        fields.add(name)
        if (formattedNumber.isNotEmpty() && formattedNumber != name) fields.add(formattedNumber)
        if (!route.contains(number ?: "")) fields.add("via $route")

        return fields
    }

    private fun buildIcon(): String {
        return when {
            call.isConference -> CallHistoryIcons.Conference.iconValue
            call.callDirection == CallDirections.Incoming -> {
                when {
                    call.missedCall -> CallHistoryIcons.CallMissed.iconValue
                    !call.answered -> CallHistoryIcons.CallNotAnswered.iconValue
                    else -> CallHistoryIcons.CallInbound.iconValue
                }
            }
            else -> CallHistoryIcons.CallOutbound.iconValue
        }
    }

    private fun buildContactMatchIcon(): String {
        return when (call.contactMatchType) {
            "ClioContactMatch" -> "assets/crm-icons/Clio.png"
            "Dynamics365ContactMatch" -> "assets/crm-icons/Microsoft.svg"
            "FreshdeskContactMatch" -> "assets/crm-icons/Freshdesk.svg"
            "SalesforceLightningContactMatch" -> "assets/crm-icons/Salesforce.svg"
            "ZendeskContactMatch" -> "assets/crm-icons/Zendesk.svg"
            "ZohoContactMatch" -> "assets/crm-icons/Zoho.png"
            else -> ""
        }
    }

    private fun buildContactMatchLabel(): String {
        return when (call.contactMatchType) {
            "ClioContactMatch" -> "Open Clio record"
            "Dynamics365ContactMatch" -> "Open Dynamics record"
            "FreshdeskContactMatch" -> "Open Freshdesk contact"
            "SalesforceLightningContactMatch" -> "Open Salesforce record"
            "ZendeskContactMatch" -> "Open Zendesk record"
            "ZohoContactMatch" -> "Open Zoho record"
            else -> ""
        }
    }
}
