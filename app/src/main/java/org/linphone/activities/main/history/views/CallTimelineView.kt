package org.linphone.activities.main.history.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.linphone.R
import org.linphone.models.callsession.Sentiment
import org.linphone.models.callsession.TimelineRow

/**
 * The call detail page's timeline, as the web client's conversation-timeline: a row per speaker,
 * with a stretch for each thing they said, coloured by its sentiment. Tapping a stretch shows what
 * was said (the web client's tooltip).
 */
class CallTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var rows: List<TimelineRow> = emptyList()
        set(value) {
            field = value
            visibility = if (value.isEmpty()) GONE else VISIBLE
            requestLayout()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val labelHeight = 16 * density
    private val barHeight = 12 * density
    private val rowGap = 6 * density
    private val radius = 3 * density

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            resources.displayMetrics
        )
        color = themeColor(android.R.attr.textColorSecondary)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.call_detail_timeline_track)
    }
    private val fragmentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    private fun color(sentiment: Sentiment) = ContextCompat.getColor(
        context,
        when (sentiment) {
            Sentiment.POSITIVE -> R.color.call_detail_positive
            Sentiment.NEGATIVE -> R.color.call_detail_negative
            Sentiment.NEUTRAL -> R.color.call_detail_neutral
        }
    )

    private val rowHeight get() = labelHeight + barHeight + rowGap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (paddingTop + paddingBottom + rows.size * rowHeight).toInt()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val width = (this.width - paddingLeft - paddingRight).toFloat()
        rows.forEachIndexed { i, row ->
            val top = paddingTop + i * rowHeight
            canvas.drawText(row.label, left, top + labelHeight - 4 * density, labelPaint)
            val barTop = top + labelHeight
            rect.set(left, barTop, left + width, barTop + barHeight)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            for (fragment in row.fragments) {
                fragmentPaint.color = color(fragment.sentiment)
                val start = left + fragment.offset * width
                // At least 3dp, so the briefest remark still shows
                val end = start + maxOf(fragment.width * width, 3 * density)
                rect.set(start, barTop, end - density, barTop + barHeight)
                canvas.drawRoundRect(rect, radius, radius, fragmentPaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        val width = (this.width - paddingLeft - paddingRight).toFloat()
        val rowIndex = ((event.y - paddingTop) / rowHeight).toInt()
        val row = rows.getOrNull(rowIndex) ?: return true
        val position = (event.x - paddingLeft) / width
        val fragment = row.fragments.firstOrNull { position >= it.offset && position <= it.offset + it.width }
        if (fragment?.text != null) {
            val sentiment = context.getString(
                when (fragment.sentiment) {
                    Sentiment.POSITIVE -> R.string.call_detail_sentiment_positive
                    Sentiment.NEGATIVE -> R.string.call_detail_sentiment_negative
                    Sentiment.NEUTRAL -> R.string.call_detail_sentiment_neutral
                }
            )
            Toast.makeText(
                context,
                context.getString(R.string.call_detail_sentiment_tooltip, sentiment, fragment.text),
                Toast.LENGTH_LONG
            ).show()
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
