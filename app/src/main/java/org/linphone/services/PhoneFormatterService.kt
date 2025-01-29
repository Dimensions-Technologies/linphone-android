package org.linphone.services

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.atomic.AtomicReference
import org.linphone.authentication.AuthStateManager

class PhoneFormatterService(val context: Context) : DefaultLifecycleObserver {
    private val authStateManager = AuthStateManager.getInstance(context)
    private val destroy = PublishSubject.create<Unit>()

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)

        destroy.onNext(Unit)
        destroy.onComplete()
    }

    companion object {
        private const val TAG: String = "PhoneFormatterService"

        private val instance: AtomicReference<PhoneFormatterService> =
            AtomicReference<PhoneFormatterService>()

        fun getInstance(context: Context): PhoneFormatterService {
            var svc = instance.get()
            if (svc == null) {
                svc = PhoneFormatterService(context.applicationContext)
                instance.set(svc)
            }
            return svc
        }
    }

    fun getSearchNumber(input: String): String {
        // TODO #25806 - Remove tolldigit if any
        return input
    }
}
