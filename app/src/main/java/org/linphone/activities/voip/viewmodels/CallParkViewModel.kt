package org.linphone.activities.voip.viewmodels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.activities.main.parking.data.ParkingSlotData
import org.linphone.models.parking.ParkingSlotRules
import org.linphone.services.ParkingSlotService
import org.linphone.utils.Event
import org.linphone.utils.Log

/**
 * The call screen's Park button, as the web client's call control Parking button: offered on a
 * connected call when the user has parking slots, it lists the slots that aren't occupied.
 */
class CallParkViewModel : ViewModel() {
    val canPark = MutableLiveData(false)

    // The slots to choose from; empty if all are occupied
    val chooseSlotEvent = MutableLiveData<Event<List<ParkingSlotData>>>()

    private var parkableSlots = emptyList<ParkingSlotData>()

    private val subscriptions = listOf<Disposable>(
        Observable.combineLatest(
            ParkingSlotService.hasSlots,
            ParkingSlotService.hasConnectedCall
        ) { hasSlots, onCall -> hasSlots && onCall }
            .subscribe(
                { canPark.postValue(it) },
                { e -> Log.e("[Call Park] Park availability failed", e) }
            ),
        ParkingSlotService.slots.subscribe(
            { slots ->
                coreContext.handler.post {
                    parkableSlots = ParkingSlotRules.parkable(slots).map {
                        ParkingSlotData(it, coreContext.context)
                    }
                }
            },
            { e -> Log.e("[Call Park] Slots failed", e) }
        )
    )

    fun chooseSlot() {
        chooseSlotEvent.value = Event(parkableSlots)
    }

    // Always parks, even if the slot has filled up since it was listed
    fun park(data: ParkingSlotData) {
        ParkingSlotService.park(data.slot.number)
    }

    override fun onCleared() {
        subscriptions.forEach { it.dispose() }
        super.onCleared()
    }
}
