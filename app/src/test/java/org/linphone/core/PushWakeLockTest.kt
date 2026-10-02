package org.linphone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the lock that keeps the device awake between a call push arriving and the incoming call
 * being shown. Without it a cold start from a push could stall for seconds or minutes (WI #35628).
 */
class PushWakeLockTest {

    private class FakeLock : PushWakeLock.Lock {
        override var isHeld = false
        val timeouts = mutableListOf<Long>()
        var releases = 0

        override fun acquire(timeoutMs: Long) {
            timeouts.add(timeoutMs)
            isHeld = true
        }

        override fun release() {
            releases++
            isHeld = false
        }
    }

    private var now = 1_000L
    private val lock = FakeLock()
    private val pushWakeLock = PushWakeLock(lock) { now }

    @Test
    fun `acquiring always sets a timeout so a push with no call can't hold the lock forever`() {
        pushWakeLock.acquire()

        assertTrue(lock.isHeld)
        assertEquals(listOf(PushWakeLock.TIMEOUT_MS), lock.timeouts)
    }

    @Test
    fun `the timeout covers a slow cold start and registration`() {
        assertTrue(PushWakeLock.TIMEOUT_MS >= 20_000L)
    }

    @Test
    fun `release reports how long the lock was held`() {
        pushWakeLock.acquire()
        now += 1_250L

        assertEquals(1_250L, pushWakeLock.release("shown"))
        assertFalse(lock.isHeld)
    }

    @Test
    fun `releasing a lock that isn't held does nothing`() {
        assertNull(pushWakeLock.release("never acquired"))
        assertEquals(0, lock.releases)
    }

    @Test
    fun `only the first of several release points releases the lock`() {
        pushWakeLock.acquire()

        pushWakeLock.release("incoming call notification shown")
        assertNull(pushWakeLock.release("call ended before it was shown"))
        assertEquals(1, lock.releases)
    }

    @Test
    fun `releasing after the timeout has expired does nothing`() {
        pushWakeLock.acquire()
        lock.isHeld = false // the platform lock released itself

        assertNull(pushWakeLock.release("shown"))
        assertEquals(0, lock.releases)
    }

    @Test
    fun `a second push restarts the timing`() {
        pushWakeLock.acquire()
        now += 5_000L
        pushWakeLock.acquire()
        now += 300L

        assertEquals(300L, pushWakeLock.release("shown"))
        assertEquals(2, lock.timeouts.size)
    }
}
