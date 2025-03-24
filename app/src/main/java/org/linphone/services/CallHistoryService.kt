package org.linphone.services

import ReportResult
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.BiFunction
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.linphone.authentication.AuthStateManager
import org.linphone.models.AuthenticatedUser
import org.linphone.models.callhistory.CallHistoryCache
import org.linphone.models.callhistory.CallHistoryItem
import org.linphone.models.callhistory.CallHistoryItemViewModel
import org.linphone.models.callhistory.ReportRequest
import org.linphone.models.callhistory.ReportStates
import org.linphone.models.realtime.RealtimeEventType
import org.linphone.services.realtime.RealtimeUserService
import org.linphone.utils.CallHistoryDatabaseHelper
import org.linphone.utils.DateUtils
import org.linphone.utils.Log
import org.linphone.utils.Optional
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class CallHistoryService(val context: Context) : DefaultLifecycleObserver {
    private val authStateManager = AuthStateManager.getInstance(context)
    private val realtimeUserService = RealtimeUserService.getInstance(context)

    private val destroy = PublishSubject.create<Unit>()

    private var callHistorySubscription: Disposable? = null

    private val callHistorySubject = BehaviorSubject.create<List<CallHistoryItem>>()
    val history: Observable<List<CallHistoryItem>> = callHistorySubject.hide()

    /** Each time a value is emitted, a new report request will be submitted.  */
    private val newReportRequest: PublishSubject<Unit> = PublishSubject.create()

    /** Each time a value is emitted and new attempt will be made to query the current report result. */
    private val queryStatus = BehaviorSubject.createDefault(0)

    private var statusQueryAttempts: Int = 0

    private val missedCallTimestampSubject = BehaviorSubject.create<Date>()
    private val missedCallTimestamp: Observable<Date> = missedCallTimestampSubject.hide()

    private val authSubscription: Disposable = authStateManager.user
        .filter { u -> u.id != null && u.id != AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER }
        .distinctUntilChanged { user -> user.id ?: "" }
        .takeUntil(destroy)
        .subscribe {
            getMissedCallTimestamp()
        }

    private val userId: Observable<String> = authStateManager.user
        .filter { u -> u.id != null && u.id != AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER }
        .distinctUntilChanged { user -> user.id ?: "" }
        .takeUntil(destroy)
        .map { user -> user.id.toString() }

    @SuppressLint("SimpleDateFormat")
    private val historyRequest: Observable<ReportRequest> = Observable.merge(
        userId,
        newReportRequest
    )
        .doOnNext { statusQueryAttempts = 0 }
        .switchMap {
            val arr = callHistorySubject.value ?: emptyList()
            val maxStartTime = arr.maxOfOrNull { it.startTime } ?: 0L
            val fromDate = if (maxStartTime > 0) {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                dateFormat.timeZone = TimeZone.getTimeZone("UTC")
                dateFormat.format(Date(maxStartTime))
            } else {
                null
            }
            val timeZoneId = TimeZone.getDefault().id

            // FixME: should we be using runBlocking for this?
            Observable.fromCallable {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        APIClientService(context)
                            .getUCGatewayService()
                            .postReportRequest(
                                mapOf("fromDate" to fromDate, "timeZoneId" to timeZoneId)
                            )
                    }
                }
            }
        }
        .share()

    private val startCache = authStateManager.user
        .filter { u -> u.id != null && u.id != AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER }
        .distinctUntilChanged { user -> user.id ?: "" }
        .takeUntil(destroy)
        .map { user -> getCachedCallHistory(user.id ?: "") }

    private val reportQuery: Observable<ReportResult> = Observable.combineLatest(
        queryStatus,
        historyRequest,
        { _, request -> request }
    )
        .switchMap { request -> backoffQuery(request) }
        .doOnNext { r ->
            statusQueryAttempts++
            if (!ReportStates.isDone(r.status)) queueNextQuery()
        }
        .share()

    val appendHistoryObservable = Observable.merge(
        startCache,
        reportQuery
            .filter { r -> ReportStates.isDone(r.status) }
            .map { r -> r.data as? List<CallHistoryItem> ?: listOf() }
            .doOnNext { data -> Log.d("History report returned item count ${data.size}") }
    )
        .subscribe { data ->
            appendToHistory(data)
        }

    val missedCallCount: Observable<Int> = Observable.combineLatest(
        history,
        missedCallTimestamp,
        { history, timestamp ->
            history.filter { it.missedCall && Date(it.startTime) > timestamp }.size
        }
    )

    val formattedHistory: Observable<List<CallHistoryItemViewModel>> = Observable.combineLatest(
        history,
        DateUtils.todaysDate,
        { history, date ->
            transformData(history, date)
        }
    )

    val historyMessage: Observable<String> = Observable.combineLatest(
        reportQuery,
        callHistorySubject
    ) { query, history ->
        when {
            !ReportStates.isDone(query.status) && history.isEmpty() -> "Loading call history..."
            history.isEmpty() -> "No calls"
            else -> ""
        }
    }

    val selectedCallSessionIdSubject: BehaviorSubject<String> = BehaviorSubject.createDefault("")

    val selectedCallSessionId = selectedCallSessionIdSubject.map { x -> x }

    val currentCallHistoryItemView: Observable<Optional<CallHistoryItemViewModel>> = Observable.combineLatest(
        selectedCallSessionId,
        formattedHistory,
        BiFunction { selectedCallId: String?, callHistory: List<CallHistoryItemViewModel> ->
            Optional.ofNullable(
                callHistory.find {
                    it.callId == selectedCallId
                }
            )
        }
    ).share()

    companion object {
        private const val TAG: String = "CallHistoryService"
        private const val CACHEVERSION = 1

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
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)

        destroy.onNext(Unit)
        destroy.onComplete()

        authSubscription.dispose()
    }

    init {
        Log.d("Created CallHistoryService")

        callHistorySubscription = authStateManager.user
            .filter { u -> u.id != null && u.id != AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER }
            .distinctUntilChanged { user -> user.id ?: "" }
            .takeUntil(destroy)
            .subscribe { user ->
                try {
                    Log.d("CallHistory user: " + user.name)
                    if ((user.id == null || user.id == AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER)) {
                        callHistorySubject.onNext(
                            listOf()
                        )
                    } else {
                        fetchCallHistory()
                    }
                } catch (ex: Exception) {
                    Log.e(ex)
                }
            }

        realtimeUserService.hubConnection?.on(RealtimeEventType.CallHistoryEvent.eventName, { event: Any ->
            try {
                Log.d(RealtimeEventType.CallHistoryEvent.eventName, event)

                getMissedCallTimestamp()

                newReportRequest.onNext(Unit)
            } catch (e: Exception) {
                Log.e(RealtimeEventType.PresenceEvent.eventName, e)
            }
        }, Any::class.java)
    }

    fun fetchCallHistory() {
    }

    private fun queueNextQuery() {
        Handler(Looper.getMainLooper()).post {
            queryStatus.onNext(0)
        }
    }

    private fun backoffQuery(request: ReportRequest): Observable<ReportResult> {
        if (statusQueryAttempts > 10) {
            return Observable.just(
                ReportResult(
                    Date(),
                    request.requestId,
                    "",
                    ReportStates.Timeout.value,
                    "",
                    "",
                    Unit
                )
            )
        }

        val delayMs = statusQueryAttempts * 500L

        return Observable.timer(delayMs, TimeUnit.MILLISECONDS)
            .switchMap {
                val response = APIClientService(context).getUCGatewayService().getReportResult(
                    request.requestId
                )
                if (response.isSuccessful && response.body() != null) {
                    Observable.just(response.body()!!)
                } else {
                    Observable.just(
                        ReportResult(
                            Date(),
                            request.requestId,
                            "",
                            ReportStates.Failed.value,
                            "",
                            "",
                            Unit
                        )
                    )
                }
            }
            .onErrorReturn {
                println("Error: ${it.message}")
                ReportResult(Date(), request.requestId, "", ReportStates.Failed.value, "", "", Unit)
            }
    }

    private fun appendToHistory(items: List<CallHistoryItem>) {
        var arr = callHistorySubject.value?.toMutableList()

        if (arr != null) {
            if (arr.isNotEmpty() && items.isNotEmpty()) {
                // Insert any new items at the beginning
                arr.addAll(0, items)

                // Take the first 200, removing any duplicates
                arr = arr.distinctBy { it.connectionId }
                    .take(200)
                    .toMutableList()

                callHistorySubject.onNext(arr)

                cacheCallHistory(arr, authStateManager.getUser().id!!)
            }
        }
    }

    private fun cacheCallHistory(data: Any, currentUserId: String) {
        try {
            // Compress the data to reduce storage size.
            val compressedData = compressString(Gson().toJson(data))

            // Add metadata to the history so we can choose whether to use it later
            val cacheObj = CallHistoryCache(
                userId = currentUserId,
                version = CACHEVERSION,
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

            db.insert("CallHistoryCache", null, contentValues)
            db.close()
        } catch (e: Exception) {
            // May not have access - this is OK
            Log.e("CallHistoryService", "Failed to cache call history.", e)
        }
    }

    private fun getCachedCallHistory(currentUserId: String): List<CallHistoryItem> {
        val dbHelper = CallHistoryDatabaseHelper(context)
        val db = dbHelper.readableDatabase

        return try {
            val cursor: Cursor = db.query(
                "CallHistoryCache",
                arrayOf("userId", "version", "data"),
                null,
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
                Log.d("CallHistoryService", "No cached history found.")
                return listOf()
            }

            val decompressedData = decompressString(cacheObj.data)
            val data = Gson().fromJson<List<CallHistoryItem>>(
                decompressedData,
                object : TypeToken<List<CallHistoryItem>>() {}.type
            )

            Log.d("CallHistoryService", "Got ${data.size} results from history cache.")

            if (cacheObj.userId != currentUserId) {
                Log.d(
                    "CallHistoryService",
                    "Cached history is for a different user. Current user: $currentUserId"
                )
                return listOf()
            }

            if (cacheObj.version < CACHEVERSION) {
                Log.w(
                    "CallHistoryService",
                    "Cached history version (${cacheObj.version}) does not match current version ($CACHEVERSION) - discarding."
                )
                return listOf()
            }

            return data ?: listOf()
        } catch (e: Exception) {
            Log.w("CallHistoryService", "Failed to load call history cache.", e)
            listOf()
        } finally {
            db.close()
        }
    }

    private fun getMissedCallTimestamp() {
        val response = APIClientService(context).getUCGatewayService().doGetMissedCallDate()

        if (response.code() < 200 || response.code() > 299) {
            throw Exception("Error fetching user info: " + response.message())
        }

        var missedCallTimestamp = deserializeDateTimeOffset(response.body()!!.missedCallTimestamp)
        if (missedCallTimestamp == null) missedCallTimestamp = Date()

        missedCallTimestampSubject.onNext(
            missedCallTimestamp
        )
    }

    fun updateMissedCallTimestamp() {
        val now = Date()

        missedCallTimestampSubject.onNext(now)

        APIClientService(context)
            .getUCGatewayService()
            .doSetMissedCallDate(now)
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

    private fun deserializeDateTimeOffset(dateTimeOffsetString: String): Date? {
        // This delight is brought to you by then API Level 23 compatibility requirement
        // Split the string into date-time and offset parts
        val parts = dateTimeOffsetString.split("+", "-")
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid DateTimeOffset format")
        }

        val dateTimePart = parts[0]
        val offsetPart = dateTimeOffsetString.substring(dateTimePart.length)

        // Parse the date-time part
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val date = dateFormat.parse(dateTimePart)

        // Calculate the offset in milliseconds
        val offsetHours = offsetPart.substring(1, 3).toInt()
        val offsetMinutes = offsetPart.substring(4, 6).toInt()
        val offsetMillis = (offsetHours * 60 + offsetMinutes) * 60 * 1000

        // Adjust the date by the offset
        if (date != null) {
            return if (offsetPart.startsWith("+")) {
                Date(date.time - offsetMillis)
            } else {
                Date(date.time + offsetMillis)
            }
        }

        return null
    }

    private fun transformData(
        callHistoryData: List<CallHistoryItem>,
        todaysDate: Date
    ): List<CallHistoryItemViewModel> {
        val countryCode = "US" // Replace with actual logic to get the country code
        return callHistoryData.map { call ->
            CallHistoryItemViewModel(call, todaysDate, countryCode)
        }
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
