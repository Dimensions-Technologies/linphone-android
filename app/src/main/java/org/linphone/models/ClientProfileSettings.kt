package org.linphone.models

import com.google.gson.annotations.SerializedName

data class ClientProfileSettings(
    @SerializedName("presenceSelectionEnabled")
    var presenceSelectionEnabled: Boolean = false,

    @SerializedName("presenceOverrideEnabled")
    var presenceOverrideEnabled: Boolean = false,

    @SerializedName("queueControlEnabled")
    var queueControlEnabled: Boolean = false,

    @SerializedName("agentControlDisplayed")
    var agentControlDisplayed: Boolean = false,

    // Offer the user's own parking slot (park code + their extension)
    @SerializedName("exposePersonalParkingSlotsEnabled")
    var exposePersonalParkingSlotsEnabled: Boolean = false,

    // Offer every PBX parking slot, not only the enabled ones
    @SerializedName("exposeAllParkingSlotsEnabled")
    var exposeAllParkingSlotsEnabled: Boolean = false,

    // Show the outgoing caller ID choice
    @SerializedName("externalCallIdEnabled")
    var externalCallIdEnabled: Boolean = false,

    // Whether the account's own caller ID can be chosen, rather than only being shown
    @SerializedName("defaultExternalCallerIdEnabled")
    var defaultExternalCallerIdEnabled: Boolean = false
)
