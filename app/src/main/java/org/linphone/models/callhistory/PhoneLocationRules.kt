package org.linphone.models.callhistory

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale

enum class PhoneLocationKind { PLACE, MOBILE, NON_GEOGRAPHIC }

/**
 * Where a number is, for the location line of a History row: a place, or Mobile or Non-geographic
 * for numbers without one. [countryCode] is the number's two-letter region, only when it's abroad.
 */
data class PhoneLocation(val kind: PhoneLocationKind, val place: String?, val countryCode: String?)

/** Ported from iOS's PhoneLocationRules (WI 36610). */
object PhoneLocationRules {
    // Extensions and feature codes are internal, so have no location
    private const val MAX_EXTENSION_LENGTH = 5

    private val nonGeographicTypes = setOf(
        PhoneNumberType.UAN,
        PhoneNumberType.TOLL_FREE,
        PhoneNumberType.SHARED_COST,
        PhoneNumberType.PREMIUM_RATE,
        PhoneNumberType.PERSONAL_NUMBER,
        PhoneNumberType.VOIP
    )

    /**
     * The number's location as seen from the user's country, or null when there's nothing to say
     * (an extension, an invalid or withheld number, or a domestic number without a place).
     */
    fun describe(number: String?, userCountry: String?): PhoneLocation? {
        if (number.isNullOrBlank()) return null
        val digits = number.filter { it.isDigit() }
        if (digits.length <= MAX_EXTENSION_LENGTH || number.trimStart().startsWith("*")) return null

        val region = if (userCountry.isNullOrEmpty()) "GB" else userCountry.uppercase()
        return try {
            val util = PhoneNumberUtil.getInstance()
            val parsed = util.parse(number, region)
            if (!util.isValidNumber(parsed)) return null

            val numberRegion = util.getRegionCodeForNumber(parsed)
            val type = util.getNumberType(parsed)
            val place = PhoneNumberOfflineGeocoder.getInstance()
                .getDescriptionForNumber(parsed, Locale.ENGLISH)
                .takeIf { it.isNotBlank() }

            val abroad = !numberRegion.isNullOrEmpty() && numberRegion != "ZZ" && numberRegion != region
            when {
                abroad -> PhoneLocation(PhoneLocationKind.PLACE, place ?: countryName(numberRegion), numberRegion)
                type == PhoneNumberType.MOBILE -> PhoneLocation(PhoneLocationKind.MOBILE, null, null)
                // At home, the country alone says nothing
                place != null && place != countryName(region) -> PhoneLocation(PhoneLocationKind.PLACE, place, null)
                type in nonGeographicTypes -> PhoneLocation(PhoneLocationKind.NON_GEOGRAPHIC, null, null)
                else -> null
            }
        } catch (e: NumberParseException) {
            null
        }
    }

    private fun countryName(region: String): String? =
        Locale("", region).getDisplayCountry(Locale.ENGLISH).takeIf { it.isNotBlank() && it != region }
}
