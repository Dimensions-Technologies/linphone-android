package org.linphone.services

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.reflect.TypeToken
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.authentication.AuthStateManager
import org.linphone.models.voicemail.VoicemailCallLink
import org.linphone.models.voicemail.VoicemailCallLinks
import org.linphone.utils.GsonUtils
import org.linphone.utils.Log

/**
 * Remembers the call each voicemail was left on, for the user, while both are loaded, and keeps it
 * until the voicemail is deleted (WI 37000). A voicemail can then still open its call's details
 * after the call has aged out of the call history.
 */
object VoicemailCallLinkService {
    private const val TAG = "[Voicemail Links]"
    private const val STORE = "voicemail"
    private const val KEY = "voicemailCallLinks"
    private const val VERSION = 1

    private val context: Context get() = coreContext.context
    private var started = false
    private var userId: String? = null
    private var stored: Map<String, VoicemailCallLink> = emptyMap()
    private val subscriptions = mutableListOf<Disposable>()

    private val linksSubject = BehaviorSubject.createDefault<Map<String, String>>(emptyMap())

    /** Each remembered voicemail's media id to the document id of its call. */
    val links: Observable<Map<String, String>> = linksSubject.distinctUntilChanged()

    fun start() {
        if (started) return
        started = true

        subscriptions += AuthStateManager.getInstance(context).user
            .distinctUntilChanged { user -> user.id }
            .subscribe(
                { user -> onUserChanged(if (user.hasValidId()) user.id else null) },
                { e -> Log.e(e, "$TAG User stream failed") }
            )

        subscriptions += Observable.combineLatest(
            VoicemailBoxService.boxes,
            CallHistoryService.getInstance(context).history
        ) { boxes, history -> boxes to history }
            .subscribe(
                { (boxes, history) ->
                    try {
                        val countryCode = PhoneFormatterService.getInstance(context).getPbxCountryCode()
                        remember { VoicemailCallLinks.updated(it, boxes, VoicemailCallLinks.match(boxes, history, countryCode)) }
                    } catch (e: Exception) {
                        Log.e(e, "$TAG Failed to link voicemails to calls")
                    }
                },
                { e -> Log.e(e, "$TAG Voicemail and history stream failed") }
            )
    }

    /** Forgets the voicemail's call, once the voicemail is deleted. */
    fun forget(mediaId: String) = remember { it - mediaId }

    @Synchronized
    private fun onUserChanged(id: String?) {
        if (id == userId) return
        userId = id
        stored = id?.let { read(it) } ?: emptyMap()
        linksSubject.onNext(stored.mapValues { it.value.documentId })
    }

    @Synchronized
    private fun remember(change: (Map<String, VoicemailCallLink>) -> Map<String, VoicemailCallLink>) {
        val user = userId ?: return
        val next = change(stored)
        if (next == stored) return
        stored = next
        linksSubject.onNext(next.mapValues { it.value.documentId })
        write(user, next)
    }

    // Kept: R8 otherwise strips the type of links, and Gson reads them back as maps
    @Keep
    private data class Store(
        val userId: String?,
        val version: Int,
        val links: Map<String, VoicemailCallLink>?
    )

    private val gson by lazy { GsonUtils.create() }

    private fun prefs() = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun write(userId: String, links: Map<String, VoicemailCallLink>) {
        try {
            prefs().edit().putString(KEY, gson.toJson(Store(userId, VERSION, links))).apply()
        } catch (e: Exception) {
            Log.e(e, "$TAG Failed to store the links")
        }
    }

    private fun read(userId: String): Map<String, VoicemailCallLink>? = try {
        val json = prefs().getString(KEY, null)
        val store: Store? = json?.let { gson.fromJson(it, object : TypeToken<Store>() {}.type) }
        when {
            store == null -> null
            store.userId != userId -> null
            store.version < VERSION -> null
            else -> store.links?.filterValues { it.boxId.isNotEmpty() && it.documentId.isNotEmpty() }
        }
    } catch (e: Exception) {
        Log.w("$TAG Failed to read the links: $e")
        null
    }
}
