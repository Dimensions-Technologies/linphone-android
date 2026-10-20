package org.linphone.services.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkAvailabilityTest {

    private val wifi = "wifi"
    private val cellular = "cellular"

    private fun NetworkAvailability.isOnlineNow(): Boolean = isOnline.blockingFirst()

    @Test
    fun offlineUntilANetworkIsAvailable() {
        val availability = NetworkAvailability()

        assertEquals(false, availability.isOnlineNow())

        availability.onAvailable(cellular)

        assertEquals(true, availability.isOnlineNow())
    }

    @Test
    fun losingOneOfTwoNetworksStaysOnline() {
        // WI 36364: mobile data then Wi-Fi came up, Wi-Fi went away, and the realtime connection
        // was never started because the service thought the device was offline
        val availability = NetworkAvailability()
        availability.onAvailable(cellular)
        availability.onAvailable(wifi)

        availability.onLost(wifi)

        assertEquals(true, availability.isOnlineNow())
        assertEquals(1, availability.networkCount)
    }

    @Test
    fun losingTheOldNetworkAfterHandoverStaysOnline() {
        // Mobile data is torn down a little after Wi-Fi connects
        val availability = NetworkAvailability()
        availability.onAvailable(cellular)
        availability.onAvailable(wifi)

        availability.onLost(cellular)

        assertEquals(true, availability.isOnlineNow())
    }

    @Test
    fun losingEveryNetworkGoesOffline() {
        val availability = NetworkAvailability()
        availability.onAvailable(cellular)
        availability.onAvailable(wifi)

        availability.onLost(wifi)
        availability.onLost(cellular)

        assertEquals(false, availability.isOnlineNow())
        assertEquals(0, availability.networkCount)
    }

    @Test
    fun losingAnUnknownNetworkWhileOfflineStaysOffline() {
        val availability = NetworkAvailability()

        availability.onLost(wifi)

        assertEquals(false, availability.isOnlineNow())
    }

    @Test
    fun theSameNetworkReportedTwiceIsCountedOnce() {
        val availability = NetworkAvailability()
        availability.onAvailable(wifi)
        availability.onAvailable(wifi)

        availability.onLost(wifi)

        assertEquals(false, availability.isOnlineNow())
    }

    @Test
    fun eachNewNetworkEmitsOnlineSoTheConnectionIsRetried() {
        // RealtimeBaseService retries straight away when a network appears, so a second network
        // must emit again even though the device was already online
        val availability = NetworkAvailability()
        val emitted = availability.isOnline.test()

        availability.onAvailable(cellular)
        availability.onAvailable(wifi)
        availability.onLost(wifi)
        availability.onLost(cellular)

        emitted.assertValues(false, true, true, false)
    }
}
