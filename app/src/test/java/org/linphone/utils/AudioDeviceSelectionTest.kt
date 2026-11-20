package org.linphone.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.core.AudioDevice
import org.linphone.core.AudioDevice.Capabilities
import org.linphone.core.AudioDevice.Type

/**
 * Checks which audio devices a call is answered on (WI 37097). With a Bluetooth headset
 * connected, the answer's call params name the headset, so the call's audio starts on it instead
 * of being switched to it once the call is up, which left the headset silent until the call was
 * held and resumed. The rules match the routing done when the first call starts: first call only,
 * a wired headset wins, and only when Bluetooth is preferred. When Telecom manages the call, the
 * SDK only gets the headset once Bluetooth SCO is on, so the SDK never starts and stops SCO itself.
 */
class AudioDeviceSelectionTest {

    private class FakeDevice(
        private val type: Type,
        private val driver: String,
        private vararg val capabilities: Capabilities
    ) : AudioDevice {
        override fun getCapabilities(): Capabilities = capabilities.first()
        override fun getDeviceName(): String = "$type ($driver)"
        override fun getDriverName(): String = driver
        override fun getFollowsSystemRoutingPolicy(): Boolean = false
        override fun getId(): String = "$driver:$type:${capabilities.joinToString()}"
        override fun getType(): Type = type
        override fun hasCapability(capability: Capabilities): Boolean =
            capabilities.contains(capability)
        override fun setUserData(data: Any?) {}
        override fun getUserData(): Any? = null
        override fun getNativePointer(): Long = 0
        override fun toString(): String = getId()
    }

    private val earpiece = FakeDevice(Type.Earpiece, "AAudio", Capabilities.CapabilityPlay)
    private val microphone = FakeDevice(Type.Microphone, "AAudio", Capabilities.CapabilityRecord)
    private val btPlayOpenSles = FakeDevice(Type.Bluetooth, "openSLES", Capabilities.CapabilityPlay)
    private val btPlay = FakeDevice(Type.Bluetooth, "AAudio", Capabilities.CapabilityPlay)
    private val btRecord = FakeDevice(Type.Bluetooth, "AAudio", Capabilities.CapabilityRecord)
    private val withBluetooth = listOf(earpiece, microphone, btPlayOpenSles, btPlay, btRecord)

    private fun answer(
        callsNb: Int = 1,
        preferBluetooth: Boolean = true,
        wiredHeadsetAvailable: Boolean = false,
        telecomManaged: Boolean = false,
        devices: List<AudioDevice> = withBluetooth
    ) = AudioDeviceSelection.forAnswer(
        callsNb,
        preferBluetooth,
        wiredHeadsetAvailable,
        telecomManaged,
        devices,
        "AAudio",
        "AAudio"
    )

    @Test
    fun `a first call is answered on the Bluetooth headset`() {
        val devices = answer()!!
        assertSame(btPlay, devices.output)
        assertSame(btRecord, devices.input)
    }

    @Test
    fun `the Core's driver is preferred when the headset has several`() {
        assertSame(btPlay, answer()!!.output)
    }

    @Test
    fun `another driver is used when the Core's isn't there`() {
        val devices = answer(devices = listOf(earpiece, btPlayOpenSles))!!
        assertSame(btPlayOpenSles, devices.output)
        assertNull(devices.input)
    }

    @Test
    fun `a hearing aid counts as Bluetooth`() {
        val hearingAid = FakeDevice(Type.HearingAid, "AAudio", Capabilities.CapabilityPlay)
        assertSame(hearingAid, answer(devices = listOf(earpiece, hearingAid))!!.output)
    }

    @Test
    fun `no Bluetooth device means the Core's defaults`() {
        assertNull(answer(devices = listOf(earpiece, microphone)))
        // A headset that can only record isn't enough to answer on
        assertNull(answer(devices = listOf(earpiece, btRecord)))
    }

    @Test
    fun `a call answered during another call keeps the current route`() {
        assertNull(answer(callsNb = 2))
    }

    @Test
    fun `a wired headset wins over Bluetooth`() {
        assertNull(answer(wiredHeadsetAvailable = true))
    }

    @Test
    fun `nothing changes when Bluetooth isn't preferred in the settings`() {
        assertNull(answer(preferBluetooth = false))
    }

    @Test
    fun `a call Telecom manages is answered with the Core's defaults`() {
        assertNull(answer(telecomManaged = true))
    }

    @Test
    fun `under Telecom the move to Bluetooth waits for SCO`() {
        assertTrue(AudioDeviceSelection.deferUntilScoOn(true, Type.Bluetooth, scoOn = false))
        assertTrue(AudioDeviceSelection.deferUntilScoOn(true, Type.HearingAid, scoOn = false))
    }

    @Test
    fun `the move to Bluetooth goes ahead once SCO is on, without Telecom, or for other devices`() {
        assertFalse(AudioDeviceSelection.deferUntilScoOn(true, Type.Bluetooth, scoOn = true))
        assertFalse(AudioDeviceSelection.deferUntilScoOn(false, Type.Bluetooth, scoOn = false))
        assertFalse(AudioDeviceSelection.deferUntilScoOn(true, Type.Speaker, scoOn = false))
        assertFalse(AudioDeviceSelection.deferUntilScoOn(true, Type.Earpiece, scoOn = false))
    }
}
