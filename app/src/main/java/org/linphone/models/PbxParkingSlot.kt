package org.linphone.models

import com.google.gson.annotations.SerializedName

// A PBX parking slot from users/me parkingSlotCollection, e.g. name "Reception", number "101".
data class PbxParkingSlot(
    @SerializedName("name")
    val name: String = "",

    @SerializedName("number")
    val number: String = "",

    @SerializedName("enabled")
    val enabled: Boolean = false
)
