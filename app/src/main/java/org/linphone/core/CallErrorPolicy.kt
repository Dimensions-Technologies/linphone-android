package org.linphone.core

import androidx.annotation.StringRes
import org.linphone.R

// Decides what, if anything, to tell the user when a call ends in error. As on the web client, an
// incoming call the caller cancelled or that was rejected says nothing (it's in History as
// missed), and a failure with nothing more specific to say is just "Call failed" - never the raw
// SIP code and phrase, which come through as "0 / null" when there was no SIP response at all.
object CallErrorPolicy {

    @StringRes
    fun message(dir: Call.Dir?, reason: Reason?): Int? = when (reason) {
        Reason.Busy -> R.string.call_error_user_busy
        Reason.IOError -> R.string.call_error_io_error
        Reason.NotAcceptable -> R.string.call_error_incompatible_media_params
        Reason.NotFound -> R.string.call_error_user_not_found
        Reason.ServerTimeout -> R.string.call_error_server_timeout
        Reason.TemporarilyUnavailable -> R.string.call_error_temporarily_unavailable
        // Rejected: the web client says nothing either (an outgoing decline is reported when the
        // call ends, see CoreContext)
        Reason.Declined -> null
        // No reason: for an incoming call the caller hung up and the cancel never arrived (e.g.
        // the SIP connection dropped), so it's missed rather than failed
        Reason.None, null -> if (dir == Call.Dir.Incoming) null else R.string.call_error_failed
        else -> R.string.call_error_failed
    }
}
