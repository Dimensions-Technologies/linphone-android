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
package org.linphone.activities.main.history.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import org.linphone.R
import org.linphone.activities.main.adapters.SelectionListAdapter
import org.linphone.activities.main.history.data.HistoryListItem
import org.linphone.activities.main.viewmodels.ListTopBarViewModel
import org.linphone.databinding.HistoryDayHeaderBinding
import org.linphone.databinding.HistoryRowCellBinding
import org.linphone.databinding.HistorySubRowCellBinding
import org.linphone.utils.BrandColors
import org.linphone.utils.Event
import org.linphone.utils.HeaderAdapter
import org.linphone.utils.TimestampUtils

/**
 * The History and Missed lists, as iOS's (WI 36610): a heading for each day, a row for each call or
 * group of a day's calls with the same number, and an open group's calls under it.
 */
class CallLogsListAdapter(
    selectionVM: ListTopBarViewModel,
    private val viewLifecycleOwner: LifecycleOwner
) : SelectionListAdapter<HistoryListItem, RecyclerView.ViewHolder>(
    selectionVM,
    HistoryRowDiffCallback()
),
    HeaderAdapter {
    // A row to offer the call's actions for (a group's count opens or closes it instead)
    val rowClickedEvent: MutableLiveData<Event<HistoryListItem>> by lazy {
        MutableLiveData<Event<HistoryListItem>>()
    }

    // A group to open or close
    val groupToggledEvent: MutableLiveData<Event<HistoryListItem>> by lazy {
        MutableLiveData<Event<HistoryListItem>>()
    }

    // The brand's direction colours, as on iOS
    var directionColors: BrandColors.DirectionColors = BrandColors.DEFAULT_DIRECTION_COLORS
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).isSubRow) SUB_ROW else ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == SUB_ROW) {
            SubRowHolder(DataBindingUtil.inflate(inflater, R.layout.history_sub_row_cell, parent, false))
        } else {
            RowHolder(DataBindingUtil.inflate(inflater, R.layout.history_row_cell, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is RowHolder -> holder.bind(item)
            is SubRowHolder -> holder.bind(item)
        }
    }

    inner class RowHolder(val binding: HistoryRowCellBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HistoryListItem) {
            with(binding) {
                this.item = item
                directionColors = this@CallLogsListAdapter.directionColors
                lifecycleOwner = viewLifecycleOwner
                // As iOS: a grouped row opens or closes; any other offers what can be done with its call
                clickListener = View.OnClickListener {
                    if (item.hasCount) {
                        groupToggledEvent.value = Event(item)
                    } else {
                        rowClickedEvent.value = Event(item)
                    }
                }
                countClickListener = View.OnClickListener { groupToggledEvent.value = Event(item) }
                executePendingBindings()
            }
        }
    }

    inner class SubRowHolder(val binding: HistorySubRowCellBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HistoryListItem) {
            with(binding) {
                this.item = item
                directionColors = this@CallLogsListAdapter.directionColors
                lifecycleOwner = viewLifecycleOwner
                clickListener = View.OnClickListener { rowClickedEvent.value = Event(item) }
                executePendingBindings()
            }
        }
    }

    override fun displayHeaderForPosition(position: Int): Boolean {
        if (position >= itemCount) return false
        val previousPosition = position - 1
        return if (previousPosition >= 0) {
            !TimestampUtils.isSameDay(getItem(position).startSecs, getItem(previousPosition).startSecs)
        } else {
            true
        }
    }

    override fun getHeaderViewForPosition(context: Context, position: Int): View {
        val binding: HistoryDayHeaderBinding = DataBindingUtil.inflate(
            LayoutInflater.from(context),
            R.layout.history_day_header,
            null,
            false
        )
        binding.title = historyDayLabel(
            timestampSecs = getItem(position).startSecs,
            nowMillis = System.currentTimeMillis(),
            todayText = context.getString(R.string.today),
            yesterdayText = context.getString(R.string.yesterday),
            locale = Locale.getDefault()
        )
        binding.executePendingBindings()
        return binding.root
    }

    companion object {
        private const val ROW = 0
        private const val SUB_ROW = 1
    }
}

// Rows are the same by their id (a group's key, or a call within it), and unchanged if all they show is
private class HistoryRowDiffCallback : DiffUtil.ItemCallback<HistoryListItem>() {
    override fun areItemsTheSame(oldItem: HistoryListItem, newItem: HistoryListItem): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: HistoryListItem, newItem: HistoryListItem): Boolean =
        oldItem == newItem
}
