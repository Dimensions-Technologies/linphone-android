package org.linphone.telecom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream
import org.linphone.R
import org.linphone.utils.Log

/**
 * Builds the icon for the Telecom phone account and call status hints as a small bitmap that
 * passes Telecom's icon size check on every device (see [PhoneAccountIconRules], WI #36796).
 */
object PhoneAccountIcon {
    @Volatile private var cached: Icon? = null

    /** The icon, or null if no size fits, in which case callers register without an icon. */
    fun get(context: Context): Icon? {
        cached?.let { return it }
        val icon = build(context)
        cached = icon
        return icon
    }

    private fun build(context: Context): Icon? {
        val drawable = try {
            AppCompatResources.getDrawable(context, R.drawable.linphone_logo)
        } catch (e: Exception) {
            Log.e("[Phone Account Icon] Can't load app logo: $e")
            null
        } ?: return null

        for (maxSide in PhoneAccountIconRules.candidateSizes()) {
            val (width, height) = PhoneAccountIconRules.scaledSize(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                maxSide
            )
            try {
                val bitmap = drawable.toBitmap(width, height, Bitmap.Config.ARGB_8888)
                val png = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
                if (PhoneAccountIconRules.fitsTelecomLimit(png.size())) {
                    Log.i(
                        "[Phone Account Icon] Using ${width}x$height icon (${png.size()} bytes as PNG)"
                    )
                    return Icon.createWithBitmap(bitmap)
                }
                Log.w(
                    "[Phone Account Icon] ${width}x$height icon is too big for Telecom (${png.size()} bytes as PNG), trying smaller"
                )
            } catch (e: Exception) {
                Log.e("[Phone Account Icon] Can't build ${width}x$height icon: $e")
            }
        }
        Log.e("[Phone Account Icon] No icon size fits Telecom's limit, using no icon")
        return null
    }
}
