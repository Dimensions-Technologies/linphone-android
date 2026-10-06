package org.linphone.services

import android.content.Context
import io.reactivex.rxjava3.disposables.Disposable
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.activities.main.contact.viewmodels.UserGroupViewModel
import org.linphone.authentication.AuthStateManager
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.search.UserDataModel
import org.linphone.utils.Log

/**
 * Keeps BLF subscriptions for the contacts whose line status is shown (see
 * ContactDirectoryRules.blfSubscriptions), as the web client's blf-subscription-manager does.
 * Recomputed whenever the contact groups are refetched, which follows any change to favourites or
 * to directory contacts.
 */
object ContactBlfManager {
    // Contact id to the number subscribed for it
    private val tracked = mutableMapOf<String, String>()
    private var subscription: Disposable? = null

    fun start(context: Context, userGroupService: UserGroupService) {
        if (subscription != null) return
        val authStateManager = AuthStateManager.getInstance(context)
        subscription = userGroupService.userGroups
            .subscribe(
                { groups ->
                    val desired = desired(
                        groups,
                        userGroupService.favouritesGroup,
                        authStateManager.getUser().id
                    )
                    // Groups can be emitted from a background thread; BLF runs on the core's thread
                    coreContext.handler.post { reconcile(desired) }
                },
                { e -> Log.e("[BLF] Contact groups failed", e) }
            )
    }

    private fun desired(
        groups: List<UserGroupViewModel>,
        favourites: UserGroupViewModel?,
        userId: String?
    ): Map<String, String> {
        val favouriteIds = favourites?.friends.orEmpty().mapNotNull {
            UserGroupService.gatewayIdOf(
                it
            )
        }.toSet()
        val directories = groups.mapNotNull { group ->
            val directory = group.directory ?: return@mapNotNull null
            directory to group.friends.mapNotNull { (it.userData as? UserDataModel)?.contact }
        }
        return ContactDirectoryRules.blfSubscriptions(directories, favouriteIds, userId)
    }

    private fun reconcile(desired: Map<String, String>) {
        for ((contactId, phone) in tracked.toMap()) {
            if (desired[contactId] != phone) {
                tracked.remove(contactId)
                BlfService.removeKey(phone)
            }
        }
        for ((contactId, phone) in desired) {
            if (tracked[contactId] != phone) {
                tracked[contactId] = phone
                BlfService.addKey(phone)
            }
        }
    }
}
