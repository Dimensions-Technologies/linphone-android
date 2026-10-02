package org.linphone.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.rx3.rxSingle
import org.linphone.authentication.AuthStateManager
import org.linphone.environment.DimensionsEnvironmentService
import org.linphone.models.AuthenticatedUser
import org.linphone.models.UserInfo
import org.linphone.models.UserSession
import org.linphone.utils.Log

@SuppressLint("CheckResult")
class UserService(val context: Context) : DefaultLifecycleObserver {

    companion object {
        private const val USER_INFO_RETRY_INITIAL_DELAY_MS = 2_000L
        private const val USER_INFO_RETRY_MAX_DELAY_MS = 60_000L

        // Compared by reference, so a real UserInfo with default values is never mistaken for it
        private val NO_USER_INFO = UserInfo()

        /**
         * Fetches the user info for each signed-in user from [authUsers] and replays the latest
         * one to every subscriber.
         *
         * Failed fetches are retried inside the switchMap rather than turned into an error here.
         * An error would end this stream for the life of the process, leaving every subscriber
         * with an empty UserInfo (no permissions, no name) until the app was force-stopped.
         * The signed-out user goes through distinct before being dropped, so signing back in as
         * the same user fetches the user info again.
         * Every change of user first replaces the replayed value with NO_USER_INFO, which is
         * filtered out at the end. Without it, signing in as someone else would hand the previous
         * user's info to new subscribers until the new fetch finished.
         */
        internal fun userInfoStream(
            authUsers: Observable<AuthenticatedUser>,
            retryInitialDelayMs: Long = USER_INFO_RETRY_INITIAL_DELAY_MS,
            retryMaxDelayMs: Long = USER_INFO_RETRY_MAX_DELAY_MS,
            fetchUserInfo: suspend () -> UserInfo
        ): Observable<UserInfo> {
            return authUsers
                .distinctUntilChanged { u -> u.id ?: "" }
                .switchMap { u ->
                    if (u.id == null || u.id == AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER) {
                        Observable.just(NO_USER_INFO)
                    } else {
                        rxSingle {
                            fetchWithRetry(retryInitialDelayMs, retryMaxDelayMs, fetchUserInfo)
                        }.toObservable().startWithItem(NO_USER_INFO)
                    }
                }
                .replay(1)
                .autoConnect()
                .filter { u -> u !== NO_USER_INFO }
        }

        private suspend fun fetchWithRetry(
            initialDelayMs: Long,
            maxDelayMs: Long,
            fetchUserInfo: suspend () -> UserInfo
        ): UserInfo {
            var retryDelayMs = initialDelayMs

            while (true) {
                try {
                    return fetchUserInfo()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("Failed to fetch user info, retrying in ${retryDelayMs}ms", e)
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(maxDelayMs)
                }
            }
        }

        private val instance: AtomicReference<UserService> = AtomicReference<UserService>()

        fun getInstance(context: Context): UserService {
            var svc = instance.get()
            if (svc == null) {
                svc = UserService(context.applicationContext)
                instance.set(svc)
            }
            return svc
        }
    }

    val pushTokenService = PushTokenService.getInstance(context)

    val user: Observable<UserInfo>
    var userSession: UserSession? = null

    var e911Accepted: Boolean = false

    var userSubscription: Disposable? = null

    init {
        Log.i("Created UserService")

        val asm = AuthStateManager.getInstance(context)
        val state: SharedPreferences = context.getSharedPreferences(
            UserInfo.STORE_NAME,
            Context.MODE_PRIVATE
        )

        e911Accepted = state.getBoolean(UserInfo.E911_STATE, false)

        user = userInfoStream(asm.user) { getUserInfo() }

        user.subscribe { u ->
            createUserSession()
        }
    }

    fun createUserSession() {
        CoroutineScope(Dispatchers.IO).launch {
            createUserSessionAsync()
        }
    }

    private suspend fun createUserSessionAsync() {
        if (userSession == null) {
            try {
                val dimensionsEnvironment =
                    DimensionsEnvironmentService.getInstance(context).getCurrentEnvironment()
                if (dimensionsEnvironment != null) {
                    val deviceId = pushTokenService.getDeviceId()

                    val newUserSession = UserSession(
                        deviceId,
                        "plummobile",
                        Build.VERSION.RELEASE,
                        dimensionsEnvironment.name,
                        deviceId,
                        "${Build.MANUFACTURER} ${Build.MODEL}",
                        "Android",
                        Build.MANUFACTURER,
                        Build.MODEL,
                        Build.VERSION.BASE_OS,
                        pushTokenService.getToken()
                    )

                    val response = APIClientService(context).getUCGatewayService().postUserSession(
                        newUserSession
                    )

                    if (response.isSuccessful) {
                        userSession = newUserSession
                    } else {
                        throw Exception("Unable to create new session: Error(${response.code()})")
                    }
                }
            } catch (e: Exception) {
                Log.e("getUserSession", e)
                userSession = null
            }
        }
    }

    fun removeUserSession() {
        runBlocking {
            try {
                val userSessionToRemove = userSession
                if (userSessionToRemove != null) {
                    val deviceId = pushTokenService.getDeviceId()

                    val response = APIClientService(context).getUCGatewayService().deleteUserSession(
                        deviceId,
                        userSessionToRemove
                    )

                    if (!response.isSuccessful) {
                        throw Exception(
                            "Unable to delete session $deviceId: Error(${response.code()})"
                        )
                    }

                    userSession = null
                }
            } catch (e: Exception) {
                Log.e("removeUserSession", e)
            }
        }
    }

    private suspend fun getUserInfo(): UserInfo {
        Log.i("Fetching user info....")

        val response = APIClientService(context).getUCGatewayService().getUserInfo()

        if (response.code() < 200 || response.code() > 299) {
            throw Exception("Error fetching user info: " + response.message())
        }

        return response.body()!!
    }

    fun updateE911Accepted(accepted: Boolean) {
        e911Accepted = accepted

        val state: SharedPreferences = context.getSharedPreferences(
            UserInfo.STORE_NAME,
            Context.MODE_PRIVATE
        )
        state.edit(true) {
            putBoolean(UserInfo.E911_STATE, e911Accepted)
        }
    }
}
