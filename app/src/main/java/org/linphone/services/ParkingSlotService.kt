package org.linphone.services

import android.content.Context
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.authentication.AuthStateManager
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.models.AuthenticatedUser
import org.linphone.models.PbxFeatureCode
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.parking.ParkingSlot
import org.linphone.models.parking.ParkingSlotAction
import org.linphone.models.parking.ParkingSlotDefinition
import org.linphone.models.parking.ParkingSlotRules
import org.linphone.utils.Log

/**
 * The user's parking slots and their live state, ported from the web client's
 * parking-slot.service.ts (see ParkingSlotRules). Keeps a BLF subscription to every slot offered,
 * and parks or retrieves calls by transferring to or calling the slot's number.
 *
 * Actions and BLF run on the core's (main) thread; the slot lists can be emitted from any thread.
 */
object ParkingSlotService {
    // Not set in start(): the slot lists can be subscribed to (by the UI) before it runs
    private val context: Context get() = coreContext.context
    private var started = false

    // Slot numbers subscribed to for BLF
    private val tracked = mutableSetOf<String>()

    private val subscriptions = mutableListOf<Disposable>()

    private val connectedCall = BehaviorSubject.createDefault(false)

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            connectedCall.onNext(isOnConnectedCall(core))
            // TEMP: trace the identity the PBX sends on calls (remove once park identity is settled)
            Log.i(
                "[Parking][TEMP] ${call.callLog.callId} $state remote=${call.remoteAddress.asStringUriOnly()} " +
                    "display=${call.remoteAddress.displayName} " +
                    "PAI=${call.remoteParams?.getCustomHeader("P-Asserted-Identity")} " +
                    "RPID=${call.remoteParams?.getCustomHeader("Remote-Party-ID")} " +
                    "contact=${call.remoteContact}"
            )
        }

        override fun onLastCallEnded(core: Core) {
            connectedCall.onNext(false)
        }
    }

    private val definitions: Observable<List<ParkingSlotDefinition>> by lazy {
        val signedIn = AuthStateManager.getInstance(context).user
            .map { u -> u.id != AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER }
            .distinctUntilChanged()
        Observable.combineLatest(
            signedIn,
            // As a list of none or one, as Rx can't emit null (and Optional needs API 24)
            UserService.getInstance(context).user.map { listOf(it) }
                .startWithItem(emptyList()),
            FeatureCodeService.getInstance(context).featureCodes
        ) { isSignedIn, user, codes ->
            if (!isSignedIn) {
                emptyList()
            } else {
                val info = user.firstOrNull()
                val parkCode = codes[PbxFeatureCode.PARK_AND_RETRIEVE]
                ParkingSlotRules.definitions(
                    info,
                    parkCode,
                    context.getString(R.string.parking_personal_slot)
                ).also { definitions ->
                    if (info != null) {
                        val profile = info.clientProfileSettings
                        Log.i(
                            "[Parking] ${definitions.size} slot(s): park code ${parkCode ?: "none"}, " +
                                "${info.parkingSlotCollection.orEmpty().size} PBX slot(s), " +
                                "expose personal ${profile.exposePersonalParkingSlotsEnabled}, " +
                                "expose all ${profile.exposeAllParkingSlotsEnabled}"
                        )
                    }
                }
            }
        }
            .distinctUntilChanged()
            .replay(1)
            .autoConnect()
    }

    /** The slots offered to the user, in order, with their live state. */
    val slots: Observable<List<ParkingSlot>> by lazy {
        Observable.combineLatest(
            definitions,
            BlfService.keys,
            UserGroupService.getInstance(context).directoryContacts
        ) { definitions, keys, directories ->
            val countryCode = PhoneFormatterService.getInstance(context).getPbxCountryCode()
            definitions.map { definition ->
                ParkingSlotRules.slot(definition, keys[definition.number]) { number ->
                    ContactDirectoryRules.findContactAndDirectoryByPhone(
                        directories,
                        number,
                        countryCode
                    )?.let { (directory, contact) ->
                        ContactDirectoryRules.toContactMatch(directory, contact).displayName
                    }
                }
            }
        }
            .distinctUntilChanged()
            .replay(1)
            .autoConnect()
    }

    val hasSlots: Observable<Boolean> by lazy {
        definitions.map { it.isNotEmpty() }.distinctUntilChanged()
    }

    val occupiedCount: Observable<Int> by lazy {
        slots.map { ParkingSlotRules.occupiedCount(it) }.distinctUntilChanged()
    }

    /** The call parked in the user's personal slot, as a list of none or one. */
    val personalParkedCall: Observable<List<ParkingSlot>> by lazy {
        slots.map { listOfNotNull(ParkingSlotRules.personalParkedCall(it)) }
            .distinctUntilChanged()
    }

    /** Whether the user is on a connected call here, which can be parked. */
    val hasConnectedCall: Observable<Boolean> = connectedCall.distinctUntilChanged()

    fun start() {
        if (started) return
        started = true
        coreContext.core.addListener(coreListener)
        connectedCall.onNext(isOnConnectedCall(coreContext.core))

        subscriptions.add(
            definitions.subscribe(
                { definitions ->
                    val numbers = definitions.map { it.number }.toSet()
                    // BLF runs on the core's thread
                    coreContext.handler.post { reconcile(numbers) }
                },
                { e -> Log.e("[Parking] Slot definitions failed", e) }
            )
        )
    }

    /** Parks the current call in [slot], or retrieves the call parked there (see ParkingSlotRules.action). */
    fun activate(slot: ParkingSlot) {
        val core = coreContext.core
        when (ParkingSlotRules.action(slot, isOnConnectedCall(core))) {
            ParkingSlotAction.PARK -> park(slot.number)
            ParkingSlotAction.RETRIEVE -> retrieve(slot)
            ParkingSlotAction.HOLD_AND_RETRIEVE -> {
                Log.i("[Parking] Holding the current call to retrieve from ${slot.number}")
                core.currentCall?.pause()
                retrieve(slot)
            }
            ParkingSlotAction.NONE -> {}
        }
    }

    /**
     * Parks the current call in the personal slot of the user with [presenceId] (their extension).
     * Calls [onNoFeatureCode] (on the main thread) if the user has no park code.
     */
    fun parkAtExtension(presenceId: String, onNoFeatureCode: () -> Unit) {
        FeatureCodeService.getInstance(context).featureCodes
            .take(1)
            .subscribe(
                { codes ->
                    coreContext.handler.post {
                        val code = codes[PbxFeatureCode.PARK_AND_RETRIEVE]
                        if (code.isNullOrEmpty()) {
                            Log.w("[Parking] No park and retrieve feature code")
                            onNoFeatureCode()
                        } else {
                            park(code + presenceId)
                        }
                    }
                },
                { e -> Log.e("[Parking] Feature codes failed", e) }
            )
    }

    /** Parks the current call at [number] (park code + slot or extension). */
    fun park(number: String) {
        Log.i("[Parking] Parking the current call at $number")
        if (!coreContext.transferCallTo(number)) {
            Log.e("[Parking] Couldn't park the call at $number")
        }
    }

    private fun retrieve(slot: ParkingSlot) {
        Log.i("[Parking] Retrieving the call parked at ${slot.number}")
        coreContext.startCall(slot.number)
    }

    private fun isOnConnectedCall(core: Core): Boolean = when (core.currentCall?.state) {
        Call.State.Connected, Call.State.StreamsRunning, Call.State.Updating,
        Call.State.UpdatedByRemote -> true
        else -> false
    }

    private fun reconcile(numbers: Set<String>) {
        for (number in tracked.toSet()) {
            if (number !in numbers) {
                tracked.remove(number)
                BlfService.removeKey(number)
            }
        }
        for (number in numbers) {
            if (tracked.add(number)) BlfService.addKey(number)
        }
    }
}
