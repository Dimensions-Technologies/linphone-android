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
package org.linphone.activities.main.history.fragments

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialSharedAxis
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.activities.*
import org.linphone.activities.clearDisplayedCallHistory
import org.linphone.activities.main.MainActivity
import org.linphone.activities.main.fragments.MasterFragment
import org.linphone.activities.main.history.adapters.CallLogsListAdapter
import org.linphone.activities.main.history.adapters.VoicemailListAdapter
import org.linphone.activities.main.history.data.GroupedCallLogData
import org.linphone.activities.main.history.viewmodels.CallLogsFilter
import org.linphone.activities.main.history.viewmodels.CallLogsListViewModel
import org.linphone.activities.main.history.viewmodels.VoicemailListViewModel
import org.linphone.activities.main.history.viewmodels.VoicemailPlayState
import org.linphone.activities.main.viewmodels.TabsViewModel
import org.linphone.core.ConferenceInfo
import org.linphone.databinding.HistoryMasterFragmentBinding
import org.linphone.models.callhistory.PbxType
import org.linphone.models.voicemail.VoicemailMessage
import org.linphone.services.CallHistoryService
import org.linphone.services.DirectoriesService
import org.linphone.services.UserService
import org.linphone.services.VoicemailAudioException
import org.linphone.services.VoicemailBoxService
import org.linphone.utils.*
import org.linphone.utils.Log

class MasterCallLogsFragment : MasterFragment<HistoryMasterFragmentBinding, CallLogsListAdapter>() {
    val callHistoryService = CallHistoryService.getInstance(coreContext.context)
    override val dialogConfirmationMessageBeforeRemoval = R.plurals.history_delete_dialog
    private lateinit var listViewModel: CallLogsListViewModel
    private lateinit var voicemailViewModel: VoicemailListViewModel

    // Plays voicemails in place, above the Voicemail list
    private var voicemailPlayer: ExoPlayer? = null

    // History and Missed each open at their top
    private var listFilter: CallLogsFilter? = null
    private var scrollToTopOnNextList = false
    private var voicemailLoadJob: Job? = null

    private var permissionSubscription: Disposable? = null

    private val observer = object : RecyclerView.AdapterDataObserver() {
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            if (positionStart == 0 && itemCount == 1) {
                scrollToTop()
            }
        }
    }

    override fun getLayoutId(): Int = R.layout.history_master_fragment

    override fun onDestroyView() {
        binding.callLogsList.adapter = null
        binding.voicemailList.adapter = null
        releaseVoicemailPlayer()
        adapter.unregisterAdapterDataObserver(observer)

        permissionSubscription?.dispose()
        permissionSubscription = null

        super.onDestroyView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useMaterialSharedAxisXForwardAnimation = false

        if (corePreferences.enableAnimations) {
            val portraitOrientation = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val axis = if (portraitOrientation) MaterialSharedAxis.X else MaterialSharedAxis.Y
            enterTransition = MaterialSharedAxis(axis, false)
            reenterTransition = MaterialSharedAxis(axis, false)
            returnTransition = MaterialSharedAxis(axis, true)
            exitTransition = MaterialSharedAxis(axis, true)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner

        listViewModel = ViewModelProvider(this)[CallLogsListViewModel::class.java]
        binding.viewModel = listViewModel

        voicemailViewModel = ViewModelProvider(this)[VoicemailListViewModel::class.java]
        binding.voicemailViewModel = voicemailViewModel
        setUpVoicemail()

        // Opened on the Voicemail tab (from the voicemail button)
        if (arguments?.getString(TAB_ARGUMENT) == TAB_VOICEMAIL) {
            arguments?.remove(TAB_ARGUMENT)
            listViewModel.showVoicemail()
        }

        /* Shared view model & sliding pane related */

        setUpSlidingPane(binding.slidingPane)

        sharedViewModel.layoutChangedEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume {
                sharedViewModel.isSlidingPaneSlideable.value = binding.slidingPane.isSlideable
                if (binding.slidingPane.isSlideable) {
                    val navHostFragment =
                        childFragmentManager.findFragmentById(R.id.history_nav_container) as NavHostFragment
                    if (navHostFragment.navController.currentDestination?.id == R.id.emptyCallHistoryFragment) {
                        Log.i(
                            "[History] Foldable device has been folded, closing side pane with empty fragment"
                        )
                        binding.slidingPane.closePane()
                    }
                }
            }
        }

        /* End of shared view model & sliding pane related */

        _adapter = CallLogsListAdapter(listSelectionViewModel, viewLifecycleOwner, context)
        // SubmitList is done on a background thread
        // We need this adapter data observer to know when to scroll
        adapter.registerAdapterDataObserver(observer)
        binding.callLogsList.setHasFixedSize(true)
        binding.callLogsList.adapter = adapter

        binding.setEditClickListener {
            listSelectionViewModel.isEditionEnabled.value = true
        }

        val layoutManager = LinearLayoutManager(requireContext())
        binding.callLogsList.layoutManager = layoutManager

        val hasPlaybackPermission = UserService.getInstance(requireContext()).user.map { u ->
            u.hasPermission(
                "recording.play"
            )
        }
        permissionSubscription = hasPlaybackPermission.subscribe(
            { hasPermission -> listViewModel.hasPlaybackPermission.postValue(hasPermission) },
            { error -> Log.e(error) }
        )

        // Swipe action
//        val swipeConfiguration = RecyclerViewSwipeConfiguration()
//        val white = ContextCompat.getColor(requireContext(), R.color.white_color)
//
//        swipeConfiguration.rightToLeftAction = RecyclerViewSwipeConfiguration.Action(
//            requireContext().getString(R.string.dialog_delete),
//            white,
//            ContextCompat.getColor(requireContext(), R.color.red_color)
//        )
//        val swipeListener = object : RecyclerViewSwipeListener {
//            override fun onLeftToRightSwipe(viewHolder: RecyclerView.ViewHolder) {}
//
//            override fun onRightToLeftSwipe(viewHolder: RecyclerView.ViewHolder) {
//                val viewModel = DialogViewModel(getString(R.string.history_delete_one_dialog))
//                viewModel.showIcon = true
//                viewModel.iconResource = R.drawable.dialog_delete_icon
//                val dialog: Dialog = DialogUtils.getDialog(requireContext(), viewModel)
//
//                val index = viewHolder.bindingAdapterPosition
//                if (index < 0 || index >= adapter.currentList.size) {
//                    Log.e("[History] Index is out of bound, can't delete call log")
//                } else {
//                    viewModel.showCancelButton {
//                        adapter.notifyItemChanged(index)
//                        dialog.dismiss()
//                    }
//
//                    viewModel.showDeleteButton(
//                        {
//                            val deletedCallGroup = adapter.currentList[index]
//                            listViewModel.deleteCallLogGroup(deletedCallGroup)
//                            if (!binding.slidingPane.isSlideable &&
//                                deletedCallGroup.lastCallLogId == sharedViewModel.selectedCallLogGroup.value?.lastCallLogId
//                            ) {
//                                Log.i(
//                                    "[History] Currently displayed history has been deleted, removing detail fragment"
//                                )
//                                clearDisplayedCallHistory()
//                            }
//                            dialog.dismiss()
//                        },
//                        getString(R.string.dialog_delete)
//                    )
//                }
//
//                dialog.show()
//            }
//        }
//
//        RecyclerViewSwipeUtils(ItemTouchHelper.LEFT, swipeConfiguration, swipeListener)
//            .attachToRecyclerView(binding.callLogsList)

        // Divider between items
//        binding.callLogsList.addItemDecoration(
//            AppUtils.getDividerDecoration(requireContext(), layoutManager)
//        )

        // Displays formatted date header
//        val headerItemDecoration = RecyclerViewHeaderDecoration(requireContext(), adapter)
//        binding.callLogsList.addItemDecoration(headerItemDecoration)

        listViewModel.callLogs.observe(
            viewLifecycleOwner
        ) { callLogs ->
            adapter.submitList(callLogs) {
                // A different tab's list starts at its top, not wherever the last one was scrolled
                if (scrollToTopOnNextList) {
                    scrollToTopOnNextList = false
                    scrollToTop()
                }
            }
        }

        listViewModel.contactsUpdatedEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }

        adapter.showCallLogContextMenuEvent.observe(viewLifecycleOwner) {
            it.consume { call ->
                binding.contextItemViewModel = call
                sharedViewModel.selectedHistoryItem.postValue(call)
                listViewModel.showContextMenu(call)
            }
        }

        listViewModel.makeCallEvent.observe(
            viewLifecycleOwner
        ) { it ->
            it.consume { callLog ->

                if (callLog.call.pbxType == PbxType.Teams
                ) {
                    if (!callLog.call.isConference) {
                        // FixMe - This sends the intent and then returns control to this app, this isn't valid behaviour
                        // context?.let { it -> UrlHelper.startTeamsCall(it, callLog.number) }
                    }
                } else {
                    val conferenceInfo = callLog.conferenceInfo
                    when {
                        conferenceInfo != null -> {
                            if (conferenceInfo.state == ConferenceInfo.State.Cancelled) {
                                var snackRes = R.string.conference_scheduled_cancelled_by_organizer

                                val organizer = conferenceInfo.organizer
                                if (organizer != null) {
                                    val localAccount =
                                        coreContext.core.accountList.find { account ->
                                            val address = account.params.identityAddress
                                            address != null && organizer.weakEqual(address)
                                        }
                                    if (localAccount != null) {
                                        snackRes = R.string.conference_scheduled_cancelled_by_me
                                    }
                                }

                                val activity = requireActivity() as MainActivity
                                activity.showSnackBar(snackRes)
                            } else {
                                navigateToConferenceWaitingRoom(
                                    conferenceInfo.uri?.asStringUriOnly().orEmpty(),
                                    conferenceInfo.subject
                                )
                            }
                        }

                        else -> {
                            val cleanAddress = LinphoneUtils.getCleanedAddress(
                                callLog.remoteAddress
                            )
                            val localAddress = callLog.localAddress
                            Log.i(
                                "[History] Starting call to ${cleanAddress.asStringUriOnly()} with local address ${localAddress.asStringUriOnly()}"
                            )

                            coreContext.startCallOrTransfer(
                                cleanAddress,
                                localAddress = localAddress
                            )
                        }
                    }
                }
            }
        }

        listViewModel.playRecordingEvent.observe(
            viewLifecycleOwner
        ) { it ->
            it.consume { call ->
                navigateToCallDetail(binding.slidingPane, call.call.documentId, autoPlay = true)
            }
        }

        listViewModel.viewDetailsEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume { call -> navigateToCallDetail(binding.slidingPane, call.call.documentId) }
        }

        listViewModel.addContactEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume { call -> addContact(call.number) }
        }

        callHistoryService.updateMissedCallTimestamp()

        coreContext.core.resetMissedCallsCount()
        coreContext.notificationsManager.dismissMissedCallNotification()

        onChildPanelClosed.observe(viewLifecycleOwner) {
            sharedViewModel.selectedHistoryItem.postValue(null)
        }
    }

    override fun onResume() {
        super.onResume()

        val tabsViewModel = requireActivity().run {
            ViewModelProvider(this)[TabsViewModel::class.java]
        }
        tabsViewModel.updateMissedCallCount()
    }

    override fun deleteItems(indexesOfItemToDelete: ArrayList<Int>) {
        val list = ArrayList<GroupedCallLogData>()
        var closeSlidingPane = false
        for (index in indexesOfItemToDelete) {
            val callLogGroup = adapter.currentList[index]
            list.add(callLogGroup)

            if (callLogGroup.lastCallLogId == sharedViewModel.selectedHistoryItem.value?.callId) {
                closeSlidingPane = true
            }
        }
        listViewModel.deleteCallLogGroups(list)

        if (!binding.slidingPane.isSlideable && closeSlidingPane) {
            Log.i(
                "[History] Currently displayed history has been deleted, removing detail fragment"
            )
            clearDisplayedCallHistory()
        }
    }

    private fun setUpVoicemail() {
        val voicemailAdapter = VoicemailListAdapter(voicemailViewModel, viewLifecycleOwner)
        binding.voicemailList.layoutManager = LinearLayoutManager(requireContext())
        binding.voicemailList.adapter = voicemailAdapter
        // Rows change in place (expanded, playing); the default animation makes them flicker
        binding.voicemailList.itemAnimator = null

        voicemailViewModel.items.observe(viewLifecycleOwner) { voicemailAdapter.submitList(it) }

        sharedViewModel.callHistoryTabEvent.observe(viewLifecycleOwner) {
            it.consume { filter ->
                when (filter) {
                    CallLogsFilter.VOICEMAIL -> listViewModel.showVoicemail()
                    else -> listViewModel.showAllCallLogs()
                }
            }
        }

        listViewModel.filter.observe(viewLifecycleOwner) { filter ->
            // Only History and Missed have their own list (Voicemail leaves it as it was)
            if (filter != CallLogsFilter.VOICEMAIL) {
                if (listFilter != null && filter != listFilter) scrollToTopOnNextList = true
                listFilter = filter
            }
            // Not cleared as the screen goes: the tabs only read it while the call history is
            // shown, and each call history screen sets it as it opens
            sharedViewModel.isVoicemailTabShown.value = filter == CallLogsFilter.VOICEMAIL
            // Leaving the tab leaves no controls on screen, so stop what it was playing
            if (filter != CallLogsFilter.VOICEMAIL) stopVoicemail()
        }

        voicemailViewModel.playEvent.observe(viewLifecycleOwner) {
            it.consume { (boxId, message) -> playVoicemail(boxId, message) }
        }

        voicemailViewModel.togglePlaybackEvent.observe(viewLifecycleOwner) {
            it.consume {
                voicemailPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                }
            }
        }

        voicemailViewModel.confirmDeleteEvent.observe(viewLifecycleOwner) {
            it.consume { (boxId, message) -> confirmDeleteVoicemail(boxId, message) }
        }

        voicemailViewModel.viewDetailsEvent.observe(viewLifecycleOwner) {
            it.consume { (documentId, boxId, mediaId) ->
                // The voicemail plays there instead, so take it out of the list's player
                stopVoicemail()
                navigateToCallDetail(binding.slidingPane, documentId, boxId, mediaId)
            }
        }

        voicemailViewModel.callEvent.observe(viewLifecycleOwner) {
            it.consume { number -> coreContext.startCall(number) }
        }

        voicemailViewModel.messageEvent.observe(viewLifecycleOwner) {
            it.consume { message -> Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show() }
        }
    }

    @OptIn(UnstableApi::class)
    private fun voicemailPlayer(): ExoPlayer = voicemailPlayer ?: ExoPlayer.Builder(
        requireContext()
    ).build().also { player ->
        // Reaching the end of a message doesn't start another
        player.pauseAtEndOfMediaItems = true
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateVoicemailPlayState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateVoicemailPlayState()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(error, "[Voicemail] Playback failed")
                showVoicemailError(getString(R.string.voicemail_play_failed))
                stopVoicemail()
            }
        })
        binding.voicemailPlayer.player = player
        binding.voicemailPlayer.showController()
        voicemailPlayer = player
    }

    private fun updateVoicemailPlayState() {
        val player = voicemailPlayer ?: return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        voicemailViewModel.onPlayerStateChanged(
            mediaId,
            if (player.isPlaying) VoicemailPlayState.PLAYING else VoicemailPlayState.PAUSED
        )
    }

    private fun playVoicemail(boxId: String, message: VoicemailMessage) {
        voicemailLoadJob?.cancel()
        voicemailPlayer?.stop()
        voicemailViewModel.onPlayerStateChanged(message.mediaId, VoicemailPlayState.LOADING)

        voicemailLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = VoicemailBoxService.getAudio(boxId, message.mediaId)
                val player = voicemailPlayer()
                player.setMediaItem(
                    MediaItem.Builder().setUri(file.toUri()).setMediaId(message.mediaId).build()
                )
                player.prepare()
                player.play()
                binding.voicemailPlayer.visibility = View.VISIBLE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(e, "[Voicemail] Failed to fetch the audio of ${message.mediaId}")
                showVoicemailError(
                    when ((e as? VoicemailAudioException)?.code) {
                        404 -> getString(R.string.voicemail_audio_not_found)
                        402 -> getString(R.string.voicemail_audio_no_credit)
                        else -> getString(R.string.voicemail_play_failed)
                    }
                )
                if (voicemailViewModel.currentMediaId == message.mediaId) stopVoicemail()
            }
        }
    }

    private fun showVoicemailError(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
    }

    private fun stopVoicemail() {
        voicemailLoadJob?.cancel()
        voicemailPlayer?.stop()
        voicemailPlayer?.clearMediaItems()
        if (isBindingAvailable()) binding.voicemailPlayer.visibility = View.GONE
        voicemailViewModel.onPlayerStateChanged(null, VoicemailPlayState.NONE)
    }

    private fun releaseVoicemailPlayer() {
        voicemailLoadJob?.cancel()
        voicemailPlayer?.release()
        voicemailPlayer = null
        voicemailViewModel.onPlayerStateChanged(null, VoicemailPlayState.NONE)
    }

    private fun confirmDeleteVoicemail(boxId: String, message: VoicemailMessage) {
        val name = voicemailViewModel.displayName(message)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.voicemail_delete_title)
            .setMessage(
                if (name.isNullOrEmpty()) {
                    getString(R.string.voicemail_delete_confirm)
                } else {
                    getString(R.string.voicemail_delete_confirm_from, name)
                }
            )
            .setPositiveButton(R.string.voicemail_delete) { _, _ ->
                // It's about to leave the list, so take it out of the player
                if (voicemailViewModel.currentMediaId == message.mediaId) stopVoicemail()
                voicemailViewModel.delete(
                    boxId,
                    message,
                    name ?: getString(R.string.voicemail_this_message)
                )
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun scrollToTop() {
        binding.callLogsList.scrollToPosition(0)
    }

    // Adds the number to a contact directory: the only one the user can contribute to, or the one
    // they pick (Personal first).
    private fun addContact(number: String) {
        val directories = DirectoriesService.getInstance(requireContext()).contributorDirectories()
        when (directories.size) {
            0 -> return
            1 -> navigateToDirectoryContactEditor(directories[0].id, phoneNumber = number)
            else -> AlertDialog.Builder(requireContext())
                .setTitle(R.string.contact_directory_choose_directory)
                .setItems(directories.map { it.name }.toTypedArray()) { _, which ->
                    navigateToDirectoryContactEditor(directories[which].id, phoneNumber = number)
                }
                .show()
        }
    }

    companion object {
        const val TAB_ARGUMENT = "Tab"
        const val TAB_VOICEMAIL = "voicemail"
    }
}
