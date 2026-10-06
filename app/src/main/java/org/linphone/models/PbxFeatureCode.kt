package org.linphone.models

import com.google.gson.annotations.SerializedName

// A PBX feature code available to the user, e.g. name "park_and_retrieve", number "*3".
data class PbxFeatureCode(
    @SerializedName("number")
    val number: String = "",

    @SerializedName("name")
    val name: String = ""
) {
    companion object {
        const val PARK_AND_RETRIEVE = "park_and_retrieve"
        const val DIMENSIONS_DND_TOGGLE = "DimensionsFeatureCode_DndToggle"
    }
}
