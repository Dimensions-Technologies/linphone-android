package org.linphone.models.contact

import com.google.gson.annotations.SerializedName

// The contactItem part of a contact directory create/update request.
data class ContactItemRequest(
    @SerializedName("dtype")
    val dtype: String = "IContactItem",

    @SerializedName("_type")
    val type: String = "ContactItem",

    @SerializedName("id")
    val id: String = "",

    @SerializedName("tenantId")
    val tenantId: String = "",

    @SerializedName("dId")
    val directoryId: String = "",

    @SerializedName("fields")
    val fields: List<ContactItemRequestField> = emptyList()
)

data class ContactItemRequestField(
    @SerializedName("id")
    val id: String,

    @SerializedName("val")
    val value: String
)
