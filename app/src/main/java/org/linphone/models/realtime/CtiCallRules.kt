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
     * The CTI call a SIP call is a leg of, by its SIP Call-ID, as the web client's
     * call-manager.service.ts getMatchingCtiCall.
     */
    fun callForSipCall(currentCalls: Map<String, CtiCallData>, sipCallId: String): CtiCallData? {
        if (sipCallId.isEmpty()) return null
        return currentCalls.values.firstOrNull { call ->
            call.relatedCalls.orEmpty().any { it.callId == sipCallId }
        }
    }

    /**
     * The SIP call (by Call-ID) an id the server gives a call is for, e.g. callMatchEvent's, which is
     * the PBX call id rather than the softphone leg's Call-ID. As the web client's
     * call-manager.service.ts findCall, each rule is tried against every call before the next:
     * the call's own Call-ID, then the id of the CTI call it's a leg of (the web client's
     * Call.sipCallId; its key or callId here, which have no separate id), then one of that CTI
     * call's other legs.
     */
    fun sipCallIdForServerId(
        currentCalls: Map<String, CtiCallData>,
        sipCallIds: List<String>,
        serverCallId: String
    ): String? {
        if (serverCallId.isEmpty()) return null
        val ids = sipCallIds.filter { it.isNotEmpty() }
        ids.firstOrNull { it == serverCallId }?.let { return it }

        fun ctiCallOf(sipCallId: String) = currentCalls.entries.firstOrNull { (_, call) ->
            call.relatedCalls.orEmpty().any { it.callId == sipCallId }
        }
        ids.firstOrNull { id ->
            ctiCallOf(id)?.let { (key, cti) -> serverCallId == key || serverCallId == cti.callId } == true
        }?.let { return it }
        return ids.firstOrNull { id ->
            ctiCallOf(id)?.value?.relatedCalls.orEmpty().any { it.callId == serverCallId }
        }
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
