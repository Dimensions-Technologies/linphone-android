package org.linphone.services

import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.rx3.rxMaybe
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.models.CustomerLicence

/**
 * Whether the user can use Contacts (the Contacts and Favourites tabs, adding a contact): the
 * customer is licensed for Contacts and the user has the Contacts permission, as the web client's
 * nav menu decides. isAvailable emits nothing until both are known, so a screen can tell "not yet
 * known" (treated as unavailable) from a real "no".
 */
object ContactsAccessService {
    // The web client's UserPermissions.Contacts
    private const val PERMISSION = "customer.user.uc.contacts"

    @Volatile
    private var latest = false

    val isAvailable: Observable<Boolean> by lazy {
        UserService.getInstance(coreContext.context).user
            .switchMap { user ->
                // Empty when the licence can't be fetched, which keeps the last answer (as on the
                // web client, and Voicemail here)
                rxMaybe {
                    LicenceService.getLicence()?.hasFeature(CustomerLicence.CONTACTS_ENABLED)
                }
                    .map { licensed -> licensed && user.hasPermission(PERMISSION) }
                    .onErrorComplete()
                    .toObservable()
            }
            .distinctUntilChanged()
            .doOnNext { latest = it }
            .replay(1)
            // Connected straight away, so isAvailableNow is kept up to date from first use
            .autoConnect(0)
    }

    /** The latest answer, for a check that can't wait (e.g. an intent to open Contacts). */
    val isAvailableNow: Boolean
        get() {
            isAvailable // Initialising it starts following the user and licence
            return latest
        }
}
