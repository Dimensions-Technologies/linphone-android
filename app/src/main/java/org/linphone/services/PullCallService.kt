package org.linphone.services

import android.content.Context
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import kotlinx.coroutines.rx3.rxSingle
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.authentication.AuthStateManager
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.models.AuthenticatedUser
import org.linphone.models.PbxFeatureCode
import org.linphone.models.realtime.CtiCallRules
import org.linphone.models.realtime.RealtimeEventType
import org.linphone.services.realtime.RealtimeUserService
import org.linphone.utils.Log

/**
 * Offers to pull a call the user is on at another device (desk phone, web or desktop client) to
 * this one, ported from the web client (call-manager.service.ts callAtOtherDevice and
 * logged-in-view's actionPullCall). The PBX's callEvent says the user is on a call; if none of
 * the calls is here, pulling dials the "move" feature code, which moves it to this device.
 *
 * The core listener runs on the core's (main) thread; realtime events arrive on SignalR's.
 */
object PullCallService {
    private lateinit var context: Context
    private var started = false

    private val localCallCount = BehaviorSubject.createDefault(0)
    private val ctiCallActive = BehaviorSubject.createDefault(false)
    private val deviceCount = BehaviorSubject.createDefault(0)

    private val subscriptions = mutableListOf<Disposable>()

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            localCallCount.onNext(core.callsNb)
        }

        override fun onLastCallEnded(core: Core) {
            localCallCount.onNext(0)
        }
    }

    /** Whether to offer to pull a call to this device. */
    val callAtOtherDevice: Observable<Boolean> = PullCallRules.delayShowing(
        Observable.combineLatest(localCallCount, ctiCallActive, deviceCount) { local, cti, devices ->
            PullCallRules.shouldOffer(local, cti, devices)
        },
        Schedulers.computation()
    )
        .replay(1)
        .autoConnect()

    fun start(context: Context) {
        if (started) return
        started = true
        this.context = context.applicationContext
        coreContext.core.addListener(coreListener)
        localCallCount.onNext(coreContext.core.callsNb)

        val realtime = RealtimeUserService.getInstance(this.context)
        subscriptions.add(
            realtime.subscribeForCurrentUser(RealtimeEventType.CallEvent).subscribe(
                {},
                { e -> Log.e("[Pull Call] Subscription failed", e) }
            )
        )
        subscriptions.add(
            realtime.callEvent.subscribe(
                { event ->
                    val calls = CtiCallRules.currentCalls(event.data?.calls)
                    ctiCallActive.onNext(CtiCallRules.activeCallId(calls) != null)
                },
                { e -> Log.e("[Pull Call] callEvent failed", e) }
            )
        )

        // Fetch the feature codes now, so they're ready by the time a pull is offered
        subscriptions.add(
            FeatureCodeService.getInstance(this.context).featureCodes.subscribe(
                {},
                { e -> Log.e("[Pull Call] Feature codes failed", e) }
            )
        )

        // The user's devices, fetched once per user. A previous user's calls no longer apply.
        val apiClient = APIClientService(this.context)
        subscriptions.add(
            AuthStateManager.getInstance(this.context).user
                .distinctUntilChanged { user -> user.id ?: "" }
                .doOnNext {
                    ctiCallActive.onNext(false)
                    deviceCount.onNext(0)
                }
                .filter { u -> u.id != null && u.id != AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER }
                .switchMapSingle { rxSingle { fetchDeviceCount(apiClient) } }
                .subscribe(
                    { count -> deviceCount.onNext(count) },
                    { e -> Log.e("[Pull Call] Devices failed", e) }
                )
        )
    }

    /**
     * Dials the "move" feature code to pull the call here. Calls [onNoFeatureCode] (on the main
     * thread) if the user has no such code.
     */
    fun pullCall(onNoFeatureCode: () -> Unit) {
        FeatureCodeService.getInstance(context).featureCodes
            .take(1)
            .subscribe(
                { codes ->
                    coreContext.handler.post {
                        val code = codes[PbxFeatureCode.MOVE_CALL]
                        if (code.isNullOrEmpty()) {
                            Log.w("[Pull Call] No move feature code")
                            onNoFeatureCode()
                        } else {
                            Log.i("[Pull Call] Pulling call with $code")
                            coreContext.startCall(code)
                        }
                    }
                },
                { e -> Log.e("[Pull Call] Feature codes failed", e) }
            )
    }

    private suspend fun fetchDeviceCount(apiClient: APIClientService): Int {
        return try {
            val response = apiClient.getUCGatewayService().getAllUserDevices()
            if (!response.isSuccessful) {
                Log.e("[Pull Call] Failed to fetch devices::${response.code()}")
                return 0
            }
            response.body().orEmpty().size
        } catch (e: Exception) {
            Log.e("[Pull Call] Failed to fetch devices", e)
            0
        }
    }
}
