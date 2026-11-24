package org.linphone.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
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
    private val mainHandler = Handler(Looper.getMainLooper())
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

    /**
     * Makes [option] the outgoing caller ID. Shown straight away, as on the web client. Returns false
     * when it can't be sent because the realtime connection is down (the list is disabled then).
     */
    fun setCurrent(option: CallerIdOption): Boolean {
        if (option.disabled) return false
        val previous = currentSubject.value ?: defaultCallerId
        if (option.callerNumber == previous.callerNumber.orEmpty()) return true

        val realtime = RealtimeUserService.getInstance(context)
        // The change only goes through the hub, so don't show a caller ID the server never got.
        // The web client only logs this failure, leaving its list on a caller ID that isn't in use.
        if (!realtime.isConnected) {
            Log.w("[Caller ID] Not setting to ${option.callerNumber.ifEmpty { "default" }}, realtime isn't connected")
            return false
        }

        val attempted = CallerId(option.callerName, option.callerNumber)
        currentSubject.onNext(attempted)

        Log.i("[Caller ID] Setting to ${option.callerNumber.ifEmpty { "default" }}")
        subscriptions.add(
            realtime.setUserCli(SetUserCliRequest(option.callerNumber))
                .subscribe(
                    { Log.i("[Caller ID] setUserCliRequest succeeded") },
                    { e ->
                        // The connection can still drop between the check and the request. The list
                        // goes back to what the server has, and is disabled until it reconnects. It
                        // may reconnect at once, so say the change failed.
                        Log.e(e, "[Caller ID] setUserCliRequest failed")
                        currentSubject.onNext(CallerIdRules.afterFailedSet(currentSubject.value, attempted, previous))
                        mainHandler.post {
                            Toast.makeText(context, R.string.presence_editor_caller_id_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                )
        )
        return true
    }
}
