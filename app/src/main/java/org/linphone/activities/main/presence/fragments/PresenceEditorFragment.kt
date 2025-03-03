package org.linphone.activities.main.presence.fragments

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import com.google.gson.GsonBuilder
import io.reactivex.rxjava3.disposables.Disposable
import org.linphone.R
import org.linphone.activities.*
import org.linphone.activities.main.presence.viewmodels.PresenceEditorViewModel
import org.linphone.databinding.FragmentPresenceEditorBinding
import org.linphone.services.UserService
import org.linphone.utils.Log

class PresenceEditorFragment : GenericFragment<FragmentPresenceEditorBinding>() {
    private lateinit var viewModel: PresenceEditorViewModel
    private var userSubscription: Disposable? = null

    override fun getLayoutId(): Int = R.layout.fragment_presence_editor

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner

        viewModel = ViewModelProvider(this)[PresenceEditorViewModel::class.java]
        binding.viewModel = viewModel

        sharedViewModel.publishPresenceToggled.observe(
            viewLifecycleOwner
        ) {
            viewModel.refreshConsolidatedPresence()
        }

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
    }
}
