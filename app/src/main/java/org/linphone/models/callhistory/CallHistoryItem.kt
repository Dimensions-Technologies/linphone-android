package org.linphone.models.callhistory

import com.google.gson.annotations.SerializedName

data class CallHistoryItem(
    @SerializedName("missedCall")
    val missedCall: Boolean,

    @SerializedName("answered")
    val answered: Boolean,

    @SerializedName("hasRecording")
    val hasRecording: Boolean,

    @SerializedName("startTime")
    val startTime: Long,

    @SerializedName("connectionId")
    val connectionId: String,

    @SerializedName("callType")
    val callType: CallTypes,

    @SerializedName("callDirection")
    val callDirection: CallDirections,

    @SerializedName("contactName")
    val contactName: String,

    @SerializedName("contactMatchType")
    val contactMatchType: String,

    @SerializedName("hasContactMatch")
    val hasContactMatch: Boolean,

    @SerializedName("calledUserName")
    val calledUserName: String,

    @SerializedName("calledUserNumber")
    val calledUserNumber: String,

    @SerializedName("callingUserName")
    val callingUserName: String,

    @SerializedName("callingUserNumber")
    val callingUserNumber: String,

    @SerializedName("routePathName")
    val routePathName: String,

    @SerializedName("groupName")
    val groupName: String,

    @SerializedName("huntgroupName")
    val huntgroupName: String,

    @SerializedName("documentId")
    val documentId: String,

    @SerializedName("isConference")
    val isConference: Boolean,

    @SerializedName("pbxType")
    val pbxType: PbxType,

    @SerializedName("interactionTags")
    val interactionTags: ArrayList<CallInteractionTag>
)
