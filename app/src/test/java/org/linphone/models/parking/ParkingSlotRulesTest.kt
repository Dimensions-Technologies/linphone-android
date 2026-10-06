package org.linphone.models.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.models.ClientProfileSettings
import org.linphone.models.PbxParkingSlot
import org.linphone.models.UserInfo
import org.linphone.models.blf.BlfKey
import org.linphone.models.blf.BlfKeyStatus

class ParkingSlotRulesTest {

    private val parkCode = "*3"

    private fun user(
        personal: Boolean = false,
        all: Boolean = false,
        presenceId: String = "1024",
        slots: List<PbxParkingSlot>? = listOf(
            PbxParkingSlot("Reception", "101", true),
            PbxParkingSlot("Warehouse", "102", false)
        )
    ) = UserInfo(
        presenceId = presenceId,
        clientProfileSettings = ClientProfileSettings(
            exposePersonalParkingSlotsEnabled = personal,
            exposeAllParkingSlotsEnabled = all
        ),
        parkingSlotCollection = slots
    )

    private fun definitions(user: UserInfo?, code: String? = parkCode) =
        ParkingSlotRules.definitions(user, code, "Personal Slot")

    private val reception = ParkingSlotDefinition("Reception", "*3101", false)

    private fun slot(
        status: ParkingSlotStatus,
        isPersonal: Boolean = false,
        callerNumber: String? = null
    ) =
        ParkingSlot("Slot", "*3101", isPersonal, status, callerNumber != null, callerNumber)

    @Test
    fun `slots are the enabled PBX slots, dialled with the park code`() {
        assertEquals(listOf(reception), definitions(user()))
    }

    @Test
    fun `exposing all slots includes the disabled ones`() {
        assertEquals(
            listOf("*3101", "*3102"),
            definitions(user(all = true)).map { it.number }
        )
    }

    @Test
    fun `the personal slot is the park code and the user's extension, listed first`() {
        val definitions = definitions(user(personal = true))
        assertEquals(ParkingSlotDefinition("Personal Slot", "*31024", true), definitions.first())
        assertEquals(2, definitions.size)
    }

    @Test
    fun `no personal slot without an extension`() {
        assertEquals(listOf(reception), definitions(user(personal = true, presenceId = "")))
    }

    @Test
    fun `no slots without a park code or user`() {
        assertTrue(definitions(user(personal = true, all = true), null).isEmpty())
        assertTrue(definitions(user(personal = true, all = true), "").isEmpty())
        assertTrue(definitions(null).isEmpty())
    }

    @Test
    fun `a missing slot collection is no slots`() {
        assertTrue(definitions(user(slots = null)).isEmpty())
    }

    @Test
    fun `BLF status maps to the slot status`() {
        assertEquals(ParkingSlotStatus.IDLE, ParkingSlotRules.status(BlfKeyStatus.IDLE))
        assertEquals(ParkingSlotStatus.OCCUPIED, ParkingSlotRules.status(BlfKeyStatus.RINGING))
        assertEquals(ParkingSlotStatus.OCCUPIED, ParkingSlotRules.status(BlfKeyStatus.INCALL))
        assertEquals(ParkingSlotStatus.UNKNOWN, ParkingSlotRules.status(BlfKeyStatus.UNKNOWN))
        assertEquals(ParkingSlotStatus.UNKNOWN, ParkingSlotRules.status(null))
    }

    @Test
    fun `a call is parked while the slot's dialog is trying, proceeding, early or confirmed`() {
        listOf("trying", "proceeding", "early", "confirmed", "Confirmed").forEach {
            assertTrue(it, ParkingSlotRules.isParked(it))
        }
        listOf("terminated", "", null).forEach {
            assertFalse("$it", ParkingSlotRules.isParked(it))
        }
    }

    @Test
    fun `a parked slot has the caller's number and contact name`() {
        val key = BlfKey("*3101", BlfKeyStatus.INCALL, "confirmed", "07968543537")
        val slot = ParkingSlotRules.slot(reception, key) { "Chris Smith" }

        assertEquals(ParkingSlotStatus.OCCUPIED, slot.status)
        assertTrue(slot.isParked)
        assertEquals("07968543537", slot.callerNumber)
        assertEquals("Chris Smith", slot.callerName)
    }

    @Test
    fun `a contact named after its number has no name`() {
        val key = BlfKey("*3101", BlfKeyStatus.INCALL, "confirmed", "07968543537")
        val slot = ParkingSlotRules.slot(reception, key) { "07968 543537" }

        assertNull(slot.callerName)
    }

    @Test
    fun `a free slot has no caller, even if the last dialog had one`() {
        val key = BlfKey("*3101", BlfKeyStatus.IDLE, "terminated", "07968543537")
        val slot = ParkingSlotRules.slot(reception, key) { "Chris Smith" }

        assertEquals(ParkingSlotStatus.IDLE, slot.status)
        assertFalse(slot.isParked)
        assertNull(slot.callerNumber)
        assertNull(slot.callerName)
    }

    @Test
    fun `a slot with no BLF key yet is unknown`() {
        val slot = ParkingSlotRules.slot(reception, null) { null }

        assertEquals(ParkingSlotStatus.UNKNOWN, slot.status)
        assertFalse(slot.isParked)
    }

    @Test
    fun `tapping a slot parks into a free one, and retrieves from an occupied one`() {
        val free = slot(ParkingSlotStatus.IDLE)
        val unknown = slot(ParkingSlotStatus.UNKNOWN)
        val occupied = slot(ParkingSlotStatus.OCCUPIED)

        assertEquals(ParkingSlotAction.PARK, ParkingSlotRules.action(free, true))
        assertEquals(ParkingSlotAction.PARK, ParkingSlotRules.action(unknown, true))
        assertEquals(ParkingSlotAction.HOLD_AND_RETRIEVE, ParkingSlotRules.action(occupied, true))
        assertEquals(ParkingSlotAction.RETRIEVE, ParkingSlotRules.action(occupied, false))
        assertEquals(ParkingSlotAction.NONE, ParkingSlotRules.action(free, false))
        assertEquals(ParkingSlotAction.NONE, ParkingSlotRules.action(unknown, false))
    }

    @Test
    fun `counts, parkable slots and the personal slot's parked call`() {
        val personalParked = slot(
            ParkingSlotStatus.OCCUPIED,
            isPersonal = true,
            callerNumber = "1025"
        )
        val occupied = slot(ParkingSlotStatus.OCCUPIED)
        val free = slot(ParkingSlotStatus.IDLE)
        val unknown = slot(ParkingSlotStatus.UNKNOWN)
        val slots = listOf(personalParked, occupied, free, unknown)

        assertEquals(2, ParkingSlotRules.occupiedCount(slots))
        assertEquals(listOf(free, unknown), ParkingSlotRules.parkable(slots))
        assertEquals(personalParked, ParkingSlotRules.personalParkedCall(slots))
        assertNull(ParkingSlotRules.personalParkedCall(listOf(occupied, free)))
        assertNull(
            ParkingSlotRules.personalParkedCall(
                listOf(slot(ParkingSlotStatus.OCCUPIED, isPersonal = true))
            )
        )
    }
}
