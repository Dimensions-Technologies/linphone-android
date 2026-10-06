package org.linphone.services

import android.content.Context
import android.telecom.TelecomManager
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Address
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.realtime.ContactMatch
import org.linphone.models.realtime.RealtimeEventType
import org.linphone.services.realtime.RealtimeUserService
import org.linphone.telecom.TelecomHelper
import org.linphone.utils.Log
import org.linphone.utils.SipIdentity

/**
 * Matches live calls to contacts, ported from the web client (call-manager.service.ts "Contact
 * matching" and the Call model):
 * - the other party's number is looked up in the user's directory contacts, once per number the call
 *   has (it can change, e.g. a P-Asserted-Identity update after a transfer); a hit goes first
 * - contacts the server matches (callMatchEvent, e.g. from a CRM) are added after, unless their
 *   directory is one the user can't see or is excluded
 * - matches are never removed during a call, and are deduplicated by contact id
 * - the call is named after its first match: full name, else company
 * Unlike the web client, the directory lookup uses the PBX country to compare numbers in E.164 form
 * (the web client passes no country there, so only contacts stored in E.164 match).
 *
 * Runs on the core's (main) thread.
 */
object CallContactMatchService {
    // A server match can arrive before the call does; the web client waits this long for the call
    private const val PENDING_MATCH_TIMEOUT_MS = 5_000L

    // Matches are kept a while after the call ends, for the missed call notification
    private const val RELEASED_CALL_RETENTION_MS = 60_000L

    private class CallMatches {
        val matches: BehaviorSubject<List<ContactMatch>> = BehaviorSubject.createDefault(
            emptyList()
        )
        val lookedUpNumbers = mutableSetOf<String>()
        var number: String = ""
    }

    private lateinit var context: Context
    private var started = false

    // By SIP Call-ID, which is also the id the server uses in callMatchEvent
    private val calls = mutableMapOf<String, CallMatches>()
    private val pendingServerMatches = mutableMapOf<String, List<ContactMatch>>()

    private var subscriptions = mutableListOf<Disposable>()

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            val callId = call.callLog.callId ?: return
            when (state) {
                Call.State.Released -> {
                    coreContext.handler.postDelayed(
                        { calls.remove(callId) },
                        RELEASED_CALL_RETENTION_MS
                    )
                }
                Call.State.End, Call.State.Error -> {}
                else -> lookUp(call)
            }
        }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        this.context = context.applicationContext
        coreContext.core.addListener(coreListener)

        val realtime = RealtimeUserService.getInstance(this.context)
        subscriptions.add(
            realtime.subscribeForCurrentUser(RealtimeEventType.CallMatchEvent).subscribe(
                {},
                { e -> Log.e("[Contact Match] Subscription failed", e) }
            )
        )
        subscriptions.add(
            realtime.callMatchEvent.subscribe(
                { event ->
                    val data = event.data ?: return@subscribe
                    coreContext.handler.post { onServerMatches(data.callId, data.matches.orEmpty()) }
                },
                { e -> Log.e("[Contact Match] callMatchEvent failed", e) }
            )
        )

        // Directory contacts can load after a call starts (e.g. when the app is woken by a push), so
        // look up again any call that hasn't been matched yet
        subscriptions.add(
            UserGroupService.getInstance(this.context).directoryContacts.subscribe(
                { coreContext.handler.post { retryUnmatchedCalls() } },
                { e -> Log.e("[Contact Match] Directory contacts failed", e) }
            )
        )
    }

    /** The call's matches, updated as they arrive. */
    fun matches(call: Call): Observable<List<ContactMatch>> {
        val entry = entryFor(call) ?: return Observable.just(emptyList())
        lookUp(call)
        return entry.matches
    }

    /**
     * The name to show for the call, if contact matching gives one: "Voicemail" for the user's own
     * voicemail, else the first match's name. Null to fall back to the SIP or device contact name.
     */
    fun displayName(call: Call): String? {
        lookUp(call)
        if (isVoicemail(call)) return context.getString(R.string.contact_match_voicemail)
        return entryFor(call)?.matches?.value?.firstOrNull()?.displayName
    }

    /** As displayName, for a call that has ended (only its address is left, e.g. for a missed call). */
    fun displayName(address: Address): String? {
        val number = address.username ?: return null
        return calls.values.lastOrNull { it.number == number }?.matches?.value?.firstOrNull()?.displayName
    }

    // The web client names calls to the user's own number (their voicemail) "Voicemail"
    private fun isVoicemail(call: Call): Boolean {
        if (call.dir != Call.Dir.Outgoing) return false
        val presenceId = PhoneFormatterService.getInstance(context).currentUser?.presenceId
        return !presenceId.isNullOrEmpty() && phoneNumber(call) == presenceId
    }

    /**
     * The other party's number: from a P-Asserted-Identity the PBX sent (it follows transfers),
     * else the remote address. The web client reads the PAI the same way.
     */
    fun phoneNumber(call: Call): String {
        val paiNumber = SipIdentity.userPart(
            call.remoteParams?.getCustomHeader("P-Asserted-Identity")
        )
        return paiNumber ?: call.remoteAddress.username.orEmpty()
    }

    private fun entryFor(call: Call): CallMatches? {
        if (!started) return null
        val callId = call.callLog.callId ?: return null
        return calls.getOrPut(callId) {
            CallMatches().also { entry ->
                // Server matches that arrived before the call
                pendingServerMatches.remove(callId)?.let { entry.matches.onNext(it) }
            }
        }
    }

    private fun lookUp(call: Call) {
        val entry = entryFor(call) ?: return
        val number = phoneNumber(call)
        entry.number = number
        if (number.isEmpty() || !entry.lookedUpNumbers.add(number)) return

        val match = ContactDirectoryRules.findContactAndDirectoryByPhone(
            UserGroupService.getInstance(context).currentDirectoryContacts(),
            number,
            PhoneFormatterService.getInstance(context).getPbxCountryCode()
        )?.let { (directory, contact) -> ContactDirectoryRules.toContactMatch(directory, contact) }
        if (match == null) {
            // Not found yet; the directory contacts may not have loaded
            entry.lookedUpNumbers.remove(number)
            return
        }

        val current = entry.matches.value.orEmpty()
        if (current.none { it.contactId == match.contactId }) {
            Log.i("[Contact Match] Call ${call.callLog.callId} matches ${match.displayName}")
            update(call, listOf(match) + current)
        }
    }

    private fun retryUnmatchedCalls() {
        for (call in coreContext.core.calls) {
            if (entryFor(call)?.matches?.value.isNullOrEmpty()) lookUp(call)
        }
    }

    private fun onServerMatches(callId: String?, matches: List<ContactMatch>) {
        if (callId.isNullOrEmpty()) return
        val allowed = DirectoriesService.getInstance(context).filterDeniedMatches(matches)
        if (allowed.isEmpty()) return

        val call = coreContext.core.calls.firstOrNull { it.callLog.callId == callId }
        if (call == null) {
            pendingServerMatches[callId] = pendingServerMatches[callId].orEmpty() + allowed
            coreContext.handler.postDelayed(
                { pendingServerMatches.remove(callId) },
                PENDING_MATCH_TIMEOUT_MS
            )
            return
        }

        val current = entryFor(call)?.matches?.value.orEmpty()
        val existingIds = current.map { it.contactId }.toSet()
        val added = allowed.filter { it.contactId !in existingIds }.distinctBy { it.contactId }
        if (added.isNotEmpty()) {
            Log.i("[Contact Match] Server matched call $callId to ${added.size} contact(s)")
            update(call, current + added)
        }
    }

    private fun update(call: Call, matches: List<ContactMatch>) {
        entryFor(call)?.matches?.onNext(matches)
        refreshCallerName(call)
    }

    // Notifications and the system's call screen are built once per call state, so rebuild them
    private fun refreshCallerName(call: Call) {
        coreContext.notificationsManager.refreshCallNotification(call)

        if (TelecomHelper.exists()) {
            val name = displayName(call) ?: return
            TelecomHelper.get().connections
                .firstOrNull { it.callId == call.callLog.callId }
                ?.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED)
        }
    }
}
