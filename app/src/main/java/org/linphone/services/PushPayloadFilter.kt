package org.linphone.services

// Decides which FCM messages go on to the linphone SDK. Call pushes carry their data with no
// title or body. Anything with a title or body is a display notification, and "NOUI" marks a
// push that isn't meant to wake the app.
object PushPayloadFilter {
    const val RECEIVE_ACTION = "com.google.android.c2dm.intent.RECEIVE"

    fun shouldHandle(extra: (String) -> String?): Boolean {
        return hasNoDisplayContent(extra) && !isNoUi(extra)
    }

    fun isMessage(action: String?): Boolean {
        return action == RECEIVE_ACTION
    }

    private fun hasNoDisplayContent(extra: (String) -> String?): Boolean {
        return extra("body").isNullOrBlank() && extra("title").isNullOrBlank()
    }

    private fun isNoUi(extra: (String) -> String?): Boolean {
        val body = extra("body").takeUnless { it.isNullOrBlank() }
            ?: extra("gcm.notification.body")
        return body == "NOUI"
    }
}
