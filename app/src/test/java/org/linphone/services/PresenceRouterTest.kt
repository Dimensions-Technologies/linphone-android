package org.linphone.services

import PresenceEventData
import PresenceObservable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.utils.GsonUtils

class PresenceRouterTest {
    private fun presence(state: String): PresenceEventData =
        GsonUtils.create().fromJson("""{"stateName":"$state","iconState":"available"}""", PresenceEventData::class.java)

    @Test
    fun `a burst of presence events from the realtime thread reaches every user`() {
        // The bug: a contacts list subscribes to every user at once, the events come back together,
        // and all but the last were lost
        val router = PresenceRouter()
        val users = (1..20).map { "user-$it" }
        val received = ConcurrentHashMap<String, String>()
        val latch = CountDownLatch(users.size)
        for (user in users) {
            router.stream(user) { PresenceObservable({}, {}) }.data.subscribe { data ->
                received[user] = data.stateName
                latch.countDown()
            }
        }

        val realtime = Thread { users.forEach { router.deliver(it, presence("Available $it")) } }
        realtime.start()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(users.associateWith { "Available $it" }, received.toMap())
    }

    @Test
    fun `a user's stream is made once, and keeps their latest presence`() {
        val router = PresenceRouter()
        var made = 0
        val first = router.stream("u") { made++; PresenceObservable({}, {}) }
        val second = router.stream("u") { made++; PresenceObservable({}, {}) }
        assertSame(first, second)
        assertEquals(1, made)

        first.data.subscribe { }
        router.deliver("u", presence("Working from Home"))
        assertEquals("Working from Home", router.current("u")?.stateName)
        // A late subscriber gets it too
        var late: String? = null
        router.stream("u") { PresenceObservable({}, {}) }.data.subscribe { late = it.stateName }
        assertEquals("Working from Home", late)
    }

    @Test
    fun `events for users nobody watches are dropped`() {
        val router = PresenceRouter()
        router.deliver("nobody", presence("Available"))
        router.deliver(null, presence("Available"))
        assertNull(router.current("nobody"))
    }
}
