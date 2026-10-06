package org.linphone.activities.main.viewmodels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.disposables.Disposable
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.activities.main.parking.data.ParkingSlotData
import org.linphone.models.parking.ParkingSlot
import org.linphone.services.ParkingSlotService
import org.linphone.utils.Log

/**
 * The banner saying a call is parked on the user's extension (their personal slot), with a link to
 * pick it up, as the web client's logged-in view shows.
 */
class ParkedCallViewModel : ViewModel() {
    val showParkedCall = MutableLiveData(false)
    val text = MutableLiveData("")

    private var parked: ParkingSlot? = null

    private val subscription: Disposable = ParkingSlotService.personalParkedCall.subscribe(
        { slots ->
            coreContext.handler.post {
                parked = slots.firstOrNull()
                val call = parked
                showParkedCall.value = call != null
                if (call != null) {
                    val context = coreContext.context
                    text.value = context.getString(
                        R.string.parking_banner_text,
                        ParkingSlotData.callerDescription(call, context)
                    )
                }
            }
        },
        { e -> Log.e("[Parking] Parked call banner failed", e) }
    )

    fun pickUp() {
        parked?.let { ParkingSlotService.activate(it) }
    }

    override fun onCleared() {
        subscription.dispose()
        super.onCleared()
    }
}
