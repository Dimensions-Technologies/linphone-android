/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.activities.main.fragments

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.findNavController
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.activities.GenericFragment
import org.linphone.activities.main.viewmodels.TabsViewModel
import org.linphone.activities.navigateToCallHistory
import org.linphone.activities.navigateToChatRooms
import org.linphone.activities.navigateToContacts
import org.linphone.activities.navigateToDialer
import org.linphone.activities.navigateToFavourites
import org.linphone.databinding.TabsFragmentBinding
import org.linphone.utils.Event

class TabsFragment : GenericFragment<TabsFragmentBinding>(), NavController.OnDestinationChangedListener {
    private lateinit var viewModel: TabsViewModel

    override fun getLayoutId(): Int = R.layout.tabs_fragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner
        useMaterialSharedAxisXForwardAnimation = false

        viewModel = requireActivity().run {
            ViewModelProvider(this)[TabsViewModel::class.java]
        }
        binding.viewModel = viewModel

        val tabsContainer = view.findViewById<RelativeLayout>(R.id.tabs_container)
        ViewCompat.setOnApplyWindowInsetsListener(tabsContainer) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())

            val contentDpHeight = 68f
            val contentPixelHeight = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                contentDpHeight,
                resources.displayMetrics
            )

            tabsContainer.layoutParams.height = (insets.bottom + contentPixelHeight).toInt()

            //  WindowInsetsCompat.CONSUMED
            windowInsets
        }

        binding.setFavouritesClickListener {
            when (findNavController().currentDestination?.id) {
                R.id.dialerFragment -> sharedViewModel.updateDialerAnimationsBasedOnDestination.value = Event(
                    R.id.favouritesFragment
                )
            }
            navigateToFavourites()
        }

        binding.setHistoryClickListener {
            when (findNavController().currentDestination?.id) {
                R.id.dimensionsContactsFragment, R.id.favouritesFragment -> sharedViewModel.updateContactsAnimationsBasedOnDestination.value = Event(
                    R.id.masterCallLogsFragment
                )
                R.id.dialerFragment -> sharedViewModel.updateDialerAnimationsBasedOnDestination.value = Event(
                    R.id.masterCallLogsFragment
                )
            }
            navigateToCallHistory()
        }

        binding.setContactsClickListener {
            when (findNavController().currentDestination?.id) {
                R.id.dialerFragment -> sharedViewModel.updateDialerAnimationsBasedOnDestination.value = Event(
                    R.id.dimensionsContactsFragment
                )
            }
            sharedViewModel.updateContactsAnimationsBasedOnDestination.value = Event(
                findNavController().currentDestination?.id ?: -1
            )
            navigateToContacts()
        }

        binding.setDialerClickListener {
            when (findNavController().currentDestination?.id) {
                R.id.dimensionsContactsFragment, R.id.favouritesFragment -> sharedViewModel.updateContactsAnimationsBasedOnDestination.value = Event(
                    R.id.dialerFragment
                )
            }
            sharedViewModel.updateDialerAnimationsBasedOnDestination.value = Event(
                findNavController().currentDestination?.id ?: -1
            )
            navigateToDialer()
        }

        binding.setChatClickListener {
            when (findNavController().currentDestination?.id) {
                R.id.dimensionsContactsFragment, R.id.favouritesFragment -> sharedViewModel.updateContactsAnimationsBasedOnDestination.value = Event(
                    R.id.masterChatRoomsFragment
                )
                R.id.dialerFragment -> sharedViewModel.updateDialerAnimationsBasedOnDestination.value = Event(
                    R.id.masterChatRoomsFragment
                )
            }
            navigateToChatRooms()
        }

        binding.setVoicemailClickListener {
            viewModel.dialVoicemail()
        }
    }

    override fun onStart() {
        super.onStart()
        findNavController().addOnDestinationChangedListener(this)
    }

    override fun onStop() {
        findNavController().removeOnDestinationChangedListener(this)
        super.onStop()
    }

    override fun onDestinationChanged(
        controller: NavController,
        destination: NavDestination,
        arguments: Bundle?
    ) {
        // Selector position (a MotionLayout state) for each tab destination
        val state = when (destination.id) {
            R.id.favouritesFragment -> R.id.favourites_list
            R.id.dimensionsContactsFragment -> R.id.contacts
            R.id.masterCallLogsFragment -> R.id.call_history
            R.id.dialerFragment -> R.id.dialer
            else -> null
        }
        if (state != null) {
            if (corePreferences.enableAnimations) {
                binding.motionLayout.transitionToState(state)
            } else {
                binding.motionLayout.setTransition(state, state)
            }
        }

        // Highlight the appropriate tab
        // First reset all
        val tabChat = view?.findViewById<ImageView>(R.id.chat)
        val tabFavourites = view?.findViewById<ImageView>(R.id.favourites)
        val tabContacts = view?.findViewById<ImageView>(R.id.contacts)
        val tabDialpad = view?.findViewById<ImageView>(R.id.dialer)
        val tabHistory = view?.findViewById<ImageView>(R.id.history)
        if (tabChat != null) tabChat.isSelected = false
        if (tabFavourites != null) tabFavourites.isSelected = false
        if (tabContacts != null) tabContacts.isSelected = false
        if (tabDialpad != null) tabDialpad.isSelected = false
        if (tabHistory != null) tabHistory.isSelected = false

        when (destination.id) {
            R.id.dialerFragment -> if (tabDialpad != null) tabDialpad.isSelected = true
            R.id.favouritesFragment -> if (tabFavourites != null) tabFavourites.isSelected = true
            R.id.dimensionsContactsFragment -> if (tabContacts != null) tabContacts.isSelected = true
            R.id.masterCallLogsFragment -> if (tabHistory != null) tabHistory.isSelected = true
            R.id.masterChatRoomsFragment -> if (tabChat != null) tabChat.isSelected = true
        }
    }
}
