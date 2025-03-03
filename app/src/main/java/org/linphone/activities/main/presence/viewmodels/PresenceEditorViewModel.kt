package org.linphone.activities.main.presence.viewmodels

import androidx.databinding.ObservableField
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.activities.main.settings.SettingListenerStub
import org.linphone.activities.main.settings.viewmodels.AccountSettingsViewModel
import org.linphone.core.Account
import org.linphone.core.ConsolidatedPresence
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.RegistrationState
import org.linphone.models.UserInfo
import org.linphone.utils.LinphoneUtils

class PresenceEditorViewModel() : ViewModel() {
    val userImageUrl = ObservableField<String>()
    val defaultAccountFound = MutableLiveData<Boolean>()
    val defaultAccountAvatar = MutableLiveData<String>()
    val defaultAccountViewModel = MutableLiveData<AccountSettingsViewModel>()

    val accounts = MutableLiveData<ArrayList<AccountSettingsViewModel>>()

    val user = ObservableField<UserInfo>()

    val presenceStatus = MutableLiveData<ConsolidatedPresence>()

    val statusMessage = MutableLiveData<String>()
    val callWaiting = MutableLiveData<Boolean>()

    lateinit var accountsSettingsListener: SettingListenerStub

    private val listener: CoreListenerStub = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String
        ) {
            // +1 is for the default account, otherwise this will trigger every time
            if (accounts.value.isNullOrEmpty() ||
                coreContext.core.accountList.size != accounts.value.orEmpty().size + 1
            ) {
                // Only refresh the list if an account has been added or removed
                updateAccountsList()
            }
        }
    }

    init {
        defaultAccountFound.value = false
        defaultAccountAvatar.value = corePreferences.defaultAccountAvatarPath

        coreContext.core.addListener(listener)
        updateAccountsList()
        refreshConsolidatedPresence()
    }

    fun refreshConsolidatedPresence() {
        presenceStatus.value = coreContext.core.consolidatedPresence
    }

    fun updateAccountsList() {
        defaultAccountFound.value = false // Do not assume a default account will still be found
        defaultAccountViewModel.value?.destroy()
        accounts.value.orEmpty().forEach(AccountSettingsViewModel::destroy)

        val list = arrayListOf<AccountSettingsViewModel>()
        val defaultAccount = coreContext.core.defaultAccount
        if (defaultAccount != null) {
            val defaultViewModel = AccountSettingsViewModel(defaultAccount)
            defaultViewModel.accountsSettingsListener = object : SettingListenerStub() {
                override fun onAccountClicked(identity: String) {
                    accountsSettingsListener.onAccountClicked(identity)
                }
            }
            defaultAccountViewModel.value = defaultViewModel
            defaultAccountFound.value = true
        }

        for (account in LinphoneUtils.getAccountsNotHidden()) {
            if (account != coreContext.core.defaultAccount) {
                val viewModel = AccountSettingsViewModel(account)
                viewModel.accountsSettingsListener = object : SettingListenerStub() {
                    override fun onAccountClicked(identity: String) {
                        accountsSettingsListener.onAccountClicked(identity)
                    }
                }
                list.add(viewModel)
            }
        }
        accounts.value = list
    }

    override fun onCleared() {
        defaultAccountViewModel.value?.destroy()
        super.onCleared()
    }
}
