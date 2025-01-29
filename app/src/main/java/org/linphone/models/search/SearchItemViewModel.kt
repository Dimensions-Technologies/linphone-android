package org.linphone.models.search

import org.linphone.models.contact.ContactItemModel
import org.linphone.models.usergroup.GroupUserSummaryModel

data class SearchItemViewModel(
    val contact: ContactItemModel?,
    val user: GroupUserSummaryModel?
)
