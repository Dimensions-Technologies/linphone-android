package org.linphone.core

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import org.linphone.utils.Log

// Firebase only keeps the device awake while it hands a push over. When the push starts the app
// cold, the Core is created and registers after that, and on some devices the process was stalled
// for seconds (once over two minutes) before the incoming call notification appeared (WI #35628).
// This keeps the CPU awake from the push arriving until the incoming call is on screen, with a
// timeout for pushes whose call never arrives.
class PushWakeLock(
    private val lock: Lock,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    interface Lock {
        val isHeld: Boolean
        fun acquire(timeoutMs: Long)
        fun release()
    }

    private var acquiredAt = 0L

    @Synchronized
    fun acquire() {
        lock.acquire(TIMEOUT_MS)
        acquiredAt = clock()
        Log.i("[Push Wake Lock] Acquired for up to $TIMEOUT_MS ms")
    }

    // Returns how long the lock was held, or null if it wasn't held (never taken, already released
    // or timed out).
    @Synchronized
    fun release(reason: String): Long? {
        if (!lock.isHeld) return null

        lock.release()
        val heldFor = clock() - acquiredAt
        Log.i("[Push Wake Lock] Released after $heldFor ms: $reason")
        return heldFor
    }

    companion object {
        const val TIMEOUT_MS = 30_000L
        private const val TAG = "linphone:PushWakeLock"

        @Volatile
        private var instance: PushWakeLock? = null

        fun get(context: Context): PushWakeLock {
            return instance ?: synchronized(this) {
                instance ?: PushWakeLock(platformLock(context.applicationContext)).also {
                    instance = it
                }
            }
        }

        private fun platformLock(context: Context): Lock {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
                // A new push before the last one's call arrived just extends the timeout
                setReferenceCounted(false)
            }
            return object : Lock {
                override val isHeld: Boolean
                    get() = wakeLock.isHeld

                override fun acquire(timeoutMs: Long) = wakeLock.acquire(timeoutMs)

                override fun release() = wakeLock.release()
            }
        }
    }
}
