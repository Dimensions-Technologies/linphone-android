package org.linphone.models.contact

import com.google.gson.annotations.SerializedName

data class FieldDefinitionModel(
    @SerializedName("Id")
    var id: String = "",

    @SerializedName("Name")
    var name: String = "",

    @SerializedName("Validation")
    var validation: String = "",

    @SerializedName("DefinitionType")
    var definitionType: String = ""
)
