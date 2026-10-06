package org.linphone.models.callquality

import org.junit.Assert.assertEquals
import org.junit.Test

class CallQualityCalculatorTest {

    private val delta = 0.001f

    private fun sample(
        t: Long = 0,
        lost: Int = 0,
        received: Int = 0,
        uplinkLossPct: Float = 0f,
        downJitter: Float = 0f,
        upJitter: Float = 0f,
        rtt: Float = 0f
    ) = CallQualitySample(t, lost, received, uplinkLossPct, downJitter, upJitter, rtt)

    /** Feeds [count] identical readings a second apart, starting at [startMs]. */
    private fun CallQualityCalculator.feed(
        count: Int,
        startMs: Long = 0,
        reading: (Long) -> CallQualitySample
    ): CallQualityResult {
        var result: CallQualityResult? = null
        for (i in 0 until count) {
            val t = startMs + i * 1000L
            result = update(reading(t))
        }
        return result!!
    }

    @Test
    fun `metric score is linear between the good and bad thresholds`() {
        assertEquals(1f, CallQualityCalculator.metricScore(0f, 20f, 60f), delta)
        assertEquals(1f, CallQualityCalculator.metricScore(20f, 20f, 60f), delta)
        assertEquals(0.5f, CallQualityCalculator.metricScore(40f, 20f, 60f), delta)
        assertEquals(0f, CallQualityCalculator.metricScore(60f, 20f, 60f), delta)
        assertEquals(0f, CallQualityCalculator.metricScore(100f, 20f, 60f), delta)
        assertEquals(1f, CallQualityCalculator.metricScore(Float.NaN, 20f, 60f), delta)
    }

    @Test
    fun `scores map to buckets and strengths like the web client`() {
        val cases = mapOf(
            5f to CallQualityStrength.GOOD,
            4f to CallQualityStrength.GOOD,
            3.99f to CallQualityStrength.OK,
            3f to CallQualityStrength.OK,
            2.99f to CallQualityStrength.POOR,
            1f to CallQualityStrength.POOR,
            0f to CallQualityStrength.POOR
        )
        for ((score, strength) in cases) {
            val bucket = CallQualityCalculator.bucketFromScore(score)
            assertEquals(
                "score $score",
                strength,
                CallQualityResult(score, bucket, 0f, 0f, 0f).strength
            )
        }
        assertEquals(CallQualityBucket.VERY_POOR, CallQualityCalculator.bucketFromScore(1.5f))
        assertEquals(CallQualityBucket.UNUSABLE, CallQualityCalculator.bucketFromScore(0.5f))
    }

    @Test
    fun `loss percentage counts packets lost out of all expected`() {
        assertEquals(0f, CallQualityCalculator.lossPct(0, 0), delta)
        assertEquals(10f, CallQualityCalculator.lossPct(10, 90), delta)
        // Everything lost is 100%, where the web client reported 0
        assertEquals(100f, CallQualityCalculator.lossPct(50, 0), delta)
    }

    @Test
    fun `a clean call scores five`() {
        val result = CallQualityCalculator().feed(10) { t ->
            sample(t, received = (t / 20).toInt(), downJitter = 0.005f, rtt = 0.05f)
        }
        assertEquals(5f, result.score, delta)
        assertEquals(CallQualityStrength.GOOD, result.strength)
    }

    @Test
    fun `jitter and round trip time are converted from seconds to milliseconds`() {
        val result = CallQualityCalculator().update(
            sample(downJitter = 0.03f, upJitter = 0.04f, rtt = 0.3f)
        )
        assertEquals(40f, result.jitterMs, delta)
        assertEquals(300f, result.rttMs, delta)
    }

    @Test
    fun `the instant score weights loss, jitter and delay 50-30-20`() {
        // Jitter fully bad (0 x 0.3), everything else perfect: 0.7 x 5 = 3.5
        val calculator = CallQualityCalculator()
        calculator.feed(5) { t -> sample(t, downJitter = 0.06f) }
        assertEquals(3.5f, calculator.update(sample(5000, downJitter = 0.06f)).score, delta)

        // Delay fully bad: 0.8 x 5 = 4
        calculator.reset()
        assertEquals(4f, calculator.feed(5) { t -> sample(t, rtt = 0.5f) }.score, delta)

        // Uplink loss fully bad: 0.5 x 5 = 2.5
        calculator.reset()
        assertEquals(2.5f, calculator.feed(5) { t -> sample(t, uplinkLossPct = 5f) }.score, delta)
    }

    @Test
    fun `the reported score is the average of the last five readings`() {
        val calculator = CallQualityCalculator()
        // History starts as five 5s, so one 0 averages to 4
        val first = calculator.update(sample(rtt = 1f, downJitter = 1f, uplinkLossPct = 100f))
        assertEquals(4f, first.score, delta)
        assertEquals(CallQualityStrength.GOOD, first.strength)

        val second = calculator.update(
            sample(1000, rtt = 1f, downJitter = 1f, uplinkLossPct = 100f)
        )
        assertEquals(3f, second.score, delta)
        assertEquals(CallQualityStrength.OK, second.strength)
    }

    @Test
    fun `downlink loss uses the change in counters over the last ten seconds`() {
        val calculator = CallQualityCalculator()
        // 30 seconds at 50 packets a second with 10% loss
        calculator.feed(30) { t ->
            val s = (t / 1000).toInt()
            sample(t, lost = s * 5, received = s * 45)
        }
        // Then a clean 11 seconds: the window no longer covers the lossy period
        val result = calculator.feed(11, startMs = 30_000) { t ->
            val s = (t / 1000).toInt()
            sample(t, lost = 29 * 5, received = 29 * 45 + (s - 29) * 50)
        }
        assertEquals(0f, result.lossPct, delta)
    }

    @Test
    fun `downlink loss over the window is the worse side`() {
        val result = CallQualityCalculator().feed(11) { t ->
            val s = (t / 1000).toInt()
            sample(t, lost = s * 2, received = s * 48, uplinkLossPct = 1f)
        }
        assertEquals(4f, result.lossPct, delta)
    }

    @Test
    fun `uplink loss is the average of reported rates in the window`() {
        val calculator = CallQualityCalculator()
        calculator.feed(5) { t -> sample(t, uplinkLossPct = 8f) }
        val result = calculator.feed(5, startMs = 5000) { t -> sample(t, uplinkLossPct = 0f) }
        assertEquals(4f, result.lossPct, delta)

        // Eleven seconds later the lossy readings have dropped out
        assertEquals(0f, calculator.feed(11, startMs = 10_000) { t -> sample(t) }.lossPct, delta)
    }

    @Test
    fun `a counter that goes backwards counts from zero`() {
        val calculator = CallQualityCalculator()
        calculator.update(sample(0, lost = 100, received = 10_000))
        val result = calculator.update(sample(1000, lost = 1, received = 99))
        assertEquals(1f, result.lossPct, delta)
    }

    @Test
    fun `negative cumulative loss from duplicate packets is treated as none`() {
        val calculator = CallQualityCalculator()
        calculator.update(sample(0, lost = 0, received = 0))
        assertEquals(0f, calculator.update(sample(1000, lost = -3, received = 50)).lossPct, delta)
    }

    @Test
    fun `reset starts a new call from a clean history`() {
        val calculator = CallQualityCalculator()
        calculator.feed(5) { t ->
            val s = (t / 1000).toInt()
            sample(t, lost = s * 50, received = s * 50, rtt = 1f, downJitter = 1f)
        }

        calculator.reset()
        // The next call's counters start at zero and its first reading is clean
        val result = calculator.update(sample(5000, lost = 0, received = 50))
        assertEquals(5f, result.score, delta)
        assertEquals(0f, result.lossPct, delta)
    }

    @Test
    fun `reason describes the score and its inputs`() {
        val result = CallQualityResult(2.1f, CallQualityBucket.POOR, 4f, 35f, 300f)
        assertEquals("2.10 / 5 (Loss 4.00%, Jitter 35ms, Delay 300ms)", result.reason)
    }
}
