package com.fastreader.app.viewer

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fastreader.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PdfPageAdapter(
    private val pdf: PdfController,
    private val scope: CoroutineScope,
) : RecyclerView.Adapter<PdfPageAdapter.PageHolder>() {

    class PageHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.pageImage)
        val placeholder: TextView = view.findViewById(R.id.pagePlaceholder)
        var job: Job? = null
        var page = -1
    }

    /** 화면에 표시되는 페이지 폭(px) */
    private var viewWidth = 0

    /** 확대 시 선명도를 위한 렌더링 배율 */
    private var renderScale = 1f

    var nightMode = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount, PAYLOAD_FILTER)
            }
        }

    fun setViewWidth(width: Int) {
        if (width > 0 && width != viewWidth) {
            viewWidth = width
            notifyItemRangeChanged(0, itemCount)
        }
    }

    fun setRenderScale(scale: Float) {
        // 2배 이상은 메모리만 많이 쓰고 체감 차이가 적다
        val s = when {
            scale >= 1.75f -> 2f
            scale >= 1.25f -> 1.5f
            else -> 1f
        }
        if (s != renderScale) {
            renderScale = s
            notifyItemRangeChanged(0, itemCount, PAYLOAD_RERENDER)
        }
    }

    override fun getItemCount() = pdf.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false)
        return PageHolder(v)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && holder.page == position) {
            if (payloads.contains(PAYLOAD_FILTER)) applyFilter(holder.image)
            if (payloads.contains(PAYLOAD_RERENDER)) startRender(holder, position, keepCurrent = true)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.page = position
        val width = if (viewWidth > 0) viewWidth else holder.itemView.resources.displayMetrics.widthPixels
        val lp = holder.image.layoutParams
        val h = (width * pdf.aspectRatio(position)).toInt().coerceAtLeast(1)
        if (lp.height != h) {
            lp.height = h
            holder.image.layoutParams = lp
        }
        applyFilter(holder.image)
        holder.placeholder.text = (position + 1).toString()
        startRender(holder, position, keepCurrent = false)
    }

    private fun startRender(holder: PageHolder, position: Int, keepCurrent: Boolean) {
        holder.job?.cancel()
        val width = if (viewWidth > 0) viewWidth else holder.itemView.resources.displayMetrics.widthPixels
        val target = (width * renderScale).toInt()
        val cached = pdf.cached(position, target)
        if (cached != null) {
            showBitmap(holder, position, cached)
            return
        }
        if (!keepCurrent) {
            holder.image.setImageDrawable(null)
            holder.placeholder.visibility = android.view.View.VISIBLE
        }
        holder.job = scope.launch {
            try {
                val bmp = pdf.render(position, target) ?: return@launch
                if (holder.page == position) showBitmap(holder, position, bmp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (holder.page == position) holder.placeholder.text = "${position + 1}\n(표시 오류)"
            } catch (e: OutOfMemoryError) {
                pdf.trimMemory()
            }
        }
    }

    private fun showBitmap(holder: PageHolder, position: Int, bmp: android.graphics.Bitmap) {
        holder.image.setImageBitmap(bmp)
        holder.placeholder.visibility = android.view.View.GONE
        // 실제 페이지 비율이 추정치와 다르면 높이를 보정
        val width = holder.image.width.takeIf { it > 0 } ?: viewWidth
        if (width > 0) {
            val h = (width * pdf.aspectRatio(position)).toInt()
            val lp = holder.image.layoutParams
            if (h > 0 && kotlin.math.abs(lp.height - h) > 1) {
                lp.height = h
                holder.image.layoutParams = lp
            }
        }
    }

    private fun applyFilter(image: ImageView) {
        image.colorFilter = if (nightMode) INVERT else null
        image.setBackgroundColor(if (nightMode) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.job?.cancel()
        holder.job = null
        holder.page = -1
        holder.image.setImageDrawable(null)
    }

    companion object {
        private const val PAYLOAD_FILTER = "filter"
        private const val PAYLOAD_RERENDER = "rerender"

        private val INVERT = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
}
