package org.linphone.activities.main.parking.fragments

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import org.linphone.R
import org.linphone.activities.GenericFragment
import org.linphone.activities.main.parking.adapters.ParkingSlotsAdapter
import org.linphone.activities.main.parking.viewmodels.ParkingSlotsViewModel
import org.linphone.databinding.ParkingSlotsFragmentBinding

/** The Parking tab, as the web client's Parking menu: every slot, to park in or pick up from. */
class ParkingSlotsFragment : GenericFragment<ParkingSlotsFragmentBinding>() {
    private lateinit var viewModel: ParkingSlotsViewModel

    override fun getLayoutId(): Int = R.layout.parking_slots_fragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner

        viewModel = ViewModelProvider(this)[ParkingSlotsViewModel::class.java]
        binding.viewModel = viewModel

        val adapter = ParkingSlotsAdapter { viewModel.activate(it) }
        binding.parkingSlotsList.layoutManager = LinearLayoutManager(requireContext())
        binding.parkingSlotsList.adapter = adapter

        viewModel.slots.observe(viewLifecycleOwner) { adapter.submitList(it) }
    }
}
