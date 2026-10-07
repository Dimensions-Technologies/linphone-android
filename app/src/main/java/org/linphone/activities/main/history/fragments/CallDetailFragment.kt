package org.linphone.activities.main.history.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.os.bundleOf
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import org.linphone.R
import org.linphone.activities.GenericFragment
import org.linphone.activities.main.history.GatewayAudioDataSource
import org.linphone.activities.main.history.GatewayAudioException
import org.linphone.activities.main.history.adapters.CallDetailAdapter
import org.linphone.activities.main.history.viewmodels.CallDetailViewModel
import org.linphone.activities.main.history.viewmodels.RecordingInfoViewModel
import org.linphone.databinding.FragmentCallDetailBinding
import org.linphone.databinding.RecordingInfoCellBinding
import org.linphone.models.callsession.CallDetailTab
import org.linphone.utils.Log

/** The call detail page (see CallDetailViewModel), in the call history's side pane. */
class CallDetailFragment : GenericFragment<FragmentCallDetailBinding>() {
    private lateinit var viewModel: CallDetailViewModel
    private lateinit var adapter: CallDetailAdapter
    private var player: ExoPlayer? = null
    private var recordingItems: List<RecordingInfoViewModel> = emptyList()
    private var autoPlayDone = false

    // The transcript follows the playback until the user scrolls it themselves
    private var autoScroll = true
    private var programmaticScroll = false

    private val handler = Handler(Looper.getMainLooper())
    private val positionPoller = object : Runnable {
        override fun run() {
            val player = player ?: return
            viewModel.onPlaybackPosition(
                if (player.isPlaying || player.playbackState == Player.STATE_READY) player.currentPosition / 1000.0 else null
            )
            handler.postDelayed(this, POSITION_POLL_MS)
        }
    }

    override fun getLayoutId(): Int = R.layout.fragment_call_detail

    @OptIn(UnstableApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.lifecycleOwner = viewLifecycleOwner

        viewModel = ViewModelProvider(this)[CallDetailViewModel::class.java]
        binding.viewModel = viewModel

        binding.close.setOnClickListener { goBack() }

        adapter = CallDetailAdapter { text -> copy(text) }
        val layoutManager = LinearLayoutManager(requireContext())
        binding.rows.layoutManager = layoutManager
        binding.rows.adapter = adapter
        binding.rows.itemAnimator = null
        binding.rows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                // The user scrolling while it plays stops the transcript following it
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && !programmaticScroll && player?.isPlaying == true) {
                    setAutoScroll(false)
                }
                if (newState == RecyclerView.SCROLL_STATE_IDLE) programmaticScroll = false
            }
        })
        binding.follow.setOnClickListener {
            setAutoScroll(true)
            scrollToHighlight()
        }

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                (tab.tag as? CallDetailTab)?.let { viewModel.selectTab(it) }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        viewModel.tabs.observe(viewLifecycleOwner) { tabs -> showTabs(tabs) }
        viewModel.selectedTab.observe(viewLifecycleOwner) { tab ->
            for (i in 0 until binding.tabs.tabCount) {
                val item = binding.tabs.getTabAt(i) ?: continue
                if (item.tag == tab && !item.isSelected) item.select()
            }
            binding.follow.visibility = View.GONE
        }
        viewModel.rows.observe(viewLifecycleOwner) { rows -> adapter.submitList(rows) }
        viewModel.timeline.observe(viewLifecycleOwner) { rows -> binding.timeline.rows = rows }
        viewModel.playlist.observe(viewLifecycleOwner) { playlist -> setPlaylist(playlist) }
        viewModel.recordings.observe(viewLifecycleOwner) { recordings -> showRecordings(recordings) }
        viewModel.highlightedIndex.observe(viewLifecycleOwner) { index ->
            adapter.highlightedIndex = index
            if (autoScroll) scrollToHighlight()
        }

        val args = requireArguments()
        viewModel.load(
            args.getString(ARG_DOCUMENT_ID).orEmpty(),
            args.getString(ARG_VOICEMAIL_BOX_ID),
            args.getString(ARG_VOICEMAIL_MEDIA_ID)
        )
    }

    private fun showTabs(tabs: List<CallDetailTab>) {
        val current = (0 until binding.tabs.tabCount).map { binding.tabs.getTabAt(it)?.tag }
        if (current == tabs) return
        binding.tabs.removeAllTabs()
        for (tab in tabs) {
            binding.tabs.addTab(
                binding.tabs.newTab().setText(tabLabel(tab)).setTag(tab),
                tab == viewModel.selectedTab.value
            )
        }
    }

    private fun tabLabel(tab: CallDetailTab) = when (tab) {
        CallDetailTab.OVERVIEW -> R.string.call_detail_tab_overview
        CallDetailTab.TRANSCRIPTION -> R.string.call_detail_tab_transcription
        CallDetailTab.DATA -> R.string.call_detail_tab_data
        CallDetailTab.TAGS -> R.string.call_detail_tab_tags
        CallDetailTab.INFO -> R.string.call_detail_tab_info
    }

    @OptIn(UnstableApi::class)
    private fun setPlaylist(playlist: List<CallDetailViewModel.PlaylistItem>) {
        if (playlist.isEmpty()) {
            binding.playerContainer.visibility = View.GONE
            return
        }
        val player = player ?: ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(GatewayAudioDataSource.Factory()))
            .build()
            .also { player ->
                player.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        binding.playerLabel.text = mediaItem?.mediaMetadata?.title
                        updatePlayingRecording()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayingRecording()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(error, "[Call Detail] Playback failed")
                        val code = generateSequence<Throwable>(error) { it.cause }
                            .filterIsInstance<GatewayAudioException>().firstOrNull()?.code
                        Toast.makeText(
                            requireContext(),
                            when (code) {
                                404 -> R.string.voicemail_audio_not_found
                                402 -> R.string.voicemail_audio_no_credit
                                else -> R.string.voicemail_play_failed
                            },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
                binding.player.player = player
                binding.player.showController()
                this.player = player
                handler.post(positionPoller)
            }

        // Nothing is fetched until play is pressed
        player.setMediaItems(
            playlist.map {
                MediaItem.Builder()
                    .setUri(it.uri)
                    .setMediaId(it.mediaId)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(it.label).build())
                    .build()
            }
        )
        binding.playerLabel.text = playlist.first().label
        binding.playerContainer.visibility = View.VISIBLE

        // "Play recording" plays a call's only recording straight away, as the old screen did
        if (requireArguments().getBoolean(ARG_AUTO_PLAY) && playlist.size == 1 && !autoPlayDone) {
            autoPlayDone = true
            player.prepare()
            player.play()
        }
    }

    private fun showRecordings(recordings: List<org.linphone.models.callhistory.CallRecordingInfo>) {
        binding.recordings.removeAllViews()
        recordingItems = recordings.map { RecordingInfoViewModel(it) }
        recordingItems.forEachIndexed { index, item ->
            val cell: RecordingInfoCellBinding = DataBindingUtil.inflate(
                LayoutInflater.from(requireContext()),
                R.layout.recording_info_cell,
                binding.recordings,
                true
            )
            cell.viewModel = item
            cell.lifecycleOwner = viewLifecycleOwner
            cell.setClickListener { playRecording(index) }
        }
        binding.recordings.visibility = if (recordings.isEmpty()) View.GONE else View.VISIBLE
        updatePlayingRecording()
    }

    private fun playRecording(index: Int) {
        val player = player ?: return
        if (index >= player.mediaItemCount) return
        if (player.currentMediaItemIndex == index && player.playbackState != Player.STATE_IDLE) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        player.seekTo(index, 0)
        player.prepare()
        player.play()
    }

    private fun updatePlayingRecording() {
        val player = player
        recordingItems.forEachIndexed { index, item ->
            item.isPlaying.value = player != null && player.isPlaying && player.currentMediaItemIndex == index
        }
    }

    private fun setAutoScroll(enabled: Boolean) {
        autoScroll = enabled
        binding.follow.visibility = if (enabled || viewModel.selectedTab.value != CallDetailTab.TRANSCRIPTION) View.GONE else View.VISIBLE
    }

    private fun scrollToHighlight() {
        if (viewModel.selectedTab.value != CallDetailTab.TRANSCRIPTION) return
        val position = adapter.highlightedPosition()
        if (position < 0) return
        programmaticScroll = true
        val scroller = object : LinearSmoothScroller(requireContext()) {
            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ) =
                (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
        }
        scroller.targetPosition = position
        binding.rows.layoutManager?.startSmoothScroll(scroller)
    }

    private fun copy(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.call_detail_copy), text))
        Toast.makeText(requireContext(), R.string.call_detail_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        // Leaving the page stops what it was playing
        handler.removeCallbacks(positionPoller)
        player?.release()
        player = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_DOCUMENT_ID = "documentId"
        const val ARG_VOICEMAIL_BOX_ID = "voicemailBoxId"
        const val ARG_VOICEMAIL_MEDIA_ID = "voicemailMediaId"
        const val ARG_AUTO_PLAY = "autoPlay"

        private const val POSITION_POLL_MS = 100L

        fun arguments(
            documentId: String,
            voicemailBoxId: String? = null,
            voicemailMediaId: String? = null,
            autoPlay: Boolean = false
        ) = bundleOf(
            ARG_DOCUMENT_ID to documentId,
            ARG_VOICEMAIL_BOX_ID to voicemailBoxId,
            ARG_VOICEMAIL_MEDIA_ID to voicemailMediaId,
            ARG_AUTO_PLAY to autoPlay
        )
    }
}
