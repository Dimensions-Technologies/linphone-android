package org.linphone.services

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.BiFunction
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.activities.main.contact.viewmodels.UserGroupViewModel
import org.linphone.authentication.AuthStateManager
import org.linphone.models.AuthenticatedUser
import org.linphone.models.UserInfo
import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.contact.ContactGroupItem
import org.linphone.models.contact.ContactItemModel
import org.linphone.models.contact.ContactItemRequest
import org.linphone.models.realtime.ContactMatch
import org.linphone.models.usergroup.GroupUserSummaryModel
import org.linphone.models.usergroup.UserGroupModel
import org.linphone.utils.GsonUtils
import org.linphone.utils.Log
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber

class DirectoriesService(val context: Context) : DefaultLifecycleObserver {
    private val apiClient = APIClientService(context)
    private val authStateManager = AuthStateManager.getInstance(context)
    private val destroy = PublishSubject.create<Unit>()

    private var contactDirectoriesSubscription: Disposable? = null
    val contactDirectoriesSubject = BehaviorSubject.create<List<ContactDirectoryModel>>()
    val contactDirectories = contactDirectoriesSubject.map { x -> x }

    private var allUsersSubscription: Disposable? = null
    private val allUsersSubject = BehaviorSubject.create<List<UserInfo>>()
    private val allUsers = allUsersSubject.map { x -> x }

    val dialSearchTextSubject = BehaviorSubject.createDefault("")
    val dialSearchText = dialSearchTextSubject
        .map { x -> x }

    // Directory search results, tagged with the search text they were made for so callers can tell
    // whether the results belong to the text currently being searched.
    data class DirectorySearchResult(val searchText: String, val results: UserGroupViewModel)

    val searchResults: Observable<DirectorySearchResult>
        get() = dialSearchText
            .debounce(500, TimeUnit.MILLISECONDS)
            .switchMap { searchText ->
                val text = formatSearchText(searchText)
                if (text.length >= 3) {
                    Log.i("searchResults($text)")
                    search(PhoneFormatterService.getInstance(context).getSearchNumber(text))
                        .map { DirectorySearchResult(searchText, it) }
                } else {
                    Observable.just(DirectorySearchResult(searchText, UserGroupViewModel.empty()))
                }
            }
            .share()
            .onErrorResumeNext { e: Throwable ->
                Log.e(e, "Error searching directories.")
                Observable.just(
                    DirectorySearchResult(
                        dialSearchTextSubject.value.orEmpty(),
                        UserGroupViewModel.empty()
                    )
                )
            }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)

        destroy.onNext(Unit)
        destroy.onComplete()

        // contactDirectoriesSubscription?.dispose()
        // allUsersSubscription?.dispose()
    }

    init {
        Log.d("Created DirectoriesService")

        contactDirectoriesSubscription = authStateManager.user
            .filter { u -> u.id != null && u.id != AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER }
            .distinctUntilChanged { user -> user.id ?: "" }
            .takeUntil(destroy)
            .subscribe { user ->
                try {
                    Log.d("Directory user: " + user.name)
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
            .filter { u -> u.id != null && u.id != AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER }
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

    val personalDirectory: ContactDirectoryModel?
        get() = contactDirectoriesSubject.value?.let {
            ContactDirectoryRules.findPersonalDirectory(
                it
            )
        }

    fun findDirectory(id: String): ContactDirectoryModel? =
        contactDirectoriesSubject.value?.firstOrNull { it.id == id }

    fun canContribute(directory: ContactDirectoryModel): Boolean =
        ContactDirectoryRules.canContribute(directory, authStateManager.getUser().id)

    // Directories the user can add contacts to, Personal first and the rest by name, as on the web client.
    fun contributorDirectories(): List<ContactDirectoryModel> =
        contactDirectoriesSubject.value.orEmpty()
            .filter { canContribute(it) }
            .sortedWith(
                compareBy<ContactDirectoryModel> { it.name != ContactDirectoryRules.PERSONAL_DIRECTORY_NAME }
                    .thenBy { it.name.lowercase() }
            )

    // Directory types whose matches are never shown, as on the web client
    private val excludedDirectoryTypes = setOf("HubspotContactDirectory")

    /**
     * Whether matches from a directory must be ignored: one the user's directory list doesn't include
     * (e.g. a server-side matching source), or an excluded type. As the web client's isDirectoryDenied.
     */
    fun isDirectoryDenied(directoryId: String?): Boolean {
        val directory = directoryId?.let { findDirectory(it) } ?: return true
        return directory.type in excludedDirectoryTypes
    }

    fun filterDeniedMatches(matches: List<ContactMatch>): List<ContactMatch> =
        matches.filter { !isDirectoryDenied(it.directoryId) }

    /**
     * The full contact behind a match: from the loaded directory contacts, or fetched from the
     * gateway if its directory isn't loaded (and isn't denied). As the web client's getContact.
     */
    suspend fun getContact(directoryId: String, contactId: String): ContactItemModel? {
        val loaded = UserGroupService.getInstance(context).currentDirectoryContacts()
            .firstOrNull { it.first.id == directoryId }
        if (loaded != null) return loaded.second.firstOrNull { it.id == contactId }
        if (isDirectoryDenied(directoryId)) return null

        return try {
            val response = apiClient.getUCGatewayService().getDirectoryContact(
                directoryId,
                contactId
            )
            if (!response.isSuccessful) {
                Log.e("Failed to fetch contact $contactId::${response.code()}")
                return null
            }
            response.body()?.withDirectoryId(directoryId)
        } catch (e: Exception) {
            Log.e("Failed to fetch contact $contactId", e)
            null
        }
    }

    /** Creates a contact and refreshes the contact groups. Returns null if the gateway call failed. */
    suspend fun createContact(request: ContactItemRequest, avatarPng: ByteArray?): ContactItemModel? {
        Log.d("createContact::${request.directoryId}")
        return try {
            val response = apiClient.getUCGatewayService().createDirectoryContact(
                request.directoryId,
                contactItemPart(request),
                avatarPart(avatarPng)
            )
            if (!response.isSuccessful) {
                Log.e("Failed to create contact::${response.code()}")
                return null
            }
            UserGroupService.getInstance(context).fetchUserGroups()
            response.body()?.withDirectoryId(request.directoryId)
        } catch (e: Exception) {
            Log.e("Failed to create contact", e)
            null
        }
    }

    /** Updates a contact and refreshes the contact groups. Returns null if the gateway call failed. */
    suspend fun updateContact(
        request: ContactItemRequest,
        avatarPng: ByteArray?,
        removeAvatar: Boolean
    ): ContactItemModel? {
        Log.d("updateContact::${request.id}")
        return try {
            val response = apiClient.getUCGatewayService().updateDirectoryContact(
                request.directoryId,
                request.id,
                contactItemPart(request),
                avatarPart(avatarPng),
                if (removeAvatar) true else null
            )
            if (!response.isSuccessful) {
                Log.e("Failed to update contact ${request.id}::${response.code()}")
                return null
            }
            UserGroupService.getInstance(context).fetchUserGroups()
            response.body()?.withDirectoryId(request.directoryId)
        } catch (e: Exception) {
            Log.e("Failed to update contact ${request.id}", e)
            null
        }
    }

    /** Deletes a contact and refreshes the contact groups. Returns false if the gateway call failed. */
    suspend fun deleteContact(directoryId: String, contactId: String): Boolean {
        Log.d("deleteContact::$contactId")
        return try {
            val response = apiClient.getUCGatewayService().deleteDirectoryContact(
                directoryId,
                contactId
            )
            if (!response.isSuccessful) {
                Log.e("Failed to delete contact $contactId::${response.code()}")
                return false
            }
            UserGroupService.getInstance(context).fetchUserGroups()
            true
        } catch (e: Exception) {
            Log.e("Failed to delete contact $contactId", e)
            false
        }
    }

    suspend fun getDirectoryContacts(directory: ContactDirectoryModel): List<ContactItemModel>? {
        return try {
            val response = apiClient.getUCGatewayService().getDirectoryContacts(directory.id)
            if (!response.isSuccessful) {
                Log.e("Failed to fetch contacts for directory ${directory.id}::${response.code()}")
                return null
            }
            response.body().orEmpty().map { it.withDirectoryId(directory.id) }
        } catch (e: Exception) {
            Log.e("Failed to fetch contacts for directory ${directory.id}", e)
            null
        }
    }

    private fun ContactItemModel.withDirectoryId(id: String) =
        if (directoryId.isEmpty()) copy(directoryId = id) else this

    private fun contactItemPart(request: ContactItemRequest) =
        GsonUtils.defaultGsonInstance.toJson(request).toRequestBody()

    private fun avatarPart(avatarPng: ByteArray?): MultipartBody.Part? =
        avatarPng?.let {
            MultipartBody.Part.createFormData(
                "file",
                "avatar.png",
                it.toRequestBody("image/png".toMediaType())
            )
        }

    private fun fetchContactDirectories() {
        Log.d("Fetch contact directories...")

        apiClient.getUCGatewayService().doGetContactDirectories()
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

        apiClient.getUCGatewayService().doGetAllUsers()
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

    private fun search(searchText: String): Observable<UserGroupViewModel> {
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
        users: List<GroupUserSummaryModel>
    ): UserGroupViewModel {
        val favourites = UserGroupService.getInstance(context).favouritesGroup

        val userGroupModel = UserGroupModel()
        userGroupModel.id = UserGroupViewModel.SEARCH_RESULTS_GROUP_NAME
        userGroupModel.name = context.resources.getString(R.string.contacts_searchResultsGroup)
        userGroupModel.contacts = contacts
        userGroupModel.users = users

        if (favourites != null) {
            contacts.forEach { c ->
                c.isInFavourites = favourites.friends.any { fu -> UserGroupService.gatewayIdOf(fu) == c.id }
            }

            users.forEach { d ->
                d.isInFavourites = favourites.friends.any { fu -> UserGroupService.gatewayIdOf(fu) == d.id }
            }
        }

        return UserGroupViewModel(userGroupModel)
    }

    private fun searchAllDirectories(searchText: String): Observable<List<ContactItemModel>> {
        return contactDirectories.map { params ->
            val contactItemLists = params.map { param ->
                // TODO: this needs reworking as its not parallel
                searchDirectory(param.id, searchText)
            }
            contactItemLists.flatten()
        }
            .onErrorResumeNext { e: Throwable ->
                Log.e(e, "Error searching directories.")
                Observable.just(listOf())
            }
    }

    private fun searchDirectory(id: String, searchText: String): List<ContactItemModel> {
        val response = apiClient.getUCGatewayService().searchDirectory(id, searchText).execute()

        return if (response.isSuccessful && response.body() != null) {
            // Search results don't say which directory they came from; needed to edit or delete them
            response.body()!!.map { it.withDirectoryId(id) }
        } else {
            listOf()
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

    fun removeUserFromFavourites(id: String) {
        Log.d("removeUserFromFavourites::$id")

        val userGroupService = UserGroupService.getInstance(context)
        val favorites = userGroupService.favouritesGroup
        if (favorites != null) {
            apiClient.getUCGatewayService().doRemoveUserFromDirectory(favorites.id, id)
                .enqueue(object : Callback<Void> {
                    override fun onFailure(call: Call<Void>, t: Throwable) {
                        Log.e("Failed to remove user $id from favourites", t)
                        Toast.makeText(
                            coreContext.context,
                            coreContext.context.getString(
                                R.string.contacts_failedToRemoveUserFromFavorites
                            ),
                            Toast.LENGTH_LONG
                        ).show()

                        userGroupService.fetchUserGroups()
                    }

                    override fun onResponse(
                        call: Call<Void>,
                        response: Response<Void>
                    ) {
                        if (response.isSuccessful) {
                            Log.i("Removed user $id from favourites")

                            Toast.makeText(
                                coreContext.context,
                                coreContext.context.getString(
                                    R.string.contacts_successfullyRemovedUserFromFavorites
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Log.e("Failed to remove user $id from favourites:: ${response.code()}")
                            Toast.makeText(
                                coreContext.context,
                                coreContext.context.getString(
                                    R.string.contacts_failedToRemoveUserFromFavorites
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        userGroupService.fetchUserGroups()
                    }
                })
        }
    }

    fun addUserToFavourites(id: String) {
        Log.d("addUserToFavourites::$id")

        val userGroupService = UserGroupService.getInstance(context)
        val favorites = userGroupService.favouritesGroup

        if (favorites != null) {
            apiClient.getUCGatewayService().doAddUserToUserDirectory(favorites.id, arrayOf(id))
                .enqueue(object : Callback<Void> {
                    override fun onFailure(call: Call<Void>, t: Throwable) {
                        Log.e("Failed to add user $id to favourites", t)

                        Toast.makeText(
                            coreContext.context,
                            coreContext.context.getString(
                                R.string.contacts_failedToAddUserToFavorites
                            ),
                            Toast.LENGTH_LONG
                        ).show()

                        userGroupService.fetchUserGroups()
                    }

                    override fun onResponse(
                        call: Call<Void>,
                        response: Response<Void>
                    ) {
                        if (response.isSuccessful) {
                            Log.i("Added user $id to favourites")

                            Toast.makeText(
                                coreContext.context,
                                coreContext.context.getString(
                                    R.string.contacts_successfullyAddedUserToFavorites
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Log.e("Failed to add user $id to favourites::${response.code()}")

                            Toast.makeText(
                                coreContext.context,
                                coreContext.context.getString(
                                    R.string.contacts_failedToAddUserToFavorites
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        userGroupService.fetchUserGroups()
                    }
                })
        }
    }

    fun removeContactFromFavourites(id: String) {
        Log.d("removeContactFromFavourites::$id")

        val userGroupService = UserGroupService.getInstance(context)
        val favorites = userGroupService.favouritesGroup

        if (favorites != null) {
            apiClient.getUCGatewayService().doRemoveContactFromDirectory(favorites.id, id)
                .enqueue(object : Callback<Void> {
                    override fun onFailure(call: Call<Void>, t: Throwable) {
                        Log.e("Failed to remove contact $id from favourites", t)

                        Toast.makeText(
                            coreContext.context,
                            coreContext.context.getString(
                                R.string.contacts_failedToRemoveContactFromFavorites
                            ),
                            Toast.LENGTH_LONG
                        ).show()

                        userGroupService.fetchUserGroups()
                    }

                    override fun onResponse(
                        call: Call<Void>,
                        response: Response<Void>
                    ) {
                        if (response.isSuccessful) {
                            Log.i("Removed contact $id from favourites")

                            Toast.makeText(
                                coreContext.context,
                                coreContext.context.getString(
                                    R.string.contacts_successfullyRemovedContactFromFavorites
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Log.e(
                                "Failed to remove contact $id from favourites::${response.code()}"
                            )

                            Toast.makeText(
                                coreContext.context,
                                coreContext.context.getString(
                                    R.string.contacts_failedToRemoveContactFromFavorites
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        userGroupService.fetchUserGroups()
                    }
                })
        }
    }

    fun addContactToFavourites(contact: ContactItemModel) {
        Log.d("addContactToFavourites::${contact.id}")

        val userGroupService = UserGroupService.getInstance(context)
        val favorites = userGroupService.favouritesGroup

        if (favorites != null) {
            val contactGroupItem = ContactGroupItem(contact.directoryId, contact.id)

            apiClient.getUCGatewayService().doAddContactToDirectory(favorites.id, contactGroupItem)
                .enqueue(object : Callback<Void> {
                    override fun onFailure(call: Call<Void>, t: Throwable) {
                        Log.e("Failed to add contact to favourites", t)

                        Toast.makeText(
                            coreContext.context,
                            coreContext.context.getString(
                                R.string.contacts_failedToAddContactToFavorites
                            ),
                            Toast.LENGTH_LONG
                        ).show()

                        userGroupService.fetchUserGroups()
                    }

                    override fun onResponse(
                        call: Call<Void>,
                        response: Response<Void>
                    ) {
                        if (response.isSuccessful) {
                            Log.i("Added contacts to favourites")
                            Toast.makeText(
                                coreContext.context,
                                coreContext.context.getString(
                                    R.string.contacts_successfullyAddedContactToFavorites
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Log.e("Failed to add contact to favourites::${response.code()}")

                            Toast.makeText(
                                coreContext.context,
                                coreContext.context.getString(
                                    R.string.contacts_failedToAddContactToFavorites
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        userGroupService.fetchUserGroups()
                    }
                })
        }
    }
}
