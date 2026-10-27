package org.linphone.services

import java.util.GregorianCalendar
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Send to support (WI 36501): the file name support searches by, and the guard that stops repeat
 * taps from writing extra blobs to the shared container. Matches the web client's
 * diagnostics.service.ts.
 */
class SendToSupportTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val sentAt = GregorianCalendar(utc).apply {
        clear()
        set(2026, 9, 27, 9, 5, 7)
    }.time

    @Test
    fun `file name has platform, cleaned tenant and local timestamp`() {
        assertEquals(
            "android_3fa85f64-5717-4562-b3fc-2c963f66afa6_20261027-090507.zip",
            DiagnosticsService.supportFileName("3FA85F64-5717-4562-B3FC-2C963F66AFA6", sentAt, utc)
        )
    }

    @Test
    fun `timestamp uses the given time zone`() {
        val name = DiagnosticsService.supportFileName("t", sentAt, TimeZone.getTimeZone("GMT+10"))
        assertEquals("android_t_20261027-190507.zip", name)
    }

    @Test
    fun `runs of other characters become one dash and ends are trimmed`() {
        assertEquals(
            "android_acme-co-uk_20261027-090507.zip",
            DiagnosticsService.supportFileName("  _Acme Co.//UK!! ", sentAt, utc)
        )
    }

    @Test
    fun `missing tenant is named unknown-tenant`() {
        assertEquals("android_unknown-tenant_20261027-090507.zip", DiagnosticsService.supportFileName(null, sentAt, utc))
        assertEquals("android_unknown-tenant_20261027-090507.zip", DiagnosticsService.supportFileName("", sentAt, utc))
        assertEquals("android_unknown-tenant_20261027-090507.zip", DiagnosticsService.supportFileName("--!!--", sentAt, utc))
    }

    @Test
    fun `taps within the cooldown after a success report sent without uploading`() = runBlocking {
        var now = 1_000L
        var uploads = 0
        val guard = SendToSupportGuard(cooldownMs = 30_000, now = { now })

        assertEquals(true, guard.run { uploads++; true })
        now += 29_999
        assertEquals(true, guard.run { uploads++; true })
        assertEquals(1, uploads)

        now += 1
        assertEquals(true, guard.run { uploads++; true })
        assertEquals(2, uploads)
    }

    @Test
    fun `a failure does not start the cooldown`() = runBlocking {
        var uploads = 0
        val guard = SendToSupportGuard(now = { 0L })

        assertEquals(false, guard.run { uploads++; false })
        assertEquals(true, guard.run { uploads++; true })
        assertEquals(2, uploads)
    }

    @Test
    fun `taps while an upload is running are ignored`() = runBlocking {
        val guard = SendToSupportGuard()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var uploads = 0

        val first = async {
            guard.run {
                uploads++
                started.complete(Unit)
                release.await()
                true
            }
        }
        started.await()

        assertNull(guard.run { uploads++; true })

        release.complete(Unit)
        assertEquals(true, first.await())
        assertEquals(1, uploads)
    }

    @Test
    fun `an exception still frees the guard for a retry`() = runBlocking {
        val guard = SendToSupportGuard()

        val thrown = runCatching { guard.run { throw IllegalStateException("boom") } }
        assertTrue(thrown.isFailure)

        var ran = false
        assertEquals(true, guard.run { ran = true; true })
        assertTrue(ran)
    }
}
