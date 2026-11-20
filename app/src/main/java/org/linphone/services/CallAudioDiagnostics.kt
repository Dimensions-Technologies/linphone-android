package org.linphone.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.utils.AudioDiagnostics
import org.linphone.utils.Log

// A few log lines per call about its audio, so a call that isn't heard on a Bluetooth headset can
// be followed in the app log (WI 37097): the codec and devices each time the call's streams start,
// and, with millisecond times, Bluetooth SCO and communication device changes and when the voice
// playback stream starts or moves. Every line starts with "[Audio Diag]".
class CallAudioDiagnostics(private val context: Context, private val core: Core) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var lastVoicePlayback: String? = null

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String
        ) {
            if (state == Call.State.StreamsRunning) streamsRunning(call)
        }
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            val previous = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE, -1)
            log(
                "Bluetooth SCO ${AudioDiagnostics.scoStateName(previous)} -> ${AudioDiagnostics.scoStateName(state)}"
            )
        }
    }

    private val communicationDeviceListener: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnCommunicationDeviceChangedListener { device ->
                log("Communication device changed to [${describe(device)}]")
            }
        } else {
            null
        }

    private val playbackCallback: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(
                    configs: MutableList<AudioPlaybackConfiguration>
                ) {
                    val voicePlayback = describeVoicePlayback(configs)
                    if (voicePlayback != lastVoicePlayback) {
                        lastVoicePlayback = voicePlayback
                        log("Voice playback $voicePlayback")
                    }
                }
            }
        } else {
            null
        }

    fun start() {
        core.addListener(coreListener)
        ContextCompat.registerReceiver(
            context,
            scoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnCommunicationDeviceChangedListener(
                ContextCompat.getMainExecutor(context),
                communicationDeviceListener as AudioManager.OnCommunicationDeviceChangedListener
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.registerAudioPlaybackCallback(
                playbackCallback as AudioManager.AudioPlaybackCallback,
                mainHandler
            )
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.unregisterAudioPlaybackCallback(
                playbackCallback as AudioManager.AudioPlaybackCallback
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.removeOnCommunicationDeviceChangedListener(
                communicationDeviceListener as AudioManager.OnCommunicationDeviceChangedListener
            )
        }
        try {
            context.unregisterReceiver(scoReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("[Audio Diag] SCO receiver wasn't registered")
        }
        core.removeListener(coreListener)
    }

    private fun streamsRunning(call: Call) {
        val payloadType = call.currentParams.usedAudioPayloadType
        val codec = AudioDiagnostics.payload(
            payloadType?.mimeType,
            payloadType?.clockRate,
            payloadType?.channels
        )
        val parts = mutableListOf(
            "codec [$codec]",
            "SDK playback [${describe(call.outputAudioDevice)}]",
            "mode [${AudioDiagnostics.modeName(audioManager.mode)}]"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            parts.add("communication device [${describe(audioManager.communicationDevice)}]")
        }
        @Suppress("DEPRECATION")
        parts.add("SCO on [${audioManager.isBluetoothScoOn}]")
        log("Call [${call.callLog.callId}] streams running: ${parts.joinToString(", ")}")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun describeVoicePlayback(configs: List<AudioPlaybackConfiguration>): String {
        val voice = configs.filter {
            it.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
        }
        if (voice.isEmpty()) return "[none]"
        return voice.joinToString(prefix = "[", postfix = "]") { config ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Deprecated in 16 in favour of a list that isn't in the public SDK
                @Suppress("DEPRECATION")
                "stream on ${describe(config.audioDeviceInfo)}"
            } else {
                "stream"
            }
        }
    }

    private fun describe(device: AudioDeviceInfo?): String {
        if (device == null) return "none"
        return AudioDiagnostics.device(device.type, device.productName, device.sampleRates)
    }

    private fun describe(device: AudioDevice?): String {
        if (device == null) return "none"
        return "${device.deviceName} (${device.driverName}, ${device.type})"
    }

    private fun log(message: String) {
        Log.i("[Audio Diag] ${timeFormat.format(Date())} $message")
    }
}
