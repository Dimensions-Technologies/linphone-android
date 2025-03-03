package org.linphone.services

import PresenceEventData
import PresenceObservable
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.ReplaySubject
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.linphone.authentication.AuthStateManager
import org.linphone.authentication.AuthorizationServiceManager
import org.linphone.environment.DimensionsEnvironmentService
import org.linphone.models.AuthenticatedUser
import org.linphone.models.realtime.RealtimeEvent
import org.linphone.models.realtime.RealtimeEventType
import org.linphone.models.realtime.SetPresenceModel
import org.linphone.services.realtime.RealtimeUserService
import org.linphone.utils.Log
import org.linphone.utils.Optional

class PresenceService(val context: Context) : DefaultLifecycleObserver {
    private val destroy = PublishSubject.create<Unit>()
    private val authStateManager = AuthStateManager.getInstance(context)
    private val apiClient = APIClientService()
    private val dimensionsEnvironment = DimensionsEnvironmentService.getInstance(context).getCurrentEnvironment()
    private val realtimeUserService = RealtimeUserService.getInstance(context)

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)

        destroy.onNext(Unit)
        destroy.onComplete()
    }

    companion object {
        private const val TAG: String = "PresenceService"

        private val instance: AtomicReference<PresenceService> =
            AtomicReference<PresenceService>()

        fun getInstance(context: Context): PresenceService {
            var svc = instance.get()
            if (svc == null) {
                svc = PresenceService(context.applicationContext)
                instance.set(svc)
            }
            return svc
        }
    }

    private val presenceObservables = mutableMapOf<String, PresenceObservable>()
    private val presenceEventSubject: ReplaySubject<RealtimeEvent<PresenceEventData>> =
        ReplaySubject.create(1)

    val currentUserPresence: Observable<Optional<PresenceEventData>> = authStateManager.user
        .switchMap { user ->
            if (user.id.toString() != AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER) {
                getUserPresenceStream(user.id.toString())
                    .map {
                        Optional.of(it)
                    }
            } else {
                Observable.just(Optional.empty())
            }
        }

    init {
        realtimeUserService.hubConnection.on(RealtimeEventType.PresenceEvent.eventName, { event: RealtimeEvent<PresenceEventData> ->
            Log.d(RealtimeEventType.PresenceEvent.eventName, event)

            try {
                val observable = presenceObservables[event.userId]
                observable?.subject?.onNext(event.data)

                presenceEventSubject.onNext(event)
            } catch (e: Exception) {
                Log.e(RealtimeEventType.PresenceEvent.eventName, e)
            }
        }, RealtimeEvent::class.java)
    }

    fun setPresenceState(presence: SetPresenceModel): Observable<Unit> {
        return authStateManager.user
            .firstElement()
            .flatMapObservable { user ->
                apiClient.getUCGatewayService(
                    dimensionsEnvironment!!.gatewayApiUri,
                    AuthorizationServiceManager.getInstance(context).authorizationServiceInstance,
                    AuthStateManager.getInstance(context)
                ).doSetPresence(user.id.toString(), presence)
            }
    }

    private fun getUserPresenceStream(userId: String): Observable<PresenceEventData> {
        val existingObservable = presenceObservables[userId]
        return if (existingObservable != null) {
            existingObservable.data
        } else {
            val newObservable = PresenceObservable(
                { realtimeUserService.addSubscription(RealtimeEventType.PresenceEvent, userId) },
                { /*onObservableRemoved(userId)*/ }
            )
            presenceObservables[userId] = newObservable
            newObservable.data
        }
    }

    private fun onObservableRemoved(userId: String) {
        println("onObservableRemoved: $userId")

        runBlocking {
            realtimeUserService.removeSubscription(RealtimeEventType.PresenceEvent, userId, 5000)
        }
    }
}
