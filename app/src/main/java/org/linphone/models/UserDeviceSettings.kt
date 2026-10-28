package org.linphone.models

import com.google.gson.annotations.SerializedName

// The gateway applies these to all the user's softphone devices (see putUserDeviceSettings)
data class UserDeviceSettings(
    @SerializedName("callWaitingEnabled")
    val callWaitingEnabled: Boolean
)
