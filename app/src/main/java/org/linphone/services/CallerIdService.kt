package org.linphone.services

import android.content.Context
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.authentication.AuthStateManager
import org.linphone.models.realtime.CallerId
import org.linphone.models.realtime.RealtimeEventType
import org.linphone.models.realtime.SetUserCliRequest
import org.linphone.services.realtime.RealtimeUserService
import org.linphone.utils.Log

/**
 * The user's outgoing caller ID, ported from the web client (call-manager.service.ts callerIdEvent,
 * user-presence.component.ts onCallerIdChange). The server sends it, and the ones it offers, in
 * callerIdEvent; choosing one sends setUserCliRequest on the user hub.
 */
object CallerIdService {
    // The account's own caller ID, used until the server says otherwise
    private val defaultCallerId = CallerId("Default", "")

    private val context: Context get() = coreContext.context
    private var started = false

    private val subscriptions = mutableListOf<Disposable>()

    private val callerIdsSubject = BehaviorSubject.createDefault<List<CallerId>>(emptyList())
    private val currentSubject = BehaviorSubject.createDefault(defaultCallerId)

    /** The caller IDs the server offers, besides the user's policy ones. */
    val callerIds: Observable<List<CallerId>> = callerIdsSubject

    /** The caller ID outgoing calls present. A blank number is the account's own. */
    val current: Observable<CallerId> = currentSubject

    fun start() {
        if (started) return
        started = true

        // Forget the last user's caller IDs when the user changes
        subscriptions.add(
            AuthStateManager.getInstance(context).user
                .map { it.id.toString() }
                .distinctUntilChanged()
                .subscribe(
                    {
                        callerIdsSubject.onNext(emptyList())
                        currentSubject.onNext(defaultCallerId)
                    },
                    { e -> Log.e("[Caller ID] User failed", e) }
                )
        )

        val realtime = RealtimeUserService.getInstance(context)
        subscriptions.add(
            realtime.subscribeForCurrentUser(RealtimeEventType.CallerIdEvent).subscribe(
                {},
                { e -> Log.e("[Caller ID] Subscription failed", e) }
            )
        )
        subscriptions.add(
            realtime.callerIdEvent.subscribe(
                { event ->
                    val data = event.data ?: return@subscribe
                    Log.i(
                        "[Caller ID] Server sent ${data.callerId?.callerNumber.orEmpty().ifEmpty { "default" }}, " +
                            "${data.callerIds.orEmpty().size} offered"
                    )
                    callerIdsSubject.onNext(data.callerIds.orEmpty())
                    currentSubject.onNext(data.callerId ?: defaultCallerId)
                },
                { e -> Log.e("[Caller ID] callerIdEvent failed", e) }
            )
        )
    }

    /** Makes [option] the outgoing caller ID. Shown straight away, as on the web client. */
    fun setCurrent(option: CallerIdOption) {
        if (option.disabled) return
        if (option.callerNumber == currentSubject.value?.callerNumber.orEmpty()) return

        currentSubject.onNext(CallerId(option.callerName, option.callerNumber))

        Log.i("[Caller ID] Setting to ${option.callerNumber.ifEmpty { "default" }}")
        subscriptions.add(
            RealtimeUserService.getInstance(context).setUserCli(SetUserCliRequest(option.callerNumber))
                .subscribe(
                    { Log.i("[Caller ID] setUserCliRequest succeeded") },
                    { e -> Log.e(e, "[Caller ID] setUserCliRequest failed") }
                )
        )
    }
}
