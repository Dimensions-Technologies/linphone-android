package org.linphone.services

import ReportResult
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Base64
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.gson.reflect.TypeToken
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.rx3.rxSingle
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.linphone.authentication.AuthStateManager
import org.linphone.models.AuthenticatedUser
import org.linphone.models.callhistory.CallHistoryCache
import org.linphone.models.callhistory.CallHistoryContactMatcher
import org.linphone.models.callhistory.CallHistoryItem
import org.linphone.models.callhistory.CallHistoryItemViewModel
import org.linphone.models.callhistory.ReportRequest
import org.linphone.models.callhistory.ReportStates
import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.contact.ContactItemModel
import org.linphone.models.realtime.RealtimeEventType
import org.linphone.services.realtime.RealtimeUserService
import org.linphone.utils.CallHistoryDatabaseHelper
import org.linphone.utils.DateUtils
import org.linphone.utils.GsonUtils
import org.linphone.utils.Log
import org.linphone.utils.Optional
import org.threeten.bp.LocalDateTime
import org.threeten.bp.ZoneId
import org.threeten.bp.ZoneOffset
import org.threeten.bp.ZonedDateTime
import org.threeten.bp.format.DateTimeFormatter
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class CallHistoryService(val context: Context) : DefaultLifecycleObserver {
    private val authStateManager = AuthStateManager.getInstance(context)
    private val realtimeUserService = RealtimeUserService.getInstance(context)

    private val destroy = PublishSubject.create<Unit>()

    private val callHistorySubject = BehaviorSubject.createDefault<List<CallHistoryItem>>(listOf())
    val history: Observable<List<CallHistoryItem>> = callHistorySubject.hide()

    /** Each time a value is emitted, a new report request will be submitted.  */
    private val newReportRequest: PublishSubject<Unit> = PublishSubject.create()

    /** Each time a value is emitted and new attempt will be made to query the current report result. */
    private val queryStatusSubject = BehaviorSubject.createDefault(0)
    val queryStatus = queryStatusSubject.map { x -> x }
        .replay(1)
        .autoConnect()

    private var statusQueryAttempts: Int = 0

    private val missedCallTimestampSubject = BehaviorSubject.create<ZonedDateTime>()
    private val missedCallTimestamp: Observable<ZonedDateTime> = missedCallTimestampSubject.hide()

    private val authSubscription: Disposable = authStateManager.user
        .filter { u -> u.id != AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER }
        .distinctUntilChanged { user -> user.id }
        .takeUntil(destroy)
        .subscribe {
            CoroutineScope(Dispatchers.IO).launch {
                getMissedCallTimestamp()
            }
        }

    private val historyRequest: Observable<ReportRequest> = newReportRequest
        .doOnNext { statusQueryAttempts = 0 }
        .switchMap {
            val arr = callHistorySubject.value ?: emptyList()
            val maxStartTime = arr.maxOfOrNull {
                if (it.startTime == null) 0 else it.startTime.toInstant().toEpochMilli()
            } ?: 0L

            val fromDate = if (maxStartTime > 0) {
                val dateFormat = SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    Locale.getDefault()
                )
                dateFormat.timeZone = TimeZone.getTimeZone("UTC")
                dateFormat.format(Date(maxStartTime))
            } else {
                null
            }

            rxSingle {
                try {
                    withContext(Dispatchers.IO) {
                        APIClientService(context)
                            .getUCGatewayService()
                            .postReportRequest(
                                mapOf(
                                    "fromDate" to fromDate,
                                    "timeZoneId" to TimeZone.getDefault().id
                                )
                            )
                    }
                } catch (e: Throwable) {
                    Log.e(e, "historyRequest")
                    ReportRequest(ReportStates.Failed.value, "") // Provide a fallback ReportResult
                }
            }.toObservable()
        }
        .share()
        .replay(1)
        .autoConnect()

    private val reportQuery: Observable<ReportResult> = Observable.combineLatest(
        queryStatus,
        historyRequest
    ) { _, request -> request }
        .switchMap { request -> backoffQuery(request) }
        .doOnNext { r ->
            statusQueryAttempts++
            if (!ReportStates.isDone(r.status)) queueNextQuery(r.status)
        }
        .share()
        .replay(1)
        .autoConnect()

    val appendHistoryObservable = reportQuery
        .filter { r -> ReportStates.isDone(r.status) }
        .map { r -> r.data ?: listOf() }
        .doOnNext { data -> Log.d("History report returned ${data.size} items") }
        .subscribe { data -> appendToHistory(data) }

    val missedCallCount: Observable<Int> = Observable.combineLatest(
        history,
        missedCallTimestamp,
        { history, timestamp ->
            try {
                history.filter {
                    it.missedCall && it.startTime != null && it.startTime > timestamp
                }.size
            } catch (e: Exception) {
                Log.e(e, "missedCallCount")
                0
            }
        }
    )

    // Contacts found by searching the gateway for numbers the downloaded directory contacts don't
    // have (only the first 100 of each directory are downloaded), by number in E.164 form. Guarded
    // by serverSearchLock, as the history, the contacts and the searches each emit on their own thread.
    private val serverSearchLock = Any()
    private val serverFoundContacts = mutableMapOf<String, Pair<ContactDirectoryModel, ContactItemModel>>()

    // The directory contacts the numbers were searched against: when they reload, every number is
    // searched for again. Null after a user change, so a search still running is dropped.
    private var serverSearchDirectories: List<Pair<ContactDirectoryModel, List<ContactItemModel>>>? = null
    private val serverSearchedNumbers = mutableSetOf<String>()

    // Bumped when serverFoundContacts changes, so the history is named again
    private val serverFoundChangedSubject = BehaviorSubject.createDefault(0)
    private val serverFoundChanged = serverFoundChangedSubject.toSerialized()
    private val serverSearchCount = AtomicInteger(0)
    private val serverSearchLimit = Semaphore(MAX_SERVER_SEARCHES)
    private val serverSearchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // External calls named after the matching directory contact, as on the web client: one of the
    // downloaded contacts, else one found by searching the gateway
    private val enrichedHistory: Observable<List<CallHistoryItem>> = Observable.combineLatest(
        history,
        Observable.defer { UserGroupService.getInstance(context).directoryContacts },
        serverFoundChangedSubject
    ) { items, directories, _ ->
        // Matching must never take the history down with it
        try {
            val countryCode = PhoneFormatterService.getInstance(context).getPbxCountryCode()
            val result = CallHistoryContactMatcher.enrich(items, directories, countryCode)
            searchServerForUnmatched(result, directories, countryCode)
            val found = synchronized(serverSearchLock) { serverFoundContacts.values.toList() }
            if (found.isEmpty()) {
                result
            } else {
                CallHistoryContactMatcher.enrich(
                    result,
                    found.map { (directory, contact) -> directory to listOf(contact) },
                    countryCode
                )
            }
        } catch (e: Exception) {
            Log.e(e, "$TAG: failed to match call history to contacts")
            items
        }
    }

    val formattedHistory: Observable<List<CallHistoryItemViewModel>> = formatHistory(
        enrichedHistory,
        DateUtils.todaysDate,
        ::CallHistoryItemViewModel
    )

    val historyMessage: Observable<String> = Observable.combineLatest(
        reportQuery,
        callHistorySubject
    ) { query, history ->
        when {
            query.status == ReportStates.Failed.value -> "Error loading call history"
            query.status == ReportStates.Timeout.value -> "Call history query timed out"
            query.status == ReportStates.Complete.value && history.isEmpty() -> "No calls"
            query.status == ReportStates.Complete.value && history.isNotEmpty() -> ""
            else -> "Loading call history..."
        }
    }
        .startWithItem("Loading call history...")

    val selectedCallSessionIdSubject: BehaviorSubject<String> = BehaviorSubject.createDefault("")

    val selectedCallSessionId = selectedCallSessionIdSubject.map { x -> x }

    val currentCallHistoryItemView: Observable<Optional<CallHistoryItemViewModel>> = Observable.combineLatest(
        selectedCallSessionId,
        formattedHistory
    ) { selectedCallId: String?, callHistory: List<CallHistoryItemViewModel> ->
        Optional.ofNullable(
            callHistory.find {
                it.callId == selectedCallId
            }
        )
    }
        .share()
        .replay(1)
        .autoConnect()

    companion object {
        private const val TAG: String = "CallHistoryService"

        // Gateway searches for unmatched numbers running at once
        private const val MAX_SERVER_SEARCHES = 4

        // 2: items carry cli (voicemails are matched to calls by it)
        private const val CACHE_VERSION = 2

        private val instance: AtomicReference<CallHistoryService> =
            AtomicReference<CallHistoryService>()

        fun getInstance(context: Context): CallHistoryService {
            var svc = instance.get()
            if (svc == null) {
                svc = CallHistoryService(context.applicationContext)
                instance.set(svc)
            }
            return svc
        }

        /**
         * Formats the history against today's date, replaying the latest list to new subscribers.
         * An item that fails to format is skipped, so one bad record can't error the stream.
         */
        internal fun <T : Any> formatHistory(
            history: Observable<List<CallHistoryItem>>,
            todaysDate: Observable<LocalDateTime>,
            format: (CallHistoryItem, LocalDateTime) -> T
        ): Observable<List<T>> = Observable.combineLatest(
            history,
            todaysDate,
            { items, date -> transformData(items, date, format) }
        )
            .replay(1)
            .autoConnect()

        private fun <T : Any> transformData(
            callHistoryData: List<CallHistoryItem>,
            localDateTime: LocalDateTime,
            format: (CallHistoryItem, LocalDateTime) -> T
        ): List<T> {
            val start = Date()
            val formatted = callHistoryData.mapNotNull { call ->
                try {
                    format(call, localDateTime)
                } catch (e: Exception) {
                    Log.e(e, "$TAG: failed to format call history item ${call.documentId}")
                    null
                }
            }
            val end = Date()
            val dt = end.time - start.time
            Log.i("$TAG: formatting took ${dt}ms")
            return formatted
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)

        destroy.onNext(Unit)
        destroy.onComplete()

        authSubscription.dispose()

        runBlocking {
            authStateManager.getUser().id?.let {
                realtimeUserService.removeSubscription(
                    RealtimeEventType.CallHistoryEvent,
                    it,
                    null
                )
            }
        }
    }

    init {
        Log.d("Created CallHistoryService")

        val sub = authStateManager.user
            .distinctUntilChanged { user -> user.id }
            .takeUntil(destroy)
            .subscribe { user ->
                Log.d("User changed ($user.id) - update history from cache.")
                clearServerSearches()
                // Replace history list
                val list = if (user.hasValidId()) listOf() else getCachedCallHistory(user.id)
                callHistorySubject.onNext(list)

                // If we have a valid user, queue a new report query
                if (user.hasValidId()) {
                    newReportRequest.onNext(Unit)
                }
            }

        realtimeUserService.subscribeForCurrentUser(RealtimeEventType.CallHistoryEvent).subscribe()

        realtimeUserService.callHistoryEvent.observeForever { event ->
            try {
                Log.d("${RealtimeEventType.CallHistoryEvent.eventName} | $event")

                CoroutineScope(Dispatchers.IO).launch {
                    getMissedCallTimestamp()

                    newReportRequest.onNext(Unit)
                }
            } catch (e: Exception) {
                Log.e(e, RealtimeEventType.CallHistoryEvent.eventName)
            }
        }
    }

    /**
     * Searches the gateway's directories (CallContactMatchService.findDirectoryContactOnServer,
     * shared with live calls) for the numbers of the external calls the downloaded contacts left
     * unmatched, as the web client's enrichWithContactNames falls back to
     * findRemoteContactMatchByPhone. Only while some directory may have more contacts than were
     * downloaded; each number once until the directory contacts reload.
     */
    private fun searchServerForUnmatched(
        items: List<CallHistoryItem>,
        directories: List<Pair<ContactDirectoryModel, List<ContactItemModel>>>,
        countryCode: String
    ) {
        if (ContactDirectoryRules.partlyFetchedDirectories(directories).isEmpty()) return
        val numbers = synchronized(serverSearchLock) {
            if (serverSearchDirectories !== directories) {
                // Reloaded: search again. Found contacts are kept until new results replace them.
                serverSearchedNumbers.clear()
                serverSearchDirectories = directories
            }
            CallHistoryContactMatcher.numbersToSearch(items, countryCode)
                .filter { (key, _) -> serverSearchedNumbers.add(key) }
        }
        if (numbers.isEmpty()) return
        Log.i("$TAG: searching the directories for ${numbers.size} unmatched number(s)")

        for ((key, number) in numbers) {
            serverSearchScope.launch {
                val found = try {
                    serverSearchLimit.withPermit {
                        CallContactMatchService.findDirectoryContactOnServer(number, countryCode)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Searched for again when the directory contacts next reload
                    Log.e(e, "$TAG: searching the directories for $number failed")
                    return@launch
                }

                val changed = synchronized(serverSearchLock) {
                    // The contacts reloaded, or the user changed, while searching
                    if (serverSearchDirectories !== directories) return@launch
                    if (found != null) {
                        serverFoundContacts.put(key, found) != found
                    } else {
                        serverFoundContacts.remove(key) != null
                    }
                }
                if (found != null) {
                    Log.i("$TAG: $number is directory contact ${found.second.id}")
                }
                if (changed) serverFoundChanged.onNext(serverSearchCount.incrementAndGet())
            }
        }
    }

    // So one user's calls are never named after another user's contacts
    private fun clearServerSearches() {
        synchronized(serverSearchLock) {
            serverFoundContacts.clear()
            serverSearchedNumbers.clear()
            serverSearchDirectories = null
        }
    }

    private fun queueNextQuery(status: Int) {
        queryStatusSubject.onNext(status)
    }

    private fun backoffQuery(request: ReportRequest): Observable<ReportResult> {
        if (statusQueryAttempts > 10) {
            return Observable.just(
                ReportResult(
                    LocalDateTime.now(ZoneOffset.UTC).toString(),
                    request.requestId,
                    "",
                    ReportStates.Timeout.value,
                    "",
                    "",
                    listOf()
                )
            )
        }

        val delayMs = statusQueryAttempts * 500L

        return Observable.timer(delayMs, TimeUnit.MILLISECONDS)
            .flatMap {
                rxSingle {
                    val response = APIClientService(context).getUCGatewayService().getReportResult(
                        request.requestId
                    )

                    if (response.isSuccessful && response.body() != null) {
                        response.body()!!
                    } else {
                        ReportResult(
                            LocalDateTime.now(ZoneOffset.UTC).toString(),
                            request.requestId,
                            "",
                            ReportStates.Failed.value,
                            "",
                            "",
                            listOf()
                        )
                    }
                }.toObservable()
            }
            .onErrorReturn {
                Log.e("backoffQuery", it.message ?: "Unknown error")
                ReportResult(
                    Date().toString(),
                    request.requestId,
                    "",
                    ReportStates.Failed.value,
                    "",
                    "",
                    listOf()
                )
            }
    }

    private fun appendToHistory(items: List<CallHistoryItem>) {
        try {
            val arr = callHistorySubject.value?.toMutableList()

            if (arr != null && items.isNotEmpty()) {
                // Insert any new items at the beginning
                arr.addAll(0, items)

                // Take the first 200, removing any duplicates
                val newArr: List<CallHistoryItem> = arr.distinctBy { it.connectionId }
                    .take(200)

                callHistorySubject.onNext(newArr)

                cacheCallHistory(newArr, authStateManager.getUser().id)
            }
        } catch (e: Exception) {
            Log.e(e, "$TAG: appendToHistory error")
        }
    }

    private fun cacheCallHistory(data: List<CallHistoryItem>, currentUserId: String) {
        try {
            Log.d("$TAG: caching ${data.size} call history items")

            // Compress the data to reduce storage size.
            val typeHint = object : TypeToken<List<CallHistoryItem>>() {}.type
            val compressedData = compressString(
                GsonUtils.defaultGsonInstance.toJson(data, typeHint)
            )

            // Add metadata to the history so we can choose whether to use it later
            val cacheObj = CallHistoryCache(
                userId = currentUserId,
                version = CACHE_VERSION,
                data = compressedData
            )

            // Store the cache object in SQLite
            val dbHelper = CallHistoryDatabaseHelper(context)
            val db = dbHelper.writableDatabase

            val contentValues = ContentValues().apply {
                put("userId", cacheObj.userId)
                put("version", cacheObj.version)
                put("data", cacheObj.data)
            }

            db.replace("CallHistoryCache", null, contentValues)
            db.close()
        } catch (e: Exception) {
            // May not have access - this is OK
            Log.e(e, "$TAG: Failed to cache call history.")
        }
    }

    private fun getCachedCallHistory(currentUserId: String): List<CallHistoryItem> {
        Log.d("$TAG: Get history from cache...")

        val dbHelper = CallHistoryDatabaseHelper(context)
        val db = dbHelper.readableDatabase

        return try {
            val cursor: Cursor = db.query(
                "CallHistoryCache",
                arrayOf("userId", "version", "data"),
                "userId=?",
                arrayOf(currentUserId),
                null,
                null,
                null,
                null
            )

            var cacheObj: CallHistoryCache? = null
            if (cursor.moveToFirst()) {
                val userId = cursor.getString(cursor.getColumnIndexOrThrow("userId"))
                val version = cursor.getInt(cursor.getColumnIndexOrThrow("version"))
                val data = cursor.getString(cursor.getColumnIndexOrThrow("data"))
                cacheObj = CallHistoryCache(userId, version, data)
            }
            cursor.close()

            if (cacheObj == null) {
                Log.d("$TAG: No cached history found.")
                return listOf()
            }

            val decompressedData = decompressString(cacheObj.data)

            val typeHint = object : TypeToken<List<CallHistoryItem>>() {}.type
            val data = GsonUtils.defaultGsonInstance.fromJson<List<CallHistoryItem>>(
                decompressedData,
                typeHint
            )

            Log.d("$TAG: Got ${data.size} results from history cache.")

            if (cacheObj.userId != currentUserId) {
                Log.d("$TAG: Cached history is for a different user. Current user: $currentUserId")
                return listOf()
            }

            if (cacheObj.version < CACHE_VERSION) {
                Log.w(
                    "$TAG: Cached history version (${cacheObj.version}) does not match current version ($CACHE_VERSION) - discarding."
                )
                return listOf()
            }

            return data ?: listOf()
        } catch (e: Exception) {
            Log.e(e, "$TAG: Failed to load call history cache.")
            listOf()
        } finally {
            db.close()
        }
    }

    private suspend fun getMissedCallTimestamp() {
        val response = APIClientService(context).getUCGatewayService().getMissedCallDate()

        if (response.code() < 200 || response.code() > 299) {
            Log.e("Error fetching user info: " + response.message())
            return
        }

        val formattedDateTimeString = response.body()!!.missedCallTimestamp
        val missedCallTimestamp: ZonedDateTime? = ZonedDateTime.parse(formattedDateTimeString)

        missedCallTimestampSubject.onNext(
            missedCallTimestamp!!
        )
    }

    fun updateMissedCallTimestamp() {
        val now = ZonedDateTime.now(ZoneId.of("UTC"))

        val formattedDateTime = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        missedCallTimestampSubject.onNext(now)

        APIClientService(context)
            .getUCGatewayService()
            .doSetMissedCallDate(formattedDateTime)
            .enqueue(object : Callback<Void> {
                override fun onFailure(call: Call<Void>, t: Throwable) {
                    Log.e("Failed to update MissedCallTimestamp", t)
                }

                override fun onResponse(
                    call: Call<Void>,
                    response: Response<Void>
                ) {
                    if (response.isSuccessful) {
                        Log.i("Successfully updated MissedCallTimestamp")
                    } else {
                        Log.e("Failed to update MissedCallTimestamp::${response.code()}")
                    }
                }
            })
    }

    private fun compressString(data: String): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
            gzipOutputStream.write(data.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT)
    }

    private fun decompressString(compressedData: String): String {
        val compressedByteArray = Base64.decode(compressedData, Base64.DEFAULT)
        val byteArrayInputStream = ByteArrayInputStream(compressedByteArray)
        GZIPInputStream(byteArrayInputStream).use { gzipInputStream ->
            return gzipInputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}
