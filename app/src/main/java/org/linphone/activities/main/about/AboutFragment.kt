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
package org.linphone.activities.main.about

import android.content.*
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.linphone.R
import org.linphone.activities.main.MainActivity
import org.linphone.activities.main.fragments.SecureFragment
import org.linphone.databinding.AboutFragmentBinding
import org.linphone.environment.DimensionsEnvironmentService
import org.linphone.services.DiagnosticsService
import org.linphone.services.UserService
import org.linphone.utils.Log

class AboutFragment : SecureFragment<AboutFragmentBinding>() {
    private lateinit var viewModel: AboutViewModel
    private var userSubscription: Disposable? = null

    override fun getLayoutId(): Int = R.layout.about_fragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, insets.bottom)

            windowInsets
        }

        viewModel = ViewModelProvider(this)[AboutViewModel::class.java]
        binding.viewModel = viewModel

        binding.setPrivacyPolicyClickListener {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(getString(R.string.about_privacy_policy_link))
            )
            try {
                startActivity(browserIntent)
            } catch (se: SecurityException) {
                Log.e("[About] Failed to start browser intent, $se")
            }
        }

        binding.setLicenseClickListener {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(getString(R.string.about_license_link))
            )
            try {
                startActivity(browserIntent)
            } catch (se: SecurityException) {
                Log.e("[About] Failed to start browser intent, $se")
            }
        }

        binding.setWeblateClickListener {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(getString(R.string.about_weblate_link))
            )
            try {
                startActivity(browserIntent)
            } catch (se: SecurityException) {
                Log.e("[About] Failed to start browser intent, $se")
            }
        }

        DiagnosticsService.clearSendToSupportResult()
        binding.sendToSupportBtn?.let { button ->
            // Grey (diagnostics_button style) until an upload succeeds, then green with white text
            // and a tick, as on iOS. The tick is left off when it would wrap the label, so the
            // button never changes size.
            val defaultTint = button.backgroundTintList
            val defaultTextColors = button.textColors
            val sentTint = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.green_color)
            )
            val sentTextColor = ContextCompat.getColor(requireContext(), R.color.white_color)
            viewModel.sendToSupportButton.observe(viewLifecycleOwner) { state ->
                button.backgroundTintList = if (state.sent) sentTint else defaultTint
                if (state.sent) button.setTextColor(sentTextColor) else button.setTextColor(defaultTextColors)
                button.icon = null
                if (state.sent) {
                    button.doOnLayout {
                        val stillSent = viewModel.sendToSupportButton.value?.sent == true
                        if (stillSent && tickFits(button, state.label)) {
                            button.setIconResource(R.drawable.icon_check)
                        }
                    }
                }
            }
        }
        binding.setSendToSupportClickListener {
            val context = requireContext().applicationContext
            // Not tied to the fragment, so leaving About doesn't cancel the upload
            CoroutineScope(Dispatchers.IO).launch {
                DiagnosticsService.sendToSupport(context)
            }
        }

        binding.setClearLogsClickListener {
            try {
                DiagnosticsService.clearLogs(requireContext())

                val activity = requireActivity() as MainActivity
                activity.showSnackBar("Logs have been cleared")
            } catch (se: SecurityException) {
                Log.e("[About] Failed to start browser intent, $se")

                val activity = requireActivity() as MainActivity
                activity.showSnackBar("Failed to clear logs!")
            }
        }

        userSubscription = UserService.getInstance(requireContext()).user
            .subscribe { u ->
                try {
                    viewModel.user = u
                } catch (e: Exception) {
                    Log.e("userSubscription", e)
                }
            }

        viewModel.region = DimensionsEnvironmentService.getInstance(requireContext()).getCurrentEnvironment()?.name
    }

    override fun onDestroyView() {
        super.onDestroyView()

        userSubscription?.dispose()
    }

    // Whether the label still fits on one line with the tick beside it, measured as the button
    // draws it (text transformation, letter spacing)
    private fun tickFits(button: MaterialButton, @StringRes label: Int): Boolean {
        val text = getString(label)
        val shown = button.transformationMethod?.getTransformation(text, button) ?: text
        return SendToSupportButtonState.tickFits(
            labelWidth = button.paint.measureText(shown, 0, shown.length),
            buttonWidth = button.width,
            horizontalPadding = button.paddingStart + button.paddingEnd,
            tickSpace = button.iconSize + button.iconPadding
        )
    }
}
