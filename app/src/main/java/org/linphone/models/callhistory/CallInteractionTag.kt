package org.linphone.models.callhistory

import com.google.gson.annotations.SerializedName
import java.util.Date

data class CallInteractionTag(
    @SerializedName("id")
    val id: String?,

    @SerializedName("type")
    val type: String?,

    @SerializedName("name")
    val name: String?,

    @SerializedName("value")
    val value: String?,

    @SerializedName("required")
    val required: Boolean?,

    @SerializedName("userId")
    val userId: String?,

    @SerializedName("timestamp")
    val timestamp: Date?
)
