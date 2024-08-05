package org.linphone.services

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.core.Observable
import java.util.concurrent.atomic.AtomicReference
import org.linphone.authentication.AuthStateManager
import org.linphone.models.AuthenticatedUser

class UserService public constructor(context: Context) {

    companion object {
        private const val TAG: String = "UserService"

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

    val user: Observable<AuthenticatedUser>

    init {
        Log.i(TAG, "Created UserService")

        val asm = AuthStateManager.getInstance(context)
        user = asm.user
    }
}
