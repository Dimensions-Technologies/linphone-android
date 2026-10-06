package org.linphone.models.contact

import com.google.gson.annotations.SerializedName

data class FieldItemModel(
    @SerializedName("id")
    val id: String = "",

    // The list endpoints send "value"; create/update responses echo the "val" we send.
    @SerializedName(value = "value", alternate = ["val"])
    val value: String = ""
)
