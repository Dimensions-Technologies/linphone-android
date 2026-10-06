package org.linphone.models.parking

import org.linphone.models.UserInfo
import org.linphone.models.blf.BlfKey
import org.linphone.models.blf.BlfKeyStatus

/** A parking slot the user is offered, with the number dialled for it (park code + slot). */
data class ParkingSlotDefinition(
    val name: String,
    val number: String,
    val isPersonal: Boolean
)

// The slot's dot colour: idle green, occupied red, unknown grey
enum class ParkingSlotStatus {
    IDLE,
    OCCUPIED,
    UNKNOWN
}

/**
 * A parking slot with its live state from BLF.
 * @param callerNumber the parked caller's number, set only while a call is parked with one
 * @param callerName the parked caller's contact name, if the number matched a contact
 */
data class ParkingSlot(
    val name: String,
    val number: String,
    val isPersonal: Boolean,
    val status: ParkingSlotStatus,
    val isParked: Boolean,
    val callerNumber: String? = null,
    val callerName: String? = null
)

enum class ParkingSlotAction {
    NONE,
    PARK,
    RETRIEVE,
    HOLD_AND_RETRIEVE
}

/**
 * Parking slots, ported from the web client (parking-slot.service.ts and models/parking-slot.ts).
 * Parking is all SIP: a call is parked by blind transferring it to the park code followed by the
 * slot (or a user's extension, for their personal slot), and retrieved by calling that number.
 * The slot's state comes from a BLF subscription to the same number.
 */
object ParkingSlotRules {
    private val parkedDialogStates = setOf("trying", "proceeding", "early", "confirmed")

    /**
     * The slots offered to the user: their personal slot if the profile exposes it, then the PBX
     * slots that are enabled (or all of them, if the profile exposes all). None without a park code.
     */
    fun definitions(
        user: UserInfo?,
        parkCode: String?,
        personalSlotName: String
    ): List<ParkingSlotDefinition> {
        if (user == null || parkCode.isNullOrEmpty()) return emptyList()
        val profile = user.clientProfileSettings

        val definitions = mutableListOf<ParkingSlotDefinition>()
        if (profile.exposePersonalParkingSlotsEnabled && user.presenceId.isNotEmpty()) {
            definitions.add(
                ParkingSlotDefinition(personalSlotName, parkCode + user.presenceId, true)
            )
        }
        for (slot in user.parkingSlotCollection.orEmpty()) {
            if (profile.exposeAllParkingSlotsEnabled || slot.enabled) {
                definitions.add(ParkingSlotDefinition(slot.name, parkCode + slot.number, false))
            }
        }
        return definitions
    }

    fun status(blfStatus: BlfKeyStatus?): ParkingSlotStatus = when (blfStatus) {
        BlfKeyStatus.IDLE -> ParkingSlotStatus.IDLE
        BlfKeyStatus.RINGING, BlfKeyStatus.INCALL -> ParkingSlotStatus.OCCUPIED
        else -> ParkingSlotStatus.UNKNOWN
    }

    // Whether the dialog state means a call is sitting in the slot
    fun isParked(dialogState: String?): Boolean =
        dialogState?.lowercase() in parkedDialogStates

    /**
     * The slot's state from its BLF key. [findCallerName] looks the parked caller's number up in the
     * user's contacts.
     */
    fun slot(
        definition: ParkingSlotDefinition,
        key: BlfKey?,
        findCallerName: (String) -> String?
    ): ParkingSlot {
        val isParked = isParked(key?.dialogState)
        val callerNumber = key?.displayNumber?.takeIf { isParked && it.isNotEmpty() }
        val callerName = callerNumber?.let { number ->
            findCallerName(number)?.takeIf { it.isNotBlank() && !isSameDigits(it, number) }
        }
        return ParkingSlot(
            definition.name,
            definition.number,
            definition.isPersonal,
            status(key?.status),
            isParked,
            callerNumber,
            callerName
        )
    }

    /**
     * What tapping a slot does: park the current call in a slot that isn't occupied, or retrieve
     * the call from an occupied one (holding the current call first).
     */
    fun action(slot: ParkingSlot, hasCurrentCall: Boolean): ParkingSlotAction {
        val occupied = slot.status == ParkingSlotStatus.OCCUPIED
        return when {
            hasCurrentCall && occupied -> ParkingSlotAction.HOLD_AND_RETRIEVE
            hasCurrentCall -> ParkingSlotAction.PARK
            occupied -> ParkingSlotAction.RETRIEVE
            else -> ParkingSlotAction.NONE
        }
    }

    // The personal slot's parked call, which the user is told about
    fun personalParkedCall(slots: List<ParkingSlot>): ParkingSlot? =
        slots.firstOrNull { it.isPersonal && it.callerNumber != null }

    fun occupiedCount(slots: List<ParkingSlot>): Int =
        slots.count { it.status == ParkingSlotStatus.OCCUPIED }

    // Slots a call can be parked in: occupied ones are left out
    fun parkable(slots: List<ParkingSlot>): List<ParkingSlot> =
        slots.filter { it.status != ParkingSlotStatus.OCCUPIED }

    // Some contacts have their number as their name; that's no name at all
    private fun isSameDigits(a: String, b: String): Boolean {
        val digits = a.filter { it.isDigit() }
        return digits.isNotEmpty() && digits == b.filter { it.isDigit() }
    }
}
