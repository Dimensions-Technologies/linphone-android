package org.linphone.services

import android.content.Context
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.models.realtime.CallRoute
import org.linphone.models.realtime.CallRouteRules
import org.linphone.models.realtime.CtiCallData
import org.linphone.models.realtime.CtiCallRules
import org.linphone.models.realtime.RealtimeEventType
import org.linphone.services.realtime.RealtimeUserService
import org.linphone.utils.Log
import org.linphone.utils.SipIdentity

/**
 * The route path and queue each live call came in on, ported from the web client
 * (call-manager.service.ts augmentSipCallData): the PBX's callEvent lists the user's calls, and a
 * call here is the CTI call that has its SIP Call-ID among its relatedCalls. The callEvent can
 * arrive before or after the INVITE, so both apply the latest calls.
 *
 * Runs on the core's (main) thread; realtime events arrive on SignalR's.
 */
object CallRouteService {
    // Routes are kept a while after the call ends, for the missed call notification
    private const val RELEASED_CALL_RETENTION_MS = 60_000L

    private lateinit var context: Context
    private var started = false

    private var ctiCalls: Map<String, CtiCallData> = emptyMap()

    // By SIP Call-ID
    private val routes = mutableMapOf<String, BehaviorSubject<CallRoute>>()

    private val subscriptions = mutableListOf<Disposable>()

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            val callId = call.callLog.callId ?: return
            when (state) {
                Call.State.Released -> {
                    coreContext.handler.postDelayed(
                        { routes.remove(callId) },
                        RELEASED_CALL_RETENTION_MS
                    )
                }
                Call.State.End, Call.State.Error -> {}
                else -> apply(call)
            }
        }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        this.context = context.applicationContext
        coreContext.core.addListener(coreListener)

        val realtime = RealtimeUserService.getInstance(this.context)
        subscriptions.add(
            realtime.subscribeForCurrentUser(RealtimeEventType.CallEvent).subscribe(
                {},
                { e -> Log.e("[Call Route] Subscription failed", e) }
            )
        )
        subscriptions.add(
            realtime.callEvent.subscribe(
                { event ->
                    val calls = CtiCallRules.currentCalls(event.data?.calls)
                    coreContext.handler.post {
                        ctiCalls = calls
                        coreContext.core.calls.forEach(::apply)
                    }
                },
                { e -> Log.e("[Call Route] callEvent failed", e) }
            )
        )
    }

    /** The call's route, updated as the PBX reports it. */
    fun route(call: Call): Observable<CallRoute> {
        val subject = subjectFor(call) ?: return Observable.just(CallRoute())
        apply(call)
        return subject
    }

    /**
     * The route to name in the call's notification ("Incoming call via {route}"), if any. The call
     * can have ended, e.g. for a missed call.
     */
    fun notificationRoute(call: Call): String? {
        if (!started) return null
        val route = routes[call.callLog.callId ?: return null]?.value ?: return null
        return CallRouteRules.notificationRoute(route, phoneNumber(call))
    }

    /**
     * The other party's number, as the web client's CallSummary.getPhoneNumber (see
     * CallRouteRules.phoneNumber). The call can have ended.
     */
    fun phoneNumber(call: Call): String {
        val route = if (started) call.callLog.callId?.let { routes[it]?.value } else null
        return CallRouteRules.phoneNumber(
            SipIdentity.userPart(call.remoteParams?.getCustomHeader("P-Asserted-Identity")),
            route ?: CallRoute(),
            call.remoteAddress.username
        )
    }

    private fun subjectFor(call: Call): BehaviorSubject<CallRoute>? {
        if (!started) return null
        val callId = call.callLog.callId
        if (callId.isNullOrEmpty()) return null
        return routes.getOrPut(callId) { BehaviorSubject.createDefault(CallRoute()) }
    }

    private fun apply(call: Call) {
        val subject = subjectFor(call) ?: return
        val callId = call.callLog.callId ?: return
        val previous = subject.value ?: CallRoute()
        val route = CallRouteRules.update(
            previous,
            CtiCallRules.callForSipCall(ctiCalls, callId),
            call.dir == Call.Dir.Incoming
        )
        if (route == previous) return

        Log.i("[Call Route] Call $callId came in on $route")
        subject.onNext(route)
        // The CTI number can change the number the call is matched on
        if (route.ctiNumber != previous.ctiNumber) CallContactMatchService.refresh(call)
        // The notification is built once per call state, so rebuild it. Posted, so that during a
        // state change it follows the notification that state shows.
        coreContext.handler.post { coreContext.notificationsManager.refreshCallNotification(call) }
    }
}
