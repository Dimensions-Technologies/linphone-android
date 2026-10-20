package org.linphone.activities

/**
 * Goes back with [dispatch] (the activity's back dispatcher), and with [fallback] when that throws:
 * the dispatcher can't run while the fragment manager is executing transactions, e.g. when a
 * fragment goes back from onViewCreated. The fallback may go back again (GenericFragment's callback
 * does when it can't pop), and a dispatcher that still throws would then recurse until the stack
 * overflows, so going back is abandoned instead.
 */
internal class BackNavigation(
    private val dispatch: () -> Unit,
    private val fallback: () -> Unit
) {
    private var fallingBack = false

    /** [onDispatchFailed] gets the dispatcher's exception, and whether going back was abandoned. */
    fun goBack(onDispatchFailed: (IllegalStateException, Boolean) -> Unit) {
        try {
            dispatch()
        } catch (ise: IllegalStateException) {
            onDispatchFailed(ise, fallingBack)
            if (fallingBack) return

            fallingBack = true
            try {
                fallback()
            } finally {
                fallingBack = false
            }
        }
    }
}
