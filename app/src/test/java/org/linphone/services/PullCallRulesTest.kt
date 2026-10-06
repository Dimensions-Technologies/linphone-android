package org.linphone.services

import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullCallRulesTest {

    @Test
    fun `a pull is offered for a call on another device when the user has several devices`() {
        assertTrue(
            PullCallRules.shouldOffer(localCallCount = 0, ctiCallActive = true, deviceCount = 2)
        )
    }

    @Test
    fun `no pull is offered when the call is here, there is no call, or there is nowhere to pull from`() {
        assertFalse(
            PullCallRules.shouldOffer(localCallCount = 1, ctiCallActive = true, deviceCount = 2)
        )
        assertFalse(
            PullCallRules.shouldOffer(localCallCount = 0, ctiCallActive = false, deviceCount = 2)
        )
        assertFalse(
            PullCallRules.shouldOffer(localCallCount = 0, ctiCallActive = true, deviceCount = 1)
        )
    }

    @Test
    fun `the offer shows only after it has held for the delay`() {
        val scheduler = TestScheduler()
        val offer = PublishSubject.create<Boolean>()
        val shown = PullCallRules.delayShowing(offer, scheduler).test()

        offer.onNext(false)
        offer.onNext(true)
        scheduler.advanceTimeBy(PullCallRules.SHOW_DELAY_MS - 1, TimeUnit.MILLISECONDS)
        shown.assertValues(false)

        scheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
        shown.assertValues(false, true)
    }

    @Test
    fun `a brief gap between call legs doesn't flash the offer on`() {
        val scheduler = TestScheduler()
        val offer = PublishSubject.create<Boolean>()
        val shown = PullCallRules.delayShowing(offer, scheduler).test()

        offer.onNext(false)
        offer.onNext(true)
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS)
        offer.onNext(false)
        scheduler.advanceTimeBy(10, TimeUnit.SECONDS)

        shown.assertValues(false, false)
    }

    @Test
    fun `the offer hides at once once the call is pulled`() {
        val scheduler = TestScheduler()
        val offer = PublishSubject.create<Boolean>()
        val shown = PullCallRules.delayShowing(offer, scheduler).test()

        offer.onNext(true)
        scheduler.advanceTimeBy(PullCallRules.SHOW_DELAY_MS, TimeUnit.MILLISECONDS)
        offer.onNext(false)

        shown.assertValues(true, false)
    }
}
