package org.linphone.activities.main.parking.viewmodels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.disposables.Disposable
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.activities.main.parking.data.ParkingSlotData
import org.linphone.services.ParkingSlotService
import org.linphone.utils.Log

/** The Parking tab: every slot offered to the user, with its state. */
class ParkingSlotsViewModel : ViewModel() {
    val slots = MutableLiveData<List<ParkingSlotData>>(emptyList())

    private val subscription: Disposable = ParkingSlotService.slots.subscribe(
        { list -> slots.postValue(list.map { ParkingSlotData(it, coreContext.context) }) },
        { e -> Log.e("[Parking] Slots failed", e) }
    )

    // Parks the current call there, or picks up the call parked there
    fun activate(data: ParkingSlotData) {
        ParkingSlotService.activate(data.slot)
    }

    override fun onCleared() {
        subscription.dispose()
        super.onCleared()
    }
}
