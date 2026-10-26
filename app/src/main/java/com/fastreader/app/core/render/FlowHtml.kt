package com.fastreader.app.core.render

import com.fastreader.app.core.doc.Align
import com.fastreader.app.core.doc.Caption
import com.fastreader.app.core.doc.DrawObject
import com.fastreader.app.core.doc.Equation
import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.LineSpacingType
import com.fastreader.app.core.doc.ObjAlign
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.ParaShape
import com.fastreader.app.core.doc.Picture
import com.fastreader.app.core.doc.Shape
import com.fastreader.app.core.doc.ShapeKind
import com.fastreader.app.core.doc.Table
import com.fastreader.app.core.render.Html.attr
import com.fastreader.app.core.render.Html.num
import com.fastreader.app.core.render.Html.px
import com.fastreader.app.core.render.Html.text

/** 읽기 모드 덩어리: 한 구역의 최상위 문단 [from, to) */
internal class FlowChunk(val section: Int, val from: Int, val to: Int, val firstKey: Int)

internal object FlowChunks {
    /** 덩어리 하나의 대략적인 크기 한도 (글자 수 기준) */
    private const val MAX_WEIGHT = 24_000
    private const val MAX_PARAS = 150

    fun build(ctx: Ctx): List<FlowChunk> {
        val out = ArrayList<FlowChunk>()
        for ((si, s) in ctx.doc.sections.withIndex()) {
            var from = 0
            var weight = 0
            for ((pi, p) in s.paragraphs.withIndex()) {
                weight += weight(p, 0)
                if (weight >= MAX_WEIGHT || pi + 1 - from >= MAX_PARAS) {
                    out.add(FlowChunk(si, from, pi + 1, firstKey(ctx, si, from)))
                    from = pi + 1
                    weight = 0
                }
            }
            if (from < s.paragraphs.size || s.paragraphs.isEmpty()) out.add(FlowChunk(si, from, s.paragraphs.size, firstKey(ctx, si, from)))
        }
        if (out.isEmpty()) out.add(FlowChunk(0, 0, 0, 0))
        return out
    }

    private fun firstKey(ctx: Ctx, si: Int, from: Int): Int {
        val keys = ctx.index.topKeys.getOrNull(si) ?: return 0
        for (k in from until keys.size) if (keys[k] >= 0) return keys[k]
        return ctx.index.keyCount
    }

    private fun weight(p: Paragraph, depth: Int): Int {
        var w = 40 + p.plainText.length
        if (depth > 6) return w
        for (i in p.content) if (i is Inline.Object) {
            w += when (val o = i.obj) {
                is Table -> 200 + o.cells.sumOf { c -> 40 + c.paragraphs.sumOf { weight(it, depth + 1) } }
                is Shape -> 200 + o.paragraphs.sumOf { weight(it, depth + 1) }
                else -> 200
            }
        }
        return w
    }
}

/**
 * 문단을 일반 흐름(줄바꿈은 브라우저가)으로 쓴다.
 * - 읽기 모드: 글자 크기 rem, 여백은 화면 폭에 맞게 축소, 표는 진짜 &lt;table&gt;, 그림은 폭 비율.
 * - pageUnits = true: 원본 보기 안에서 줄 정보가 없는 문단을 그릴 때 (모든 길이 px, 원본 크기 그대로).
 */
internal class FlowWriter(private val ctx: Ctx, private val pageUnits: Boolean, dark: Boolean) {

    private val doc = ctx.doc
    private val css = Css(doc, if (pageUnits) ViewMode.PAGE else ViewMode.FLOW, dark)
    private val rw = RunWriter(ctx, false, { sb, inl, depth -> inlineObject(sb, inl, depth) }, { null })

    /** 읽기 모드: 덩어리 끝에 모아 쓸 각주/미주 */
    val notes = ArrayList<Inline.Note>()

    /** 그림/표 폭 비율의 기준이 되는 본문 폭 */
    private val refWidth = maxOf(10_000, doc.sections.firstOrNull()?.page?.bodyWidth ?: 48_190)

    fun paragraph(sb: StringBuilder, p: Paragraph, depth: Int) {
        if (p.hidden || depth > DocIndex.MAX_DEPTH) return
        val ps = doc.paraShapes[p.paraShapeId] ?: ParaShape.DEFAULT
        val key = ctx.index.keyString(p)
        val heading = ctx.index.heading(p)
        val empty = p.content.none { it is Inline.Text || it is Inline.Object || it is Inline.Tab || it is Inline.LineBreak || it is Inline.Note || it is Inline.Ruby || it is Inline.AutoNumber } && heading == null
        val bf = ps.borderFillId.takeIf { it != 0 }?.let { doc.borderFills[it] }
        sb.append("<div class=\"p")
        if (!pageUnits) sb.append(" q").append(p.paraShapeId)
        if (empty) sb.append(" e")
        if (bf != null && css.background(bf) != null) sb.append(" g").append(ps.borderFillId)
        if (bf != null && css.darkFill(bf)) sb.append(" dkb")
        // 테두리는 쉘 CSS 의 .k{id} (어두운 테마 색 반영)
        if (bf != null && css.hasBorder(bf)) sb.append(" pbx k").append(ps.borderFillId)
        sb.append('"')
        if (key != null) sb.append(" data-p=\"").append(key).append('"')
        if (pageUnits) {
            val style = StringBuilder()
            pageStyle(style, p, ps)
            sb.append(" style=\"").append(style).append('"')
        }
        sb.append('>')
        if (heading != null) rw.heading(sb, heading, p)
        if (!empty) {
            val st = RunState()
            rw.write(sb, Segments.whole(p), st, depth = depth)
            rw.closeLink(sb, st)
        }
        sb.append("</div>")
        if (!pageUnits) for (i in p.content) if (i is Inline.Note) notes.add(i)
    }

    /** 원본 보기 안 흐름 문단: 문단 모양을 px 로 */
    private fun pageStyle(sb: StringBuilder, p: Paragraph, ps: ParaShape) {
        sb.append("text-align:").append(alignCss(ps.align)).append(';')
        val ml = ps.marginLeft.coerceIn(0, 200000)
        val mr = ps.marginRight.coerceIn(0, 200000)
        val left = ml + (if (ps.indent < 0) -ps.indent else 0)
        if (left > 0) sb.append("padding-left:").px(left).append(';')
        if (mr > 0) sb.append("padding-right:").px(mr).append(';')
        if (ps.indent != 0) sb.append("text-indent:").px(ps.indent.coerceIn(-200000, 200000)).append(';')
        if (ps.spacingBefore > 0) sb.append("margin-top:").px(ps.spacingBefore.coerceAtMost(50000)).append(';')
        if (ps.spacingAfter > 0) sb.append("margin-bottom:").px(ps.spacingAfter.coerceAtMost(50000)).append(';')
        val size = ctx.geom.fontSize(p)
        val lh = Geom.lineHeight(ps, size)
        sb.append("line-height:").px(lh).append(';')
        if (ps.lineSpacingType == LineSpacingType.PERCENT) sb.append("min-height:").px(lh).append(';')
    }

    private fun alignCss(a: Align) = when (a) {
        Align.LEFT -> "left"
        Align.RIGHT -> "right"
        Align.CENTER -> "center"
        Align.JUSTIFY, Align.DISTRIBUTE, Align.DISTRIBUTE_SPACE -> "justify"
    }

    /** 덩어리 끝 각주/미주 목록 */
    fun writeNotes(sb: StringBuilder) {
        if (notes.isEmpty()) return
        sb.append("<div class=\"notes\">")
        for (n in notes) {
            sb.append("<div class=\"note\"><span class=\"gen nn\">").text(noteLabel(n)).append("</span><div class=\"nb\">")
            for (np in n.paragraphs) paragraph(sb, np, 1)
            sb.append("</div></div>")
        }
        sb.append("</div>")
        notes.clear()
    }

    private fun noteLabel(n: Inline.Note): String = (if (n.endnote) "미주 " else "") + n.number + ")"

    // ------------------------------------------------------------------ 개체

    private fun inlineObject(sb: StringBuilder, inl: Inline.Object, depth: Int) {
        if (depth > DocIndex.MAX_DEPTH) return
        val o = inl.obj
        // 원본 보기에서 떠 있는 개체는 쪽 배치(PageLayout/area)가 따로 그린다
        if (pageUnits && !o.layout.treatAsChar) return
        val cap = o.caption?.takeIf { it.paragraphs.isNotEmpty() }
        val block = o is Table || !o.layout.treatAsChar || (o is Shape && o.paragraphs.isNotEmpty())
        if (block) {
            sb.append("<div class=\"obk\"")
            if (!o.layout.treatAsChar && o !is Table) {
                val align = when (o.layout.horzAlign) {
                    ObjAlign.CENTER -> "center"
                    ObjAlign.END, ObjAlign.OUTSIDE -> "right"
                    else -> "left"
                }
                sb.append(" style=\"text-align:").append(align).append('"')
            }
            sb.append('>')
        }
        if (cap != null && (cap.side == 2 || cap.side == 0)) caption(sb, cap, depth)
        when (o) {
            is Table -> table(sb, o, depth)
            is Picture -> picture(sb, o)
            is Shape -> shape(sb, o, depth)
            is Equation -> {
                sb.append("<span class=\"eqf\"")
                if (o.color != 0) sb.append(" style=\"color:").append(css.textColor(o.color) ?: "inherit").append('"')
                sb.append('>')
                EquationHtml.write(sb, o.script)
                sb.append("</span>")
            }
        }
        if (cap != null && (cap.side == 3 || cap.side == 1)) caption(sb, cap, depth)
        if (block) sb.append("</div>")
    }

    private fun caption(sb: StringBuilder, cap: Caption, depth: Int) {
        sb.append("<div class=\"cap\">")
        for (p in cap.paragraphs) paragraph(sb, p, depth + 1)
        sb.append("</div>")
    }

    /** 폭 (원본 보기: px, 읽기 모드: 본문 폭 대비 %) */
    private fun StringBuilder.width(hwp: Int): StringBuilder {
        if (pageUnits) return px(hwp)
        val pct = (hwp * 100.0 / refWidth).coerceIn(1.0, 100.0)
        return num(pct).append('%')
    }

    private fun picture(sb: StringBuilder, p: Picture) {
        val img = p.imageId?.let { doc.images[it] }
        val w = p.layout.width.coerceIn(0, 2_000_000)
        val h = p.layout.height.coerceIn(0, 2_000_000)
        if (img == null || !img.displayable || w <= 0 || h <= 0) {
            sb.append("<span class=\"phf\">그림</span>")
            return
        }
        val cropW = p.cropRight - p.cropLeft
        val cropH = p.cropBottom - p.cropTop
        val cropped = p.imageWidth > 0 && p.imageHeight > 0 && cropW > 0 && cropH > 0 &&
            (p.cropLeft > 0 || p.cropTop > 0 || cropW < p.imageWidth - 10 || cropH < p.imageHeight - 10)
        val filter = when (p.effect) {
            1 -> "grayscale(1)"
            2 -> "grayscale(1) contrast(4)"
            else -> null
        }
        sb.append("<span class=\"imw\" style=\"width:").width(w).append(";aspect-ratio:").append(w).append('/').append(h)
        if (filter != null) sb.append(";filter:").append(filter)
        sb.append("\"><img alt=\"\" loading=\"lazy\" decoding=\"async\" src=\"").attr(img.path).append('"')
        if (cropped) {
            sb.append(" style=\"position:absolute;max-width:none;left:").num(-p.cropLeft * 100.0 / cropW).append("%;top:").num(-p.cropTop * 100.0 / cropH)
                .append("%;width:").num(p.imageWidth * 100.0 / cropW).append("%;height:").num(p.imageHeight * 100.0 / cropH).append("%\"")
        }
        sb.append("></span>")
    }

    private fun shape(sb: StringBuilder, s: Shape, depth: Int) {
        when {
            s.kind == ShapeKind.CONTAINER -> {
                // 묶음: 글이 있는 자식만 차례로
                for (c in s.children) if (hasText(c)) inlineObject(sb, Inline.Object(0, -1, c), depth + 1)
            }
            s.paragraphs.isNotEmpty() -> {
                sb.append("<div class=\"tbx").append(if (s.fill?.let { css.darkFill(it) } == true) " dkb" else "").append("\" style=\"")
                if (s.line.visible) sb.append("border:").append(css.borderValue(s.line)).append(';')
                s.fill?.let { css.background(it) }?.let { sb.append(it) }
                if (pageUnits) sb.append("width:").px(s.layout.width.coerceIn(0, 2_000_000)).append(';')
                sb.append("\">")
                for (p in s.paragraphs) paragraph(sb, p, depth + 1)
                sb.append("</div>")
            }
            s.kind == ShapeKind.TEXT_ART && s.text.isNotEmpty() -> sb.append("<span class=\"gen ta2\">").text(s.text).append("</span>")
            else -> {} // 글 없는 선/도형은 읽기 모드에서 생략
        }
    }

    private fun hasText(o: DrawObject): Boolean = when (o) {
        is Shape -> o.paragraphs.isNotEmpty() || o.children.any { hasText(it) } || (o.kind == ShapeKind.TEXT_ART && o.text.isNotEmpty())
        is Table, is Picture, is Equation -> true
    }

    private fun table(sb: StringBuilder, t: Table, depth: Int) {
        val g = ctx.geom.table(t)
        if (g.cells.isEmpty()) return
        val totalW = maxOf(1, g.width)
        val cols = g.colX.size - 1
        sb.append("<div class=\"tw\"><table class=\"tb")
        val tbf = t.borderFillId.takeIf { it != 0 }?.let { doc.borderFills[it] }
        if (tbf != null && css.background(tbf) != null) sb.append(" g").append(t.borderFillId)
        if (tbf != null && css.darkFill(tbf)) sb.append(" dkb")
        sb.append("\" style=\"")
        if (pageUnits) sb.append("width:").px(totalW).append(';')
        else {
            // 넓은 표는 화면 폭, 좁은 표는 원본 비율. 칸이 많으면 가로 스크롤
            if (totalW * 2 >= refWidth) sb.append("width:100%;") else sb.append("width:").width(totalW).append(';')
            if (cols > 5) sb.append("min-width:").num(cols * 3.0).append("em;")
        }
        if (t.cellSpacing > 0) sb.append("border-collapse:separate;border-spacing:").px(t.cellSpacing.coerceAtMost(5000)).append(';')
        sb.append("\">")
        if (pageUnits) {
            // 원본 보기 안에서는 원본 열 너비 그대로. 읽기 모드는 내용에 맞춰 브라우저가 나눈다 (좁은 화면에서 글자가 잘게 끊기지 않게)
            sb.append("<colgroup>")
            for (c in 0 until cols) {
                val cw = g.colX[c + 1] - g.colX[c]
                sb.append("<col style=\"width:").num(cw * 100.0 / totalW).append("%\">")
            }
            sb.append("</colgroup>")
        }
        sb.append("<tbody>")
        val byRow = g.cells.groupBy { it.row }
        for (r in 0 until g.rows) {
            sb.append("<tr>")
            val rowCells = byRow[r]?.sortedBy { it.col } ?: emptyList()
            for (pc in rowCells) cell(sb, pc, g, depth)
            sb.append("</tr>")
        }
        sb.append("</tbody></table></div>")
    }

    private fun cell(sb: StringBuilder, pc: TableGeom.PlacedCell, g: TableGeom, depth: Int) {
        val c = pc.cell
        val bf = c.borderFillId.takeIf { it != 0 }?.let { doc.borderFills[it] }
        sb.append("<td")
        if (pc.colSpan > 1) sb.append(" colspan=\"").append(pc.colSpan).append('"')
        if (pc.rowSpan > 1) sb.append(" rowspan=\"").append(pc.rowSpan).append('"')
        if (bf != null) {
            val cls = ArrayList<String>(3)
            if (css.background(bf) != null) cls.add("g" + c.borderFillId)
            if (css.hasBorder(bf)) cls.add("k" + c.borderFillId)
            if (css.darkFill(bf)) cls.add("dkb")
            if (cls.isNotEmpty()) sb.append(" class=\"").append(cls.joinToString(" ")).append('"')
        }
        sb.append(" style=\"vertical-align:").append(
            when (c.vertAlign) {
                1 -> "middle"
                2 -> "bottom"
                else -> "top"
            },
        ).append(';')
        val m = c.margin
        if (pageUnits) {
            sb.append("padding:").px(m.top).append(' ').px(m.right).append(' ').px(m.bottom).append(' ').px(m.left).append(';')
            sb.append("height:").px(g.h(pc)).append(';')
        } else {
            sb.append("padding:").num(cellPad(m.top)).append("em ").num(cellPad(m.right)).append("em ").num(cellPad(m.bottom)).append("em ").num(cellPad(m.left)).append("em;")
        }
        sb.append("\">")
        for (p in c.paragraphs) paragraph(sb, p, depth + 1)
        sb.append("</td>")
    }

    /** 셀 안쪽 여백 HWPUNIT → em (화면용으로 줄임) */
    private fun cellPad(v: Int) = (v.coerceIn(0, 5000) / 1000.0 * 0.45).coerceIn(0.15, 0.9)
}

/** 읽기 모드 덩어리 HTML */
internal object FlowRenderer {
    fun chunk(ctx: Ctx, chunks: List<FlowChunk>, index: Int, dark: Boolean): String {
        val ch = chunks.getOrNull(index) ?: return ""
        val s = ctx.doc.sections.getOrNull(ch.section) ?: return ""
        val sb = StringBuilder(32 * 1024)
        if (ch.from == 0 && ch.section > 0) sb.append("<hr class=\"sec\">")
        val fw = FlowWriter(ctx, pageUnits = false, dark = dark)
        for (pi in ch.from until ch.to.coerceAtMost(s.paragraphs.size)) fw.paragraph(sb, s.paragraphs[pi], 0)
        fw.writeNotes(sb)
        return sb.toString()
    }
}
