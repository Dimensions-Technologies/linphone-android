package org.linphone.activities.main.viewmodels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.disposables.Disposable
import org.linphone.services.PullCallService
import org.linphone.utils.Event
import org.linphone.utils.Log

/** The banner offering to pull a call the user is on at another device to this one. */
class PullCallViewModel : ViewModel() {
    val showPullCall = MutableLiveData(false)

    val noFeatureCodeEvent = MutableLiveData<Event<Boolean>>()

    private val subscription: Disposable = PullCallService.callAtOtherDevice.subscribe(
        { show -> showPullCall.postValue(show) },
        { e -> Log.e("[Pull Call] Banner state failed", e) }
    )

    fun pullCall() {
        PullCallService.pullCall {
            noFeatureCodeEvent.value = Event(true)
        }
    }

    override fun onCleared() {
        subscription.dispose()
        super.onCleared()
    }
}
