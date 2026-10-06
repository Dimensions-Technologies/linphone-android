package org.linphone.activities.main.contact.viewmodels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Friend
import org.linphone.models.contact.ContactDirectoryModel
import org.linphone.models.contact.ContactDirectoryRules
import org.linphone.models.contact.ContactItemModel
import org.linphone.models.search.UserDataModel
import org.linphone.services.DirectoriesService
import org.linphone.services.PhoneFormatterService
import org.linphone.utils.Event

class DirectoryContactEditorViewModelFactory(
    private val directory: ContactDirectoryModel,
    private val contact: ContactItemModel?,
    private val photoUrl: String?,
    private val isFavourite: Boolean,
    private val phoneNumber: String?
) : ViewModelProvider.NewInstanceFactory() {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DirectoryContactEditorViewModel(
            directory,
            contact,
            photoUrl,
            isFavourite,
            phoneNumber
        ) as T
    }
}

/**
 * Adds or edits a contact in a gateway contact directory. The form's fields come from the directory,
 * so values are kept by field id.
 */
class DirectoryContactEditorViewModel(
    val directory: ContactDirectoryModel,
    private val contact: ContactItemModel?,
    photoUrl: String?,
    isFavourite: Boolean,
    phoneNumber: String?
) : ViewModel() {
    private val directoriesService = DirectoriesService.getInstance(coreContext.context)

    val isEditing = contact != null

    val nameField = ContactDirectoryRules.nameField(directory)
    val primaryPhoneField = ContactDirectoryRules.primaryPhoneField(directory)
    val additionalFields = ContactDirectoryRules.additionalFields(directory)
    val hasInternalNumberField = ContactDirectoryRules.blfField(directory) != null

    // Field id to current value
    val values: MutableMap<String, String> = directory.fields.associate { it.id to "" }.toMutableMap()

    val isFavourite = MutableLiveData(isFavourite)
    private val wasFavourite = contact != null && isFavourite
    val isInternalNumber = MutableLiveData(false)

    // Avatar: the existing picture, a newly picked one (PNG bytes), or removed
    val avatarUrl = MutableLiveData(photoUrl)
    val newAvatar = MutableLiveData<ByteArray?>(null)
    private var avatarChanged = false

    val saving = MutableLiveData(false)
    val nameError = MutableLiveData(false)
    val phoneError = MutableLiveData(false)

    val messageEvent = MutableLiveData<Event<Int>>()

    // The saved contact as a Friend (null after a delete), for the details screen to show.
    val doneEvent = MutableLiveData<Event<Friend?>>()

    init {
        if (contact != null) {
            contact.fields.forEach { values[it.id] = it.value }
            isInternalNumber.value = ContactDirectoryRules.isInternalNumber(directory, contact)
        } else if (primaryPhoneField != null && !phoneNumber.isNullOrBlank()) {
            values[primaryPhoneField.id] = phoneNumber
        }
    }

    fun toggleFavourite() {
        isFavourite.value = isFavourite.value != true
    }

    fun setAvatar(png: ByteArray) {
        newAvatar.value = png
        avatarChanged = true
    }

    fun removeAvatar() {
        newAvatar.value = null
        avatarUrl.value = null
        avatarChanged = true
    }

    fun hasAvatar() = newAvatar.value != null || !avatarUrl.value.isNullOrEmpty()

    fun save() {
        if (saving.value == true) return

        nameError.value = !ContactDirectoryRules.isNameOrCompanyValid(directory, values)
        phoneError.value = !ContactDirectoryRules.isPrimaryPhoneValid(directory, values)
        if (nameError.value == true || phoneError.value == true) return

        val countryCode = PhoneFormatterService.getInstance(coreContext.context).getPbxCountryCode()
        val request = ContactDirectoryRules.buildRequest(
            directory,
            contact?.id.orEmpty(),
            values,
            isInternalNumber.value == true,
            countryCode
        )
        val avatar = if (avatarChanged) newAvatar.value else null
        val removeAvatar = avatarChanged && newAvatar.value == null

        saving.value = true
        viewModelScope.launch {
            val saved = if (contact != null) {
                directoriesService.updateContact(request, avatar, removeAvatar)
            } else {
                directoriesService.createContact(request, avatar)
            }
            saving.value = false

            if (saved == null) {
                messageEvent.value = Event(R.string.contact_directory_save_failed)
                return@launch
            }

            val favourite = isFavourite.value == true
            if (favourite && !wasFavourite) {
                directoriesService.addContactToFavourites(saved)
            } else if (!favourite && wasFavourite) {
                directoriesService.removeContactFromFavourites(saved.id)
            }

            val friend = UserGroupViewModel.createFriendFromContactItemModel(saved)
            (friend.userData as? UserDataModel)?.isInFavourites = favourite
            saved.isInFavourites = favourite
            doneEvent.value = Event(friend)
        }
    }

    fun delete() {
        val contact = contact ?: return
        if (saving.value == true) return

        saving.value = true
        viewModelScope.launch {
            val deleted = directoriesService.deleteContact(directory.id, contact.id)
            saving.value = false

            if (!deleted) {
                messageEvent.value = Event(R.string.contact_directory_delete_failed)
                return@launch
            }
            doneEvent.value = Event(null)
        }
    }
}
