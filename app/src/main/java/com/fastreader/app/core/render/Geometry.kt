package com.fastreader.app.core.render

import com.fastreader.app.core.doc.Cell
import com.fastreader.app.core.doc.CharShape
import com.fastreader.app.core.doc.DrawObject
import com.fastreader.app.core.doc.Equation
import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.LineSeg
import com.fastreader.app.core.doc.LineSpacingType
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.ParaShape
import com.fastreader.app.core.doc.Picture
import com.fastreader.app.core.doc.RichDocument
import com.fastreader.app.core.doc.Shape
import com.fastreader.app.core.doc.Table
import com.fastreader.app.core.doc.TextWrap
import com.fastreader.app.core.doc.VertRel
import java.util.Collections
import java.util.IdentityHashMap

/** 줄(LineSeg) 시작 위치를 단조 증가로 정리한 것 */
internal class LineStarts(p: Paragraph) {
    val lines: List<LineSeg> = p.lines
    val starts = IntArray(lines.size)

    init {
        var prev = Int.MIN_VALUE
        for (i in lines.indices) {
            val s = if (i == 0) Int.MIN_VALUE else maxOf(prev, lines[i].textStart)
            starts[i] = s
            prev = s
        }
    }

    /** pos 가 속한 줄 */
    fun lineOf(pos: Int): Int {
        if (starts.isEmpty()) return 0
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= pos) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun end(i: Int): Int = if (i + 1 < starts.size) starts[i + 1] else Int.MAX_VALUE
}

/** 줄 들여쓰기가 적용되는가 (LineSeg bit20, 플래그가 없으면 들여쓰기 부호로 추정) */
internal fun indentApplies(p: Paragraph, lineIndex: Int, indent: Int): Boolean {
    if (indent == 0) return false
    val l = p.lines.getOrNull(lineIndex) ?: return false
    val anyFlags = p.lines.any { it.flags and 0x60000 != 0 }
    return if (anyFlags) l.flags and (1 shl 20) != 0 else (if (indent > 0) lineIndex == 0 else lineIndex > 0)
}

/** 문단 안 글자/개체 내용의 세로 범위 (줄 기준). 줄이 없으면 null */
internal fun linesExtent(p: Paragraph): Int? {
    if (p.lines.isEmpty()) return null
    var max = 0
    for (l in p.lines) max = maxOf(max, l.vertPos + maxOf(0, l.height))
    return max
}

/**
 * 표 격자. 열 경계는 셀 폭과 열 주소로, 행 경계는 셀 높이와 "셀 내용 높이" 중 큰 값으로 정한다
 * (한글은 셀 높이를 최솟값으로 저장해 두는 경우가 많아 내용이 더 크면 행이 늘어난다).
 */
internal class TableGeom(
    val colX: IntArray,
    val rowY: IntArray,
    /** 격자 안에 들어오는 셀 (주소/병합 범위 정리됨) */
    val cells: List<PlacedCell>,
) {
    val width get() = colX.last()
    val height get() = rowY.last()
    val rows get() = rowY.size - 1

    class PlacedCell(val cell: Cell, val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int, val contentHeight: Int)

    fun x(c: PlacedCell) = colX[c.col]
    fun y(c: PlacedCell) = rowY[c.row]
    fun w(c: PlacedCell) = colX[c.col + c.colSpan] - colX[c.col]
    fun h(c: PlacedCell) = rowY[c.row + c.rowSpan] - rowY[c.row]
}

/**
 * 문서별 크기 계산 (표 격자는 캐시). 여러 스레드에서 불러도 된다.
 */
internal class Geom(val doc: RichDocument) {

    private val tables: MutableMap<Table, TableGeom> = Collections.synchronizedMap(IdentityHashMap())

    fun table(t: Table, depth: Int = 0): TableGeom {
        tables[t]?.let { return it }
        val g = computeTable(t, depth)
        if (tables.size > 200_000) tables.clear()
        tables[t] = g
        return g
    }

    private fun computeTable(t: Table, depth: Int): TableGeom {
        // 실제 셀 주소로 격자 크기를 정한다 (rowCount/colCount 가 틀린 파일 대비)
        var rows = 0
        var cols = 0
        val valid = ArrayList<Cell>(t.cells.size)
        for (c in t.cells) {
            if (c.row < 0 || c.col < 0 || c.row >= MAX_ROWS || c.col >= MAX_COLS) continue
            valid.add(c)
            rows = maxOf(rows, c.row + c.rowSpan.coerceIn(1, MAX_ROWS))
            cols = maxOf(cols, c.col + c.colSpan.coerceIn(1, MAX_COLS))
        }
        rows = rows.coerceAtMost(MAX_ROWS)
        cols = cols.coerceAtMost(MAX_COLS)
        if (rows == 0 || cols == 0) {
            return TableGeom(intArrayOf(0, maxOf(0, t.layout.width)), intArrayOf(0, maxOf(0, t.layout.height)), emptyList())
        }
        val placed = ArrayList<TableGeom.PlacedCell>(valid.size)
        for (c in valid) {
            val rs = c.rowSpan.coerceIn(1, rows - c.row)
            val cs = c.colSpan.coerceIn(1, cols - c.col)
            val content = if (depth >= DocIndex.MAX_DEPTH) 0 else paragraphsHeight(c.paragraphs, maxOf(0, c.width - c.margin.left - c.margin.right), depth + 1)
            placed.add(TableGeom.PlacedCell(c, c.row, c.col, rs, cs, content))
        }

        // 열 경계 (한 칸짜리 셀부터 왼쪽에서 오른쪽으로 전파)
        val x = LongArray(cols + 1) { -1L }
        x[0] = 0
        val byCol = placed.sortedWith(compareBy({ it.colSpan }, { it.col }))
        for (pass in 0 until 4) {
            var changed = false
            for (c in byCol) {
                val a = c.col
                val b = c.col + c.colSpan
                val w = c.cell.width.coerceIn(0, 10_000_000).toLong()
                if (x[a] >= 0 && x[b] < 0) {
                    x[b] = x[a] + w; changed = true
                } else if (x[b] >= 0 && x[a] < 0) {
                    x[a] = maxOf(0L, x[b] - w); changed = true
                }
            }
            if (!changed) break
        }
        if (x[cols] < 0) x[cols] = maxOf(t.layout.width.toLong(), x.maxOrNull() ?: 0L)
        fillUnknown(x)

        // 행 높이: 한 행짜리 셀 먼저, 그다음 병합 셀이 모자라면 늘린다
        val rh = LongArray(rows)
        for (c in placed) if (c.rowSpan == 1) rh[c.row] = maxOf(rh[c.row], need(c))
        for (c in placed.filter { it.rowSpan > 1 }.sortedBy { it.rowSpan }) {
            var sum = 0L
            for (r in c.row until c.row + c.rowSpan) sum += rh[r]
            val deficit = need(c) - sum
            if (deficit > 0) {
                val empty = (c.row until c.row + c.rowSpan).filter { rh[it] == 0L }
                if (empty.isNotEmpty()) {
                    val each = deficit / empty.size
                    for (r in empty) rh[r] = each
                    rh[empty.last()] += deficit - each * empty.size
                } else rh[c.row + c.rowSpan - 1] += deficit
            }
        }
        val y = IntArray(rows + 1)
        var acc = 0L
        for (r in 0 until rows) {
            acc += rh[r]
            y[r + 1] = acc.coerceAtMost(Int.MAX_VALUE / 4L).toInt()
        }
        val xi = IntArray(cols + 1) { x[it].coerceIn(0L, Int.MAX_VALUE / 4L).toInt() }
        return TableGeom(xi, y, placed)
    }

    private fun need(c: TableGeom.PlacedCell): Long {
        val m = c.cell.margin
        val content = m.top.toLong() + c.contentHeight + m.bottom
        return maxOf(c.cell.height.coerceIn(0, 10_000_000).toLong(), content)
    }

    /** 모르는 경계는 앞뒤 아는 경계 사이를 고르게 나눈다 */
    private fun fillUnknown(x: LongArray) {
        var i = 0
        while (i < x.size) {
            if (x[i] >= 0) {
                i++; continue
            }
            var j = i
            while (j < x.size && x[j] < 0) j++
            val left = x[i - 1]
            val right = if (j < x.size) x[j] else left + (j - i + 1) * 1000L
            val n = j - i + 1
            for (k in i until j) x[k] = left + (right - left) * (k - i + 1) / n
            i = j
        }
        for (k in 1 until x.size) if (x[k] < x[k - 1]) x[k] = x[k - 1]
    }

    /** 문단 목록 높이: 줄 정보가 있으면 그 끝, 없으면 추정해 쌓는다 */
    fun paragraphsHeight(paras: List<Paragraph>, width: Int, depth: Int = 0): Int {
        var max = 0
        var y = 0
        for (p in paras) {
            if (p.hidden) continue
            val ext = linesExtent(p)
            if (ext != null) {
                max = maxOf(max, ext)
                val last = p.lines.last()
                y = maxOf(y, last.vertPos + last.height + last.spacing)
            } else {
                val ps = doc.paraShapes[p.paraShapeId] ?: ParaShape.DEFAULT
                y += ps.spacingBefore.coerceIn(0, 50000)
                y += paraHeight(p, width, depth)
                max = maxOf(max, y)
                y += ps.spacingAfter.coerceIn(0, 50000)
            }
        }
        return max
    }

    fun charShape(id: Int): CharShape = doc.charShapes[id] ?: CharShape.DEFAULT

    /** 문단의 대표 글자 크기 (HWPUNIT) */
    fun fontSize(p: Paragraph): Int {
        var max = 0
        for (i in p.content) {
            val cs = when (i) {
                is Inline.Text -> i.charShapeId
                is Inline.Tab -> i.charShapeId
                is Inline.LineBreak -> i.charShapeId
                else -> continue
            }
            max = maxOf(max, charShape(cs).baseSize.coerceIn(100, 40000))
        }
        return if (max == 0) 1000 else max
    }

    /** 줄 정보가 없는 문단의 높이 추정 (한글 프로그램 글자 폭에 가깝게 대략). width = 영역 폭 */
    fun paraHeight(p: Paragraph, width0: Int, depth: Int = 0): Int {
        val ps = doc.paraShapes[p.paraShapeId] ?: ParaShape.DEFAULT
        val width = maxOf(2000, width0 - ps.marginLeft.coerceAtLeast(0) - ps.marginRight.coerceAtLeast(0))
        val size = fontSize(p)
        val lh = lineHeight(ps, size)
        var lines = 1
        var x = maxOf(0, ps.indent).toLong()
        var extra = 0
        var lineObj = 0
        for (inl in p.content) {
            when (inl) {
                is Inline.Text -> {
                    val cs = charShape(inl.charShapeId)
                    val sz = cs.baseSize.coerceIn(100, 40000)
                    for (ch in inl.text) {
                        val cw = charWidth(ch, sz, cs)
                        x += cw
                        if (x > width) {
                            lines++
                            x = cw + (if (ps.indent < 0) -ps.indent.toLong() else 0L)
                        }
                    }
                }
                is Inline.Tab -> x += if (inl.width > 0) inl.width else 4000
                is Inline.LineBreak -> {
                    lines++; x = 0
                }
                is Inline.Object -> {
                    val l = inl.obj.layout
                    val h = objectHeight(inl.obj, depth + 1) + l.outMargin.top + l.outMargin.bottom
                    if (l.treatAsChar) {
                        x += l.width
                        if (x > width && x != l.width.toLong()) {
                            lines++; x = l.width.toLong()
                        }
                        lineObj = maxOf(lineObj, h)
                    } else if (l.vertRelTo == VertRel.PARA && (l.textWrap == TextWrap.TOP_AND_BOTTOM || l.textWrap == TextWrap.SQUARE || l.textWrap == TextWrap.TIGHT)) {
                        extra += h + l.vertOffset.coerceIn(0, 100000)
                    }
                }
                else -> {}
            }
        }
        val total = lines.toLong() * lh + maxOf(0, lineObj - size) + extra
        return total.coerceAtMost(50_000_000L).toInt()
    }

    /** 개체 높이 (표는 계산한 격자 높이와 저장된 높이 중 큰 값, 위/아래 캡션 포함) */
    fun objectHeight(o: DrawObject, depth: Int = 0): Int {
        val body = when (o) {
            is Table -> if (depth >= DocIndex.MAX_DEPTH) o.layout.height else maxOf(o.layout.height, table(o, depth).height)
            is Picture, is Shape, is Equation -> o.layout.height
        }.coerceIn(0, 10_000_000)
        val cap = o.caption ?: return body
        if (cap.side == 0 || cap.side == 1 || depth >= DocIndex.MAX_DEPTH) return body
        val ch = paragraphsHeight(cap.paragraphs, if (cap.width > 0) cap.width else o.layout.width, depth + 1)
        return body + cap.gap.coerceIn(0, 20000) + ch
    }

    companion object {
        private const val MAX_ROWS = 20000
        private const val MAX_COLS = 512

        fun lineHeight(ps: ParaShape, size: Int): Int = when (ps.lineSpacingType) {
            LineSpacingType.PERCENT -> size * ps.lineSpacing.coerceIn(50, 500) / 100
            LineSpacingType.FIXED -> ps.lineSpacing.coerceIn(100, 50000)
            LineSpacingType.BETWEEN_LINES -> size + ps.lineSpacing.coerceIn(0, 50000)
            LineSpacingType.AT_LEAST -> maxOf(size * 13 / 10, ps.lineSpacing.coerceIn(0, 50000))
        }

        /** 글자 하나 폭 (HWPUNIT) */
        fun charWidth(c: Char, size: Int, cs: CharShape): Int {
            val em = when {
                c == ' ' -> 0.34
                c.code < 0x80 -> if (c.isUpperCase()) 0.66 else if (c.isDigit()) 0.55 else 0.52
                else -> 1.0
            }
            val ratio = cs.ratios.getOrElse(0) { 100 }.coerceIn(10, 500) / 100.0
            val sp = cs.spacings.getOrElse(0) { 0 }.coerceIn(-50, 100) / 100.0
            return ((em * ratio + sp) * size).toInt().coerceAtLeast(1)
        }
    }
}
