package org.linphone.utils

import org.linphone.core.AudioDevice

// Picks the SDK audio devices for call audio, with no dependency on the Core so the rules can be
// unit tested.
object AudioDeviceSelection {

    val BLUETOOTH_TYPES = listOf(AudioDevice.Type.Bluetooth, AudioDevice.Type.HearingAid)

    data class Devices(val output: AudioDevice, val input: AudioDevice?)

    // First device of one of the given types with the capability, preferring the driver the Core
    // already uses (e.g. AAudio over OpenSLES) when there's a choice.
    fun find(
        devices: List<AudioDevice>,
        types: List<AudioDevice.Type>,
        capability: AudioDevice.Capabilities,
        preferredDriver: String?
    ): AudioDevice? {
        val matching = devices.filter { types.contains(it.type) && it.hasCapability(capability) }
        return matching.find { it.driverName == preferredDriver } ?: matching.firstOrNull()
    }

    // The Bluetooth devices to answer an incoming call on, or null to answer with the Core's
    // defaults. Choosing them in the answer's call params means the call's audio starts on the
    // headset, as it does when a held call is resumed, instead of starting on the phone and being
    // switched to Bluetooth once the call is up, while Telecom is still bringing up the headset's
    // call audio (WI 37097: no audio on the headset until the call was held and resumed).
    // Same rules as the first StreamsRunning routing in CoreContext: only for the first call, a
    // wired headset wins, and only when the "prefer Bluetooth" setting is on.
    fun forAnswer(
        callsNb: Int,
        preferBluetooth: Boolean,
        wiredHeadsetAvailable: Boolean,
        devices: List<AudioDevice>,
        preferredOutputDriver: String?,
        preferredInputDriver: String?
    ): Devices? {
        if (callsNb != 1 || !preferBluetooth || wiredHeadsetAvailable) return null
        val output = find(
            devices,
            BLUETOOTH_TYPES,
            AudioDevice.Capabilities.CapabilityPlay,
            preferredOutputDriver
        ) ?: return null
        val input = find(
            devices,
            BLUETOOTH_TYPES,
            AudioDevice.Capabilities.CapabilityRecord,
            preferredInputDriver
        )
        return Devices(output, input)
    }
}
