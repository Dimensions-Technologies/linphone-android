package org.linphone.models.contact

import com.google.gson.annotations.SerializedName

data class ContactItemModel(
    @SerializedName("id")
    val id: String = "",

    @SerializedName("directoryid")
    val directoryId: String = "",

    @SerializedName("fields")
    val fields: List<FieldItemModel> = emptyList()
)
