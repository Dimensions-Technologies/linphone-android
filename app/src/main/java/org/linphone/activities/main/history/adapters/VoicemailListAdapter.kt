package org.linphone.activities.main.history.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.BR
import org.linphone.R
import org.linphone.activities.main.history.viewmodels.VoicemailListItem
import org.linphone.activities.main.history.viewmodels.VoicemailListViewModel
import org.linphone.models.voicemail.VoicemailRow

class VoicemailListAdapter(
    private val viewModel: VoicemailListViewModel,
    private val viewLifecycleOwner: LifecycleOwner
) : ListAdapter<VoicemailListItem, VoicemailListAdapter.ViewHolder>(DiffCallback()) {

    override fun getItemViewType(position: Int): Int = when (getItem(position).row) {
        is VoicemailRow.BoxHeader -> R.layout.voicemail_box_header_cell
        is VoicemailRow.FolderHeader -> R.layout.voicemail_folder_header_cell
        is VoicemailRow.EmptyFolder -> R.layout.voicemail_empty_cell
        is VoicemailRow.Message -> R.layout.voicemail_message_cell
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding: ViewDataBinding = DataBindingUtil.inflate(
            LayoutInflater.from(parent.context),
            viewType,
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ViewDataBinding) : RecyclerView.ViewHolder(
        binding.root
    ) {
        fun bind(item: VoicemailListItem) {
            binding.setVariable(BR.item, item)
            binding.setVariable(BR.viewModel, viewModel)
            binding.lifecycleOwner = viewLifecycleOwner
            binding.executePendingBindings()
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<VoicemailListItem>() {
        override fun areItemsTheSame(oldItem: VoicemailListItem, newItem: VoicemailListItem) =
            oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: VoicemailListItem, newItem: VoicemailListItem) =
            oldItem == newItem
    }
}
