package org.linphone.utils

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDiagnosticsTest {

    @Test
    fun `payload shows mime type, clock rate and channels`() {
        assertEquals("opus/48000/2", AudioDiagnostics.payload("opus", 48000, 2))
        assertEquals("G722/8000/1", AudioDiagnostics.payload("G722", 8000, 1))
        assertEquals("none", AudioDiagnostics.payload(null, null, null))
        assertEquals("PCMA/?/?", AudioDiagnostics.payload("PCMA", null, null))
    }

    @Test
    fun `SCO states and audio modes are named`() {
        assertEquals("CONNECTED", AudioDiagnostics.scoStateName(AudioManager.SCO_AUDIO_STATE_CONNECTED))
        assertEquals("CONNECTING", AudioDiagnostics.scoStateName(AudioManager.SCO_AUDIO_STATE_CONNECTING))
        assertEquals("DISCONNECTED", AudioDiagnostics.scoStateName(AudioManager.SCO_AUDIO_STATE_DISCONNECTED))
        assertEquals("ERROR", AudioDiagnostics.scoStateName(AudioManager.SCO_AUDIO_STATE_ERROR))
        assertEquals("UNKNOWN(7)", AudioDiagnostics.scoStateName(7))
        assertEquals("IN_COMMUNICATION", AudioDiagnostics.modeName(AudioManager.MODE_IN_COMMUNICATION))
        assertEquals("MODE(42)", AudioDiagnostics.modeName(42))
    }

    @Test
    fun `device shows type, name and sample rates`() {
        assertEquals(
            "BLUETOOTH_SCO \"Bose Sport Earbuds\" rates 8000/16000",
            AudioDiagnostics.device(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                "Bose Sport Earbuds",
                intArrayOf(8000, 16000)
            )
        )
        assertEquals(
            "EARPIECE \"SM-S936B\" rates any",
            AudioDiagnostics.device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "SM-S936B", intArrayOf())
        )
        assertEquals("TYPE(999) \"x\" rates any", AudioDiagnostics.device(999, "x", null))
    }
}
