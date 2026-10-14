package org.linphone.services

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks the message for a failed log upload. It used to be the ResponseBody object's name, so
 * Sentry (1D) never showed why uploads failed.
 */
class DiagnosticsUploadFailureTest {

    @Test
    fun `includes status code and server reason`() {
        assertEquals(
            "HTTP 413: Request body too large",
            DiagnosticsService.describeUploadFailure(413, "Request body too large")
        )
    }

    @Test
    fun `status code alone when the server sends no body`() {
        assertEquals("HTTP 500", DiagnosticsService.describeUploadFailure(500, null))
        assertEquals("HTTP 500", DiagnosticsService.describeUploadFailure(500, "  \n "))
    }

    @Test
    fun `multi-line body is flattened and capped`() {
        val message = DiagnosticsService.describeUploadFailure(400, "{\n  \"error\": \"" + "x".repeat(500) + "\"\n}")

        assertEquals("HTTP 400: { \"error\": \"" + "x".repeat(200 - 12), message)
    }
}
