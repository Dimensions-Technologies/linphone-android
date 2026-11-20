package org.linphone.utils

import android.media.AudioDeviceInfo
import android.media.AudioManager

// Formatting for the call audio diagnostics (CallAudioDiagnostics), with no
// dependency on the Core or Android services so it can be unit tested.
object AudioDiagnostics {

    fun payload(mimeType: String?, clockRate: Int?, channels: Int?): String {
        if (mimeType == null) return "none"
        return "$mimeType/${clockRate ?: "?"}/${channels ?: "?"}"
    }

    fun scoStateName(state: Int): String = when (state) {
        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "DISCONNECTED"
        AudioManager.SCO_AUDIO_STATE_CONNECTED -> "CONNECTED"
        AudioManager.SCO_AUDIO_STATE_CONNECTING -> "CONNECTING"
        AudioManager.SCO_AUDIO_STATE_ERROR -> "ERROR"
        else -> "UNKNOWN($state)"
    }

    fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        else -> "MODE($mode)"
    }

    fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        else -> "TYPE($type)"
    }

    // Empty sample rates means the device takes any rate
    fun device(type: Int, name: CharSequence?, sampleRates: IntArray?): String {
        val rates = if (sampleRates == null || sampleRates.isEmpty()) {
            "any"
        } else {
            sampleRates.joinToString("/")
        }
        return "${deviceTypeName(type)} \"$name\" rates $rates"
    }
}
