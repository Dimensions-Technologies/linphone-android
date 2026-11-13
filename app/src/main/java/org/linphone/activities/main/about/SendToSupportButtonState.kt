package org.linphone.activities.main.about

import androidx.annotation.StringRes
import org.linphone.R
import org.linphone.services.SendToSupportState

/**
 * How About's Send to support button looks for each upload state. The Diagnostics heading gives
 * the button context, so its labels are short. After a successful upload the button turns green
 * with a tick, as on iOS, so the result is obvious at a glance.
 */
data class SendToSupportButtonState(
    @StringRes val label: Int,
    val enabled: Boolean,
    val sent: Boolean
) {
    companion object {
        /**
         * The tick only goes beside the label when both fit on one line. On a narrow button the
         * label already wraps, and the tick would push it onto more lines and make the button
         * taller, so it turns green without the tick instead.
         */
        fun tickFits(labelWidth: Float, buttonWidth: Int, horizontalPadding: Int, tickSpace: Int): Boolean =
            buttonWidth > 0 && labelWidth + tickSpace <= buttonWidth - horizontalPadding

        fun of(state: SendToSupportState?): SendToSupportButtonState = when (state) {
            SendToSupportState.SENDING -> SendToSupportButtonState(
                R.string.about_send_to_support_sending,
                enabled = false,
                sent = false
            )
            SendToSupportState.SENT -> SendToSupportButtonState(
                R.string.about_send_to_support_sent,
                enabled = true,
                sent = true
            )
            SendToSupportState.FAILED -> SendToSupportButtonState(
                R.string.about_send_to_support_failed,
                enabled = true,
                sent = false
            )
            else -> SendToSupportButtonState(
                R.string.about_send_to_support,
                enabled = true,
                sent = false
            )
        }
    }
}
