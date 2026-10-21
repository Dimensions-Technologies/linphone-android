package org.linphone.services

import android.content.Context
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.realtime.CallParty
import org.linphone.models.realtime.CallPartyRules
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
 * arrive before or after the INVITE, so both apply the latest calls. Each update also checks whether
 * the call's other party has changed (CallPartyRules), e.g. after an attended transfer.
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
    private val parties = mutableMapOf<String, CallParty>()

    private val subscriptions = mutableListOf<Disposable>()

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            val callId = call.callLog.callId ?: return
            when (state) {
                Call.State.Released -> {
                    coreContext.handler.postDelayed(
                        {
                            routes.remove(callId)
                            parties.remove(callId)
                        },
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
        return CallRouteRules.phoneNumber(paiNumber(call), route ?: CallRoute(), call.remoteAddress.username)
    }

    /** The number the PBX asserted for the call (P-Asserted-Identity), unless it's gone stale. */
    fun paiNumber(call: Call): String? = CallPartyRules.paiNumber(pai(call), partyOf(call))

    /**
     * The name to show for a call no contact matches, while its SIP identity still names the party
     * it had before a transfer (see CallPartyRules.staleIdentityName); else null.
     */
    fun staleIdentityName(call: Call): String? {
        if (!started) return null
        val route = routes[call.callLog.callId ?: return null]?.value ?: return null
        return CallPartyRules.staleIdentityName(
            partyOf(call),
            call.remoteAddress.asString(),
            paiNumber(call),
            route,
            phoneNumber(call)
        )
    }

    /** How many times the call's other party has changed (see CallPartyRules), e.g. by a transfer. */
    fun otherPartyChanges(call: Call): Int = partyOf(call).changes

    private fun pai(call: Call) = SipIdentity.userPart(call.remoteParams?.getCustomHeader("P-Asserted-Identity"))

    private fun partyOf(call: Call): CallParty =
        if (started) call.callLog.callId?.let { parties[it] } ?: CallParty() else CallParty()

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
        val cti = CtiCallRules.callForSipCall(ctiCalls, callId)
        val route = CallRouteRules.update(previous, cti, call.dir == Call.Dir.Incoming)
        val previousParty = parties[callId] ?: CallParty()
        val countryCode = PhoneFormatterService.getInstance(context).getPbxCountryCode()
        val party = CallPartyRules.update(
            previousParty,
            previous,
            route,
            cti != null,
            pai(call),
            call.remoteAddress.username,
            call.remoteAddress.asString()
        ) { ContactDirectoryRules.toE164(it, countryCode) }
        parties[callId] = party
        if (route == previous && party == previousParty) return

        if (route != previous) Log.i("[Call Route] Call $callId came in on $route")
        if (party.stalePaiNumber != previousParty.stalePaiNumber && party.stalePaiNumber.isNotEmpty()) {
            Log.i("[Call Route] Call $callId: asserted number ${party.stalePaiNumber} has gone stale")
        }
        subject.onNext(route)
        if (party.changes != previousParty.changes) {
            Log.i("[Call Route] Call $callId is now with someone else: ${party.shownNumber}")
            CallContactMatchService.otherPartyChanged(call)
        } else if (route.ctiNumber != previous.ctiNumber || party.stalePaiNumber != previousParty.stalePaiNumber) {
            // The CTI number, or a stale asserted one, can change the number the call is matched on
            CallContactMatchService.refresh(call)
        }
        // The notification is built once per call state, so rebuild it. Posted, so that during a
        // state change it follows the notification that state shows.
        coreContext.handler.post { coreContext.notificationsManager.refreshCallNotification(call) }
    }
}
