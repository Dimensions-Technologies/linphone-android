package org.linphone.models.contact

import com.google.gson.annotations.SerializedName

data class ContactDirectoryModel(
    @SerializedName("Id")
    val id: String = "",

    @SerializedName("TenantId")
    val tenantId: String = "",

    @SerializedName("Name")
    val name: String = "",

    @SerializedName("Description")
    val description: String = "",

    @SerializedName("Fields")
    val fields: List<FieldDefinitionModel> = emptyList(),

    @SerializedName("DisplayFields")
    val displayFields: List<String> = emptyList(),

    @SerializedName("TagFields")
    val tagFields: List<String> = emptyList(),

    @SerializedName("UserRoleAssociations")
    val userRoleAssociations: Map<String, String>,

    @SerializedName("Items")
    val items: List<Int> = emptyList(),

    @SerializedName("AvatarDisplayFieldId")
    val avatarDisplayFieldId: String = ""
)
