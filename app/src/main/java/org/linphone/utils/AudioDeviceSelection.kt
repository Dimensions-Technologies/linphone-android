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
    // headset instead of being switched to it once the call is up.
    // Same rules as the first StreamsRunning routing in CoreContext: only for the first call, a
    // wired headset wins, and only when the "prefer Bluetooth" setting is on.
    // Not when Telecom manages the call (WI 37097): it brings the headset's call audio (SCO) up
    // when the call goes active, after the answer. A Bluetooth device in the SDK before that makes
    // the SDK start SCO itself, and then stop it whenever the audio stream restarts, such as for the
    // re-INVITE the PBX sends right after the answer, which left the headset silent until the
    // call was held and resumed. The route is set once SCO is on instead (see deferUntilScoOn).
    fun forAnswer(
        callsNb: Int,
        preferBluetooth: Boolean,
        wiredHeadsetAvailable: Boolean,
        telecomManaged: Boolean,
        devices: List<AudioDevice>,
        preferredOutputDriver: String?,
        preferredInputDriver: String?
    ): Devices? {
        if (callsNb != 1 || !preferBluetooth || wiredHeadsetAvailable || telecomManaged) return null
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

    // Whether moving a call's audio to a Bluetooth device in the SDK has to wait until Android
    // reports Bluetooth SCO on (WI 37097). The SDK starts SCO itself for a Bluetooth device when
    // it isn't on yet, and stops it again when the audio stream closes or restarts. Under Telecom
    // that tears down the headset audio Telecom brought up, so the SDK must only see a Bluetooth
    // device once SCO is already on, when it leaves SCO to Telecom.
    fun deferUntilScoOn(
        telecomManaged: Boolean,
        deviceType: AudioDevice.Type,
        scoOn: Boolean
    ): Boolean = telecomManaged && BLUETOOTH_TYPES.contains(deviceType) && !scoOn
}
