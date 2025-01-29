package org.linphone.services

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.BiFunction
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.linphone.authentication.AuthStateManager
import org.linphone.authentication.AuthorizationServiceManager
import org.linphone.environment.DimensionsEnvironmentService
import org.linphone.models.AuthenticatedUser
import org.linphone.models.UserInfo
import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactItemModel
import org.linphone.models.search.SearchItemViewModel
import org.linphone.models.usergroup.GroupUserSummaryModel
import org.linphone.utils.Log
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber

class DirectoriesService(val context: Context) : DefaultLifecycleObserver {
    private val apiClient = APIClientService()
    private val dimensionsEnvironment =
        DimensionsEnvironmentService.getInstance(context).getCurrentEnvironment()
    private val authStateManager = AuthStateManager.getInstance(context)
    private val destroy = PublishSubject.create<Unit>()

    private var contactDirectoriesSubscription: Disposable? = null
    private val contactDirectoriesSubject = BehaviorSubject.create<List<ContactDirectoryModel>>()
    private val contactDirectories = contactDirectoriesSubject.map { x -> x }

    private var allUsersSubscription: Disposable? = null
    private val allUsersSubject = BehaviorSubject.create<List<UserInfo>>()
    private val allUsers = allUsersSubject.map { x -> x }

    val dialSearchTextSubject = BehaviorSubject.createDefault("")
    private val dialSearchText = dialSearchTextSubject.map { x -> x }

    val searchResults: Observable<List<SearchItemViewModel>>
        get() = dialSearchText
            .debounce(500, TimeUnit.MILLISECONDS)
            .map { formatSearchText(it) }
            .switchMap { text ->
                if (text.length >= 3) {
                    search(text)
                } else {
                    Observable.just(emptyList())
                }
            }
            .share()
            .onErrorResumeNext { e: Throwable ->
                Log.e(e, "Error searching directories.")
                Observable.just(emptyList())
            }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)

        destroy.onNext(Unit)
        destroy.onComplete()

        contactDirectoriesSubscription?.dispose()
        allUsersSubscription?.dispose()
    }

    init {
        Log.d("Created DirectoriesService")

        contactDirectoriesSubscription = authStateManager.user
            .distinctUntilChanged { user -> user.id ?: "" }
            .takeUntil(destroy)
            .subscribe { user ->
                try {
                    Log.d("Brand user: " + user.name)
                    if ((user.id == null || user.id == AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER) && contactDirectoriesSubject.value != null) {
                        contactDirectoriesSubject.onNext(
                            listOf()
                        )
                    } else {
                        fetchContactDirectories()
                    }
                } catch (ex: Exception) {
                    Log.e(ex)
                }
            }

        allUsersSubscription = authStateManager.user
            .distinctUntilChanged { user -> user.id ?: "" }
            .takeUntil(destroy)
            .subscribe { user ->
                try {
                    Log.d("Directories user: " + user.name)
                    if ((user.id == null || user.id == AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER) && allUsersSubject.value != null) {
                        allUsersSubject.onNext(
                            listOf()
                        )
                    } else {
                        fetchAllUsers()
                    }
                } catch (ex: Exception) {
                    Log.e(ex)
                }
            }
    }

    companion object {
        private const val TAG: String = "DirectoriesService"

        private val instance: AtomicReference<DirectoriesService> =
            AtomicReference<DirectoriesService>()

        fun getInstance(context: Context): DirectoriesService {
            var svc = instance.get()
            if (svc == null) {
                svc = DirectoriesService(context.applicationContext)
                instance.set(svc)
            }
            return svc
        }
    }

    private fun fetchContactDirectories() {
        Log.d("Fetch contact directories...")

        apiClient.getUCGatewayService(
            dimensionsEnvironment!!.gatewayApiUri,
            AuthorizationServiceManager.getInstance(context).authorizationServiceInstance,
            AuthStateManager.getInstance(context)
        ).doGetContactDirectories()
            .enqueue(object : Callback<List<ContactDirectoryModel>> {
                override fun onFailure(call: Call<List<ContactDirectoryModel>>, t: Throwable) {
                    Log.e("Failed to fetch contact directories", t)
                }

                override fun onResponse(
                    call: Call<List<ContactDirectoryModel>>,
                    response: Response<List<ContactDirectoryModel>>
                ) {
                    Timber.d("Got contact directories from API")
                    response.body()?.let { contactDirectoriesSubject.onNext(it) }
                }
            })
    }

    private fun fetchAllUsers() {
        Log.d("Fetch all users...")

        apiClient.getUCGatewayService(
            dimensionsEnvironment!!.gatewayApiUri,
            AuthorizationServiceManager.getInstance(context).authorizationServiceInstance,
            AuthStateManager.getInstance(context)
        ).doGetAllUsers()
            .enqueue(object : Callback<List<UserInfo>> {
                override fun onFailure(call: Call<List<UserInfo>>, t: Throwable) {
                    Log.e("Failed to fetch all users", t)
                }

                override fun onResponse(
                    call: Call<List<UserInfo>>,
                    response: Response<List<UserInfo>>
                ) {
                    Timber.d("Got all users from API")
                    response.body()?.let { allUsersSubject.onNext(it) }
                }
            })
    }

    private fun search(searchText: String): Observable<List<SearchItemViewModel>> {
        return Observable.zip(
            searchAllDirectories(searchText),
            searchAllUsers(searchText),
            BiFunction {
                    contacts, directories ->
                return@BiFunction mergeSearchItems(
                    contacts,
                    directories
                )
            }
        )
    }

    private fun mergeSearchItems(
        contacts: List<ContactItemModel>,
        directories: List<GroupUserSummaryModel>
    ): List<SearchItemViewModel> {
        val result = arrayListOf<SearchItemViewModel>()

        contacts.forEach { c -> result.add(SearchItemViewModel(c, null)) }
        directories.forEach { d -> result.add(SearchItemViewModel(null, d)) }

        return result
    }

    private fun searchAllDirectories(searchText: String): Observable<List<ContactItemModel>> {
        Log.i("searchAllDirectories::$searchText")
        return contactDirectories.flatMap { params ->
            val observables = params.map { param ->
                apiClient.getUCGatewayService(
                    dimensionsEnvironment!!.gatewayApiUri,
                    AuthorizationServiceManager.getInstance(context).authorizationServiceInstance,
                    AuthStateManager.getInstance(context)
                )
                    .doSearchDirectory(param.id, searchText)
                    .defaultIfEmpty(listOf())
            }
            return@flatMap Observable.merge(observables)
        }
    }

    private fun searchAllUsers(searchText: String): Observable<List<GroupUserSummaryModel>> {
        Log.i("searchAllUsers::$searchText")
        // return Observable.just(listOf())
        val lowerSearchText = searchText.lowercase()

        val currentUserId = AuthStateManager.getInstance(context).getUser().id

        return allUsers.map { allUsers ->
            allUsers
                .filter { user ->
                    user.id != currentUserId && user.displayName.lowercase().indexOf(
                        lowerSearchText
                    ) > -1
                }
                .map { user -> toGroupUserSummaryModel(user) }
        }
    }

    private fun toGroupUserSummaryModel(user: UserInfo): GroupUserSummaryModel {
        return GroupUserSummaryModel(
            user.id,
            user.displayName,
            user.email,
            user.presenceId,
            user.profileImageUrl
        )
    }

    private fun formatSearchText(searchText: String): String {
        if (searchText.isNotBlank() && isValidPhoneNumber(searchText)) {
            val pattern = "/[()*#+\\- ]/gi".toRegex()
            return searchText.replace(pattern, "")
        }
        return searchText
    }

    private fun isValidPhoneNumber(searchText: String): Boolean {
        val pattern = "^[-+*#() 0-9]+\$".toRegex()
        return pattern.matches(searchText)
    }
}
