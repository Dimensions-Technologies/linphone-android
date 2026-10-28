package org.linphone.services

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.authentication.DimensionsAccountsManager
import org.linphone.models.UserDevice
import org.linphone.models.UserDeviceSettings
import org.linphone.utils.Log

/** The call waiting setting, as read from the app's own device. Saving it sets all the user's devices. */
object CallWaitingService {
    /** [available] is false when the user has no device to set it on. */
    data class State(val available: Boolean, val enabled: Boolean)

    // Ticks when the setting is saved, as the device list itself is only fetched at login
    private val settingChanged = BehaviorSubject.createDefault(Unit)

    private val devicesSubject
        get() = DimensionsAccountsManager.getInstance(coreContext.context).devicesSubject

    private val primaryDevice: UserDevice?
        get() = CallWaitingRules.primaryDevice(devicesSubject.value?.userDevices)

    val state: Observable<State>
        get() = Observable.combineLatest(devicesSubject, settingChanged) { devices, _ ->
            val device = CallWaitingRules.primaryDevice(devices.userDevices)
            State(device != null, CallWaitingRules.isEnabled(device))
        }

    fun shouldReject(otherCallsInProgress: Int): Boolean =
        CallWaitingRules.shouldReject(otherCallsInProgress, primaryDevice)

    /** Saves the setting, and returns whether it was saved. The state is re-sent either way. */
    suspend fun setEnabled(enabled: Boolean): Boolean {
        try {
            val response = APIClientService(coreContext.context).getUCGatewayService()
                .putUserDeviceSettings(UserDeviceSettings(enabled))
            if (!response.isSuccessful) throw Exception("HTTP ${response.code()}")
            primaryDevice?.callWaitingEnabled = enabled
            Log.i("[Call Waiting] Set to $enabled")
            return true
        } catch (e: Exception) {
            Log.e(e, "[Call Waiting] Failed to save the setting")
            return false
        } finally {
            settingChanged.onNext(Unit)
        }
    }
}
