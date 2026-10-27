package org.linphone.services

import java.util.concurrent.atomic.AtomicBoolean

enum class SendToSupportState { IDLE, SENDING, SENT, FAILED }

/**
 * Stops repeat taps on Send to support from uploading the logs again, as on the web client
 * (diagnostics.service.ts). Every upload writes a new blob to the shared support container, and
 * the button is easy to double-tap. Only one upload runs at a time, and after a successful one,
 * taps within the cooldown report success without uploading. A failure doesn't start the
 * cooldown, so the user can retry straight away.
 */
class SendToSupportGuard(
    private val cooldownMs: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var lastSentAt: Long? = null

    /** Whether the upload succeeded, or null when one is already running and the tap is ignored. */
    suspend fun run(upload: suspend () -> Boolean): Boolean? {
        if (!inFlight.compareAndSet(false, true)) return null
        try {
            val last = lastSentAt
            if (last != null && now() - last < cooldownMs) return true

            val sent = upload()
            if (sent) lastSentAt = now()
            return sent
        } finally {
            inFlight.set(false)
        }
    }
}
