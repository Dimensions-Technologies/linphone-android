package org.linphone.services

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import java.util.concurrent.TimeUnit

/** When to offer to pull a call here from another device, ported from the web client's call-manager.service.ts. */
object PullCallRules {
    const val SHOW_DELAY_MS = 5_000L

    /**
     * True when the PBX has the user on a call that isn't on this device. Needs more than one
     * device, or there is nowhere to pull from.
     */
    fun shouldOffer(localCallCount: Int, ctiCallActive: Boolean, deviceCount: Int): Boolean {
        return localCallCount == 0 && ctiCallActive && deviceCount > 1
    }

    /**
     * Shows the offer only once it has held for [SHOW_DELAY_MS], but hides it at once.
     * Between legs (mid-transfer, or the instant a pull lands) there are briefly no local calls
     * while the PBX still reports the call, which would flash the offer on. Hiding at once means a
     * pull that has worked doesn't look like it did nothing.
     */
    fun delayShowing(offer: Observable<Boolean>, scheduler: Scheduler): Observable<Boolean> {
        return offer
            .distinctUntilChanged()
            .switchMap { show ->
                if (show) {
                    Observable.just(true).delay(SHOW_DELAY_MS, TimeUnit.MILLISECONDS, scheduler)
                } else {
                    Observable.just(false)
                }
            }
    }
}
