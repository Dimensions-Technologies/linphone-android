package org.linphone.models.callhistory

import com.google.gson.annotations.SerializedName

data class UserCallHistorySummary(
    @SerializedName("userId")
    val userId: String,

    @SerializedName("missedCallTimestamp")
    val missedCallTimestamp: String // Handling as a string rather than a offsetDatetime due to API level issues
)
