package com.fastreader.app.viewer

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fastreader.app.R
import com.fastreader.app.core.BlockKind
import com.fastreader.app.core.TextBlock

/** 검색 결과 위치: blocks[block] 의 start 위치 */
data class SearchMatch(val block: Int, val start: Int)

class TextBlockAdapter(private val blocks: List<TextBlock>) : RecyclerView.Adapter<TextBlockAdapter.Holder>() {

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    var textSizeSp = 17f
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    private var query: String = ""
    private var current: SearchMatch? = null

    fun setSearch(query: String, current: SearchMatch?) {
        val old = this.current
        val queryChanged = this.query != query
        this.query = query
        this.current = current
        if (queryChanged) {
            notifyItemRangeChanged(0, itemCount)
        } else {
            old?.let { notifyItemChanged(it.block) }
            current?.let { notifyItemChanged(it.block) }
        }
    }

    override fun getItemCount() = blocks.size

    override fun getItemViewType(position: Int) = blocks[position].kind.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_text_block, parent, false) as TextView
        if (viewType == BlockKind.TABLE_ROW.ordinal) {
            tv.setBackgroundColor(ContextCompat.getColor(parent.context, R.color.table_row_background))
            val lp = tv.layoutParams as ViewGroup.MarginLayoutParams
            lp.setMargins(12.dp(tv), 1, 12.dp(tv), 1)
            tv.layoutParams = lp
            tv.setPadding(10.dp(tv), 6.dp(tv), 10.dp(tv), 6.dp(tv))
        }
        return Holder(tv)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val block = blocks[position]
        val factor = if (block.kind == BlockKind.TABLE_ROW) 0.9f else 1f
        holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp * factor)
        holder.text.text = highlight(holder.text, block.text, position)
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        // 재활용된 TextView 에서 텍스트 선택이 동작하지 않는 플랫폼 버그 우회
        holder.text.isEnabled = false
        holder.text.isEnabled = true
    }

    private fun highlight(view: TextView, text: String, position: Int): CharSequence {
        if (query.isEmpty() || text.isEmpty()) return text
        var idx = text.indexOf(query, 0, ignoreCase = true)
        if (idx < 0) return text
        val span = SpannableString(text)
        val normal = ContextCompat.getColor(view.context, R.color.search_highlight)
        val cur = ContextCompat.getColor(view.context, R.color.search_current)
        while (idx >= 0) {
            val isCurrent = current?.block == position && current?.start == idx
            span.setSpan(
                BackgroundColorSpan(if (isCurrent) cur else normal),
                idx, idx + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            idx = text.indexOf(query, idx + query.length, ignoreCase = true)
        }
        return span
    }

    companion object {
        private fun Int.dp(v: TextView) = (this * v.resources.displayMetrics.density).toInt()

        /** 백그라운드에서 호출 – 모든 일치 위치 (최대 limit 개) */
        fun findMatches(blocks: List<TextBlock>, query: String, limit: Int = 5000): List<SearchMatch> {
            if (query.isEmpty()) return emptyList()
            val result = ArrayList<SearchMatch>()
            for (i in blocks.indices) {
                val t = blocks[i].text
                var idx = t.indexOf(query, 0, ignoreCase = true)
                while (idx >= 0) {
                    result.add(SearchMatch(i, idx))
                    if (result.size >= limit) return result
                    idx = t.indexOf(query, idx + query.length, ignoreCase = true)
                }
            }
            return result
        }
    }
}
