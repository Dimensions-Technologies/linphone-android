/*
 * Copyright (c) 2010-2021 Belledonne Communications SARL.
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
package org.linphone.activities.voip.viewmodels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.models.callquality.CallQualityCalculator
import org.linphone.models.callquality.CallQualityResult
import org.linphone.models.callquality.CallQualitySample
import org.linphone.models.callquality.CallQualityStrength
import org.linphone.utils.AppUtils
import timber.log.Timber

/**
 * The call quality indicator, scored the same way as the web client. Shared by the call screen
 * and the status bar through the call activity, so there's only one reading a second.
 */
class CallQualityViewModel : ViewModel() {
    companion object {
        private const val CALL_QUALITY_INTERVAL_MS = 1000L
    }

    val callQualityIcon = MutableLiveData<Int>()
    val callQualityContentDescription = MutableLiveData<String>()

    private val callQualityCalculator = CallQualityCalculator()
    private var callQualityCallId: String? = null
    private var callQualityStrength = CallQualityStrength.NONE
    private var lastDownlinkPacketsLost: Int? = null
    private var lastUplinkLossPct: Float? = null

    init {
        // Sampled once a second, like the web client, so both score over the same time windows
        viewModelScope.launch {
            while (isActive) {
                updateCallQuality()
                delay(CALL_QUALITY_INTERVAL_MS)
            }
        }
    }

    private fun updateCallQuality() {
        val call = coreContext.core.currentCall ?: coreContext.core.calls.firstOrNull()
        val stats = call?.audioStats
        if (call == null || stats == null) {
            showCallQuality(CallQualityStrength.NONE, null)
            return
        }

        // Start afresh for each call so one call's readings don't colour the next
        val callId = call.callLog.callId ?: call.nativePointer.toString()
        if (callId != callQualityCallId) {
            callQualityCallId = callId
            callQualityCalculator.reset()
            lastDownlinkPacketsLost = null
            lastUplinkLossPct = null
        }

        val sample = CallQualitySample(
            timestampMs = System.currentTimeMillis(),
            downlinkPacketsLost = stats.rtpCumPacketLoss,
            downlinkPacketsReceived = stats.rtpPacketRecv,
            uplinkLossPct = stats.receiverLossRate,
            downlinkJitterSeconds = stats.senderInterarrivalJitter,
            uplinkJitterSeconds = stats.receiverInterarrivalJitter,
            roundTripDelaySeconds = stats.roundTripDelay
        )
        val result = callQualityCalculator.update(sample)
        logCallQuality(sample, result)
        showCallQuality(result.strength, result)
    }

    private fun logCallQuality(sample: CallQualitySample, result: CallQualityResult) {
        if (result.strength != callQualityStrength && result.strength != CallQualityStrength.GOOD) {
            Timber.w("Connection strength changed to ${result.strength} (${result.reason})")
        }
        val lossChanged = sample.downlinkPacketsLost != lastDownlinkPacketsLost ||
            sample.uplinkLossPct != lastUplinkLossPct
        if (lossChanged) {
            Timber.d("Call quality stats: $sample")
        }
        lastDownlinkPacketsLost = sample.downlinkPacketsLost
        lastUplinkLossPct = sample.uplinkLossPct
    }

    private fun showCallQuality(strength: CallQualityStrength, result: CallQualityResult?) {
        callQualityStrength = strength
        callQualityIcon.value = when (strength) {
            CallQualityStrength.GOOD -> R.drawable.call_quality_good
            CallQualityStrength.OK -> R.drawable.call_quality_ok
            CallQualityStrength.POOR -> R.drawable.call_quality_poor
            CallQualityStrength.NONE -> R.drawable.call_quality_none
        }
        callQualityContentDescription.value = when (strength) {
            CallQualityStrength.GOOD -> AppUtils.getString(R.string.call_quality_good)
            CallQualityStrength.OK -> coreContext.context.getString(
                R.string.call_quality_ok,
                result?.reason
            )
            CallQualityStrength.POOR -> coreContext.context.getString(
                R.string.call_quality_poor,
                result?.reason
            )
            CallQualityStrength.NONE -> AppUtils.getString(R.string.call_quality_none)
        }
    }
}
