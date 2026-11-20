package org.linphone.utils

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

// Holds back moving a Telecom call's audio to a Bluetooth device in the SDK until Android reports
// Bluetooth SCO on, so the SDK leaves SCO to Telecom (WI 37097, see
// AudioDeviceSelection.deferUntilScoOn). Only one move waits at a time: a newer route request
// replaces or cancels it.
object BluetoothScoWait {
    private const val POLL_MS = 100L
    private const val TIMEOUT_MS = 3000L

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    // Android's SCO state, the same check the SDK makes before starting SCO itself
    fun isScoOn(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        return audioManager.isBluetoothScoOn
    }

    // Runs the action once SCO is on, or after the timeout so the call still gets the route
    fun runWhenScoOn(context: Context, action: () -> Unit) {
        cancel()
        val started = SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                val waited = SystemClock.elapsedRealtime() - started
                when {
                    isScoOn(context) -> {
                        Log.i("[Bluetooth SCO Wait] SCO is on after [$waited] ms, routing call audio to Bluetooth")
                    }
                    waited >= TIMEOUT_MS -> {
                        Log.w("[Bluetooth SCO Wait] SCO still off after [$waited] ms, routing call audio to Bluetooth anyway")
                    }
                    else -> {
                        handler.postDelayed(this, POLL_MS)
                        return
                    }
                }
                pending = null
                action()
            }
        }
        pending = check
        handler.postDelayed(check, POLL_MS)
    }

    fun cancel() {
        pending?.let {
            Log.i("[Bluetooth SCO Wait] Cancelling the pending move to Bluetooth")
            handler.removeCallbacks(it)
        }
        pending = null
    }
}
