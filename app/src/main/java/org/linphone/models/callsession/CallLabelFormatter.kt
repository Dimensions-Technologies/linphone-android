package org.linphone.models.callsession

import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Names the parties to a call segment, ported from the web client's call-label-formatter.ts.
 */
object CallLabelFormatter {

    /**
     * "origin ➔ destination", each named after the directory contact with its number if there is
     * one ([contactName] looks a number up, in E.164 form).
     */
    fun callLabel(call: CallSegment, countryCode: String, contactName: (String) -> String?): String {
        val resolve = { value: String ->
            if (value.isEmpty()) value else contactName(PhoneFormat.toE164(value, countryCode)) ?: value
        }
        return listOf(
            resolve(originLabel(call, countryCode)),
            resolve(destinationLabel(call, countryCode))
        )
            .filter { it.isNotEmpty() }
            .joinToString(" ➔ ")
    }

    fun originLabel(call: CallSegment, countryCode: String): String {
        val conns = call.conns.orEmpty()
        val origin = if (call.type == SessionCallTypes.EXTERNAL && call.dir == SessionCallDirections.INCOMING) {
            conns.firstOrNull { it.type == ConnectionTypes.TRUNK }
        } else {
            conns.filter {
                (call.type == SessionCallTypes.EXTERNAL && it.type != ConnectionTypes.TRUNK) ||
                    it.dir == ConnectionDirections.INVOKING
            }.sortedWith(connectionOrder).firstOrNull()
        }
        return connectionLabel(origin, countryCode)
    }

    fun destinationLabel(call: CallSegment, countryCode: String): String {
        val conns = call.conns.orEmpty()
        val dest = when {
            call.conference != 0 -> conns.firstOrNull { it.confParts != 0 || it.confAtt != 0 }
            call.type == SessionCallTypes.EXTERNAL && call.dir == SessionCallDirections.OUTGOING ->
                conns.firstOrNull { it.type == ConnectionTypes.TRUNK }
            else -> conns.filter {
                (call.type == SessionCallTypes.EXTERNAL && it.type != ConnectionTypes.TRUNK) ||
                    it.dir == ConnectionDirections.INVOKED
            }.sortedWith(connectionOrder).firstOrNull()
        }

        val label = connectionLabel(dest, countryCode)
        return when {
            call.type == SessionCallTypes.EXTERNAL && call.dir == SessionCallDirections.INCOMING &&
                !call.hgName.isNullOrEmpty() -> call.hgName
            call.ovfOut != 0 && call.xfrToDevType == "Group" ->
                call.hgName?.takeIf { it.isNotEmpty() } ?: call.hgNum?.takeIf { it.isNotEmpty() } ?: label
            else -> label
        }
    }

    /** A connection's label: short (as in the header) or full, with number and device. */
    fun connectionLabel(connection: CallConnection?, countryCode: String, full: Boolean = false): String {
        if (connection == null) return ""
        val tagName = connection.tags?.contName?.takeIf { it.isNotEmpty() }
            ?: connection.tags?.compName?.takeIf { it.isNotEmpty() }
        if (tagName != null && !full) return tagName

        if (connection.type == ConnectionTypes.TRUNK) {
            val externalNumber = if (connection.dir == ConnectionDirections.INVOKING) {
                connection.callerName?.takeIf { it.isNotEmpty() }
                    ?: PhoneFormat.format(connection.caller.orEmpty(), countryCode)
            } else {
                connection.calledName?.takeIf { it.isNotEmpty() }
                    ?: PhoneFormat.format(connection.called.orEmpty(), countryCode)
            }
            return if (tagName != null) "$tagName ($externalNumber)" else externalNumber
        }

        return if (!full) {
            listOf(
                connection.userName,
                connection.agtName,
                connection.devName,
                connection.userNum,
                connection.agtNum,
                connection.devNum
            ).firstOrNull { !it.isNullOrEmpty() }.orEmpty()
        } else if (!connection.userName.isNullOrEmpty() || !connection.userNum.isNullOrEmpty()) {
            joinNameAndNumber(connection.userName, connection.userNum, connection.devName)
        } else if (!connection.agtName.isNullOrEmpty() || !connection.agtNum.isNullOrEmpty()) {
            joinNameAndNumber(connection.agtName, connection.agtNum, connection.devName)
        } else {
            joinNameAndNumber(connection.devName, connection.devNum, connection.devName)
        }
    }

    private fun joinNameAndNumber(name: String?, number: String?, deviceName: String?): String {
        var str = name.orEmpty()
        if (!number.isNullOrEmpty()) str = if (str.isNotEmpty()) "$str @ $number" else number
        if (!deviceName.isNullOrEmpty()) str = if (str.isNotEmpty()) "$str [$deviceName]" else deviceName
        return str
    }

    // Answered first, then the latest start first
    private val connectionOrder = compareByDescending<CallConnection> { it.answered }
        .thenByDescending { it.start ?: 0.0 }
}

/** Phone number display, as the web client's phone-formatter.service.ts. */
object PhoneFormat {
    private val dialable = Regex("^[+*#0-9]{5,}$")

    /** National form for numbers in the PBX's country, international otherwise; short numbers as they are. */
    fun format(input: String, countryCode: String): String {
        if (!dialable.matches(input)) return input
        return try {
            val util = PhoneNumberUtil.getInstance()
            val number = util.parse(input, countryCode)
            val format = if (util.getRegionCodeForNumber(number) == countryCode) {
                PhoneNumberUtil.PhoneNumberFormat.NATIONAL
            } else {
                PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL
            }
            util.format(number, format)
        } catch (e: Exception) {
            input
        }
    }

    /** E.164 form for lookups; feature codes and short numbers as they are. */
    fun toE164(input: String, countryCode: String): String {
        val cleaned = input.replace(Regex("[\\s\\-().]"), "")
        if (cleaned.startsWith("*") || !dialable.matches(cleaned)) return input
        return try {
            val util = PhoneNumberUtil.getInstance()
            util.format(util.parse(cleaned, countryCode), PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (e: Exception) {
            cleaned
        }
    }
}
