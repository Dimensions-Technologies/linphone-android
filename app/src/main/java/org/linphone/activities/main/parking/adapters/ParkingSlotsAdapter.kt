package org.linphone.activities.main.parking.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.activities.main.parking.data.ParkingSlotData
import org.linphone.databinding.ParkingSlotCellBinding

class ParkingSlotsAdapter(
    private val onSlotClicked: (ParkingSlotData) -> Unit
) : ListAdapter<ParkingSlotData, ParkingSlotsAdapter.ViewHolder>(ParkingSlotDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ParkingSlotCellBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ParkingSlotCellBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(data: ParkingSlotData) {
            binding.data = data
            binding.setClickListener { onSlotClicked(data) }
            binding.executePendingBindings()
        }
    }
}

private class ParkingSlotDiffCallback : DiffUtil.ItemCallback<ParkingSlotData>() {
    override fun areItemsTheSame(oldItem: ParkingSlotData, newItem: ParkingSlotData): Boolean =
        oldItem.slot.number == newItem.slot.number

    override fun areContentsTheSame(oldItem: ParkingSlotData, newItem: ParkingSlotData): Boolean =
        oldItem.slot == newItem.slot
}
