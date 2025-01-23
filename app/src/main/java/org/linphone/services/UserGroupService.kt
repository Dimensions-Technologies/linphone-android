package org.linphone.services

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Function3
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.atomic.AtomicReference
import org.linphone.R
import org.linphone.authentication.AuthStateManager
import org.linphone.authentication.AuthorizationServiceManager
import org.linphone.environment.DimensionsEnvironmentService
import org.linphone.models.AuthenticatedUser
import org.linphone.models.usergroup.UserGroupModel
import org.linphone.utils.Log
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber

class UserGroupService(val context: Context) : DefaultLifecycleObserver {
    private val apiClient = APIClientService()
    private val dimensionsEnvironment =
        DimensionsEnvironmentService.getInstance(context).getCurrentEnvironment()
    private val authStateManager = AuthStateManager.getInstance(context)
    private val destroy = PublishSubject.create<Unit>()

    private val tenantUserGroupsSubject = BehaviorSubject.create<List<UserGroupModel>>()
    private val personalUserGroupsSubject = BehaviorSubject.create<List<UserGroupModel>>()
    val localContactsSubject = BehaviorSubject.create<List<UserGroupModel>>()

    private var userSubscription: Disposable? = null

    val userGroups: Observable<List<UserGroupModel>> = Observable.zip(
        tenantUserGroupsSubject,
        personalUserGroupsSubject,
        localContactsSubject,
        Function3 {
                tenantUserGroups, personalUserGroups, localContactsSubject ->
            return@Function3 mergeUserGroups(
                tenantUserGroups,
                personalUserGroups,
                localContactsSubject
            )
        }
    )

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)

        destroy.onNext(Unit)
        destroy.onComplete()

        userSubscription?.dispose()
    }

    init {
        Log.d("Created UserGroupService")

        userSubscription = authStateManager.user
            .distinctUntilChanged { user -> user.id ?: "" }
            .takeUntil(destroy)
            .subscribe { user ->
                try {
                    Log.d("ContactDirectory user: " + user.name)
                    if ((user.id == null || user.id == AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER) && tenantUserGroupsSubject.value != null) {
                        tenantUserGroupsSubject.onNext(
                            listOf()
                        )

                        personalUserGroupsSubject.onNext(
                            listOf()
                        )
                    } else {
                        fetchTenantUserGroups()
                        fetchPersonalUserGroups()
                    }
                } catch (ex: Exception) {
                    Log.e(ex)
                }
            }
    }

    companion object {
        private const val TAG: String = "UserGroupService"
        private const val FAVORITES_GROUP_NAME: String = "CosmosPersonalUserGroupFavoritesName"

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
    }

    private fun fetchTenantUserGroups() {
        Log.d("Fetch tenant user groups...")

        apiClient.getUCGatewayService(
            dimensionsEnvironment!!.gatewayApiUri,
            AuthorizationServiceManager.getInstance(context).authorizationServiceInstance,
            AuthStateManager.getInstance(context)
        ).doGetTenantUserGroups()
            .enqueue(object : Callback<List<UserGroupModel>> {
                override fun onFailure(call: Call<List<UserGroupModel>>, t: Throwable) {
                    Log.e("Failed to fetch tenant user groups", t)
                }

                override fun onResponse(
                    call: Call<List<UserGroupModel>>,
                    response: Response<List<UserGroupModel>>
                ) {
                    Timber.d("Got tenant user groups from API")
                    response.body()?.let { tenantUserGroupsSubject.onNext(it) }
                }
            })
    }

    private fun fetchPersonalUserGroups() {
        Log.d("Fetch personal user groups...")

        apiClient.getUCGatewayService(
            dimensionsEnvironment!!.gatewayApiUri,
            AuthorizationServiceManager.getInstance(context).authorizationServiceInstance,
            AuthStateManager.getInstance(context)
        ).doGetPersonalUserGroups()
            .enqueue(object : Callback<List<UserGroupModel>> {
                override fun onFailure(call: Call<List<UserGroupModel>>, t: Throwable) {
                    Log.e("Failed to fetch personal user groups", t)
                }

                override fun onResponse(
                    call: Call<List<UserGroupModel>>,
                    response: Response<List<UserGroupModel>>
                ) {
                    Timber.d("Got tenant personal user groups from API")
                    response.body()?.let { personalUserGroupsSubject.onNext(it) }
                }
            })
    }

    private fun mergeUserGroups(
        tenantUserGroups: List<UserGroupModel>,
        personalUserGroups: List<UserGroupModel>,
        localContactsUserGroup: List<UserGroupModel>
    ): List<UserGroupModel> {
        val favoriteGroup = personalUserGroups.firstOrNull { x -> x.name == FAVORITES_GROUP_NAME }
        if (favoriteGroup != null) {
            favoriteGroup.name = context.resources.getString(R.string.contacts_favoritesGroup)
            favoriteGroup.isFavorites = true

            tenantUserGroups.forEach { group ->
                group.users.forEach { u ->
                    if (favoriteGroup.users.any { fu -> fu.id == u.id }) {
                        u.isInFavourites = true
                    }
                }
            }
        }

        val sortedUserGroups = (personalUserGroups + tenantUserGroups).sortedBy { x -> x.name }

        return sortedUserGroups + localContactsUserGroup
    }
}
