package org.linphone.activities.main.history.adapters

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.NumberFormat
import org.linphone.R
import org.linphone.models.callsession.CallDetailRules
import org.linphone.models.callsession.DetailRow
import org.linphone.models.callsession.Sentiment
import org.linphone.models.callsession.TextStyle
import org.linphone.models.callsession.UiText
import org.linphone.utils.Log

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Literal -> text
    is UiText.Res -> context.getString(id, *args.toTypedArray())
}

/** The rows of a tab of the call detail page. */
class CallDetailAdapter(
    private val onCopy: (String) -> Unit
) : ListAdapter<DetailRow, RecyclerView.ViewHolder>(DiffCallback()) {

    /** The transcript paragraph (its idx) being played, highlighted. */
    var highlightedIndex: Int = -1
        set(value) {
            if (field == value) return
            val old = field
            field = value
            currentList.forEachIndexed { i, row ->
                val index = (row as? DetailRow.Transcript)?.line?.index
                if (index != null && (index == old || index == value)) notifyItemChanged(i)
            }
        }

    /** The position of the highlighted paragraph, or -1. */
    fun highlightedPosition(): Int = currentList.indexOfFirst {
        it is DetailRow.Transcript && it.line.index != null && it.line.index == highlightedIndex
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is DetailRow.Card -> R.layout.call_detail_card_cell
        is DetailRow.SubHeader -> R.layout.call_detail_subheader_cell
        is DetailRow.Text -> R.layout.call_detail_text_cell
        is DetailRow.Field -> R.layout.call_detail_field_cell
        is DetailRow.Loading -> R.layout.call_detail_loading_cell
        is DetailRow.Transcript -> R.layout.call_detail_transcript_cell
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        ) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val view = holder.itemView
        val context = view.context
        when (val row = getItem(position)) {
            is DetailRow.Card -> {
                view.findViewById<TextView>(R.id.title).text = row.title.resolve(context)
                view.alpha = if (row.greyed) 0.5f else 1f
                val copy = view.findViewById<ImageView>(R.id.copy)
                copy.visibility = if (row.copyText.isNullOrEmpty()) View.GONE else View.VISIBLE
                copy.setOnClickListener { row.copyText?.let(onCopy) }
            }
            is DetailRow.SubHeader -> view.findViewById<TextView>(R.id.text).text = row.title.resolve(
                context
            )
            is DetailRow.Text -> {
                val text = view.findViewById<TextView>(R.id.text)
                text.text = row.text.resolve(context)
                text.setTextAppearance(R.style.contact_number_list_cell_font)
                text.setTypeface(
                    null,
                    if (row.style == TextStyle.MUTED) Typeface.ITALIC else Typeface.NORMAL
                )
                if (row.style == TextStyle.ERROR) {
                    text.setTextColor(ContextCompat.getColor(context, R.color.call_detail_negative))
                }
                view.alpha = if (row.greyed) 0.5f else 1f
            }
            is DetailRow.Field -> bindField(view, row)
            is DetailRow.Loading -> {}
            is DetailRow.Transcript -> bindTranscript(view, row, position)
        }
    }

    private fun bindField(view: View, row: DetailRow.Field) {
        val context = view.context
        view.findViewById<TextView>(R.id.label).text = row.label.resolve(context)
        val value = view.findViewById<TextView>(R.id.value)
        val text = row.value.resolve(context)
        if (row.link != null) {
            value.text = SpannableStringBuilder(text).apply { setSpan(UnderlineSpan(), 0, length, 0) }
            value.setOnClickListener {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.link)))
                } catch (e: Exception) {
                    Log.e(e, "[Call Detail] Couldn't open ${row.link}")
                }
            }
        } else {
            value.text = text
            value.setOnClickListener(null)
            value.isClickable = false
        }
        val note = view.findViewById<TextView>(R.id.note)
        note.text = row.note?.let { "($it)" }.orEmpty()
        note.visibility = if (row.note == null) View.GONE else View.VISIBLE
    }

    private fun bindTranscript(view: View, row: DetailRow.Transcript, position: Int) {
        val context = view.context
        val line = row.line

        // A run of the same speaker shares one header
        val header = view.findViewById<View>(R.id.header)
        header.visibility = if (line.label != null) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.speaker).text = line.label.orEmpty()
        view.findViewById<TextView>(R.id.start).text = line.start?.let {
            CallDetailRules.formatDuration(
                it
            )
        }.orEmpty()

        // The user's paragraphs are indented from the left, everyone else's from the right
        val container = view.findViewById<LinearLayout>(R.id.container)
        container.gravity = if (line.isUser) Gravity.END else Gravity.START
        val bubble = view.findViewById<LinearLayout>(R.id.bubble)
        bubble.setBackgroundResource(
            if (line.isUser) R.drawable.call_detail_bubble_user else R.drawable.call_detail_bubble_other
        )
        val indent = (context.resources.displayMetrics.density * 48).toInt()
        (bubble.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = if (line.isUser) indent else 0
            marginEnd = if (line.isUser) 0 else indent
        }
        (header.layoutParams as LinearLayout.LayoutParams).gravity = if (line.isUser) Gravity.END else Gravity.START

        val sentiment = view.findViewById<View>(R.id.sentiment)
        val icon = when (line.sentiment) {
            Sentiment.POSITIVE -> R.drawable.ic_call_detail_sentiment_up
            Sentiment.NEGATIVE -> R.drawable.ic_call_detail_sentiment_down
            Sentiment.NEUTRAL -> null
        }
        sentiment.visibility = if (icon != null) View.VISIBLE else View.GONE
        if (icon != null) view.findViewById<ImageView>(R.id.sentiment_icon).setImageResource(icon)
        sentiment.contentDescription = line.sentimentLabel?.resolve(context)
        val score = view.findViewById<TextView>(R.id.sentiment_score)
        val value = line.sentimentScore?.takeIf { it != 0.0 }
        score.text = value?.let { NumberFormat.getPercentInstance().format(it / 100) }.orEmpty()
        score.visibility = if (value != null) View.VISIBLE else View.GONE

        val text = SpannableStringBuilder()
        for (part in line.parts) {
            val start = text.length
            text.append(part.text)
            val category = part.piiCategory
            if (category != null) {
                // Masked personal information, in the accent colour, labelled with its category
                val pii = ContextCompat.getColor(context, R.color.call_detail_pii)
                text.setSpan(
                    ForegroundColorSpan(pii),
                    start,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                text.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                val labelStart = text.length
                text.append(" [").append(category.resolve(context)).append("]")
                text.setSpan(
                    RelativeSizeSpan(0.7f),
                    labelStart,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                text.setSpan(
                    ForegroundColorSpan(pii),
                    labelStart,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                text.append(" ")
            }
        }
        if (line.index != null && line.index == highlightedIndex) {
            text.setSpan(
                BackgroundColorSpan(
                    ContextCompat.getColor(context, R.color.call_detail_highlight_background)
                ),
                0,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text.setSpan(
                ForegroundColorSpan(
                    ContextCompat.getColor(context, R.color.call_detail_highlight_text)
                ),
                0,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (line.isLowConfidence) {
            val start = text.length
            text.append(" ").append(context.getString(R.string.call_detail_low_confidence))
            text.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, R.color.call_detail_negative)),
                start,
                text.length,
                0
            )
            text.setSpan(RelativeSizeSpan(0.75f), start, text.length, 0)
        }
        val textView = view.findViewById<TextView>(R.id.text)
        textView.text = text
        textView.contentDescription = if (line.isLowConfidence) {
            "${line.plainText} ${context.getString(R.string.call_detail_low_confidence_hint)}"
        } else {
            null
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<DetailRow>() {
        override fun areItemsTheSame(oldItem: DetailRow, newItem: DetailRow) = oldItem == newItem
        override fun areContentsTheSame(oldItem: DetailRow, newItem: DetailRow) = oldItem == newItem
    }
}
