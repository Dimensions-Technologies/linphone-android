package org.linphone.telecom

import kotlin.math.roundToInt

/**
 * Size rules for the icon on the self-managed Telecom phone account.
 *
 * Telecom refuses to register a phone account whose icon, written with `Icon.writeToStream` and
 * base64 encoded, is longer than 65,535 characters ("Icon cannot be written to memory. Try
 * compressing or downsizing"). Some devices (a Samsung Galaxy S26 Ultra) appear to turn a resource
 * icon into a full-density bitmap before that check, so the app logo went over the limit, the phone
 * account was never registered and every incoming call was refused as busy (WI #36796). The app now
 * hands Telecom a small bitmap it has already checked against the limit.
 */
object PhoneAccountIconRules {
    /** Longest base64 string Telecom accepts for the icon (FastDataOutput.writeUTF limit). */
    const val TELECOM_ICON_LIMIT = 65_535

    /** Largest side of the bitmap first tried. Telecom only shows the icon small. */
    const val MAX_ICON_SIZE_PX = 192

    /** Smallest side worth trying before giving up and registering without an icon. */
    const val MIN_ICON_SIZE_PX = 24

    /** Header `Icon.writeToStream` writes before the PNG: version int + type byte. */
    private const val STREAM_HEADER_BYTES = 5

    /** Android's `Base64.DEFAULT` wraps lines at 76 characters and ends each line with '\n'. */
    private const val BASE64_LINE_LENGTH = 76

    /**
     * Length of the base64 string Telecom builds from a bitmap icon whose PNG is [pngBytes] long.
     */
    fun serialisedLength(pngBytes: Int): Int {
        val streamBytes = pngBytes + STREAM_HEADER_BYTES
        val chars = ((streamBytes + 2) / 3) * 4
        val newlines = (chars + BASE64_LINE_LENGTH - 1) / BASE64_LINE_LENGTH
        return chars + newlines
    }

    /** True when a bitmap icon whose PNG is [pngBytes] long passes Telecom's size check. */
    fun fitsTelecomLimit(pngBytes: Int): Boolean {
        return serialisedLength(pngBytes) <= TELECOM_ICON_LIMIT
    }

    /**
     * Scales a [width] x [height] image so its longest side is at most [maxSide], keeping the aspect
     * ratio. Images already small enough keep their size. Unknown sizes (0 or less, as vector or
     * colour drawables report) become a [maxSide] square.
     */
    fun scaledSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return Pair(maxSide, maxSide)
        val longest = maxOf(width, height)
        if (longest <= maxSide) return Pair(width, height)
        val scale = maxSide.toDouble() / longest
        return Pair(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1)
        )
    }

    /** Sides to try in turn, halving each time, from [MAX_ICON_SIZE_PX] down to [MIN_ICON_SIZE_PX]. */
    fun candidateSizes(): List<Int> {
        return generateSequence(MAX_ICON_SIZE_PX) { it / 2 }
            .takeWhile { it >= MIN_ICON_SIZE_PX }
            .toList()
    }
}
