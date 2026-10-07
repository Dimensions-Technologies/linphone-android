package org.linphone.services

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.models.CustomerLicence
import org.linphone.utils.Log

/**
 * The customer's licence, cached for 30 minutes as on the web client (licence.service.ts). A failed
 * fetch isn't cached, so the next caller retries.
 */
object LicenceService {
    private const val CACHE_MS = 30 * 60 * 1000L

    private val mutex = Mutex()
    private var licence: CustomerLicence? = null
    private var fetchedAt = 0L

    /** The licence, or null if it couldn't be fetched. */
    suspend fun getLicence(): CustomerLicence? = mutex.withLock {
        licence?.let { if (System.currentTimeMillis() - fetchedAt < CACHE_MS) return it }
        try {
            val response = APIClientService(coreContext.context).getUCGatewayService().getLicence()
            if (!response.isSuccessful) throw Exception("HTTP ${response.code()}")
            response.body().also {
                licence = it
                fetchedAt = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.e(e, "[Licence] Failed to fetch the licence")
            null
        }
    }

    /** Forgets the licence, e.g. when the user changes. */
    suspend fun clear() = mutex.withLock {
        licence = null
        fetchedAt = 0L
    }
}
