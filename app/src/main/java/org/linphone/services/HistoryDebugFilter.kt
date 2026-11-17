package org.linphone.services

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.linphone.BuildConfig

/**
 * Debug builds only: hides the calls that voicemails were left on from History and Voicemail, as if
 * they'd aged out of the call history, so the voicemails fall back to their remembered calls
 * (WI 37000). Toggled by long-pressing the History tab from another tab (Missed or Voicemail), and
 * off again when the app restarts.
 */
object HistoryDebugFilter {
    private val subject = BehaviorSubject.createDefault(false)

    val hideCallsWithVoicemail: Observable<Boolean> = subject.distinctUntilChanged()

    val isAvailable: Boolean get() = BuildConfig.DEBUG

    /** Turns the filter on or off; true if it's now on. */
    fun toggle(): Boolean {
        if (!isAvailable) return false
        val on = subject.value != true
        subject.onNext(on)
        return on
    }
}
