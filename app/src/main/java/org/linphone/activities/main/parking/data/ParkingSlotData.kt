package org.linphone.activities.main.parking.data

import android.content.Context
import org.linphone.R
import org.linphone.core.ConsolidatedPresence
import org.linphone.models.parking.ParkingSlot
import org.linphone.models.parking.ParkingSlotStatus
import org.linphone.services.PhoneFormatterService

/** A parking slot as listed: "name: status" and a coloured dot, as on the web client. */
class ParkingSlotData(val slot: ParkingSlot, context: Context) {
    val label: String = context.getString(
        R.string.parking_slot_label,
        slot.name,
        statusText(slot, context)
    )

    // The dot shares the contacts' BLF colours: idle green, occupied red, unknown grey
    val presence: ConsolidatedPresence = when (slot.status) {
        ParkingSlotStatus.IDLE -> ConsolidatedPresence.Online
        ParkingSlotStatus.OCCUPIED -> ConsolidatedPresence.DoNotDisturb
        ParkingSlotStatus.UNKNOWN -> ConsolidatedPresence.Offline
    }

    companion object {
        // "Free", else the parked caller's name or number, else "Occupied"
        fun statusText(slot: ParkingSlot, context: Context): String {
            if (!slot.isParked) return context.getString(R.string.blf_free)
            return slot.callerName
                ?: slot.callerNumber?.let { formatNumber(it, context) }
                ?: context.getString(R.string.parking_slot_occupied)
        }

        // "Name (number)", else the number
        fun callerDescription(slot: ParkingSlot, context: Context): String {
            val number = slot.callerNumber?.let { formatNumber(it, context) }.orEmpty()
            val name = slot.callerName ?: return number
            return context.getString(R.string.parking_slot_caller, name, number)
        }

        private fun formatNumber(number: String, context: Context) =
            PhoneFormatterService.getInstance(context).formatDisplayNumber(number)
    }
}
