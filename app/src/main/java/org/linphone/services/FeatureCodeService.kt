package org.linphone.services

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.rx3.rxSingle
import org.linphone.authentication.AuthStateManager
import org.linphone.models.AuthenticatedUser
import org.linphone.utils.Log

/** The user's PBX feature codes (feature name to the number dialled), fetched once per user. */
class FeatureCodeService(val context: Context) : DefaultLifecycleObserver {

    private val apiClient = APIClientService(context)
    private val authStateManager = AuthStateManager.getInstance(context)
    private val destroy = PublishSubject.create<Unit>()

    val featureCodes: Observable<Map<String, String>> = authStateManager.user
        .filter { u -> u.id != null && u.id != AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER }
        .distinctUntilChanged { user -> user.id ?: "" }
        .switchMapSingle { rxSingle { fetchFeatureCodes() } }
        .startWithItem(emptyMap())
        .replay(1)
        .autoConnect()
        .takeUntil(destroy)

    init {
        Log.d("Created FeatureCodeService")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)

        destroy.onNext(Unit)
        destroy.onComplete()
    }

    companion object {
        private val instance: AtomicReference<FeatureCodeService> = AtomicReference<FeatureCodeService>()

        fun getInstance(context: Context): FeatureCodeService {
            var svc = instance.get()
            if (svc == null) {
                svc = FeatureCodeService(context.applicationContext)
                instance.set(svc)
            }
            return svc
        }
    }

    private suspend fun fetchFeatureCodes(): Map<String, String> {
        return try {
            val response = apiClient.getUCGatewayService().getUserFeatureCodes()
            if (!response.isSuccessful) {
                Log.e("Failed to fetch feature codes::${response.code()}")
                return emptyMap()
            }
            response.body().orEmpty().associate { it.name to it.number }
        } catch (e: Exception) {
            Log.e("Failed to fetch feature codes", e)
            emptyMap()
        }
    }
}
