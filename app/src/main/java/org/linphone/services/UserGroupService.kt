package org.linphone.services

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Function4
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import java.text.Collator
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.linphone.R
import org.linphone.activities.main.contact.viewmodels.UserGroupViewModel
import org.linphone.activities.main.contact.viewmodels.UserGroupViewModelSubjectWrapper
import org.linphone.authentication.AuthStateManager
import org.linphone.core.Friend
import org.linphone.models.AuthenticatedUser
import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.contact.ContactItemModel
import org.linphone.models.search.UserDataModel
import org.linphone.models.usergroup.GroupUserSummaryModel
import org.linphone.models.usergroup.UserGroupModel
import org.linphone.utils.Log
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber

class UserGroupService(val context: Context) : DefaultLifecycleObserver {
    private val apiClient = APIClientService(context)
    private val authStateManager = AuthStateManager.getInstance(context)
    private val destroy = PublishSubject.create<Unit>()

    private val tenantUserGroupsSubject = BehaviorSubject.create<List<UserGroupViewModel>>()
    private val personalUserGroupsSubject = BehaviorSubject.create<List<UserGroupViewModel>>()
    val localContactsSubject = BehaviorSubject.create<UserGroupViewModelSubjectWrapper>()

    // Contact directories shown as groups: the Personal directory and the other listed ones
    private val directoryGroupsSubject = BehaviorSubject.createDefault(
        emptyList<UserGroupViewModel>()
    )

    // Main dispatcher: Friends are created from the fetched contacts, and the core isn't thread safe.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var directoryGroupsJob: Job? = null

    var favouritesGroup: UserGroupViewModel? = null

    private var userSubscription: Disposable? = null
    private var contactDirectoriesSubscription: Disposable? = null

    val userGroups: Observable<List<UserGroupViewModel>> = Observable.combineLatest(
        tenantUserGroupsSubject,
        personalUserGroupsSubject,
        directoryGroupsSubject,
        localContactsSubject,
        Function4 {
                tenantUserGroups, personalUserGroups, directoryGroups, localContactsSubject ->
            return@Function4 mergeUserGroups(
                tenantUserGroups,
                personalUserGroups,
                directoryGroups,
                localContactsSubject
            )
        }
    )

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)

        destroy.onNext(Unit)
        destroy.onComplete()

        userSubscription?.dispose()
        contactDirectoriesSubscription?.dispose()
    }

    init {
        Log.d("Created UserGroupService")

        localContactsSubject.onNext(UserGroupViewModelSubjectWrapper(null))

        // Watch the line status (BLF) of favourite internal-number contacts
        ContactBlfManager.start(context, this)

        contactDirectoriesSubscription = DirectoriesService.getInstance(context).contactDirectories
            .takeUntil(destroy)
            .subscribe {
                try {
                    val user = authStateManager.getUser()

                    Log.d("ContactDirectory user: " + user.name)
                    if ((user.id == null || user.id == AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER) && tenantUserGroupsSubject.value != null) {
                        tenantUserGroupsSubject.onNext(
                            listOf()
                        )

                        personalUserGroupsSubject.onNext(
                            listOf()
                        )

                        directoryGroupsSubject.onNext(emptyList())
                    } else {
                        fetchUserGroups()
                    }
                } catch (ex: Exception) {
                    Log.e(ex)
                }
            }
    }

    companion object {
        private const val TAG: String = "UserGroupService"

        private val instance: AtomicReference<UserGroupService> =
            AtomicReference<UserGroupService>()

        fun getInstance(context: Context): UserGroupService {
            var svc = instance.get()
            if (svc == null) {
                svc = UserGroupService(context.applicationContext)
                instance.set(svc)
            }
            return svc
        }

        // Match friends on the gateway id, not Friend.refKey: setting a friend's name creates its
        // vCard, which resets refKey to the vCard's empty UID, so every refKey is null.
        fun gatewayIdOf(friend: Friend): String? {
            val userData = friend.userData as? UserDataModel ?: return null
            return userData.user?.id ?: userData.contact?.id
        }
    }

    // The directories listed as groups, with their contacts as last fetched
    fun currentDirectoryContacts(): List<Pair<ContactDirectoryModel, List<ContactItemModel>>> =
        toDirectoryContacts(directoryGroupsSubject.value.orEmpty())

    private fun toDirectoryContacts(groups: List<UserGroupViewModel>) =
        groups.mapNotNull { group ->
            val directory = group.directory ?: return@mapNotNull null
            directory to group.friends.mapNotNull { (it.userData as? UserDataModel)?.contact }
        }

    // The directories listed as groups, with their contacts, each time they're fetched
    val directoryContacts: Observable<List<Pair<ContactDirectoryModel, List<ContactItemModel>>>> =
        directoryGroupsSubject.map { toDirectoryContacts(it) }

    fun fetchUserGroups() {
        fetchTenantUserGroups()
        fetchPersonalUserGroups()
        fetchDirectoryGroups()
    }

    // Contact directories are listed as groups alongside the user groups, as on the web client.
    private fun fetchDirectoryGroups() {
        val directoriesService = DirectoriesService.getInstance(context)
        val directories = directoriesService.contactDirectoriesSubject.value.orEmpty()
        val listed = listOfNotNull(ContactDirectoryRules.findPersonalDirectory(directories)) +
            ContactDirectoryRules.otherListedDirectories(directories)
        if (listed.isEmpty()) {
            directoryGroupsSubject.onNext(emptyList())
            return
        }

        Log.d("Fetch contacts for ${listed.size} directories...")
        directoryGroupsJob?.cancel()
        directoryGroupsJob = scope.launch {
            val contactLists = listed
                .map { directory -> async { directoriesService.getDirectoryContacts(directory) } }
                .awaitAll()
            val groups = listed.zip(contactLists)
                // A directory that failed to load is left out rather than shown empty
                .filter { (_, contacts) -> contacts != null }
                .map { (directory, contacts) -> directoryGroup(directory, contacts!!) }
            directoryGroupsSubject.onNext(groups)
        }
    }

    private fun directoryGroup(directory: ContactDirectoryModel, contacts: List<ContactItemModel>): UserGroupViewModel {
        val group = UserGroupViewModel(
            UserGroupModel(directory.id, directory.name, emptyList(), contacts)
        )
        group.directory = directory
        return group
    }

    private fun fetchTenantUserGroups() {
        Log.d("Fetch tenant user groups...")

        apiClient.getUCGatewayService().doGetTenantUserGroups()
            .enqueue(object : Callback<List<UserGroupModel>> {
                override fun onFailure(call: Call<List<UserGroupModel>>, t: Throwable) {
                    Log.e("Failed to fetch tenant user groups", t)
                }

                override fun onResponse(
                    call: Call<List<UserGroupModel>>,
                    response: Response<List<UserGroupModel>>
                ) {
                    Timber.d("Got tenant user groups from API")

                    val userGroupViewModels = arrayListOf<UserGroupViewModel>()
                    response.body()?.let {
                        for (userGroupModel in it) {
                            userGroupViewModels.add(UserGroupViewModel(userGroupModel))
                        }
                    }
                    tenantUserGroupsSubject.onNext(userGroupViewModels)
                }
            })
    }

    private fun fetchPersonalUserGroups() {
        Log.d("Fetch personal user groups...")

        apiClient.getUCGatewayService().doGetPersonalUserGroups()
            .enqueue(object : Callback<List<UserGroupModel>> {
                override fun onFailure(call: Call<List<UserGroupModel>>, t: Throwable) {
                    Log.e("Failed to fetch personal user groups", t)
                }

                override fun onResponse(
                    call: Call<List<UserGroupModel>>,
                    response: Response<List<UserGroupModel>>
                ) {
                    Timber.d("Got personal user groups from API")

                    val userGroupViewModels = arrayListOf<UserGroupViewModel>()
                    response.body()?.let {
                        for (userGroupModel in it) {
                            userGroupViewModels.add(UserGroupViewModel(userGroupModel))
                        }
                    }
                    personalUserGroupsSubject.onNext(userGroupViewModels)
                }
            })
    }

    private fun setIsFavorite(friend: Friend, isFavorite: Boolean) {
        val user = friend.userData as? GroupUserSummaryModel
        if (user != null) {
            user.isInFavourites = isFavorite
        }

        val contact = friend.userData as? ContactItemModel
        if (contact != null) {
            contact.isInFavourites = isFavorite
        }

        val searchItem = friend.userData as? UserDataModel
        if (searchItem != null) {
            searchItem.isInFavourites = isFavorite
        }
    }

    private fun mergeUserGroups(
        tenantUserGroups: List<UserGroupViewModel>,
        personalUserGroups: List<UserGroupViewModel>,
        directoryGroups: List<UserGroupViewModel>,
        localContactsUserGroup: UserGroupViewModelSubjectWrapper
    ): List<UserGroupViewModel> {
        val personalDirectoryGroup = directoryGroups.firstOrNull {
            it.name == ContactDirectoryRules.PERSONAL_DIRECTORY_NAME
        }

        val favorites = personalUserGroups.firstOrNull { x ->
            x.name == context.resources.getString(
                R.string.contacts_favoritesGroup
            )
        }
        if (favorites != null) {
            favorites.friends.forEach { f -> setIsFavorite(f, true) }

            (tenantUserGroups + directoryGroups).forEach { group ->
                group.friends.forEach { f ->
                    val id = gatewayIdOf(f)
                    setIsFavorite(
                        f,
                        id != null && favorites.friends.any { fu -> gatewayIdOf(fu) == id }
                    )
                }
            }
        }

        favouritesGroup = favorites

        // Favourites at the top, then the Personal directory, then the other user groups and
        // directories by name, with the device contacts at the end
        val collator = Collator.getInstance()
        val others = (personalUserGroups + tenantUserGroups + directoryGroups)
            .filter { it !== favorites && it !== personalDirectoryGroup }
            .sortedWith { a, b -> collator.compare(a.name, b.name) }
        val sortedUserGroups = listOfNotNull(favorites, personalDirectoryGroup) + others

        if (localContactsUserGroup.userGroupViewModel == null) return sortedUserGroups

        return sortedUserGroups + localContactsUserGroup.userGroupViewModel
    }
}
