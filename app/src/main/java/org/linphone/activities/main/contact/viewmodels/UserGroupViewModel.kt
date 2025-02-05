package org.linphone.activities.main.contact.viewmodels

import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.activities.main.contact.data.ContactAdditionalData
import org.linphone.core.Friend
import org.linphone.core.SubscribePolicy
import org.linphone.environment.DimensionsEnvironmentService
import org.linphone.models.contact.ContactItemModel
import org.linphone.models.search.UserDataModel
import org.linphone.models.usergroup.GroupUserSummaryModel
import org.linphone.models.usergroup.UserGroupModel
import org.linphone.services.DirectoriesService

class UserGroupViewModel(
    userGroupModel: UserGroupModel
) {
    var id: String = ""
    var name: String = ""
    var friends: ArrayList<Friend> = arrayListOf()
    var isFavorites: Boolean = false

    companion object {
        const val FAVORITES_GROUP_NAME: String = "CosmosPersonalUserGroupFavoritesName"
        const val SEARCH_RESULTS_GROUP_NAME: String = "SearchResultsGroupName"
        const val ANDROID_CONTACTS_GROUP_NAME: String = "AndroidContactsGroupName"

        fun createFriendFromGroupUserSummaryModel(
            user: GroupUserSummaryModel,
            resourceBaseUrl: String?
        ): Friend {
            val friend = coreContext.core.createFriend()

            friend.refKey = user.id
            friend.name = user.name
            friend.organization = coreContext.context.resources.getString(
                R.string.contacts_user
            )

            friend.addPhoneNumber(user.presenceId)

            if (user.profileImagePath.isNotBlank()) {
                // TODO #25806 - When converted to ContactViewModel the photo doesn't appear to be taken into account
                friend.photo = resourceBaseUrl + "/images/" + user.profileImagePath
            }

            // Disable short term presence
            friend.isSubscribesEnabled = false
            friend.incSubscribePolicy = SubscribePolicy.SPDeny

            friend.userData = UserDataModel(null, user, arrayListOf(), user.isInFavourites)

            return friend
        }

        private fun createFriendFromContactItemModel(
            contactItemModel: ContactItemModel,
            resourceBaseUrl: String?
        ): Friend {
            val friend = coreContext.core.createFriend()

            val contactDirectoryModels = DirectoriesService.getInstance(coreContext.context).contactDirectoriesSubject.value
            val contactDirectoryModel = contactDirectoryModels?.singleOrNull { x -> x.id == contactItemModel.directoryId }

            val fieldDictionary = contactItemModel.fields.associateBy(
                { it.id },
                { it.value }
            )

            friend.refKey = contactItemModel.id
            friend.name = fieldDictionary[ContactItemModel.FULL_NAME] ?: ""

            if (contactDirectoryModel == null) {
                friend.organization = fieldDictionary[ContactItemModel.ORGANIZATION] ?: ""
            } else {
                friend.organization = "${contactDirectoryModel.name} | ${fieldDictionary[ContactItemModel.ORGANIZATION] ?: ""}"
            }

            val phoneNumbers = arrayListOf<String>()
            phoneNumbers.add(fieldDictionary[ContactItemModel.PHONE1] ?: "")
            phoneNumbers.add(fieldDictionary[ContactItemModel.PHONE2] ?: "")
            phoneNumbers.add(fieldDictionary[ContactItemModel.PHONE3] ?: "")
            phoneNumbers.add(fieldDictionary[ContactItemModel.PHONE4] ?: "")

            for (phoneNumber in phoneNumbers.filter { x -> x.isNotBlank() }) {
                friend.addPhoneNumber(phoneNumber)
            }

            if (fieldDictionary.keys.contains(ContactItemModel.AVATAR_URL)) {
                // TODO #25806 - When converted to ContactViewModel the photo doesn't appear to be taken into account
                friend.photo = resourceBaseUrl + "/images/" + fieldDictionary[ContactItemModel.AVATAR_URL]
            }

            // Disable short term presence
            friend.isSubscribesEnabled = false
            friend.incSubscribePolicy = SubscribePolicy.SPDeny

            var additionalData = arrayListOf<ContactAdditionalData>()
            if (contactDirectoryModel != null) {
                val fieldExclusions = arrayOf(
                    "fullName",
                    "companyName",
                    "phone1",
                    "phone2",
                    "phone3",
                    "phone4",
                    "avatar"
                )

                for (displayField in contactDirectoryModel.displayFields) {
                    if (fieldExclusions.contains(displayField)) continue

                    val fieldDefinition = contactDirectoryModel.fields.singleOrNull { x -> x.id == displayField }
                    if (fieldDefinition != null) {
                        val fieldValue = fieldDictionary[fieldDefinition.id]
                        if (fieldValue != null) {
                            additionalData.add(
                                ContactAdditionalData(
                                    fieldDefinition.id,
                                    fieldDefinition.name,
                                    fieldValue
                                )
                            )
                        }
                    }
                }

                val fieldSortOrder = arrayOf(
                    "title", "fullName", "companyName", "jobTitle",
                    "phone1", "phone2", "phone3", "phone4", "email", "crmId",
                    "field1", "field2", "field3", "field4", "field5",
                    "field6", "field7", "field8", "field9", "field10",
                    "avatar"
                )

                val comparator = Comparator<ContactAdditionalData> { item1, item2 ->
                    fieldSortOrder.indexOf(item1.key) - fieldSortOrder.indexOf(item2.key)
                }

                additionalData = ArrayList(additionalData.sortedWith(comparator))
            }

            friend.userData = UserDataModel(contactItemModel, null, additionalData, false)

            return friend
        }

        fun empty(): UserGroupViewModel {
            return UserGroupViewModel(UserGroupModel("empty", "empty", emptyList(), emptyList()))
        }
    }

    init {
        id = userGroupModel.id
        name = userGroupModel.name

        if (userGroupModel.id != ANDROID_CONTACTS_GROUP_NAME) {
            if (name == FAVORITES_GROUP_NAME) {
                name = coreContext.context.resources.getString(R.string.contacts_favoritesGroup)
                isFavorites = true
            }

            val resourceBaseUrl = DimensionsEnvironmentService
                .getInstance(coreContext.context)
                .getCurrentEnvironment()?.resourcesBlobUrl

            userGroupModel.users.forEach {
                friends.add(createFriendFromGroupUserSummaryModel(it, resourceBaseUrl))
            }

            userGroupModel.contacts.forEach {
                friends.add(createFriendFromContactItemModel(it, resourceBaseUrl))
            }
        }
    }

    fun count(): Int {
        return friends.count()
    }

    override fun toString(): String {
        return name
    }
}
