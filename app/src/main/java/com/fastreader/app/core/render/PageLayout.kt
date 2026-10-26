package com.fastreader.app.core.render

import com.fastreader.app.core.doc.BreakType
import com.fastreader.app.core.doc.DrawObject
import com.fastreader.app.core.doc.HeaderFooter
import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.PageApply
import com.fastreader.app.core.doc.PageDef
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.ParaShape
import com.fastreader.app.core.doc.RichDocument
import com.fastreader.app.core.doc.Section
import com.fastreader.app.core.doc.Shape
import com.fastreader.app.core.doc.Table
import com.fastreader.app.core.doc.TextWrap
import com.fastreader.app.core.doc.VertRel
import java.util.IdentityHashMap

/**
 * 원본 보기 쪽 나누기.
 *
 * 한글이 저장한 줄 위치(LineSeg.vertPos)를 그대로 쓴다. 최상위 문단의 줄 vertPos 가 앞 줄보다 작아지면 새 쪽(다단이면 다음 단).
 * (실제 파일에는 "쪽의 첫 줄" 플래그 bit0 이 거의 없어서 vertPos 로 판단한다.)
 * 쪽 아래를 넘는 위아래 배치 표는 행 경계에서 잘라 다음 쪽들에 이어 그리고(제목 행 반복), 그 뒤 문단은 표가 끝나는 쪽에 온다.
 * 한글은 이런 표의 높이로 "첫 쪽 조각" 높이를 저장하고, 다음 줄의 vertPos 는 표가 끝나는 쪽 기준이다.
 * 줄 정보가 없는 문단은 높이를 추정해 흐름 배치한다.
 */
internal class PageLayout(private val doc: RichDocument, private val index: DocIndex, private val geom: Geom) {

    class LineRun(
        val para: Paragraph,
        val from: Int,
        val to: Int,
        val col: Int,
        /** vertPos 에 더할 값 (미주처럼 다른 기준의 줄을 본문에 놓을 때) */
        val dy: Int,
        /** 최상위 문단 index (미주 등은 -1) */
        val top: Int,
    )

    /** 줄 정보 없는 문단 묶음 (흐름 배치) */
    class FlowRun(val paras: List<Paragraph>, val y: Int, val col: Int, val height: Int)

    /** 떠 있는 개체 (또는 쪽을 넘는 표의 조각) */
    class ObjPlace(
        val para: Paragraph,
        val obj: Inline.Object,
        val col: Int,
        /** 문단 기준(VertRel.PARA) 위치: 이 쪽에서 그 문단 첫 줄의 vertPos (본문 기준) */
        val paraTop: Int,
        /** 표 조각: 표 안 세로 범위 [fragStart, fragEnd) (전체면 0, -1) */
        val fragStart: Int = 0,
        val fragEnd: Int = -1,
        /** 이어지는 조각이면 본문 위쪽부터 놓는다 */
        val continuation: Boolean = false,
        /** 반복할 제목 행 높이 (표 좌표, 0 이면 없음) */
        val headerHeight: Int = 0,
    )

    class Page(val index: Int, val section: Int, val pageInSection: Int, val def: PageDef) {
        var number = 1
        val lines = ArrayList<LineRun>()
        val flows = ArrayList<FlowRun>()
        val objects = ArrayList<ObjPlace>()
        val notes = ArrayList<Inline.Note>()
        var header: HeaderFooter? = null
        var footer: HeaderFooter? = null
        var showPageNumber = true
        /** 미주 구분선 위치 (본문 기준, 없으면 -1) */
        var endnoteRuleY = -1
        /** 이 쪽에서 시작하는 첫 최상위 문단 key (없으면 앞 쪽 값) */
        var firstKey = 0
        /** 이 쪽에 놓인 첫/마지막 최상위 문단 index */
        var firstTop = Int.MAX_VALUE
        var lastTop = -1
        val hasContent get() = lines.isNotEmpty() || flows.isNotEmpty() || objects.isNotEmpty()
    }

    val pages = ArrayList<Page>()

    /** [section][최상위 문단] → 첫 줄이 놓인 쪽 */
    private val paraFirstPage: Array<IntArray> = Array(doc.sections.size) { IntArray(doc.sections[it].paragraphs.size) { -1 } }

    /** 미주 문단 → 쪽 */
    private val notePage = IdentityHashMap<Paragraph, Int>()

    /** 쪽을 넘는 표: 행 → 쪽 */
    private val tableRowPage = IdentityHashMap<Table, IntArray>()

    /** [section] → 그 구역 첫 쪽 */
    val sectionFirstPage = IntArray(doc.sections.size)

    init {
        for ((si, s) in doc.sections.withIndex()) {
            sectionFirstPage[si] = pages.size
            layoutSection(si, s)
        }
        if (pages.isEmpty()) pages.add(Page(0, 0, 0, doc.sections.firstOrNull()?.page ?: PageDef()))
        assignNumbers()
        for ((si, s) in doc.sections.withIndex()) assignHeaders(si, s)
        var key = 0
        for (p in pages) {
            if (p.firstTop != Int.MAX_VALUE) {
                val k = index.topKeys.getOrNull(p.section)?.getOrNull(p.firstTop) ?: -1
                if (k >= 0) key = k
            }
            p.firstKey = key
        }
    }

    // ------------------------------------------------------------------ 구역 배치

    private inner class SectionState(val si: Int, val s: Section) {
        val def = s.page
        val bodyH = maxOf(1000, def.bodyHeight)
        val cols = s.columns.count.coerceIn(1, 16)
        var page: Page = newPage()
        var col = 0
        var lastV = Int.MIN_VALUE
        /** 본문에서 마지막으로 차지한 아래 위치 (흐름 배치용) */
        var cursorY = 0
        /** 표가 다음 쪽(들)로 넘어갔으면 다음 줄은 그 표가 끝나는 쪽에 놓는다 */
        var spillTarget: Page? = null
        var spillEndY = 0
        val flowBuf = ArrayList<Paragraph>()
        var flowY = 0
        var flowH = 0

        fun newPage(): Page {
            val p = Page(pages.size, si, pages.size - sectionFirstPage[si], def)
            pages.add(p)
            return p
        }

        fun breakPage() {
            flushFlow()
            val target = spillTarget
            if (target != null) {
                page = target
                cursorY = spillEndY
                spillTarget = null
            } else {
                if (page.hasContent || page.notes.isNotEmpty()) page = newPage()
                cursorY = 0
            }
            col = 0
            lastV = Int.MIN_VALUE
        }

        fun breakColumn() {
            flushFlow()
            if (spillTarget != null || col + 1 >= cols) {
                breakPage()
                return
            }
            col++
            lastV = Int.MIN_VALUE
            cursorY = 0
        }

        fun flushFlow() {
            if (flowBuf.isEmpty()) return
            page.flows.add(FlowRun(ArrayList(flowBuf), flowY, col, flowH))
            flowBuf.clear()
        }
    }

    private fun layoutSection(si: Int, s: Section) {
        val st = SectionState(si, s)
        val colWidth = columnWidth(s, 0)
        val paras = s.paragraphs
        for ((pi, p) in paras.withIndex()) {
            if (p.hidden) continue
            val ps = doc.paraShapes[p.paraShapeId] ?: ParaShape.DEFAULT
            val forced = (p.breakType == BreakType.PAGE || p.breakType == BreakType.SECTION || ps.pageBreakBefore)

            if (p.lines.isEmpty()) {
                // 줄 정보 없음: 높이를 추정해 흐름 배치
                if (st.spillTarget != null) st.breakPage()
                if (forced && (st.page.hasContent || st.flowBuf.isNotEmpty())) st.breakPage()
                else if (p.breakType == BreakType.COLUMN && st.cols > 1) st.breakColumn()
                val h = geom.paraHeight(p, colWidth)
                var y = st.cursorY + (if (st.cursorY > 0) ps.spacingBefore.coerceIn(0, 50000) else 0)
                if (y + h > st.bodyH && (st.page.hasContent || st.flowBuf.isNotEmpty())) {
                    st.breakColumn()
                    y = st.cursorY
                }
                if (st.flowBuf.isEmpty()) {
                    st.flowY = y; st.flowH = 0
                }
                st.flowBuf.add(p)
                st.flowH = y + h - st.flowY
                st.cursorY = y + h + ps.spacingAfter.coerceIn(0, 50000)
                markTop(st.page, si, pi)
                collectNotes(st.page, p, Int.MIN_VALUE, Int.MAX_VALUE)
                continue
            }
            st.flushFlow()

            val ls = LineStarts(p)
            var runFrom = 0
            var runPage = st.page
            var runCol = st.col
            fun closeRun(to: Int) {
                if (to > runFrom) {
                    runPage.lines.add(LineRun(p, runFrom, to, runCol, 0, pi))
                    markTop(runPage, si, pi)
                    collectNotes(runPage, p, ls.starts[runFrom], ls.end(to - 1))
                }
            }
            val linePages = IntArray(p.lines.size)
            val lineCols = IntArray(p.lines.size)
            for ((li, line) in p.lines.withIndex()) {
                val v = line.vertPos
                var brk = 0 // 1 쪽, 2 단
                if (li == 0 && st.spillTarget != null) {
                    // 앞 표가 다음 쪽으로 넘어갔다: 표가 끝나는 쪽으로 (쪽 나누기가 있으면 그 다음 쪽)
                    closeRun(0)
                    st.breakPage()
                    if (forced) st.breakPage()
                    runPage = st.page
                    runCol = st.col
                } else if (li == 0 && forced && st.page.hasContent) brk = 1
                else if (li == 0 && p.breakType == BreakType.COLUMN && st.cols > 1 && st.page.hasContent) brk = 2
                else if (v < st.lastV) brk = if (st.cols > 1 && st.col + 1 < st.cols && !(li == 0 && forced)) 2 else 1
                if (brk != 0) {
                    closeRun(li)
                    if (brk == 1) st.breakPage() else st.breakColumn()
                    runFrom = li
                    runPage = st.page
                    runCol = st.col
                }
                linePages[li] = st.page.index
                lineCols[li] = st.col
                st.lastV = v
                st.cursorY = maxOf(st.cursorY, v + maxOf(0, line.height) + maxOf(0, line.spacing))
            }
            closeRun(p.lines.size)
            if (paraFirstPage[si][pi] < 0) paraFirstPage[si][pi] = linePages[0]

            // 떠 있는 개체: 기준 위치가 든 줄이 놓인 쪽에
            for (inl in p.content) {
                if (inl !is Inline.Object) continue
                val o = inl.obj
                if (o.layout.treatAsChar) continue
                val li = ls.lineOf(inl.start)
                val pg = pages[linePages[li]]
                val col = lineCols[li]
                var firstLi = li
                while (firstLi > 0 && linePages[firstLi - 1] == pg.index && lineCols[firstLi - 1] == col) firstLi--
                val paraTop = p.lines[firstLi].vertPos
                if (o is Table && flowsWithText(o) && o.layout.vertRelTo == VertRel.PARA) {
                    // 기준 줄 다음 줄 (같은 문단 또는 다음 문단 첫 줄)
                    val nextV = if (li + 1 < p.lines.size) p.lines[li + 1].vertPos else nextLineV(paras, pi)
                    placeFlowTable(st, p, inl, o, pg, col, paraTop, nextV)
                } else {
                    pg.objects.add(ObjPlace(p, inl, col, paraTop))
                }
            }
        }
        st.flushFlow()
        placeEndnotes(st)
    }

    private fun nextLineV(paras: List<Paragraph>, pi: Int): Int? {
        for (k in pi + 1 until paras.size) {
            val q = paras[k]
            if (q.hidden) continue
            return q.lines.firstOrNull()?.vertPos
        }
        return null
    }

    private fun flowsWithText(o: DrawObject): Boolean {
        val w = o.layout.textWrap
        return w == TextWrap.TOP_AND_BOTTOM || w == TextWrap.SQUARE || w == TextWrap.TIGHT || w == TextWrap.THROUGH
    }

    /**
     * 문단 기준 위아래 배치 표. 쪽 아래를 넘으면 행 경계에서 잘라 다음 쪽들에 이어 놓는다.
     * 넘쳤는지는 "저장된 높이로 놓았을 때 다음 줄이 표 바로 아래에 오는가" 로 판단한다
     * (넘친 표는 한글이 첫 쪽 조각 높이만 저장하고, 다음 줄은 다른 쪽에 있다).
     */
    private fun placeFlowTable(st: SectionState, p: Paragraph, inl: Inline.Object, t: Table, pg: Page, col: Int, paraTop: Int, nextV: Int?) {
        val l = t.layout
        val g = geom.table(t)
        val stored = l.height.coerceAtLeast(0)
        val full = if (g.height > stored + 50) g.height else maxOf(stored, g.height)
        val top = paraTop + l.vertOffset + l.outMargin.top
        val bodyH = st.bodyH
        val overflows = top + full > bodyH + 100
        val nextRightAfter = nextV != null && nextV >= top + stored + l.outMargin.bottom - 400
        if (!overflows || nextRightAfter || g.rows < 1 || top >= bodyH || g.cells.isEmpty()) {
            pg.objects.add(ObjPlace(p, inl, col, paraTop))
            return
        }
        // 반복할 제목 행
        var headerRows = 0
        if (t.repeatHeader) {
            while (headerRows < g.rows) {
                val rowCells = g.cells.filter { it.row == headerRows }
                if (rowCells.isEmpty() || !rowCells.all { it.cell.header }) break
                headerRows++
            }
        }
        val headerH = if (headerRows in 1 until g.rows) g.rowY[headerRows] else 0
        val rowPage = IntArray(g.rows) { -1 }

        fun cutAt(start: Int, avail: Int): Int {
            // start 부터 avail 안에 들어가는 가장 아래 행 경계 (한 행도 안 들어가면 쪽 높이로 자른다)
            var r = 0
            while (r <= g.rows && g.rowY[r] <= start) r++
            var best = -1
            while (r <= g.rows && g.rowY[r] - start <= avail + 50) {
                best = g.rowY[r]; r++
            }
            return if (best > start) best else minOf(g.height, start + maxOf(1000, avail))
        }

        var end = cutAt(0, bodyH - top)
        // 첫 행조차 들어가지 않으면 (드묾) 남은 높이만큼 잘라 보인다
        if (end <= 0) end = minOf(g.height, maxOf(1, bodyH - top))
        pg.objects.add(ObjPlace(p, inl, col, paraTop, 0, end, false, 0))
        markRows(g, rowPage, 0, end, pg.index)
        var curPage = pg
        var start = end
        var guard = 0
        while (start < g.height && guard++ < 10_000) {
            curPage = nextPageAfter(st, curPage)
            val hh = if (start >= headerH) headerH else 0
            val avail = bodyH - l.outMargin.top - hh
            val e = cutAt(start, avail)
            curPage.objects.add(ObjPlace(p, inl, 0, 0, start, e, true, hh))
            markRows(g, rowPage, start, e, curPage.index)
            st.spillEndY = l.outMargin.top + hh + (e - start) + l.outMargin.bottom
            start = e
        }
        st.spillTarget = curPage
        tableRowPage[t] = rowPage
    }

    private fun markRows(g: TableGeom, rowPage: IntArray, start: Int, end: Int, page: Int) {
        for (r in 0 until g.rows) if (g.rowY[r] >= start && g.rowY[r] < end && rowPage[r] < 0) rowPage[r] = page
    }

    /** p 다음 쪽 (같은 문단의 뒷줄이 이미 놓인 쪽이면 그 쪽, 아니면 새 쪽) */
    private fun nextPageAfter(st: SectionState, p: Page): Page {
        val next = pages.getOrNull(p.index + 1)
        if (next != null && next.section == st.si) return next
        st.flushFlow()
        return st.newPage()
    }

    /** 미주: 구역 끝 본문 아래에 이어서 (각 미주 문단의 줄 정보 그대로, 넘치면 다음 쪽) */
    private fun placeEndnotes(st: SectionState) {
        val notes = ArrayList<Inline.Note>()
        for (p in st.s.paragraphs) if (!p.hidden) collectAllNotes(p, notes, true, 0)
        if (notes.isEmpty()) return
        if (st.spillTarget != null) st.breakPage()
        var y = st.cursorY + ENDNOTE_GAP
        if (y > st.bodyH - 2000) {
            st.breakPage(); y = ENDNOTE_GAP
        }
        st.page.endnoteRuleY = y - ENDNOTE_GAP / 2
        for (n in notes) {
            for (np in n.paragraphs) {
                if (np.hidden) continue
                val h = linesExtent(np)
                if (h == null) {
                    val eh = geom.paraHeight(np, st.def.bodyWidth)
                    if (y + eh > st.bodyH && st.page.hasContent) {
                        st.breakPage(); y = 0
                    }
                    st.page.flows.add(FlowRun(listOf(np), y, 0, eh))
                    notePage[np] = st.page.index
                    y += eh
                    continue
                }
                val first = np.lines.first().vertPos
                if (y + (h - first) > st.bodyH && st.page.hasContent) {
                    st.breakPage(); y = 0
                }
                st.page.lines.add(LineRun(np, 0, np.lines.size, 0, y - first, -1))
                notePage[np] = st.page.index
                val last = np.lines.last()
                y += last.vertPos + last.height + last.spacing - first
            }
            y += NOTE_GAP
        }
        st.cursorY = y
    }

    private fun collectAllNotes(p: Paragraph, out: MutableList<Inline.Note>, endnote: Boolean, depth: Int) {
        if (depth > DocIndex.MAX_DEPTH) return
        for (inl in p.content) {
            when (inl) {
                is Inline.Note -> if (inl.endnote == endnote) out.add(inl)
                is Inline.Object -> forEachNested(inl.obj, depth) { collectAllNotes(it, out, endnote, depth + 1) }
                else -> {}
            }
        }
    }

    /** 각주: 이 쪽에 놓인 줄 범위 [from, to) 의 각주 (개체 안 각주 포함) */
    private fun collectNotes(page: Page, p: Paragraph, from: Int, to: Int) {
        for (inl in p.content) {
            if (inl.start < from || inl.start >= to) continue
            when (inl) {
                is Inline.Note -> if (!inl.endnote) page.notes.add(inl)
                is Inline.Object -> forEachNested(inl.obj, 0) { np -> collectAllNotes(np, page.notes, false, 1) }
                else -> {}
            }
        }
    }

    private fun forEachNested(o: DrawObject, depth: Int, f: (Paragraph) -> Unit) {
        if (depth > DocIndex.MAX_DEPTH) return
        when (o) {
            is Table -> for (c in o.cells) c.paragraphs.forEach(f)
            is Shape -> {
                o.paragraphs.forEach(f)
                for (c in o.children) forEachNested(c, depth + 1, f)
            }
            else -> {}
        }
        o.caption?.paragraphs?.forEach(f)
    }

    private fun markTop(page: Page, si: Int, pi: Int) {
        if (pi < page.firstTop) page.firstTop = pi
        if (pi > page.lastTop) page.lastTop = pi
        if (paraFirstPage[si][pi] < 0) paraFirstPage[si][pi] = page.index
    }

    // ------------------------------------------------------------------ 머리말/꼬리말/쪽 번호

    /** 머리말은 그 머리말이 정의된 문단이 있는 쪽부터. 홀/짝 쪽은 표시 쪽 번호로 */
    private fun assignHeaders(si: Int, s: Section) {
        val first = sectionFirstPage[si]
        val sortedHeaders = s.headers.withIndex().sortedWith(compareBy({ it.value.fromParagraph }, { it.index })).map { it.value }
        val sortedFooters = s.footers.withIndex().sortedWith(compareBy({ it.value.fromParagraph }, { it.index })).map { it.value }
        var lastTop = -1
        var i = first
        while (i < pages.size && pages[i].section == si) {
            val pg = pages[i]
            lastTop = maxOf(lastTop, pg.lastTop)
            pg.header = pick(sortedHeaders, lastTop, pg.number)
            pg.footer = pick(sortedFooters, lastTop, pg.number)
            if (i == first) {
                if (s.hideFirstHeader) pg.header = null
                if (s.hideFirstFooter) pg.footer = null
            }
            i++
        }
    }

    private fun pick(list: List<HeaderFooter>, lastTop: Int, number: Int): HeaderFooter? {
        if (list.isEmpty()) return null
        var both: HeaderFooter? = null
        var odd: HeaderFooter? = null
        var even: HeaderFooter? = null
        for (h in list) {
            if (h.fromParagraph > 0 && h.fromParagraph > lastTop) break
            when (h.apply) {
                PageApply.BOTH -> {
                    both = h; odd = null; even = null
                }
                PageApply.ODD -> odd = h
                PageApply.EVEN -> even = h
            }
        }
        return (if (number % 2 != 0) odd else even) ?: both
    }

    private fun assignNumbers() {
        var n = 0
        var lastSection = -1
        for (p in pages) {
            if (p.section != lastSection) {
                val start = doc.sections.getOrNull(p.section)?.startPageNumber ?: 0
                n = if (start > 0) start else n + 1
                lastSection = p.section
            } else n++
            p.number = n
        }
        for ((si, s) in doc.sections.withIndex()) {
            val first = sectionFirstPage[si]
            val pn = s.pageNumbering
            var i = first
            val fromPage = if (pn != null && pn.fromParagraph > 0) firstPageOf(si, pn.fromParagraph.coerceAtMost(s.paragraphs.size - 1)) else first
            while (i < pages.size && pages[i].section == si) {
                pages[i].showPageNumber = pn != null && i >= fromPage && !(i == first && s.hideFirstPageNumber)
                i++
            }
        }
    }

    // ------------------------------------------------------------------ 조회

    /** 최상위 문단 top 의 line 번째 줄이 있는 쪽 */
    fun pageOfLine(si: Int, top: Int, line: Int): Int {
        val first = firstPageOf(si, top)
        var i = first
        while (i < pages.size && pages[i].section == si && i < first + 4000) {
            val pg = pages[i]
            for (r in pg.lines) if (r.top == top && line >= r.from && line < r.to) return i
            if (pg.firstTop > top) break
            i++
        }
        return first
    }

    fun firstPageOf(si: Int, top: Int): Int {
        val arr = paraFirstPage.getOrNull(si) ?: return 0
        if (top in arr.indices && arr[top] >= 0) return arr[top]
        for (k in minOf(top, arr.size - 1) downTo 0) if (arr[k] >= 0) return arr[k]
        return sectionFirstPage.getOrElse(si) { 0 }
    }

    fun pageOfEndnote(p: Paragraph): Int? = notePage[p]

    fun pageOfTableRow(t: Table, row: Int): Int? = tableRowPage[t]?.getOrNull(row)?.takeIf { it >= 0 }

    fun columnLeft(s: Section, col: Int): Int {
        val c = s.columns
        if (c.count <= 1 || col <= 0) return 0
        val n = c.count.coerceIn(1, 16)
        val gap = c.gap.coerceIn(0, 100000)
        if (c.widths.size >= n) {
            var x = 0
            for (k in 0 until col.coerceAtMost(n - 1)) x += c.widths[k].coerceAtLeast(0) + gap
            return x
        }
        val w = (s.page.bodyWidth - gap * (n - 1)) / n
        return col.coerceAtMost(n - 1) * (w + gap)
    }

    fun columnWidth(s: Section, col: Int): Int {
        val c = s.columns
        if (c.count <= 1) return s.page.bodyWidth
        val n = c.count.coerceIn(1, 16)
        if (c.widths.size >= n) return c.widths[col.coerceIn(0, n - 1)].coerceAtLeast(0)
        return (s.page.bodyWidth - c.gap.coerceIn(0, 100000) * (n - 1)) / n
    }

    companion object {
        const val ENDNOTE_GAP = 1700
        const val NOTE_GAP = 283
    }
}
