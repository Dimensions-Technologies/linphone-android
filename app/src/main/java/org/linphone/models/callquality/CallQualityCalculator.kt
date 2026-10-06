package org.linphone.models.callquality

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * One reading of a call's audio stats, taken from Linphone's CallStats. Downlink is what the user
 * hears and uplink is how they sound to the far end.
 */
data class CallQualitySample(
    val timestampMs: Long,
    // Cumulative counters for incoming RTP
    val downlinkPacketsLost: Int,
    val downlinkPacketsReceived: Int,
    // Loss reported by the far end in its last RTCP receiver report, as a percentage
    val uplinkLossPct: Float,
    val downlinkJitterSeconds: Float,
    val uplinkJitterSeconds: Float,
    val roundTripDelaySeconds: Float
)

enum class CallQualityBucket { GOOD, AVERAGE, POOR, VERY_POOR, UNUSABLE }

/** The three levels shown to the user, plus NONE when there's no call. */
enum class CallQualityStrength { GOOD, OK, POOR, NONE }

data class CallQualityResult(
    // 0.0 – 5.0, averaged over the last few readings
    val score: Float,
    val bucket: CallQualityBucket,
    // Worse of uplink and downlink loss over the loss window
    val lossPct: Float,
    // Worse of uplink and downlink jitter
    val jitterMs: Float,
    val rttMs: Float
) {
    val strength: CallQualityStrength
        get() = when (bucket) {
            CallQualityBucket.GOOD -> CallQualityStrength.GOOD
            CallQualityBucket.AVERAGE -> CallQualityStrength.OK
            else -> CallQualityStrength.POOR
        }

    /** e.g. "2.10 / 5 (Loss 4.00%, Jitter 35ms, Delay 300ms)" */
    val reason: String
        get() = String.format(
            Locale.US,
            "%.2f / 5 (Loss %.2f%%, Jitter %.0fms, Delay %.0fms)",
            score,
            lossPct,
            jitterMs,
            rttMs
        )
}

/**
 * Scores call quality from 0 to 5, the same way as the web client (WebApp sip-stats.ts
 * calculateCallQuality), so both clients show the same indicator for the same network.
 *
 * Loss, jitter and round trip time each score 1 at or below a "good" threshold, falling in a
 * straight line to 0 at a "bad" threshold. They're weighted 0.5 / 0.3 / 0.2 and scaled to 0–5.
 * Loss is measured over the last 10 seconds, and the reported score is the average of the last
 * five readings, which starts out as five perfect scores.
 *
 * Feed it one sample a second, and call [reset] when a different call becomes current.
 */
class CallQualityCalculator {
    companion object {
        const val LOSS_WINDOW_MS = 10_000L
        const val SCORE_HISTORY_SIZE = 5
        const val MAX_SCORE = 5f

        const val LOSS_GOOD_PCT = 3f
        const val LOSS_BAD_PCT = 5f
        const val JITTER_GOOD_MS = 20f
        const val JITTER_BAD_MS = 60f
        const val RTT_GOOD_MS = 250f
        const val RTT_BAD_MS = 500f

        const val LOSS_WEIGHT = 0.5f
        const val JITTER_WEIGHT = 0.3f
        const val RTT_WEIGHT = 0.2f

        /** 1 at or below [good], 0 at or above [bad], linear in between. */
        fun metricScore(value: Float, good: Float, bad: Float): Float {
            if (!value.isFinite()) return 1f
            if (value <= good) return 1f
            if (value >= bad) return 0f
            return 1f - (value - good) / (bad - good)
        }

        fun bucketFromScore(score: Float): CallQualityBucket = when {
            score >= 4f -> CallQualityBucket.GOOD
            score >= 3f -> CallQualityBucket.AVERAGE
            score >= 2f -> CallQualityBucket.POOR
            score >= 1f -> CallQualityBucket.VERY_POOR
            else -> CallQualityBucket.UNUSABLE
        }

        /** Percentage of packets lost, or 0 if nothing was expected. */
        fun lossPct(lost: Int, received: Int): Float {
            val total = lost + received
            if (total <= 0) return 0f
            return lost * 100f / total
        }
    }

    private val scoreHistory = ArrayDeque<Float>()
    private val samples = ArrayDeque<CallQualitySample>()

    init {
        reset()
    }

    /** Clears the history so a new call doesn't inherit the previous call's readings. */
    fun reset() {
        scoreHistory.clear()
        repeat(SCORE_HISTORY_SIZE) { scoreHistory.addLast(MAX_SCORE) }
        samples.clear()
    }

    fun update(sample: CallQualitySample): CallQualityResult {
        addSample(sample)

        val lossPct = max(windowDownlinkLossPct(), windowUplinkLossPct())
        val jitterMs = max(sample.downlinkJitterSeconds, sample.uplinkJitterSeconds) * 1000f
        val rttMs = max(sample.roundTripDelaySeconds, 0f) * 1000f

        val combined = (
            metricScore(lossPct, LOSS_GOOD_PCT, LOSS_BAD_PCT) * LOSS_WEIGHT +
                metricScore(jitterMs, JITTER_GOOD_MS, JITTER_BAD_MS) * JITTER_WEIGHT +
                metricScore(rttMs, RTT_GOOD_MS, RTT_BAD_MS) * RTT_WEIGHT
            ) / (LOSS_WEIGHT + JITTER_WEIGHT + RTT_WEIGHT)
        val instantScore = min(max(combined * MAX_SCORE, 0f), MAX_SCORE)

        scoreHistory.addLast(instantScore)
        while (scoreHistory.size > SCORE_HISTORY_SIZE) scoreHistory.removeFirst()
        val score = scoreHistory.average().toFloat()

        return CallQualityResult(score, bucketFromScore(score), lossPct, jitterMs, rttMs)
    }

    private fun addSample(sample: CallQualitySample) {
        samples.addLast(sample)
        // Keep at least one sample; drop any older than the window
        val cutoff = sample.timestampMs - LOSS_WINDOW_MS
        while (samples.size > 1 && samples.first().timestampMs < cutoff) samples.removeFirst()
    }

    private fun windowDownlinkLossPct(): Float {
        val latest = samples.last()
        val earliest = samples.first()

        // A counter that went backwards was reset, so count from zero
        var lost = latest.downlinkPacketsLost - earliest.downlinkPacketsLost
        if (lost < 0) lost = latest.downlinkPacketsLost
        var received = latest.downlinkPacketsReceived - earliest.downlinkPacketsReceived
        if (received < 0) received = latest.downlinkPacketsReceived

        // Linphone's cumulative loss can dip below zero when duplicates arrive
        return lossPct(max(lost, 0), max(received, 0))
    }

    /**
     * Linphone only exposes the far end's loss rate per RTCP report, not its cumulative counters,
     * so uplink loss is the average of the reported rates over the window.
     */
    private fun windowUplinkLossPct(): Float =
        samples.map { max(it.uplinkLossPct, 0f) }.average().toFloat()
}
