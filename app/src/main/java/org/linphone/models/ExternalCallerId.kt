package org.linphone.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/** A caller ID the user's policy allows them to present on outgoing calls (users/me externalCallerIdPolicy). */
@Keep
data class ExternalCallerId(
    @SerializedName("name")
    val name: String? = null,

    @SerializedName("callerId")
    val callerId: String? = null
)
