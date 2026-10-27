package org.linphone.services

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.telecom.TelecomManager
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transform.CircleCropTransformation
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Address
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.models.UserInfo
import org.linphone.models.contact.CallAvatarRules
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.contact.ContactItemModel
import org.linphone.models.contact.VoicemailRules
import org.linphone.models.realtime.ContactMatch
import org.linphone.models.realtime.RealtimeEventType
import org.linphone.services.realtime.RealtimeUserService
import org.linphone.telecom.TelecomHelper
import org.linphone.utils.LinphoneUtils
import org.linphone.utils.Log
import org.linphone.utils.SipIdentity

/**
 * Matches live calls to contacts, ported from the web client (call-manager.service.ts "Contact
 * matching" and the Call model):
 * - the other party's number is looked up in the user's directory contacts, once per number the call
 *   has (it can change, e.g. a P-Asserted-Identity update after a transfer, or the PBX's CTI number
 *   arriving after the INVITE); a hit goes first
 * - contacts the server matches (callMatchEvent, e.g. from a CRM) are added after, unless their
 *   directory is one the user can't see or is excluded
 * - matches are deduplicated by contact id, and only removed when the call's other party changes
 *   (CallPartyRules, e.g. after an attended transfer): they're for the old party
 * - the call is named after its first match: full name, else company
 * - its picture is the single match's avatar, else the internal user's profile image (CallAvatarRules)
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

    private const val AVATAR_SIZE_PX = 256

    private class CallMatches {
        val matches: BehaviorSubject<List<ContactMatch>> = BehaviorSubject.createDefault(
            emptyList()
        )
        val lookedUpNumbers = mutableSetOf<String>()
        var number: String = ""

        // The picture for the call's notification, downloaded from avatarUrl
        var avatarUrl: String? = null
        var avatar: Bitmap? = null
        var avatarJob: Job? = null
    }

    private lateinit var context: Context
    private var started = false

    // By SIP Call-ID
    private val calls = mutableMapOf<String, CallMatches>()

    // By the id the server gives the call in callMatchEvent: the PBX call id, which the callEvent
    // links to the SIP Call-ID (CallRouteService.callForServerId)
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
                else -> {
                    lookUp(call)
                    applyPendingServerMatches()
                }
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

        // An internal caller's profile image needs the user list, which can also load after a call starts
        subscriptions.add(
            DirectoriesService.getInstance(this.context).users.subscribe(
                { coreContext.handler.post { coreContext.core.calls.forEach(::refreshAvatar) } },
                { e -> Log.e("[Contact Match] Users failed", e) }
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
     * voicemail, else the first match's name, else the other party's number when it isn't the call's
     * own remote address (see otherPartyNumber), else the CTI name or number while the SIP identity
     * names the party the call had before a transfer (CallRouteService.staleIdentityName). Null to
     * fall back to the SIP or device contact name.
     */
    fun displayName(call: Call): String? {
        lookUp(call)
        if (isVoicemail(call)) return context.getString(R.string.contact_match_voicemail)
        return entryFor(call)?.matches?.value?.firstOrNull()?.displayName
            ?: otherPartyNumber(call)?.let { formatNumber(it) }
            ?: CallRouteService.staleIdentityName(call)?.let { formatNumber(it) }
    }

    /**
     * The name to show for a call that displayName doesn't name and no device contact matches: its
     * remote address's display name, else its number, formatted when it's a number.
     */
    fun remoteName(call: Call): String = formatNumber(LinphoneUtils.getDisplayName(call.remoteAddress))

    /** The other party's number (see phoneNumber), formatted for display; null if it has none. */
    fun displayNumber(call: Call): String? = phoneNumber(call).takeIf { it.isNotEmpty() }?.let { formatNumber(it) }

    // See SipIdentity.otherPartyNumber: its name comes from contact matching on the number. None
    // while the asserted number is stale (CallRouteService.paiNumber).
    private fun otherPartyNumber(call: Call): String? = SipIdentity.otherPartyNumber(
        call.remoteParams?.getCustomHeader("P-Asserted-Identity"),
        call.remoteAddress.username
    )?.takeIf { CallRouteService.paiNumber(call) != null }

    /**
     * The internal user the call is with, by extension, for their profile image. None for the user's
     * own voicemail.
     */
    fun otherUser(call: Call): UserInfo? {
        if (!started || isVoicemail(call)) return null
        return CallAvatarRules.findUser(
            DirectoriesService.getInstance(context).currentUsers(),
            phoneNumber(call)
        )
    }

    /** A match's full contact (e.g. for its avatar), if its directory can be read. */
    suspend fun matchedContact(match: ContactMatch): ContactItemModel? {
        val directoryId = match.directoryId ?: return null
        val contactId = match.contactId ?: return null
        return DirectoriesService.getInstance(context).getContact(directoryId, contactId)
    }

    /** The call's picture, round, for its notification: null until downloaded, or if it has none. */
    fun avatar(call: Call): Bitmap? = entryFor(call)?.avatar

    // Not context, which is only set once started
    private fun formatNumber(number: String) =
        PhoneFormatterService.getInstance(coreContext.context).formatDisplayNumber(number)

    /** As displayName, for a call that has ended (only its address is left, e.g. for a missed call). */
    fun displayName(address: Address): String? {
        val number = address.username ?: return null
        return calls.values.lastOrNull { it.number == number }?.matches?.value?.firstOrNull()?.displayName
    }

    /** Whether the call is to the user's own voicemail, which is named "Voicemail" (VoicemailRules). */
    fun isVoicemail(call: Call): Boolean {
        if (!started) return false
        return VoicemailRules.isOwnVoicemailCall(
            call.dir == Call.Dir.Outgoing,
            phoneNumber(call),
            PhoneFormatterService.getInstance(context).currentUser?.presenceId
        )
    }

    /**
     * The other party's number, as the web client's: from a P-Asserted-Identity the PBX sent (it
     * follows transfers), else the call's CTI number, else the remote address (see
     * CallRouteRules.phoneNumber).
     */
    fun phoneNumber(call: Call): String = CallRouteService.phoneNumber(call)

    /** Looks the call up again, e.g. once the PBX has sent its CTI number. */
    fun refresh(call: Call) = lookUp(call)

    /**
     * Drops the call's matches, as the web client's Call.update does when its other party changes
     * (CallPartyRules, e.g. after an attended transfer), then looks it up again for the new party.
     */
    fun otherPartyChanged(call: Call) {
        val entry = entryFor(call) ?: return
        entry.lookedUpNumbers.clear()
        update(call, emptyList())
        lookUp(call)
    }

    private fun entryFor(call: Call): CallMatches? {
        if (!started) return null
        val callId = call.callLog.callId ?: return null
        return calls.getOrPut(callId) { CallMatches() }
    }

    private fun lookUp(call: Call) {
        val entry = entryFor(call) ?: return
        val number = phoneNumber(call)
        val changed = entry.number.isNotEmpty() && entry.number != number
        entry.number = number
        // The PBX said who the other party is now: renamed even if they're no contact
        if (changed) {
            refreshCallerName(call)
            refreshAvatar(call)
        } else if (entry.avatarJob == null) {
            refreshAvatar(call)
        }
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

    // The filter completes by itself once the directory list has loaded, so isn't disposed
    @SuppressLint("CheckResult")
    private fun onServerMatches(callId: String?, matches: List<ContactMatch>) {
        if (callId.isNullOrEmpty()) return
        val directories = DirectoriesService.getInstance(context)
        // The directory list can still be loading when a call comes in soon after the app starts;
        // the matches are filtered once it has loaded
        if (directories.contactDirectoriesSubject.value == null) {
            Log.i("[Contact Match] Call $callId: waiting for the directories to filter server matches")
        }
        directories.filterDeniedMatches(matches).subscribe(
            { allowed ->
                Log.i(
                    "[Contact Match] Call $callId: server sent ${matches.size} match(es), ${allowed.size} allowed"
                )
                coreContext.handler.post { applyServerMatches(callId, allowed) }
            },
            { e -> Log.e("[Contact Match] Filtering server matches failed", e) }
        )
    }

    private fun applyServerMatches(callId: String, allowed: List<ContactMatch>) {
        if (allowed.isEmpty()) return

        val call = callForServerId(callId)
        if (call == null) {
            // The call, or the callEvent that links its PBX call id to it, hasn't arrived yet
            Log.i("[Contact Match] Call $callId: no call yet for the server matches, keeping them")
            pendingServerMatches[callId] = pendingServerMatches[callId].orEmpty() + allowed
            coreContext.handler.postDelayed(
                { pendingServerMatches.remove(callId) },
                PENDING_MATCH_TIMEOUT_MS
            )
            return
        }
        addServerMatches(call, callId, allowed)
    }

    /** Applies server matches whose call has arrived since, or has since been linked by a callEvent. */
    fun applyPendingServerMatches() {
        if (!started) return
        for (callId in pendingServerMatches.keys.toList()) {
            val call = callForServerId(callId) ?: continue
            pendingServerMatches.remove(callId)?.let { addServerMatches(call, callId, it) }
        }
    }

    private fun callForServerId(callId: String): Call? = CallRouteService.callForServerId(callId)

    private fun addServerMatches(call: Call, callId: String, allowed: List<ContactMatch>) {
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
        refreshAvatar(call)
    }

    // Downloads the call's picture for its notification (see CallAvatarRules), then rebuilds it
    private fun refreshAvatar(call: Call) {
        val entry = entryFor(call) ?: return
        entry.avatarJob?.cancel()
        entry.avatarJob = coreContext.coroutineScope.launch {
            // The user's own voicemail has no picture, whatever contact (the user themselves) it matches
            val contact = if (isVoicemail(call)) {
                null
            } else {
                entry.matches.value.orEmpty().singleOrNull()?.let { matchedContact(it) }
            }
            val url = CallAvatarRules.avatarUrl(contact, otherUser(call))
            if (url == entry.avatarUrl) return@launch
            // Only kept once downloaded, so a refresh that cancels the download tries again
            val avatar = url?.let { loadAvatar(it) }
            entry.avatarUrl = url
            entry.avatar = avatar
            Log.i(
                "[Contact Match] Call ${call.callLog.callId} picture ${if (entry.avatar != null) "loaded" else "cleared"}"
            )
            refreshCallerName(call)
        }
    }

    private suspend fun loadAvatar(url: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(AVATAR_SIZE_PX)
            .transformations(CircleCropTransformation())
            // Notifications need a software bitmap
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        if (result !is SuccessResult) {
            Log.w("[Contact Match] Couldn't load call picture $url")
            return null
        }
        return result.drawable.toBitmap()
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
