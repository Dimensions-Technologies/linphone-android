package org.linphone.services

import android.content.Context
import io.reactivex.rxjava3.core.Observable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.rx3.rxSingle
import org.linphone.authentication.AuthStateManager
import org.linphone.authentication.AuthorizationServiceManager
import org.linphone.environment.DimensionsEnvironmentService
import org.linphone.interfaces.CTGatewayService
import org.linphone.models.AuthenticatedUser
import org.linphone.models.UserInfo
import org.linphone.utils.Log

class UserService public constructor(context: Context) {

    companion object {
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

    val user: Observable<UserInfo>

    private val ucGatewayService: CTGatewayService

    init {
        Log.i("Created UserService")

        val asm = AuthStateManager.getInstance(context)
        val apiClientService = APIClientService()
        val dimensionsEnvironment = DimensionsEnvironmentService.getInstance(context).getCurrentEnvironment()

        ucGatewayService = apiClientService.getUCGatewayService(
            context,
            dimensionsEnvironment!!.gatewayApiUri,
            AuthorizationServiceManager.getInstance(context).getAuthorizationServiceInstance(),
            asm
        )

        user = asm.user
            .filter { u -> u.id != null && u.id != AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER }
            .doOnNext { Log.i("Fetching user info....") }
            .switchMapSingle {
                rxSingle { getUserInfo() }
            }
            .replay(1)
            .autoConnect()
    }

    private suspend fun getUserInfo(): UserInfo {
        val response = ucGatewayService.getUserInfo()

        if (response.code() < 200 || response.code() > 299) {
            throw Exception("Error fetching user info: " + response.message())
        }

        return response.body()!!
    }
}
