package org.linphone.services

import android.content.Context
import com.google.gson.reflect.TypeToken
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.authentication.AuthStateManager
import org.linphone.core.Content
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Event
import org.linphone.models.CustomerLicence
import org.linphone.models.voicemail.VoicemailBoxWithMessages
import org.linphone.models.voicemail.VoicemailMessage
import org.linphone.models.voicemail.VoicemailRules
import org.linphone.models.voicemail.VoicemailTranscription
import org.linphone.utils.GsonUtils
import org.linphone.utils.Log

/**
 * The user's voicemail boxes and their first page of messages, ported from the web client's
 * voicemail-box.service.ts (see VoicemailRules).
 *
 * Refreshes when the user signs in, when the app comes to the foreground, on every SIP
 * message-waiting (MWI) notification, and after a save or delete - but only when the customer is
 * licensed for voicemail. The gateway has no realtime voicemail event, and MWI notifications sent
 * while the app is in the background (and often not registered) are missed.
 */
object VoicemailBoxService {
    private const val TAG = "[Voicemail]"
    private const val CACHE_STORE = "voicemail"
    private const val CACHE_KEY = "voicemailBoxes"

    // Cached data from an older version is discarded and requeried
    private const val CACHE_VERSION = 1

    private val context: Context get() = coreContext.context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val boxesSubject = BehaviorSubject.createDefault<List<VoicemailBoxWithMessages>>(
        emptyList()
    )
    private val isRefreshingSubject = BehaviorSubject.createDefault(false)
    private val licensedSubject = BehaviorSubject.createDefault(false)

    /** The latest known boxes, with their first page of messages. */
    val boxes: Observable<List<VoicemailBoxWithMessages>> = boxesSubject.hide()

    /** True while a refresh is in flight. */
    val isRefreshing: Observable<Boolean> = isRefreshingSubject.distinctUntilChanged()

    /** Unheard voicemails across all boxes, for the badges. */
    val newCount: Observable<Int> = boxes.map { VoicemailRules.newCount(it) }.distinctUntilChanged()

    /** Whether voicemail is shown at all: licensed, permitted, and with an enabled mailbox. */
    val isAvailable: Observable<Boolean> by lazy {
        Observable.combineLatest(
            licensedSubject,
            UserService.getInstance(context).user
                .map { it.hasPermission(VoicemailRules.PERMISSION) }
                .startWithItem(false),
            boxes
        ) { licensed, permitted, boxes -> VoicemailRules.isAvailable(licensed, permitted, boxes) }
            .distinctUntilChanged()
            .replay(1)
            .autoConnect()
    }

    private var userId: String? = null
    private var refreshJob: Job? = null
    private var userSubscription: Disposable? = null

    private val coreListener = object : CoreListenerStub() {
        override fun onNotifyReceived(
            core: Core,
            event: Event,
            notifiedEvent: String,
            body: Content?
        ) {
            // SIP voicemail (MWI) notification: the mailbox has changed
            if (body?.type == "application" && body.subtype == "simple-message-summary") {
                Log.i("$TAG Message-waiting notification, refreshing")
                refresh()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        coreContext.core.addListener(coreListener)

        userSubscription = AuthStateManager.getInstance(context).user
            .distinctUntilChanged { user -> user.id }
            .subscribe(
                { user ->
                    if (user.hasValidId()) {
                        onUserChanged(user.id)
                    } else {
                        onUserChanged(null)
                    }
                },
                { e -> Log.e(e, "$TAG User stream failed") }
            )
    }

    private fun onUserChanged(id: String?) {
        if (id == userId) return
        userId = id
        refreshJob?.cancel()
        licensedSubject.onNext(false)
        scope.launch { LicenceService.clear() }

        // Show the cached snapshot straight away, then replace it with live data
        boxesSubject.onNext(id?.let { readCache(it) } ?: emptyList())
        if (id != null) refresh()
    }

    /** Refreshes the boxes and messages now. */
    fun refresh() {
        if (userId == null) return
        refreshJob?.cancel()
        refreshJob = scope.launch { doRefresh() }
    }

    private suspend fun doRefresh() {
        isRefreshingSubject.onNext(true)
        try {
            // The gateway refuses voicemailbox for a tenant without the licence
            if (!isLicensed()) {
                Log.i("$TAG Not licensed for voicemail")
                boxesSubject.onNext(emptyList())
                return
            }
            val boxes = fetchAll()
            Log.i("$TAG Refreshed ${boxes.size} box(es), ${VoicemailRules.newCount(boxes)} new")
            boxesSubject.onNext(boxes)
            userId?.let { writeCache(it, boxes) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(e, "$TAG Failed to refresh voicemail boxes")
        } finally {
            isRefreshingSubject.onNext(false)
        }
    }

    private suspend fun isLicensed(): Boolean {
        // A failed fetch leaves the tab as it was, and is retried on the next refresh
        val licence = LicenceService.getLicence() ?: return licensedSubject.value == true
        return licence.hasFeature(CustomerLicence.VOICEMAIL_ENABLED).also {
            licensedSubject.onNext(
                it
            )
        }
    }

    private suspend fun fetchAll(): List<VoicemailBoxWithMessages> = withContext(Dispatchers.IO) {
        val response = api().getVoicemailBoxes()
        if (!response.isSuccessful) throw Exception("Voicemail boxes: HTTP ${response.code()}")

        // Null rather than [] when the user has no mailbox - not an error
        val boxes = response.body() ?: return@withContext emptyList()
        val previous = boxesSubject.value.orEmpty()

        boxes.map { box ->
            async {
                val before = previous.firstOrNull { it.box.id == box.id }
                try {
                    val page = api().getVoicemailMessages(box.id)
                    if (!page.isSuccessful) throw Exception("HTTP ${page.code()}")
                    val messages = page.body()?.messages.orEmpty()
                    VoicemailBoxWithMessages(
                        box,
                        VoicemailRules.withCachedTranscriptions(messages, before),
                        page.body()?.nextStartKey,
                        before?.collapsed ?: false,
                        before?.collapsedFolders ?: emptyList()
                    )
                } catch (e: Exception) {
                    // The box is still shown, without its messages
                    Log.e(e, "$TAG Failed to fetch messages for box ${box.id}")
                    VoicemailBoxWithMessages(
                        box,
                        emptyList(),
                        null,
                        before?.collapsed ?: false,
                        before?.collapsedFolders ?: emptyList()
                    )
                }
            }
        }.awaitAll()
    }

    /** The message's transcription: held already, or fetched and held. Null if there isn't one. */
    suspend fun getOrFetchTranscription(boxId: String, mediaId: String): VoicemailTranscription? {
        val message = find(boxId, mediaId) ?: return null
        message.transcription?.let { return it }
        return try {
            val response = api().getVoicemailTranscription(boxId, mediaId)
            if (!response.isSuccessful) throw Exception("HTTP ${response.code()}")
            val transcription = response.body() ?: VoicemailTranscription()
            update { VoicemailRules.withTranscription(it, boxId, mediaId, transcription) }
            transcription
        } catch (e: Exception) {
            Log.e(e, "$TAG Failed to fetch the transcription of $mediaId")
            null
        }
    }

    /** Downloads the message's audio to the cache, once. */
    suspend fun getAudio(boxId: String, mediaId: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "voicemail").apply { mkdirs() }
        dir.listFiles()?.firstOrNull { it.nameWithoutExtension == "voicemail-$mediaId" }?.let {
            return@withContext it
        }

        val response = api().getVoicemailAudio(boxId, mediaId)
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            throw VoicemailAudioException(response.code())
        }
        val extension = VoicemailRules.audioExtension(
            body.contentType()?.toString()?.lowercase(Locale.ROOT)
        )
        val file = File(dir, "voicemail-$mediaId.$extension")
        val partial = File(dir, "${file.name}.part")
        try {
            body.byteStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            partial.renameTo(file)
        } finally {
            partial.delete()
        }
        file
    }

    /** Moves the message to the saved folder. True if the server accepted it. */
    suspend fun save(boxId: String, mediaId: String): Boolean = try {
        val response = api().saveVoicemailMessage(boxId, mediaId)
        if (!response.isSuccessful) throw Exception("HTTP ${response.code()}")
        refresh()
        true
    } catch (e: Exception) {
        Log.e(e, "$TAG Failed to save $mediaId")
        false
    }

    /**
     * Deletes optimistically: the message leaves the list straight away, as the request takes a
     * couple of seconds. If it fails the message goes back where it was. True if the server
     * accepted it.
     */
    suspend fun deleteOptimistically(boxId: String, mediaId: String): Boolean {
        val box = boxesSubject.value.orEmpty().firstOrNull { it.box.id == boxId }
        val index = box?.messages?.indexOfFirst { it.mediaId == mediaId } ?: -1
        val removed = if (index >= 0) box!!.messages[index] else null
        if (removed != null) {
            update { boxes ->
                boxes.map {
                    if (it.box.id == boxId) {
                        VoicemailRules.withoutMessage(
                            it,
                            mediaId
                        )
                    } else {
                        it
                    }
                }
            }
        }

        return try {
            val response = api().deleteVoicemailMessage(boxId, mediaId)
            if (!response.isSuccessful) throw Exception("HTTP ${response.code()}")
            refresh()
            true
        } catch (e: Exception) {
            Log.e(e, "$TAG Failed to delete $mediaId")
            // A refresh may have landed meanwhile, so restore into the current list
            if (removed != null) {
                update { boxes ->
                    boxes.map {
                        if (it.box.id == boxId) {
                            VoicemailRules.withMessage(
                                it,
                                removed,
                                index
                            )
                        } else {
                            it
                        }
                    }
                }
            }
            false
        }
    }

    fun toggleBox(boxId: String) = update { VoicemailRules.toggledBox(it, boxId) }

    fun toggleFolder(boxId: String, folder: String) = update {
        VoicemailRules.toggledFolder(
            it,
            boxId,
            folder
        )
    }

    private fun find(boxId: String, mediaId: String): VoicemailMessage? =
        boxesSubject.value.orEmpty().firstOrNull { it.box.id == boxId }?.messages?.firstOrNull { it.mediaId == mediaId }

    @Synchronized
    private fun update(change: (List<VoicemailBoxWithMessages>) -> List<VoicemailBoxWithMessages>) {
        val boxes = change(boxesSubject.value.orEmpty())
        boxesSubject.onNext(boxes)
        userId?.let { writeCache(it, boxes) }
    }

    private fun api() = APIClientService(context).getUCGatewayService()

    // Cache: the user's boxes and collapsed state, shown at startup until live data arrives

    private data class Cache(
        val userId: String?,
        val version: Int,
        val data: List<VoicemailBoxWithMessages>?
    )

    private val gson by lazy { GsonUtils.create() }

    private fun prefs() = context.getSharedPreferences(CACHE_STORE, Context.MODE_PRIVATE)

    private fun writeCache(userId: String, boxes: List<VoicemailBoxWithMessages>) {
        try {
            prefs().edit().putString(CACHE_KEY, gson.toJson(Cache(userId, CACHE_VERSION, boxes))).apply()
        } catch (e: Exception) {
            Log.e(e, "$TAG Failed to cache voicemail boxes")
        }
    }

    private fun readCache(userId: String): List<VoicemailBoxWithMessages>? = try {
        val json = prefs().getString(CACHE_KEY, null)
        val cache: Cache? = json?.let { gson.fromJson(it, object : TypeToken<Cache>() {}.type) }
        when {
            cache == null -> null
            cache.userId != userId -> null
            cache.version < CACHE_VERSION -> null
            else -> cache.data
        }
    } catch (e: Exception) {
        Log.w("$TAG Failed to read the voicemail cache: $e")
        null
    }
}

/** The gateway refused the audio: 404 if it no longer exists, 402 if there's no credit to fetch it. */
class VoicemailAudioException(val code: Int) : Exception("Failed to fetch audio ($code)")
