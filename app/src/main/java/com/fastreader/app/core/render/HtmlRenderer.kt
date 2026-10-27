package com.fastreader.app.core.render

import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.RichDocument
import com.fastreader.app.core.render.Html.json
import com.fastreader.app.core.render.Html.num

/**
 * RichDocument → HTML.
 *
 * 두 가지 보기를 만든다.
 * - [ViewMode.PAGE] 원본 보기: 원본의 쪽 크기/여백/줄 위치(LINE_SEG)를 그대로 써서 한글과 같은 모양으로 쪽 단위 표시.
 * - [ViewMode.FLOW] 읽기 모드: 화면 폭에 맞춰 다시 흐르게 하되 글자/문단 모양, 줄바꿈, 들여쓰기, 표, 그림을 살린다.
 *
 * 스레드 안전해야 한다 (WebView 자바스크립트 브리지는 백그라운드 스레드에서 [pageHtml]/[flowChunkHtml] 을 부른다).
 * 큰 문서도 빨리 열리도록 쪽/덩어리 단위로 필요할 때 만든다.
 *
 * HTML ↔ Android 계약은 docs/rich-rendering.md 참고.
 */
class HtmlRenderer(val doc: RichDocument) {

    private val ctx: Ctx by lazy { Ctx(doc) }
    private val layout: PageLayout by lazy { PageLayout(doc, ctx.index, ctx.geom) }
    private val chunks: List<FlowChunk> by lazy { FlowChunks.build(ctx) }

    /** 조각(쪽/덩어리)은 옵션 없이 요청되므로 마지막으로 만든 쉘의 테마를 따른다 */
    @Volatile
    private var dark = false

    /** 원본 보기 쪽 수 (구역 전체) */
    val pageCount: Int
        get() = layout.pages.size

    /** 읽기 모드 덩어리 수 */
    val flowChunkCount: Int
        get() = chunks.size

    /** 처음 로드하는 전체 HTML 문서 (CSS, JS 포함) */
    fun shellHtml(mode: ViewMode, options: RenderOptions = RenderOptions()): String = shell(mode, options, standalone = false)

    /** 원본 보기 index 번째 쪽의 내부 HTML 조각 */
    fun pageHtml(index: Int): String {
        if (index !in 0 until pageCount) return ""
        return try {
            PageRenderer(ctx, layout, index).render()
        } catch (e: OutOfMemoryError) {
            ERROR_HTML
        } catch (e: RuntimeException) {
            ERROR_HTML
        }
    }

    /** 읽기 모드 index 번째 덩어리의 HTML 조각 */
    fun flowChunkHtml(index: Int): String {
        if (index !in 0 until flowChunkCount) return ""
        return try {
            FlowRenderer.chunk(ctx, chunks, index, dark)
        } catch (e: OutOfMemoryError) {
            "<div class=\"p\">(이 부분을 표시하지 못했습니다)</div>"
        } catch (e: RuntimeException) {
            "<div class=\"p\">(이 부분을 표시하지 못했습니다)</div>"
        }
    }

    /** 본문 검색 (대소문자 무시). 문서 순서대로 최대 limit 개 */
    fun search(query: String, limit: Int = 2000): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty() || limit <= 0) return emptyList()
        val out = ArrayList<SearchHit>()
        for (e in ctx.index.entries) {
            val text = e.para.plainText
            var idx = text.indexOf(q, 0, ignoreCase = true)
            while (idx >= 0) {
                out.add(SearchHit("p${e.key}", idx, q.length, pageOf(e, idx), chunkOf(e.section, e.top), preview(text, idx, q.length)))
                if (out.size >= limit) return out
                idx = text.indexOf(q, idx + q.length, ignoreCase = true)
            }
        }
        return out
    }

    /** 전체 텍스트 (복사용). maxChars 를 넘으면 자른다 */
    fun plainText(maxChars: Int = 300_000): String {
        val sb = StringBuilder()
        for (e in ctx.index.entries) {
            if (e.kind != 0 && e.kind != 1) continue
            val t = e.para.plainText
            if (sb.length + t.length + 1 > maxChars) {
                sb.append(t, 0, (maxChars - sb.length).coerceIn(0, t.length))
                break
            }
            sb.append(t).append('\n')
        }
        return sb.toString()
    }

    /** 데스크톱 브라우저 확인용: 모든 조각을 window.FR_FRAGMENTS 로 넣은 독립 HTML (최대 [maxFragments] 개) */
    fun standaloneHtml(mode: ViewMode, options: RenderOptions = RenderOptions(), maxFragments: Int = 400): String =
        shell(mode, options, standalone = true, maxFragments = maxFragments)

    // ------------------------------------------------------------------ 쉘

    private fun shell(mode: ViewMode, options: RenderOptions, standalone: Boolean, maxFragments: Int = 0): String {
        dark = options.darkMode
        val page = mode == ViewMode.PAGE
        val sb = StringBuilder(64 * 1024)
        sb.append("<!doctype html><html lang=\"ko\" data-mode=\"").append(if (page) "page" else "flow").append('"')
        if (!page && options.textScale != 1f) sb.append(" style=\"font-size:").num(16.0 * options.textScale.coerceIn(0.6f, 3f)).append("px\"")
        sb.append("><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=5,user-scalable=yes\">")
        sb.append("<style>").append(if (page) RuntimeAssets.PAGE_CSS else RuntimeAssets.FLOW_CSS)
        Css(doc, mode, options.darkMode).build(sb)
        sb.append("</style></head><body")
        if (options.darkMode) sb.append(" class=\"dk\"")
        sb.append('>')
        if (page) {
            val pages = layout.pages
            val maxW = pages.maxOfOrNull { it.def.width } ?: 59528
            sb.append("<div id=\"doc\" data-maxw=\"").num(Html.px(maxW)).append("\">")
            for (p in pages) {
                sb.append("<div class=\"pg\" data-i=\"").append(p.index).append("\" style=\"width:").num(Html.px(p.def.width))
                    .append("px;height:").num(Html.px(p.def.height)).append("px\">")
                if (p.index < INLINE_PAGES) sb.append(pageHtml(p.index))
                sb.append("</div>")
            }
            sb.append("</div>")
            sb.append("<script>window.FR_META={keys:[")
            pages.forEachIndexed { i, p -> if (i > 0) sb.append(','); sb.append(p.firstKey) }
            sb.append("],keyCount:").append(ctx.index.keyCount).append("};</script>")
        } else {
            sb.append("<main id=\"flow\" data-total=\"").append(chunks.size).append("\"><div class=\"ck\" data-c=\"0\">")
            sb.append(flowChunkHtml(0))
            sb.append("</div><div id=\"sent\"></div></main>")
            sb.append("<script>window.FR_META={keys:[")
            chunks.forEachIndexed { i, c -> if (i > 0) sb.append(','); sb.append(c.firstKey) }
            sb.append("],keyCount:").append(maxOf(1, ctx.index.keyCount)).append("};</script>")
        }
        if (standalone) {
            sb.append("<script>window.FR_FRAGMENTS={").append(if (page) "page" else "flow").append(":[")
            val n = minOf(maxFragments, if (page) pageCount else flowChunkCount)
            for (i in 0 until n) {
                if (i > 0) sb.append(',')
                sb.json(if (page) pageHtml(i) else flowChunkHtml(i))
            }
            sb.append("]};</script>")
        }
        sb.append("<script>").append(RuntimeAssets.JS).append("</script></body></html>")
        return sb.toString()
    }

    // ------------------------------------------------------------------ 검색 위치

    private fun pageOf(e: DocIndex.Entry, plainOffset: Int): Int {
        val s = doc.sections.getOrNull(e.section) ?: return 0
        if (e.kind == 0) {
            val p = e.para
            if (p.lines.isEmpty()) return layout.firstPageOf(e.section, e.top)
            val line = LineStarts(p).lineOf(rawPosition(p, plainOffset))
            return layout.pageOfLine(e.section, e.top, line)
        }
        if (e.kind == 3) layout.pageOfEndnote(e.para)?.let { return it }
        e.table?.let { t -> layout.pageOfTableRow(t, e.row)?.let { return it } }
        val top = s.paragraphs.getOrNull(e.top) ?: return layout.firstPageOf(e.section, e.top)
        if (top.lines.isEmpty() || e.anchorPos < 0) return layout.firstPageOf(e.section, e.top)
        return layout.pageOfLine(e.section, e.top, LineStarts(top).lineOf(e.anchorPos))
    }

    /** plainText 위치 → 문단 안 위치(Inline.start 좌표) */
    private fun rawPosition(p: Paragraph, plainOffset: Int): Int {
        var off = plainOffset
        var last = 0
        for (i in p.content) {
            when (i) {
                is Inline.Text -> {
                    if (off < i.text.length) return i.start + off
                    off -= i.text.length
                    last = i.start + i.text.length
                }
                is Inline.Tab, is Inline.LineBreak -> {
                    if (off == 0) return i.start
                    off--
                    last = i.start + 1
                }
                else -> {}
            }
        }
        return last
    }

    private fun chunkOf(section: Int, top: Int): Int {
        var lo = 0
        var hi = chunks.size - 1
        while (lo < hi) {
            val m = (lo + hi + 1) ushr 1
            val c = chunks[m]
            if (c.section < section || (c.section == section && c.from <= top)) lo = m else hi = m - 1
        }
        return lo
    }

    private fun preview(text: String, idx: Int, len: Int): String {
        val a = (idx - 24).coerceAtLeast(0)
        val b = (idx + len + 40).coerceAtMost(text.length)
        return (if (a > 0) "…" else "") + text.substring(a, b).replace('\n', ' ').replace('\t', ' ') + (if (b < text.length) "…" else "")
    }

    private companion object {
        /** 쉘에 바로 넣는 앞쪽 쪽 수 (첫 화면을 브리지 호출 없이 바로 보이게) */
        const val INLINE_PAGES = 2
        const val ERROR_HTML = "<div class=\"err\">이 쪽을 그리지 못했습니다</div>"
    }
}

enum class ViewMode { PAGE, FLOW }

data class RenderOptions(
    /** 앱이 어두운 테마인지 (읽기 모드 색/원본 보기 바탕색에 반영) */
    val darkMode: Boolean = false,
    /** 읽기 모드 글자 배율 (1.0 = 기본) */
    val textScale: Float = 1f,
)

/**
 * 검색 결과.
 * @param paraKey 문단 요소의 data-p 값
 * @param offset 그 문단 plainText 안에서의 시작 위치
 * @param page 원본 보기에서 그 위치가 있는 쪽 (0부터)
 * @param chunk 읽기 모드에서 그 문단이 있는 덩어리 (0부터)
 * @param preview 결과 주변 문장 (목록 표시용)
 */
data class SearchHit(
    val paraKey: String,
    val offset: Int,
    val length: Int,
    val page: Int,
    val chunk: Int,
    val preview: String,
)

/** WebView 기준 주소. 그림은 BASE_URL + ImageInfo.path 로 요청된다 */
const val BASE_URL = "https://fastreader.local/"
