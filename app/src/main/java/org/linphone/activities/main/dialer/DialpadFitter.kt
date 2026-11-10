package org.linphone.activities.main.dialer

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import kotlin.math.min
import org.linphone.R

/**
 * Sizes the dialer's keypad and call button to the height the dialer actually has.
 *
 * The button size from the h600dp/h900dp resources is picked from the whole screen's height, not
 * the space left after the top bar, tabs and (during a call) the "Return to call" banner - so on a
 * short screen, e.g. a Galaxy at a high display density, the rows didn't fit and the layout
 * squashed them. That size is now the largest a button gets: when there isn't room for the field
 * and five rows (four of digits, one for the call button) the buttons shrink, with their text and
 * padding scaled by the same amount so they keep their look.
 */
class DialpadFitter(
    private val root: View,
    private val content: ViewGroup,
    private val uriField: View,
    private val numpad: ViewGroup,
    private val callButton: ViewGroup
) {
    private val resources = root.resources
    private val maxSize = resources.getDimensionPixelSize(R.dimen.voip_numpad_button_max_size)
    private val minSize = (48 * resources.displayMetrics.density).toInt() // Smallest comfortable touch target

    // Each child's inflated text size and padding, so a re-fit always scales from the original
    private val originals = HashMap<View, Original>()
    private var fittedSize = maxSize

    private val layoutListener = View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top != oldBottom - oldTop) root.post { fit() }
    }

    fun start() {
        root.addOnLayoutChangeListener(layoutListener)
        root.post { fit() }
    }

    fun stop() {
        root.removeOnLayoutChangeListener(layoutListener)
    }

    private fun fit() {
        val height = root.height
        if (height == 0) return

        val buttons = numpad.children.filterIsInstance<ViewGroup>().toList() + callButton
        val margins = callButton.layoutParams as ViewGroup.MarginLayoutParams
        val rowMargin = margins.topMargin + margins.bottomMargin
        val fieldParams = uriField.layoutParams as ViewGroup.MarginLayoutParams
        // The field's set height, not its measured one, which the layout may already have squashed
        val fieldHeight = fieldParams.height + fieldParams.topMargin + fieldParams.bottomMargin
        val contentMargin = (content.layoutParams as ViewGroup.MarginLayoutParams).let {
            it.topMargin + it.bottomMargin
        }

        val perRow = (height - contentMargin - fieldHeight) / ROWS - rowMargin
        val size = min(maxSize, perRow).coerceAtLeast(minSize)
        if (size == fittedSize && buttons.all { it.layoutParams.height == size }) return
        fittedSize = size

        val scale = size.toFloat() / maxSize
        for (button in buttons) {
            button.updateLayoutParams {
                width = size
                this.height = size
            }
            for (child in button.children) scaleChild(child, scale)
        }
    }

    private fun scaleChild(child: View, scale: Float) {
        val original = originals.getOrPut(child) {
            Original(
                (child as? TextView)?.textSize,
                child.paddingLeft,
                child.paddingTop,
                child.paddingRight,
                child.paddingBottom
            )
        }
        original.textSize?.let { (child as TextView).setTextSize(TypedValue.COMPLEX_UNIT_PX, it * scale) }
        child.setPadding(
            (original.left * scale).toInt(),
            (original.top * scale).toInt(),
            (original.right * scale).toInt(),
            (original.bottom * scale).toInt()
        )
    }

    private class Original(
        val textSize: Float?,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private companion object {
        const val ROWS = 5
    }
}
