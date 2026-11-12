package org.linphone.services

import android.content.Context
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.reactivex.rxjava3.disposables.Disposable
import org.linphone.utils.BrandColors
import org.linphone.utils.Log

/**
 * The tenant brand's colours that the app applies as iOS does: the accent highlights the selected
 * bottom-bar item and History tab; the primary fills the Call voicemail button, its icon in the
 * colour that reads on it; the navbar colour draws the top bar's menu icon; and green, info and
 * red colour the History rows' direction icons. The last ones are kept, so the app doesn't flash
 * the defaults on each launch before the branding loads.
 */
object BrandHighlight {
    private const val PREFS = "branding"
    private const val KEY_ACCENT = "accent_color"
    private const val KEY_PRIMARY = "primary_color"
    private const val KEY_ON_PRIMARY = "on_primary_color"
    private const val KEY_NAVBAR = "navbar_color"
    private const val KEY_NAVBAR_BG = "navbar_bg_color"
    private const val KEY_OUTGOING = "outgoing_color"
    private const val KEY_INCOMING = "incoming_color"
    private const val KEY_MISSED = "missed_color"

    private var accent: MutableLiveData<Int>? = null
    private var primary: MutableLiveData<Int>? = null
    private var onPrimary: MutableLiveData<Int>? = null
    private var navbar: MutableLiveData<Int>? = null
    private var navbarBg: MutableLiveData<Int>? = null
    private var directions: MutableLiveData<BrandColors.DirectionColors>? = null
    private var subscription: Disposable? = null

    /** The highlight colour: the brand's accent. */
    fun color(context: Context): LiveData<Int> {
        start(context)
        return accent!!
    }

    /** The brand's primary colour. */
    fun primary(context: Context): LiveData<Int> {
        start(context)
        return primary!!
    }

    /** What reads on the primary colour: the brand's dark colour, or white. */
    fun onPrimary(context: Context): LiveData<Int> {
        start(context)
        return onPrimary!!
    }

    /** Icons on the top bar: the brand's navbar colour. */
    fun navbarColor(context: Context): LiveData<Int> {
        start(context)
        return navbar!!
    }

    /**
     * An avatar without a picture, as iOS's CallAvatar: its initials or icon in the brand's navbar
     * colour on its navbar background. Background, then foreground.
     */
    fun avatarColors(context: Context): Pair<Int, Int> {
        start(context)
        return (navbarBg!!.value ?: BrandColors.DEFAULT_NAVBAR_BG) to
            (navbar!!.value ?: BrandColors.DEFAULT_NAVBAR_COLOR)
    }

    /** The History rows' direction icons: the brand's green, info and red. */
    fun directionColors(context: Context): LiveData<BrandColors.DirectionColors> {
        start(context)
        return directions!!
    }

    @Synchronized
    private fun start(context: Context) {
        if (accent != null) return

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val accentLive = MutableLiveData(prefs.getInt(KEY_ACCENT, BrandColors.DEFAULT_ACCENT))
        val primaryLive = MutableLiveData(prefs.getInt(KEY_PRIMARY, BrandColors.DEFAULT_PRIMARY))
        val onPrimaryLive = MutableLiveData(
            prefs.getInt(KEY_ON_PRIMARY, BrandColors.contrastOf(BrandColors.DEFAULT_PRIMARY))
        )
        val navbarLive = MutableLiveData(prefs.getInt(KEY_NAVBAR, BrandColors.DEFAULT_NAVBAR_COLOR))
        val navbarBgLive = MutableLiveData(prefs.getInt(KEY_NAVBAR_BG, BrandColors.DEFAULT_NAVBAR_BG))
        navbarBg = navbarBgLive
        val defaults = BrandColors.DEFAULT_DIRECTION_COLORS
        val directionsLive = MutableLiveData(
            BrandColors.DirectionColors(
                outgoing = prefs.getInt(KEY_OUTGOING, defaults.outgoing),
                incoming = prefs.getInt(KEY_INCOMING, defaults.incoming),
                missed = prefs.getInt(KEY_MISSED, defaults.missed)
            )
        )
        directions = directionsLive
        accent = accentLive
        navbar = navbarLive
        primary = primaryLive
        onPrimary = onPrimaryLive

        subscription = BrandingService.getInstance(appContext).brand.subscribe(
            { brand ->
                val css = brand.getOrNull()?.cssVariables
                val accentColor = BrandColors.accentOf(css)
                val primaryColor = BrandColors.primaryOf(css)
                val onPrimaryColor = BrandColors.contrastOf(primaryColor, BrandColors.darkOf(css))
                val navbarColor = BrandColors.navbarColorOf(css)
                val navbarBgColor = BrandColors.navbarBgOf(css)
                val directionColors = BrandColors.directionColorsOf(css)
                prefs.edit()
                    .putInt(KEY_ACCENT, accentColor)
                    .putInt(KEY_PRIMARY, primaryColor)
                    .putInt(KEY_ON_PRIMARY, onPrimaryColor)
                    .putInt(KEY_NAVBAR, navbarColor)
                    .putInt(KEY_NAVBAR_BG, navbarBgColor)
                    .putInt(KEY_OUTGOING, directionColors.outgoing)
                    .putInt(KEY_INCOMING, directionColors.incoming)
                    .putInt(KEY_MISSED, directionColors.missed)
                    .apply()
                val updates = listOf(
                    accentLive to accentColor,
                    primaryLive to primaryColor,
                    onPrimaryLive to onPrimaryColor,
                    navbarLive to navbarColor,
                    navbarBgLive to navbarBgColor
                )
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    updates.forEach { (live, value) -> live.value = value }
                    directionsLive.value = directionColors
                } else {
                    updates.forEach { (live, value) -> live.postValue(value) }
                    directionsLive.postValue(directionColors)
                }
            },
            { e -> Log.e(e, "[Branding] Failed to read the brand's colours") }
        )
    }
}
