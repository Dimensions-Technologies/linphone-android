package org.linphone.activities.main.viewmodels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.disposables.Disposable
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.authentication.DimensionsAccountsManager
import org.linphone.models.SoftphoneDeviceRules
import org.linphone.utils.Log

/**
 * The banner saying the user has no phone device set up, so can't make or receive calls, as the web
 * client's logged-in view shows. Dismissing it hides it for good, as on the web client.
 */
class NoDeviceViewModel : ViewModel() {
    val showNoDevice = MutableLiveData(false)

    private val subscription: Disposable =
        DimensionsAccountsManager.getInstance(coreContext.context).devicesSubject.subscribe(
            { devices ->
                coreContext.handler.post {
                    showNoDevice.value =
                        SoftphoneDeviceRules.noDeviceConfigured(devices.userDevices) &&
                        !corePreferences.noDeviceWarningDismissed
                }
            },
            { e -> Log.e("[Devices] No device banner failed", e) }
        )

    fun dismiss() {
        corePreferences.noDeviceWarningDismissed = true
        showNoDevice.value = false
    }

    override fun onCleared() {
        subscription.dispose()
        super.onCleared()
    }
}
