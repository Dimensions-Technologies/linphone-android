package org.linphone.models.callhistory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The same cases as iOS's HistoryTimelineRulesTests and PhoneLocationRulesTests (WI 36610)
class HistoryTimelineRulesTest {
    private fun call(
        direction: CallDirections,
        answered: Boolean = true,
        missed: Boolean = false,
        conference: Boolean = false
    ) = CallHistoryItem(
        missedCall = missed,
        answered = answered,
        hasRecording = false,
        startTime = null,
        connectionId = "c",
        callType = CallTypes.External,
        callDirection = direction.value,
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
        documentId = "d",
        isConference = conference,
        pbxType = PbxType.Kazoo,
        interactionTags = emptyList()
    )

    @Test
    fun `the icon is the direction, with a voicemail shown instead of missed`() {
        assertEquals(HistoryDirection.OUTGOING, HistoryTimelineRules.direction(call(CallDirections.Outgoing), false))
        assertEquals(HistoryDirection.INCOMING, HistoryTimelineRules.direction(call(CallDirections.Incoming), false))
        assertEquals(
            HistoryDirection.MISSED,
            HistoryTimelineRules.direction(call(CallDirections.Incoming, answered = false, missed = true), false)
        )
        assertEquals(
            HistoryDirection.UNANSWERED,
            HistoryTimelineRules.direction(call(CallDirections.Incoming, answered = false), false)
        )
        assertEquals(
            HistoryDirection.MISSED_VOICEMAIL,
            HistoryTimelineRules.direction(call(CallDirections.Incoming, answered = false, missed = true), true)
        )
        // Calls that went to voicemail are recorded as answered
        assertEquals(HistoryDirection.VOICEMAIL, HistoryTimelineRules.direction(call(CallDirections.Incoming), true))
        assertEquals(
            HistoryDirection.CONFERENCE,
            HistoryTimelineRules.direction(call(CallDirections.Incoming, conference = true), true)
        )
        // Internal calls the user made are outgoing, as on iOS
        assertEquals(HistoryDirection.OUTGOING, HistoryTimelineRules.direction(call(CallDirections.Internal), false))
    }

    @Test
    fun `Missed lists missed calls with or without a voicemail`() {
        assertTrue(HistoryTimelineRules.isMissed(HistoryDirection.MISSED))
        assertTrue(HistoryTimelineRules.isMissed(HistoryDirection.MISSED_VOICEMAIL))
        assertFalse(HistoryTimelineRules.isMissed(HistoryDirection.VOICEMAIL))
        assertFalse(HistoryTimelineRules.isMissed(HistoryDirection.UNANSWERED))
        assertFalse(HistoryTimelineRules.isMissed(HistoryDirection.INCOMING))
    }

    @Test
    fun `talk time is shown in seconds, minutes or hours`() {
        assertEquals("", HistoryTimelineRules.formatTalkTime(0.0))
        assertEquals("45s", HistoryTimelineRules.formatTalkTime(45.0))
        assertEquals("4m 12s", HistoryTimelineRules.formatTalkTime(252.0))
        assertEquals("1h 02m", HistoryTimelineRules.formatTalkTime(3720.0))
    }

    @Test
    fun `calls with the same number on the same day are grouped under their newest`() {
        data class C(val id: String, val day: Int, val key: String?)
        val calls = listOf(
            C("a1", 10, "a"),
            C("b1", 10, "b"),
            C("a2", 10, "a"),
            C("x1", 10, null),
            C("x2", 10, null),
            C("a3", 9, "a")
        )

        val days = HistoryTimelineRules.groupByDay(calls, { it.day }, { it.key })

        assertEquals(listOf(10, 9), days.map { it.first })
        assertEquals(
            listOf("a1,a2", "b1", "x1", "x2"),
            days[0].second.map { group -> group.joinToString(",") { it.id } }
        )
        // Another day starts another group
        assertEquals(listOf("a3"), days[1].second.map { group -> group.joinToString(",") { it.id } })
    }

    @Test
    fun `calls group on their number in E164, else as given`() {
        assertEquals("+441332362900", HistoryTimelineRules.numberKey("01332 362900", "GB"))
        assertEquals("+441332362900", HistoryTimelineRules.numberKey("+44 1332 362900", "GB"))
        assertEquals("1040", HistoryTimelineRules.numberKey("1040", "GB"))
        // Teams calls give the user's email
        assertEquals("noel@example.com", HistoryTimelineRules.numberKey(" Noel@Example.com ", "GB"))
        assertNull(HistoryTimelineRules.numberKey("", "GB"))
        assertNull(HistoryTimelineRules.numberKey(null, "GB"))
    }

    @Test
    fun `the line under the name has the number and route, but never an email`() {
        assertEquals("07921 910119 via Sales", HistoryTimelineRules.subtitle(listOf("07921 910119", "via Sales")))
        // Teams calls give the user's email as the number, which iOS doesn't show
        assertEquals("", HistoryTimelineRules.subtitle(listOf("noel@dimensions.team")))
        assertEquals("via Sales", HistoryTimelineRules.subtitle(listOf("noel@dimensions.team", "via Sales")))
    }

    @Test
    fun `a grouped call's line says how it went`() {
        assertEquals(
            "Incoming via Sales · 4m 12s",
            HistoryTimelineRules.subLabel("Incoming", "via Sales", HistoryDirection.INCOMING, 252.0, "left voicemail")
        )
        assertEquals(
            "Missed · left voicemail",
            HistoryTimelineRules.subLabel("Missed", null, HistoryDirection.MISSED_VOICEMAIL, 0.0, "left voicemail")
        )
        assertEquals(
            "Outgoing",
            HistoryTimelineRules.subLabel("Outgoing", null, HistoryDirection.OUTGOING, 0.0, "left voicemail")
        )
    }

    @Test
    fun `home numbers show their place without a country code`() {
        assertEquals(PhoneLocation(PhoneLocationKind.PLACE, "Derby", null), PhoneLocationRules.describe("01332 362900", "GB"))
        assertEquals(PhoneLocation(PhoneLocationKind.PLACE, "Derby", null), PhoneLocationRules.describe("+441332362900", "GB"))
    }

    @Test
    fun `home numbers without a place are mobile or non-geographic`() {
        assertEquals(PhoneLocationKind.MOBILE, PhoneLocationRules.describe("07400 123456", "GB")?.kind)
        assertEquals(PhoneLocationKind.NON_GEOGRAPHIC, PhoneLocationRules.describe("0333 242 0042", "GB")?.kind)
        assertEquals(PhoneLocationKind.NON_GEOGRAPHIC, PhoneLocationRules.describe("0800 123 4567", "GB")?.kind)
    }

    @Test
    fun `numbers abroad show their country code`() {
        assertEquals(
            PhoneLocation(PhoneLocationKind.PLACE, "New York, NY", "US"),
            PhoneLocationRules.describe("+1 212 555 0147", "GB")
        )
        assertEquals(PhoneLocation(PhoneLocationKind.PLACE, "Berlin", "DE"), PhoneLocationRules.describe("+49 30 12345678", "GB"))
        assertEquals(PhoneLocation(PhoneLocationKind.PLACE, "France", "FR"), PhoneLocationRules.describe("+33 1 99 00 12 34", "GB"))
        // Seen from abroad, a UK number is abroad
        assertEquals("GB", PhoneLocationRules.describe("+441332362900", "US")?.countryCode)
    }

    @Test
    fun `extensions, invalid and withheld numbers have no location`() {
        assertNull(PhoneLocationRules.describe("1076", "GB"))
        assertNull(PhoneLocationRules.describe("*3101", "GB"))
        assertNull(PhoneLocationRules.describe("Withheld", "GB"))
        assertNull(PhoneLocationRules.describe(null, "GB"))
        assertNull(PhoneLocationRules.describe("01632 960217", "GB"))
    }
}
