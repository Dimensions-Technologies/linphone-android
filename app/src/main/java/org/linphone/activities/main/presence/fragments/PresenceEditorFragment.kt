package org.linphone.activities.main.presence.fragments

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.activities.*
import org.linphone.activities.main.presence.viewmodels.PresenceEditorViewModel
import org.linphone.authentication.AuthStateManager
import org.linphone.databinding.FragmentPresenceEditorBinding
import org.linphone.models.realtime.PresenceProfile
import org.linphone.services.CallerIdOption
import org.linphone.services.PresenceProfileService
import org.linphone.services.UserService
import org.linphone.utils.Event
import org.linphone.utils.Log

class PresenceEditorFragment : GenericFragment<FragmentPresenceEditorBinding>() {
    private lateinit var viewModel: PresenceEditorViewModel
    private val presenceProfileService = PresenceProfileService.getInstance(coreContext.context)

    private var presenceProfileSubscription: Disposable? = null
    private var userSubscription: Disposable? = null

    override fun getLayoutId(): Int = R.layout.fragment_presence_editor

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner

        viewModel = ViewModelProvider(this)[PresenceEditorViewModel::class.java]
        binding.viewModel = viewModel

        val userSvc = UserService.getInstance(requireContext())
        userSubscription = userSvc.user
            .subscribe(
                { u ->
                    Log.i("Userinfo: " + GsonBuilder().create().toJson(u))
                    viewModel.user.set(u)
                    viewModel.userImageUrl.set(u.profileImageUrl.replace("_36.png", "_128.png"))
                },
                { error -> Log.e(error) }
            )

        presenceProfileSubscription = presenceProfileService.presenceProfiles.subscribe(
            { response ->
                try {
                    updateSpinnerAdapter(response)
                } catch (e: Exception) {
                    Log.e("presenceProfileSubscription", e)
                }
            },
            { error -> Log.e(error) }
        )

        val presenceProfileSpinner: Spinner = requireView().findViewById(
            R.id.presenceProfileSpinner
        )

        binding.setApplyProfileClickListener {
            try {
                viewModel.applyPresence()
            } catch (e: Exception) {
                Log.e("setApplyProfileClickListener", e)
            }
        }

        binding.setLogoutClickListener {
            logout()
        }

        setUpCallerIdSpinner()
    }

    private fun setUpCallerIdSpinner() {
        val spinner = binding.callerIdSpinner
        // Disabled entries (the account default when it can't be chosen, or a caller ID no longer
        // offered) are shown greyed out and can't be picked, as on the web client
        val adapter = object : ArrayAdapter<CallerIdOption>(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item
        ) {
            override fun areAllItemsEnabled(): Boolean = false

            override fun isEnabled(position: Int): Boolean = getItem(position)?.disabled != true

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getDropDownView(position, convertView, parent).apply {
                    alpha = if (isEnabled(position)) 1f else DISABLED_ALPHA
                }
        }
        spinner.adapter = adapter

        viewModel.callerIdChoice.observe(viewLifecycleOwner) { choice ->
            adapter.setNotifyOnChange(false)
            adapter.clear()
            adapter.addAll(choice.options)
            adapter.notifyDataSetChanged()
            if (choice.selectedIndex >= 0) spinner.setSelection(choice.selectedIndex, false)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>) {}

            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                // Also called when the list is filled; only act on the user picking another one
                if (position == viewModel.callerIdChoice.value?.selectedIndex) return
                adapter.getItem(position)?.let { viewModel.setCallerId(it) }
            }
        }
    }

    private fun logout() {
        val context = requireContext()
        val authManager = AuthStateManager.getInstance(context)
        sharedViewModel.togglePresenceDrawerEvent.value = Event(true)

        // Removing the user session is a network call, so keep it off the main thread. It needs
        // the current access token, so the auth state is only cleared once it has finished.
        lifecycleScope.launch {
            // SIP is unregistered at the same time, and before the accounts are cleared, so the
            // push token can be taken out of the Contact first
            val sipUnregistration = launch { coreContext.unregisterForLogout() }
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(LOGOUT_SESSION_REMOVAL_TIMEOUT_MS) {
                    UserService.getInstance(context).removeUserSession()
                } ?: Log.w("Timed out removing user session during logout")
            }
            sipUnregistration.join()
            authManager.logout(context)
            Log.i("DONE")
        }
    }

    private fun updateSpinnerAdapter(presenceProfiles: List<PresenceProfile>) {
        val spinner: Spinner = requireView().findViewById(R.id.presenceProfileSpinner)
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            presenceProfiles
        )

        spinner.adapter = adapter

        // Observe the selectedItem LiveData
        viewModel.presenceProfile.observe(viewLifecycleOwner) { selectedItem ->
            val position = (spinner.adapter as ArrayAdapter<PresenceProfile>).getPosition(
                selectedItem
            )
            spinner.setSelection(position)
        }

        // Set the spinner selection listener to update the ViewModel
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>) {
                // Do nothing
            }

            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selectedItem = parent.getItemAtPosition(position) as PresenceProfile
                viewModel.presenceProfile.value = selectedItem
                viewModel.applyPresence()
            }
        }
    }

    companion object {
        private const val LOGOUT_SESSION_REMOVAL_TIMEOUT_MS = 5_000L
        private const val DISABLED_ALPHA = 0.4f
    }
}
