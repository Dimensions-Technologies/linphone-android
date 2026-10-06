package org.linphone.models.blf

// BLF (busy lamp field) state of a number, from SIP dialog event (RFC 4235) notifications.
enum class BlfKeyStatus {
    IDLE,
    RINGING,
    INCALL,
    UNKNOWN
}

/**
 * @param id the number subscribed to
 * @param dialogState the raw <state> of the reported dialog ("confirmed", "early", ...), empty if none
 * @param displayNumber the display name of the dialog's remote identity, empty if none
 */
data class BlfKey(
    val id: String,
    val status: BlfKeyStatus,
    val dialogState: String = "",
    val displayNumber: String = ""
)
