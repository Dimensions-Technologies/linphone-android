package org.linphone.services

import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.linphone.models.callhistory.CallHistoryItem
import org.linphone.models.callhistory.CallTypes
import org.linphone.models.callhistory.PbxType
import org.threeten.bp.LocalDateTime

/**
 * Checks that a call history record which fails to format can't error the formattedHistory stream.
 * An error there used to be replayed to subscribers without an onError handler, crashing the app
 * with OnErrorNotImplementedException (WI #30448).
 */
class CallHistoryFormattingTest {

    private val today = BehaviorSubject.createDefault(LocalDateTime.of(2025, 3, 1, 0, 0))
    private val history = BehaviorSubject.create<List<CallHistoryItem>>()

    /** Errors RxJava couldn't deliver, which crash the app outside of tests. */
    private val undeliverableErrors = mutableListOf<Throwable>()

    /** Stands in for CallHistoryItemViewModel, failing on records marked "bad". */
    private val format: (CallHistoryItem, LocalDateTime) -> String = { call, _ ->
        if (call.documentId.startsWith("bad")) throw NullPointerException("startTime")
        call.documentId
    }

    @Before
    fun setUp() {
        RxJavaPlugins.setErrorHandler { undeliverableErrors.add(it) }
    }

    @After
    fun tearDown() {
        RxJavaPlugins.reset()
    }

    @Test
    fun `a record that fails to format is skipped`() {
        val observer = CallHistoryService.formatHistory(history, today, format).test()

        history.onNext(listOf(item("call-1"), item("bad-1"), item("call-2")))

        observer.assertNoErrors()
        observer.assertValue(listOf("call-1", "call-2"))
    }

    @Test
    fun `the stream keeps emitting after a record fails to format`() {
        val observer = CallHistoryService.formatHistory(history, today, format).test()

        history.onNext(listOf(item("bad-1")))
        history.onNext(listOf(item("call-1")))

        observer.assertNoErrors()
        observer.assertNotComplete()
        observer.assertValues(listOf(), listOf("call-1"))
    }

    @Test
    fun `a late subscriber without an error handler doesn't crash`() {
        val formatted = CallHistoryService.formatHistory(history, today, format)
        formatted.test()
        history.onNext(listOf(item("call-1"), item("bad-1")))

        // Mirrors CallLogsListViewModel.updateCallLogs, which subscribes after the replay has a value.
        var received: List<String>? = null
        formatted.subscribe { received = it }.dispose()

        assertEquals(listOf("call-1"), received)
        assertTrue("Undelivered errors: $undeliverableErrors", undeliverableErrors.isEmpty())
    }

    private fun item(documentId: String) = CallHistoryItem(
        missedCall = false,
        answered = true,
        hasRecording = false,
        startTime = null,
        connectionId = "connection-$documentId",
        callType = CallTypes.External,
        callDirection = 1,
        contactName = null,
        contactMatchType = null,
        hasContactMatch = false,
        calledUserName = null,
        calledUserNumber = null,
        callingUserName = null,
        callingUserNumber = null,
        routePathName = null,
        groupName = null,
        huntgroupName = null,
        documentId = documentId,
        isConference = false,
        pbxType = PbxType.DimensionsVoice,
        interactionTags = listOf()
    )
}
