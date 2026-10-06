package org.linphone.models.blf

/**
 * The status text shown for a contact whose primary number has a BLF key. Ported from the web
 * client's contact-item getThirdLineDisplay; the text comes from the raw dialog state.
 */
object BlfDisplay {
    sealed class Text {
        object Available : Text()
        object OnACall : Text()
        object Ringing : Text()
        object DoNotDisturb : Text()

        // Feature code toggles (numbers starting with *)
        object Enabled : Text()
        object Disabled : Text()

        // Parking slots (numbers starting with the park and retrieve code)
        object Free : Text()
        data class CallParked(val displayNumber: String) : Text()
    }

    private val ringingStates = setOf("trying", "proceeding", "early")
    private const val CONFIRMED = "confirmed"

    fun text(key: BlfKey, primaryPhone: String, parkCode: String?, dndCode: String?): Text {
        val state = key.dialogState.lowercase()

        if (!parkCode.isNullOrEmpty() && primaryPhone.startsWith(parkCode)) {
            val parked = state in ringingStates || state == CONFIRMED
            return if (parked) Text.CallParked(key.displayNumber) else Text.Free
        }

        val featureToggle = primaryPhone.startsWith("*")
        return when {
            state in ringingStates -> if (featureToggle) Text.Enabled else Text.Ringing
            state == CONFIRMED -> when {
                featureToggle -> Text.Enabled
                !dndCode.isNullOrEmpty() && key.displayNumber.startsWith(dndCode) -> Text.DoNotDisturb
                else -> Text.OnACall
            }
            else -> if (featureToggle) Text.Disabled else Text.Available
        }
    }
}
