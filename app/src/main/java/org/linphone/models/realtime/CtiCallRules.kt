package org.linphone.models.realtime

/**
 * Reads the user's calls from a callEvent, ported from the web client's cti-calls.service.ts.
 */
object CtiCallRules {
    private const val QUICKCALL_SUFFIX = "quickcall"

    /**
     * The calls still live, in call id order: cleared calls, and calls the PBX knows too little
     * about (no start time, and only a device snapshot), are left out. Quickcall legs for one
     * logical call are collapsed into one, as in [dedupeQuickCallLegs].
     */
    fun currentCalls(calls: Map<String, CtiCallData>?): Map<String, CtiCallData> {
        val current = calls.orEmpty().filterValues { call ->
            call.state != "cleared" &&
                (call.state != "unknown" || (!call.eventType.isNullOrEmpty() && call.eventType != "PbxCleared")) &&
                (
                    !call.startTime.isNullOrEmpty() ||
                        (
                            !call.deviceNumber.isNullOrEmpty() && !call.eventType.isNullOrEmpty() &&
                                call.eventType != "PbxDeviceSnapshot"
                            )
                    )
        }
        return dedupeQuickCallLegs(current)
    }

    /** The id of the first connected call, as the web client picks its current CTI call. */
    fun activeCallId(currentCalls: Map<String, CtiCallData>): String? {
        return currentCalls.entries.firstOrNull { it.value.state == "connected" }?.key
    }

    /**
     * The PBX can report several "-quickcall" legs at once (one per device rung while originating
     * or bridging a call) that are the same call to the user, differing only in PBX bookkeeping.
     * Each group with the same direction, type and numbers is collapsed to its first leg by call id.
     */
    fun dedupeQuickCallLegs(calls: Map<String, CtiCallData>): Map<String, CtiCallData> {
        val result = LinkedHashMap<String, CtiCallData>()
        val consumed = mutableSetOf<String>()
        val sortedIds = calls.keys.sorted()

        fun isQuickCall(callId: String) = callId.lowercase().endsWith(QUICKCALL_SUFFIX)

        // As the web client joins them, a missing value is the same as an empty one
        fun signatureOf(call: CtiCallData) = listOf(
            call.direction.orEmpty(),
            call.type.orEmpty(),
            call.callerNumber.orEmpty(),
            call.calledNumber.orEmpty()
        )

        for (callId in sortedIds) {
            if (callId in consumed) continue
            val call = calls.getValue(callId)
            consumed.add(callId)
            result[callId] = call

            if (isQuickCall(callId)) {
                val signature = signatureOf(call)
                sortedIds
                    .filter { otherId ->
                        otherId !in consumed && isQuickCall(otherId) &&
                            signatureOf(calls.getValue(otherId)) == signature
                    }
                    .forEach { consumed.add(it) }
            }
        }

        return result
    }
}
