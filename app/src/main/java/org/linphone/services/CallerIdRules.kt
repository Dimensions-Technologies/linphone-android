package org.linphone.services

import org.linphone.models.ExternalCallerId
import org.linphone.models.realtime.CallerId

/** One entry in the outgoing caller ID list. [disabled] entries are shown but can't be chosen. */
data class CallerIdOption(val callerName: String, val callerNumber: String, val disabled: Boolean) {
    // Shown by the spinner's ArrayAdapter
    override fun toString(): String = callerName
}

data class CallerIdChoice(val options: List<CallerIdOption>, val selectedIndex: Int)

/**
 * The outgoing caller ID list, as the web client builds it (user-presence.component.ts callerIdList$).
 * Each number is listed once, in the order first seen, with the later source's name winning.
 */
object CallerIdRules {
    fun choice(
        serverCallerIds: List<CallerId>?,
        policy: List<ExternalCallerId>?,
        defaultEnabled: Boolean,
        defaultLabel: String,
        current: CallerId?
    ): CallerIdChoice {
        val byNumber = LinkedHashMap<String, CallerIdOption>()

        // The account's own caller ID is always listed, but can only be chosen when enabled
        byNumber[""] = CallerIdOption(defaultLabel, "", !defaultEnabled)

        serverCallerIds?.forEach { c ->
            val number = c.callerNumber
            if (!number.isNullOrEmpty()) byNumber[number] = CallerIdOption(c.callerName ?: number, number, false)
        }

        // The user's policy takes precedence over what the server sent
        policy?.forEach { c ->
            val number = c.callerId
            if (!number.isNullOrEmpty()) byNumber[number] = CallerIdOption(c.name ?: number, number, false)
        }

        // A current caller ID that's no longer offered is still shown, but can't be chosen again
        val currentNumber = current?.callerNumber.orEmpty()
        if (currentNumber.isNotEmpty() && !byNumber.containsKey(currentNumber)) {
            byNumber[currentNumber] = CallerIdOption(current?.callerName ?: currentNumber, currentNumber, true)
        }

        val options = byNumber.values.toList()
        return CallerIdChoice(options, options.indexOfFirst { it.callerNumber == currentNumber })
    }
}
