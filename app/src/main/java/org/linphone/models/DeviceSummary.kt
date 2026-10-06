package org.linphone.models

import com.google.gson.annotations.SerializedName

// One of the user's devices (softphones and PBX handsets), as listed by users/me/devices
data class DeviceSummary(
    @SerializedName("deviceId")
    val deviceId: String = "",

    @SerializedName("deviceName")
    val deviceName: String = "",

    // UCB web, UCD desktop, UCM mobile, UCT Teams, UCW embedded CRM; else a PBX model
    @SerializedName("model")
    val model: String = ""
)
