package org.linphone.activities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackNavigationTest {
    private val busy = IllegalStateException("FragmentManager is already executing transactions")

    @Test
    fun `dispatcher going back doesn't use the fallback`() {
        var fallbacks = 0
        val failures = mutableListOf<Boolean>()
        val navigation = BackNavigation(dispatch = {}, fallback = { fallbacks++ })

        navigation.goBack { _, abandoned -> failures.add(abandoned) }

        assertEquals(0, fallbacks)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `dispatcher throwing uses the fallback once`() {
        var fallbacks = 0
        val failures = mutableListOf<Boolean>()
        val navigation = BackNavigation(dispatch = { throw busy }, fallback = { fallbacks++ })

        navigation.goBack { _, abandoned -> failures.add(abandoned) }

        assertEquals(1, fallbacks)
        assertEquals(listOf(false), failures)
    }

    @Test
    fun `fallback going back again while the dispatcher still throws is abandoned, not recursed`() {
        // GenericFragment's callback calls goBack() again when it can't pop or navigate up. Before
        // the guard this recursed until the stack overflowed (seen while fixing WI 36365)
        var fallbacks = 0
        val failures = mutableListOf<Boolean>()
        lateinit var navigation: BackNavigation
        val onFailed: (IllegalStateException, Boolean) -> Unit = { _, abandoned -> failures.add(abandoned) }
        navigation = BackNavigation(
            dispatch = { throw busy },
            fallback = {
                fallbacks++
                navigation.goBack(onFailed)
            }
        )

        navigation.goBack(onFailed)

        assertEquals(1, fallbacks)
        assertEquals(listOf(false, true), failures)
    }

    @Test
    fun `fallback is available again after an abandoned or failed attempt`() {
        var fallbacks = 0
        lateinit var navigation: BackNavigation
        navigation = BackNavigation(
            dispatch = { throw busy },
            fallback = {
                fallbacks++
                if (fallbacks == 1) navigation.goBack { _, _ -> } else throw busy
            }
        )

        navigation.goBack { _, _ -> }
        try {
            navigation.goBack { _, _ -> }
        } catch (ise: IllegalStateException) {
            // The second fallback throws; the guard must still be reset afterwards
        }
        try {
            navigation.goBack { _, _ -> }
        } catch (ise: IllegalStateException) {
        }

        assertEquals(3, fallbacks)
    }
}
