package com.fastreader.app.core.render

import com.fastreader.app.core.doc.Align
import com.fastreader.app.core.doc.Border
import com.fastreader.app.core.doc.BorderFill
import com.fastreader.app.core.doc.Caption
import com.fastreader.app.core.doc.DrawObject
import com.fastreader.app.core.doc.Equation
import com.fastreader.app.core.doc.HeaderFooter
import com.fastreader.app.core.doc.HorzRel
import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.LineType
import com.fastreader.app.core.doc.ObjAlign
import com.fastreader.app.core.doc.PageNumberPos
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.ParaShape
import com.fastreader.app.core.doc.Picture
import com.fastreader.app.core.doc.Section
import com.fastreader.app.core.doc.Shape
import com.fastreader.app.core.doc.ShapeKind
import com.fastreader.app.core.doc.Table
import com.fastreader.app.core.doc.TextWrap
import com.fastreader.app.core.doc.VertRel
import com.fastreader.app.core.render.Html.attr
import com.fastreader.app.core.render.Html.num
import com.fastreader.app.core.render.Html.px
import com.fastreader.app.core.render.Html.pxd
import com.fastreader.app.core.render.Html.text

/**
 * 원본 보기 한 쪽의 HTML 조각.
 * 좌표는 모두 HWPUNIT 로 계산하고 쓸 때 px(÷75) 로 바꾼다. 줄은 LineSeg 위치에 절대 배치한다.
 * 한 쪽을 그릴 때만 쓰는 객체이므로 스레드 사이에 공유하지 않는다.
 */
internal class PageRenderer(private val ctx: Ctx, private val layout: PageLayout, private val pageIndex: Int) {

    private val doc = ctx.doc
    private val pg = layout.pages[pageIndex]
    private val section: Section = doc.sections[pg.section]
    private val def = pg.def
    private val css = Css(doc, ViewMode.PAGE, false)
    private val numbers = PageInfoForNumbers(pg.number, layout.pages.size, section.pageNumbering?.numberType ?: com.fastreader.app.core.doc.NumberType.DIGIT)
    private val rw = RunWriter(ctx, true, { sb, inl, depth -> inlineObject(sb, inl, depth) }, { numbers })

    /** 지금 그리는 영역이 머리말/꼬리말인가 (문단 key 접두어) */
    private var inHf = false

    fun render(): String {
        val sb = StringBuilder(16 * 1024)
        pageBorderFill(sb)
        val bodyL = def.bodyLeft
        val bodyT = def.bodyTop
        // 본문
        sb.append("<div class=\"bd\" style=\"").box(bodyL, bodyT, def.bodyWidth, def.bodyHeight).append("\">")
        paragraphBorders(sb)
        for (r in pg.lines) {
            val x = layout.columnLeft(section, r.col)
            writeLines(sb, r.para, r.from, r.to, x, r.dy, layout.columnWidth(section, r.col), 0, r.top < 0)
        }
        for (f in pg.flows) {
            val x = layout.columnLeft(section, f.col)
            flowBlock(sb, f.paras, x, f.y, layout.columnWidth(section, f.col), 0)
        }
        if (pg.endnoteRuleY >= 0) {
            sb.append("<div class=\"nr\" style=\"left:0;top:").px(pg.endnoteRuleY).append(";width:").px(minOf(def.bodyWidth / 3, 14000)).append("\"></div>")
        }
        footnotes(sb)
        sb.append("</div>")

        // 떠 있는 개체 (쪽 좌표)
        val objs = pg.objects.sortedBy { it.obj.obj.layout.zOrder }
        for (o in objs) floating(sb, o)

        // 머리말/꼬리말/쪽 번호
        pg.header?.let { headerFooter(sb, it, true) }
        pg.footer?.let { headerFooter(sb, it, false) }
        if (pg.showPageNumber) pageNumber(sb)
        return sb.toString()
    }

    // ------------------------------------------------------------------ 쪽 테두리/배경

    /** 쪽 테두리/배경. 홀/짝 설정은 실제 테두리나 배경이 있을 때만 양쪽 설정보다 우선한다 */
    private fun pageBorderFill(sb: StringBuilder) {
        val list = section.pageBorderFills
        if (list.isEmpty()) return
        fun usable(p: com.fastreader.app.core.doc.PageBorderFill): BorderFill? =
            doc.borderFills[p.borderFillId]?.takeIf { css.hasBorder(it) || css.background(it) != null }
        val odd = pg.number % 2 != 0
        val specific = list.firstOrNull { it.apply == (if (odd) com.fastreader.app.core.doc.PageApply.ODD else com.fastreader.app.core.doc.PageApply.EVEN) && usable(it) != null }
        val pbf = specific ?: list.firstOrNull { it.apply == com.fastreader.app.core.doc.PageApply.BOTH && usable(it) != null } ?: return
        val bf = usable(pbf) ?: return
        val o = pbf.offset
        // 본문 기준 영역 (머리말/꼬리말 포함 여부)
        val bTop = if (pbf.includeHeader) def.marginTop else def.bodyTop
        val bBottom = if (pbf.includeFooter) def.height - def.marginBottom else def.bodyTop + def.bodyHeight
        val bodyRect = intArrayOf(def.bodyLeft, bTop, def.bodyWidth, bBottom - bTop)
        val borderRect = if (pbf.relativeToPaper) intArrayOf(o.left, o.top, def.width - o.left - o.right, def.height - o.top - o.bottom)
        else intArrayOf(bodyRect[0] - o.left, bodyRect[1] - o.top, bodyRect[2] + o.left + o.right, bodyRect[3] + o.top + o.bottom)
        if (css.background(bf) != null) {
            val f = when (pbf.fillArea) {
                1 -> bodyRect
                2 -> borderRect
                else -> intArrayOf(0, 0, def.width, def.height)
            }
            sb.append("<div class=\"pbg g").append(pbf.borderFillId).append("\" style=\"").box(f[0], f[1], f[2], f[3]).append("\"></div>")
        }
        if (css.hasBorder(bf)) {
            sb.append("<div class=\"pbg\" style=\"").box(borderRect[0], borderRect[1], borderRect[2], borderRect[3])
                .append("\"><i class=\"bx k").append(pbf.borderFillId).append("\"></i></div>")
        }
    }

    // ------------------------------------------------------------------ 줄

    /**
     * 문단의 줄 [from, to) 를 쓴다. 좌표 원점은 감싸는 영역(본문/셀 안/글상자 안)의 왼쪽 위.
     * @param ox 줄 horzStart 에 더할 값 (단 위치 등), @param dy vertPos 에 더할 값
     * @param areaW 영역 폭 (폭 0 인 줄의 대체 폭)
     */
    private fun writeLines(sb: StringBuilder, p: Paragraph, from: Int, to: Int, ox: Int, dy: Int, areaW: Int, depth: Int, note: Boolean = false) {
        if (from >= to || p.lines.isEmpty()) return
        val ps = doc.paraShapes[p.paraShapeId] ?: ParaShape.DEFAULT
        val ls = LineStarts(p)
        val st = RunState()
        val split = Segments.split(p, ls, from, to, st, rw)
        val key = ctx.index.keyString(p)
        val heading = ctx.index.heading(p)
        val last = p.lines.size - 1
        for (li in from until to) {
            val line = p.lines[li]
            val k = li - from
            val segs = split.segs[k]
            val ind = if (indentApplies(p, li, ps.indent)) Math.abs(ps.indent) else 0
            val x = ox + line.horzStart + ind
            var w = (if (line.width > 0) line.width else areaW - line.horzStart) - ind
            if (w < 0) w = 0
            val y = dy + line.vertPos
            val h = maxOf(0, line.height)
            val b = baselineOf(line, h)
            val cls = when (ps.align) {
                Align.JUSTIFY -> if (li == last || split.endsWithBreak[k]) "al" else "aj"
                Align.DISTRIBUTE -> "ad"
                Align.DISTRIBUTE_SPACE -> "aj"
                Align.LEFT -> "al"
                Align.RIGHT -> "ar"
                Align.CENTER -> "ac"
            }
            val ratio = if (segs.isEmpty()) 100 else RunWriter.lineRatio(ctx, segs)
            sb.append("<div class=\"l ").append(cls)
            if (note) sb.append(" nt")
            sb.append('"')
            if (key != null) sb.append(" data-p=\"").append(key).append('"')
            if (split.plainStart[k] > 0) sb.append(" data-o=\"").append(split.plainStart[k]).append('"')
            sb.append(" style=\"left:").px(x).append(";top:").px(y).append(";height:").px(h)
            if (ratio != 100) {
                val r = ratio / 100.0
                sb.append(";width:").pxd(Html.px(w) / r).append(";transform:scaleX(").num(r).append(")\" data-r=\"").num(r).append('"')
            } else sb.append(";width:").px(w).append('"')
            sb.append("><i class=\"s\" style=\"height:").px(b).append("\"></i>")
            if (li == 0 && heading != null) rw.heading(sb, heading, p)
            rw.openLink(sb, st)
            val (ts, tc) = Segments.trailing(segs)
            descent = h - b
            rw.write(sb, segs, st, ts, tc, ratio, depth)
            rw.closeLink(sb, st)
            sb.append("</div>")
        }
        // 이 문단에 속한 떠 있는 개체는 호출한 쪽에서 (본문은 PageLayout, 셀/글상자는 area)
    }

    /**
     * 줄 위에서 베이스라인까지 거리. 한글이 계산한 값은 글자 높이의 약 85% 인데, 다른 프로그램이 만든 문서에는
     * 50% 같은 임시값이 들어 있는 경우가 있어(글자가 칸 위로 올라감) 그런 값은 표준 비율로 바꾼다.
     */
    private fun baselineOf(line: com.fastreader.app.core.doc.LineSeg, h: Int): Int {
        val th = if (line.textHeight in 1..h) line.textHeight else h
        val std = h - th + th * 85 / 100
        if (th <= 0) return std
        val ratio = line.baseline * 100 / th
        return if (line.baseline in 1..h && ratio >= 70) line.baseline else std
    }

    /** 지금 쓰는 줄의 베이스라인 아래 높이 (글자처럼 취급한 개체를 줄 아래에 맞추는 데 씀) */
    private var descent = 0

    /** 줄 정보 없는 문단 묶음: 영역 안에서 일반 흐름으로 */
    private fun flowBlock(sb: StringBuilder, paras: List<Paragraph>, x: Int, y: Int, w: Int, depth: Int) {
        sb.append("<div class=\"fb\" style=\"left:").px(x).append(";top:").px(y).append(";width:").px(w).append("\">")
        val fw = FlowWriter(ctx, pageUnits = true, dark = false)
        for (p in paras) fw.paragraph(sb, p, depth)
        sb.append("</div>")
    }

    // ------------------------------------------------------------------ 영역 (셀/글상자/머리말/각주)

    /** 문단 목록을 (ox, oy) 영역 안에 쓴다. 줄 없는 문단은 흐름 상자로 */
    private fun area(sb: StringBuilder, paras: List<Paragraph>, ox: Int, oy: Int, areaW: Int, depth: Int, note: Boolean = false) {
        if (depth > DocIndex.MAX_DEPTH) return
        var cursor = 0
        var i = 0
        while (i < paras.size) {
            val p = paras[i]
            if (p.hidden) {
                i++; continue
            }
            if (p.lines.isEmpty()) {
                val group = ArrayList<Paragraph>()
                while (i < paras.size && (paras[i].lines.isEmpty() || paras[i].hidden)) {
                    if (!paras[i].hidden) group.add(paras[i])
                    i++
                }
                flowBlock(sb, group, ox, oy + cursor, areaW, depth + 1)
                // 줄 정보 없는 문단의 떠 있는 개체 (흐름 상자 위쪽 기준으로 근사)
                for (gp in group) for (inl in gp.content) {
                    if (inl is Inline.Object && !inl.obj.layout.treatAsChar) areaObject(sb, inl, gp, ox, oy + cursor, areaW, depth + 1)
                }
                cursor += ctx.geom.paragraphsHeight(group, areaW, depth + 1)
                continue
            }
            writeLines(sb, p, 0, p.lines.size, ox, oy, areaW, depth + 1, note)
            val lastL = p.lines.last()
            cursor = maxOf(cursor, lastL.vertPos + lastL.height + lastL.spacing)
            // 이 문단의 떠 있는 개체 (영역 기준)
            for (inl in p.content) {
                if (inl is Inline.Object && !inl.obj.layout.treatAsChar) {
                    areaObject(sb, inl, p, ox, oy, areaW, depth + 1)
                }
            }
            i++
        }
    }

    /** 셀/글상자 안에서 떠 있는 개체: 문단 기준이면 그 문단 위, 아니면 영역 왼쪽 위 기준으로 근사 */
    private fun areaObject(sb: StringBuilder, inl: Inline.Object, p: Paragraph, ox: Int, oy: Int, areaW: Int, depth: Int) {
        val o = inl.obj
        val l = o.layout
        val (w, h) = objSize(o)
        val paraTop = p.lines.firstOrNull()?.vertPos ?: 0
        val x = ox + alignX(l.horzAlign, 0, areaW, w, l.horzOffset) + (if (l.horzAlign == ObjAlign.START) l.outMargin.left else 0)
        val y = oy + (if (l.vertRelTo == VertRel.PARA) paraTop + l.vertOffset + l.outMargin.top else l.vertOffset)
        objectAt(sb, o, x, y, w, h, depth, zIndex(l, inArea = true))
    }

    // ------------------------------------------------------------------ 떠 있는 개체 (쪽)

    private fun floating(sb: StringBuilder, place: PageLayout.ObjPlace) {
        val o = place.obj.obj
        val l = o.layout
        val (w, h) = objSize(o)
        val ps = doc.paraShapes[place.para.paraShapeId] ?: ParaShape.DEFAULT
        val colL = def.bodyLeft + layout.columnLeft(section, place.col)
        val colW = layout.columnWidth(section, place.col)
        val oddPage = pg.number % 2 != 0

        // 가로 기준 영역
        val (bx, bw) = when (l.horzRelTo) {
            HorzRel.PAPER -> 0 to def.width
            HorzRel.PAGE -> def.bodyLeft to def.bodyWidth
            HorzRel.COLUMN -> colL to colW
            HorzRel.PARA -> (colL + ps.marginLeft.coerceAtLeast(0)) to maxOf(0, colW - ps.marginLeft.coerceAtLeast(0) - ps.marginRight.coerceAtLeast(0))
        }
        var halign = l.horzAlign
        if (halign == ObjAlign.INSIDE) halign = if (oddPage) ObjAlign.START else ObjAlign.END
        if (halign == ObjAlign.OUTSIDE) halign = if (oddPage) ObjAlign.END else ObjAlign.START
        var x = bx + alignX(halign, 0, bw, w, l.horzOffset)
        if (l.horzRelTo == HorzRel.PARA || l.horzRelTo == HorzRel.COLUMN) {
            if (halign == ObjAlign.START) x += l.outMargin.left else if (halign == ObjAlign.END) x -= l.outMargin.right
        }

        if (place.continuation) {
            // 쪽을 넘어 이어지는 표 조각: 본문 위에서부터
            val y = def.bodyTop + l.outMargin.top
            tableFragment(sb, o as Table, x, y, w, place)
            return
        }
        val y = when (l.vertRelTo) {
            VertRel.PAPER -> alignY(l.vertAlign, 0, def.height, h, l.vertOffset)
            VertRel.PAGE -> def.bodyTop + alignY(l.vertAlign, 0, def.bodyHeight, h, l.vertOffset)
            VertRel.PARA -> def.bodyTop + place.paraTop + l.vertOffset + l.outMargin.top
        }
        if (o is Table && place.fragEnd >= 0) {
            tableFragment(sb, o, x, y, w, place)
            return
        }
        objectAt(sb, o, x, y, w, h, 0, zIndex(l))
    }

    /**
     * 글 뒤로: 0, 글 앞으로: 글자 위 (본문에서는 표 선 .gl 보다도 위, 셀/글상자 안에서는 그 칸의 선 아래 = 1),
     * 그 밖: 2 = 지정 안 함 (문서 순서대로)
     */
    private fun zIndex(l: com.fastreader.app.core.doc.ObjectLayout, inArea: Boolean = false) = when (l.textWrap) {
        TextWrap.BEHIND_TEXT -> 0
        TextWrap.IN_FRONT_OF_TEXT -> if (inArea) 1 else 3
        else -> 2
    }

    private fun alignX(a: ObjAlign, base: Int, size: Int, w: Int, off: Int): Int = when (a) {
        ObjAlign.START, ObjAlign.INSIDE -> base + off
        ObjAlign.CENTER -> base + (size - w) / 2 + off
        ObjAlign.END, ObjAlign.OUTSIDE -> base + size - w - off
    }

    private fun alignY(a: ObjAlign, base: Int, size: Int, h: Int, off: Int): Int = when (a) {
        ObjAlign.START, ObjAlign.INSIDE -> base + off
        ObjAlign.CENTER -> base + (size - h) / 2 + off
        ObjAlign.END, ObjAlign.OUTSIDE -> base + size - h - off
    }

    /** 개체 그리기 상자 크기 (캡션 포함, HWPUNIT) */
    private fun objSize(o: DrawObject): Pair<Int, Int> {
        val l = o.layout
        var w = l.width.coerceIn(0, 2_000_000)
        var h = if (o is Table) maxOf(l.height, ctx.geom.table(o).height) else l.height
        h = h.coerceIn(0, 10_000_000)
        val cap = o.caption
        if (cap != null && cap.paragraphs.isNotEmpty()) {
            if (cap.side == 0 || cap.side == 1) w += cap.gap.coerceIn(0, 20000) + captionWidth(cap, o)
            else h += cap.gap.coerceIn(0, 20000) + captionHeight(cap, o)
        }
        return w to h
    }

    private fun captionWidth(cap: Caption, o: DrawObject) = if (cap.width > 0) cap.width.coerceAtMost(500000) else o.layout.width
    private fun captionHeight(cap: Caption, o: DrawObject) = ctx.geom.paragraphsHeight(cap.paragraphs, captionWidth(cap, o))

    /** 개체(캡션 포함)를 (x, y) 에 절대 배치 */
    private fun objectAt(sb: StringBuilder, o: DrawObject, x: Int, y: Int, w: Int, h: Int, depth: Int, z: Int) {
        sb.append("<div class=\"ob\" style=\"").box(x, y, w, h)
        if (z != 2) sb.append(";z-index:").append(z)
        sb.append("\">")
        objectContent(sb, o, depth)
        sb.append("</div>")
    }

    /** 글자처럼 취급한 개체: 줄 안 inline-block, 아래쪽을 줄 아래에 맞춘다 */
    private fun inlineObject(sb: StringBuilder, inl: Inline.Object, depth: Int) {
        val o = inl.obj
        val l = o.layout
        if (!l.treatAsChar) return // 떠 있는 개체는 따로 그린다
        if (depth > DocIndex.MAX_DEPTH) return
        val (w, h) = objSize(o)
        val d = descent
        sb.append("<span class=\"ob ib\" style=\"width:").px(w).append(";height:").px(h)
            .append(";margin:").px(l.outMargin.top).append(' ').px(l.outMargin.right).append(' ').px(l.outMargin.bottom).append(' ').px(l.outMargin.left)
            .append(";vertical-align:").px(-maxOf(0, d)).append("\">")
        objectContent(sb, o, depth + 1)
        sb.append("</span>")
        descent = d
    }

    /** 개체 내용 (상자 원점 기준). 캡션도 여기서 */
    private fun objectContent(sb: StringBuilder, o: DrawObject, depth: Int) {
        val cap = o.caption?.takeIf { it.paragraphs.isNotEmpty() }
        var ox = 0
        var oy = 0
        val bw = o.layout.width
        val bh = if (o is Table) maxOf(o.layout.height, ctx.geom.table(o).height) else o.layout.height
        if (cap != null) {
            val gap = cap.gap.coerceIn(0, 20000)
            when (cap.side) {
                0 -> ox = captionWidth(cap, o) + gap
                2 -> oy = captionHeight(cap, o) + gap
            }
        }
        if (ox != 0 || oy != 0) sb.append("<div class=\"oi\" style=\"").box(ox, oy, bw, bh).append("\">")
        when (o) {
            is Table -> tableCells(sb, o, depth, 0, -1, 0, bh)
            is Picture -> picture(sb, o, bw, bh)
            is Shape -> shape(sb, o, bw, bh, depth)
            is Equation -> equation(sb, o)
        }
        if (ox != 0 || oy != 0) sb.append("</div>")
        if (cap != null) {
            val gap = cap.gap.coerceIn(0, 20000)
            val cw = captionWidth(cap, o)
            val (cx, cy) = when (cap.side) {
                0 -> 0 to 0
                1 -> (bw + gap) to 0
                2 -> 0 to 0
                else -> 0 to (bh + gap)
            }
            sb.append("<div class=\"cp\" style=\"").box(cx, cy, cw, captionHeight(cap, o)).append("\">")
            area(sb, cap.paragraphs, 0, 0, cw, depth + 1)
            sb.append("</div>")
        }
    }

    // ------------------------------------------------------------------ 표

    /**
     * 셀들. [fragStart, fragEnd) 범위의 셀만 쓰고 위로 fragStart 만큼 당긴다 (fragEnd < 0 이면 전부).
     * shift = 위에 반복 제목 행이 있을 때 그 높이.
     */
    private fun tableCells(sb: StringBuilder, t: Table, depth: Int, fragStart: Int, fragEnd: Int, shift: Int, boxH: Int) {
        val g = ctx.geom.table(t)
        // 저장된 표 높이가 계산보다 크면 (한글은 마지막 행에 남는 높이를 둔다) 마지막 행을 늘린다
        val extra = if (fragEnd < 0) maxOf(0, boxH - g.height) else 0
        val sp = t.cellSpacing.coerceIn(0, 10000)
        if (t.borderFillId != 0) {
            val bf = doc.borderFills[t.borderFillId]
            if (bf != null && fragEnd < 0 && css.background(bf) != null) {
                sb.append("<div class=\"tbg g").append(t.borderFillId).append("\"></div>")
            }
        }
        for (pc in g.cells) {
            val cy0 = g.y(pc)
            var ch = g.h(pc)
            if (extra > 0 && pc.row + pc.rowSpan == g.rows) ch += extra
            if (fragEnd >= 0 && (cy0 >= fragEnd || cy0 + ch <= fragStart)) continue
            val cx = g.x(pc) + sp / 2
            val cy = cy0 - fragStart + shift + sp / 2
            val cw = g.w(pc) - sp
            ch -= sp
            cell(sb, pc, cx, cy, cw, ch, depth, ownBorder = sp > 0)
        }
        // 칸 사이 간격이 없으면 격자선을 한 번씩만 그린다 (이웃 칸이 같은 선을 두 번 그리면 픽셀 맞춤 때문에 굵어 보임)
        if (sp == 0) gridLines(sb, g, extra, fragStart, fragEnd, shift)
    }

    private fun cell(sb: StringBuilder, pc: TableGeom.PlacedCell, x: Int, y: Int, w: Int, h: Int, depth: Int, ownBorder: Boolean) {
        val c = pc.cell
        val bf = doc.borderFills[c.borderFillId]
        sb.append("<div class=\"td")
        if (bf != null && css.background(bf) != null) sb.append(" g").append(c.borderFillId)
        sb.append("\" style=\"").box(x, y, w, h).append("\">")
        if (bf != null) {
            if (ownBorder && css.hasBorder(bf)) sb.append("<i class=\"bx k").append(c.borderFillId).append("\"></i>")
            diagonals(sb, bf, w, h)
        }
        if (depth <= DocIndex.MAX_DEPTH) {
            val m = c.margin
            val innerW = maxOf(0, w - m.left - m.right)
            val innerH = maxOf(0, h - m.top - m.bottom)
            val contentH = pc.contentHeight
            val off = when (c.vertAlign) {
                1 -> maxOf(0, (innerH - contentH) / 2)
                2 -> maxOf(0, innerH - contentH)
                else -> 0
            }
            area(sb, c.paragraphs, m.left, m.top + off, innerW, depth + 1)
        }
        sb.append("</div>")
    }

    /**
     * 표 격자선. 격자 한 칸 단위로 맞닿은 두 셀의 테두리 중 강한 것을 고르고, 같은 선이 이어지면 하나로 합쳐 그린다.
     * 병합 셀 안쪽 경계는 그리지 않는다. fragEnd >= 0 이면 [fragStart, fragEnd] 안의 가로선만.
     */
    private fun gridLines(sb: StringBuilder, g: TableGeom, extra: Int, fragStart: Int, fragEnd: Int, shift: Int) {
        val rows = g.rows
        val cols = g.colX.size - 1
        if (rows <= 0 || cols <= 0) return
        if (rows.toLong() * cols > 400_000L) {
            // 비정상적으로 큰 격자: 셀마다 테두리 (느리지만 안전)
            for (pc in g.cells) {
                val bf = doc.borderFills[pc.cell.borderFillId] ?: continue
                if (!css.hasBorder(bf)) continue
                val y0 = g.y(pc)
                if (fragEnd >= 0 && (y0 >= fragEnd || y0 + g.h(pc) <= fragStart)) continue
                sb.append("<div class=\"td\" style=\"").box(g.x(pc), y0 - fragStart + shift, g.w(pc), g.h(pc)).append("\"><i class=\"bx k").append(pc.cell.borderFillId).append("\"></i></div>")
            }
            return
        }
        val owner = arrayOfNulls<TableGeom.PlacedCell>(rows * cols)
        for (pc in g.cells) {
            for (r in pc.row until minOf(rows, pc.row + pc.rowSpan)) for (c in pc.col until minOf(cols, pc.col + pc.colSpan)) {
                if (owner[r * cols + c] == null) owner[r * cols + c] = pc
            }
        }
        fun bf(pc: TableGeom.PlacedCell?) = pc?.let { doc.borderFills[it.cell.borderFillId] }
        fun rowY(r: Int) = if (r >= rows) g.rowY[rows] + extra else g.rowY[r]
        val visTop = if (fragEnd >= 0) fragStart else Int.MIN_VALUE
        val visBottom = if (fragEnd >= 0) fragEnd else Int.MAX_VALUE

        // 가로선
        for (r in 0..rows) {
            val y = rowY(r)
            if (y < visTop || y > visBottom) continue
            var runStart = 0
            var runB: Border? = null
            for (c in 0..cols) {
                val b = if (c < cols) {
                    val above = if (r > 0) owner[(r - 1) * cols + c] else null
                    val below = if (r < rows) owner[r * cols + c] else null
                    if (above != null && above === below) null else stronger(bf(above)?.bottom, bf(below)?.top)
                } else null
                if (b != runB) {
                    if (runB != null) hLine(sb, g.colX[runStart], g.colX[c], y - fragStart + shift, runB)
                    runStart = c
                    runB = b
                }
            }
        }
        // 세로선 (행 단위로 끊어 같은 선은 합침)
        for (c in 0..cols) {
            val x = g.colX[c]
            var runStart = 0
            var runB: Border? = null
            for (r in 0..rows) {
                val b = if (r < rows) {
                    val left = if (c > 0) owner[r * cols + c - 1] else null
                    val right = if (c < cols) owner[r * cols + c] else null
                    if (left != null && left === right) null else stronger(bf(left)?.right, bf(right)?.left)
                } else null
                if (b != runB) {
                    if (runB != null) {
                        val y0 = maxOf(rowY(runStart), visTop)
                        val y1 = minOf(rowY(r), visBottom)
                        if (y1 > y0) vLine(sb, x, y0 - fragStart + shift, y1 - fragStart + shift, runB)
                    }
                    runStart = r
                    runB = b
                }
            }
        }
    }

    /** 두 테두리 중 더 눈에 띄는 것 (없음 < 있음, 가는 < 굵은) */
    private fun stronger(a: Border?, b: Border?): Border? {
        val av = a?.takeIf { it.visible }
        val bv = b?.takeIf { it.visible }
        if (av == null) return bv
        if (bv == null) return av
        return if (bv.widthMm > av.widthMm) bv else av
    }

    private fun hLine(sb: StringBuilder, x0: Int, x1: Int, y: Int, b: Border) {
        val w = lineWidth(b)
        sb.append("<i class=\"gl\" style=\"left:").pxd(Html.px(x0) - w / 2).append(";top:").pxd(Html.px(y) - w / 2)
            .append(";width:").pxd(Html.px(x1 - x0) + w).append(";border-top:").append(css.borderValue(b)).append("\"></i>")
    }

    private fun vLine(sb: StringBuilder, x: Int, y0: Int, y1: Int, b: Border) {
        val w = lineWidth(b)
        sb.append("<i class=\"gl\" style=\"left:").pxd(Html.px(x) - w / 2).append(";top:").pxd(Html.px(y0) - w / 2)
            .append(";height:").pxd(Html.px(y1 - y0) + w).append(";border-left:").append(css.borderValue(b)).append("\"></i>")
    }

    private fun lineWidth(b: Border): Double {
        val w = Css.bwPx(b)
        return if (Css.borderStyle(b.type) == "double") w.coerceAtLeast(3.0) else w
    }

    private fun diagonals(sb: StringBuilder, bf: BorderFill, w: Int, h: Int) {
        val d = bf.diagonal?.takeIf { it.visible }
        val bd = bf.backDiagonal?.takeIf { it.visible }
        if (d == null && bd == null) return
        sb.append("<svg class=\"dg\" viewBox=\"0 0 ").num(Html.px(w)).append(' ').num(Html.px(h)).append("\" preserveAspectRatio=\"none\">")
        // diagonal: 왼쪽 위 → 오른쪽 아래 ("\"), backDiagonal: 왼쪽 아래 → 오른쪽 위 ("/")
        if (d != null) svgLine(sb, 0.0, 0.0, Html.px(w), Html.px(h), d)
        if (bd != null) svgLine(sb, 0.0, Html.px(h), Html.px(w), 0.0, bd)
        sb.append("</svg>")
    }

    private fun tableFragment(sb: StringBuilder, t: Table, x: Int, y: Int, w: Int, place: PageLayout.ObjPlace) {
        val start = place.fragStart
        val end = if (place.fragEnd < 0) ctx.geom.table(t).height else place.fragEnd
        val hh = place.headerHeight
        val h = (end - start) + hh
        sb.append("<div class=\"ob fr\" style=\"").box(x, y, w, h).append("\">")
        if (hh > 0) tableCells(sb, t, 0, 0, hh, 0, hh)
        tableCells(sb, t, 0, start, end, hh, h)
        sb.append("</div>")
    }

    // ------------------------------------------------------------------ 그림/도형/수식

    private fun picture(sb: StringBuilder, p: Picture, w: Int, h: Int) {
        val img = p.imageId?.let { doc.images[it] }
        val border = p.border.takeIf { it.visible }
        if (img == null || !img.displayable) {
            sb.append("<div class=\"ph\"><span>그림</span></div>")
            return
        }
        val filters = StringBuilder()
        when (p.effect) {
            1 -> filters.append("grayscale(1) ")
            2 -> filters.append("grayscale(1) contrast(4) ")
        }
        if (p.brightness != 0) filters.append("brightness(").num(1.0 + p.brightness.coerceIn(-100, 100) / 100.0).append(") ")
        if (p.contrast != 0) filters.append("contrast(").num(1.0 + p.contrast.coerceIn(-100, 100) / 100.0).append(") ")
        val alpha = p.alpha.coerceIn(0, 255)
        sb.append("<div class=\"pic\"")
        if (border != null || filters.isNotEmpty() || alpha > 0) {
            sb.append(" style=\"")
            if (border != null) sb.append("outline:").pxd(Css.bwPx(border)).append(' ').append(Css.borderStyle(border.type)).append(' ').append(Html.color(border.color)).append(";outline-offset:-").pxd(Css.bwPx(border)).append(';')
            if (filters.isNotEmpty()) sb.append("filter:").append(filters.trim()).append(';')
            if (alpha > 0) sb.append("opacity:").num(1.0 - alpha / 255.0).append(';')
            sb.append('"')
        }
        sb.append(">")
        val cropW = p.cropRight - p.cropLeft
        val cropH = p.cropBottom - p.cropTop
        val cropped = p.imageWidth > 0 && p.imageHeight > 0 && cropW > 0 && cropH > 0 &&
            (p.cropLeft > 0 || p.cropTop > 0 || cropW < p.imageWidth - 10 || cropH < p.imageHeight - 10) && w > 0 && h > 0
        sb.append("<img alt=\"\" loading=\"lazy\" decoding=\"async\" src=\"").attr(img.path).append('"')
        if (cropped) {
            val sx = w.toDouble() / cropW
            val sy = h.toDouble() / cropH
            sb.append(" style=\"position:absolute;max-width:none;left:").pxd(-Html.px(p.cropLeft) * sx).append(";top:").pxd(-Html.px(p.cropTop) * sy)
                .append(";width:").pxd(Html.px(p.imageWidth) * sx).append(";height:").pxd(Html.px(p.imageHeight) * sy).append('"')
        }
        sb.append("></div>")
    }

    private fun shape(sb: StringBuilder, s: Shape, w: Int, h: Int, depth: Int) {
        val line = s.line
        val fillCss = s.fill?.let { css.background(it) }
        when (s.kind) {
            ShapeKind.RECT, ShapeKind.ELLIPSE, ShapeKind.TEXT_ART, ShapeKind.OTHER, ShapeKind.OLE -> {
                sb.append("<div class=\"sh\" style=\"")
                if (line.visible && s.kind != ShapeKind.TEXT_ART) {
                    sb.append("border:").pxd(Css.bwPx(line)).append(' ').append(Css.borderStyle(line.type)).append(' ').append(Html.color(line.color)).append(';')
                }
                if (fillCss != null) sb.append(fillCss)
                if (s.kind == ShapeKind.ELLIPSE) sb.append("border-radius:50%;")
                else if (s.cornerRadius > 0) sb.append("border-radius:").pxd(Html.px(minOf(w, h)) * s.cornerRadius.coerceIn(0, 50) / 100.0).append(';')
                sb.append("\">")
                if (s.kind == ShapeKind.OLE && s.paragraphs.isEmpty()) sb.append("<div class=\"ph\"><span>개체</span></div>")
                if (s.kind == ShapeKind.TEXT_ART && s.text.isNotEmpty()) {
                    sb.append("<div class=\"ta\" style=\"font-size:").pxd(Html.px(h) * 0.8).append("\">").text(s.text).append("</div>")
                }
                sb.append("</div>")
            }
            ShapeKind.LINE, ShapeKind.ARC, ShapeKind.POLYGON, ShapeKind.CURVE -> svgShape(sb, s, w, h, fillCss)
            ShapeKind.CONTAINER -> {
                for (c in s.children) {
                    if (depth > DocIndex.MAX_DEPTH) break
                    val cl = c.layout
                    val (cw, ch) = objSize(c)
                    objectAt(sb, c, cl.horzOffset, cl.vertOffset, cw, ch, depth + 1, 2)
                }
            }
        }
        // 글상자 내용
        if (s.paragraphs.isNotEmpty() && depth <= DocIndex.MAX_DEPTH) {
            val m = s.textMargin
            val innerW = maxOf(0, w - m.left - m.right)
            val innerH = maxOf(0, h - m.top - m.bottom)
            val contentH = ctx.geom.paragraphsHeight(s.paragraphs, innerW, depth + 1)
            val off = when (s.textVertAlign) {
                1 -> maxOf(0, (innerH - contentH) / 2)
                2 -> maxOf(0, innerH - contentH)
                else -> 0
            }
            area(sb, s.paragraphs, m.left, m.top + off, innerW, depth + 1)
        }
    }

    private fun svgShape(sb: StringBuilder, s: Shape, w: Int, h: Int, fillCss: String?) {
        val wp = Html.px(w)
        val hp = Html.px(h)
        val pts = s.points
        val sw = if (s.line.visible) Css.bwPx(s.line) else 0.0
        sb.append("<svg class=\"sv\" width=\"").num(maxOf(wp, 1.0)).append("\" height=\"").num(maxOf(hp, 1.0)).append("\" viewBox=\"0 0 ").num(maxOf(wp, 1.0)).append(' ').num(maxOf(hp, 1.0)).append("\">")
        val fill = s.fill?.fillColor?.let { Html.color(it) } ?: "none"
        when (s.kind) {
            ShapeKind.LINE -> {
                if (pts.size >= 4) svgLine(sb, Html.px(pts[0]), Html.px(pts[1]), Html.px(pts[2]), Html.px(pts[3]), s.line)
                else svgLine(sb, 0.0, 0.0, wp, hp, s.line)
            }
            ShapeKind.ARC -> {
                // 사분원 근사: 왼쪽 위에서 오른쪽 아래로
                sb.append("<path d=\"M0 0 A").num(wp).append(' ').num(hp).append(" 0 0 1 ").num(wp).append(' ').num(hp).append("\" fill=\"none\"")
                stroke(sb, s.line)
                sb.append("/>")
            }
            ShapeKind.POLYGON, ShapeKind.CURVE -> {
                if (pts.size >= 4) {
                    sb.append(if (s.kind == ShapeKind.POLYGON) "<polygon points=\"" else "<polyline points=\"")
                    var i = 0
                    while (i + 1 < pts.size && i < 20000) {
                        sb.num(Html.px(pts[i])).append(',').num(Html.px(pts[i + 1])).append(' ')
                        i += 2
                    }
                    sb.append("\" fill=\"").append(if (s.kind == ShapeKind.POLYGON) fill else "none").append('"')
                    stroke(sb, s.line)
                    sb.append("/>")
                }
            }
            else -> {}
        }
        sb.append("</svg>")
        if (sw < 0) sb.append("") // (선 없음)
    }

    private fun svgLine(sb: StringBuilder, x1: Double, y1: Double, x2: Double, y2: Double, b: Border) {
        sb.append("<line x1=\"").num(x1).append("\" y1=\"").num(y1).append("\" x2=\"").num(x2).append("\" y2=\"").num(y2).append('"')
        stroke(sb, b)
        sb.append("/>")
    }

    private fun stroke(sb: StringBuilder, b: Border) {
        if (!b.visible) {
            sb.append(" stroke=\"none\"")
            return
        }
        val w = Css.bwPx(b)
        sb.append(" stroke=\"").append(Html.color(b.color)).append("\" stroke-width=\"").num(w).append('"')
        val dash = when (b.type) {
            LineType.DASH, LineType.LONG_DASH -> "${Html.fmt(w * 4)} ${Html.fmt(w * 2)}"
            LineType.DOT, LineType.CIRCLE -> "${Html.fmt(w)} ${Html.fmt(w * 1.5)}"
            LineType.DASH_DOT, LineType.DASH_DOT_DOT -> "${Html.fmt(w * 4)} ${Html.fmt(w * 1.5)} ${Html.fmt(w)} ${Html.fmt(w * 1.5)}"
            else -> null
        }
        if (dash != null) sb.append(" stroke-dasharray=\"").append(dash).append('"')
    }

    private fun equation(sb: StringBuilder, e: Equation) {
        val size = (e.baseSize.coerceIn(100, 40000) / 100.0) * 96.0 / 72.0
        sb.append("<div class=\"eq\" style=\"font-size:").pxd(size)
        if (e.color != 0) sb.append(";color:").append(Html.color(e.color))
        // flex 컨테이너의 직접 자식이면 위/아래 첨자의 vertical-align 이 무시되므로 한 번 감싼다
        sb.append("\"><span>")
        EquationHtml.write(sb, e.script)
        sb.append("</span></div>")
    }

    // ------------------------------------------------------------------ 문단 테두리/배경

    /** 문단 테두리/배경: 같은 테두리를 쓰는 이어진 문단은 한 상자로 */
    private fun paragraphBorders(sb: StringBuilder) {
        var curBf = 0
        var x0 = 0
        var x1 = 0
        var y0 = 0
        var y1 = 0
        var ofs = intArrayOf(0, 0, 0, 0)
        fun flush() {
            if (curBf == 0) return
            sb.append("<div class=\"pb g").append(curBf).append("\" style=\"").box(x0 - ofs[0], y0 - ofs[2], x1 - x0 + ofs[0] + ofs[1], y1 - y0 + ofs[2] + ofs[3]).append("\">")
            if (doc.borderFills[curBf]?.let { css.hasBorder(it) } == true) sb.append("<i class=\"bx k").append(curBf).append("\"></i>")
            sb.append("</div>")
            curBf = 0
        }
        for (r in pg.lines) {
            val ps = doc.paraShapes[r.para.paraShapeId]
            val bfId = ps?.borderFillId ?: 0
            val bf = doc.borderFills[bfId]
            if (ps == null || bf == null || (!css.hasBorder(bf) && css.background(bf) == null)) {
                flush(); continue
            }
            val colX = layout.columnLeft(section, r.col)
            val first = r.para.lines[r.from]
            val last = r.para.lines[r.to - 1]
            val left = colX + ps.marginLeft.coerceAtLeast(0)
            val right = colX + layout.columnWidth(section, r.col) - ps.marginRight.coerceAtLeast(0)
            val top = r.dy + first.vertPos
            val bottom = r.dy + last.vertPos + last.height
            if (bfId == curBf && left == x0 && right == x1 && top >= y1 - 10) {
                y1 = bottom
            } else {
                flush()
                curBf = bfId; x0 = left; x1 = right; y0 = top; y1 = bottom
                ofs = intArrayOf(ps.borderOffsetLeft, ps.borderOffsetRight, ps.borderOffsetTop, ps.borderOffsetBottom).map { it.coerceIn(0, 20000) }.toIntArray()
            }
        }
        flush()
    }

    // ------------------------------------------------------------------ 각주

    private fun footnotes(sb: StringBuilder) {
        if (pg.notes.isEmpty()) return
        val w = layout.columnWidth(section, 0)
        val heights = pg.notes.map { n -> ctx.geom.paragraphsHeight(n.paragraphs, w) }
        val total = FN_ABOVE + FN_BELOW + heights.sum() + FN_BETWEEN * (pg.notes.size - 1)
        var y = maxOf(0, def.bodyHeight - total)
        sb.append("<div class=\"fn\" style=\"left:0;top:").px(y + FN_ABOVE).append(";width:").px(w / 3).append("\"></div>")
        y += FN_ABOVE + FN_BELOW
        for ((i, n) in pg.notes.withIndex()) {
            area(sb, n.paragraphs, 0, y, w, 1, note = true)
            y += heights[i] + FN_BETWEEN
        }
    }

    // ------------------------------------------------------------------ 머리말/꼬리말/쪽 번호

    private fun headerFooter(sb: StringBuilder, hf: HeaderFooter, header: Boolean) {
        val x = def.marginLeft + def.marginGutter
        val w = if (hf.width > 0) hf.width else def.bodyWidth
        val h = if (header) def.marginHeader else def.marginFooter
        val y = if (header) def.marginTop else def.height - def.marginBottom - def.marginFooter
        sb.append("<div class=\"hf ").append(if (header) "hh" else "ff").append("\" style=\"").box(x, y, w, h).append("\">")
        inHf = true
        area(sb, hf.paragraphs, 0, 0, w, 1)
        inHf = false
        sb.append("</div>")
    }

    private fun pageNumber(sb: StringBuilder) {
        val pn = section.pageNumbering ?: return
        if (pn.position == PageNumberPos.NONE) return
        val num = NumberFormat.format(pg.number, pn.numberType)
        val text = if (pn.dash.isNotEmpty()) pn.prefix + pn.dash + " " + num + " " + pn.dash + pn.suffix else pn.prefix + num + pn.suffix
        val odd = pg.number % 2 != 0
        val top = when (pn.position) {
            PageNumberPos.TOP_LEFT, PageNumberPos.TOP_CENTER, PageNumberPos.TOP_RIGHT, PageNumberPos.OUTSIDE_TOP, PageNumberPos.INSIDE_TOP -> true
            else -> false
        }
        val align = when (pn.position) {
            PageNumberPos.TOP_LEFT, PageNumberPos.BOTTOM_LEFT -> "left"
            PageNumberPos.TOP_RIGHT, PageNumberPos.BOTTOM_RIGHT -> "right"
            PageNumberPos.OUTSIDE_TOP, PageNumberPos.OUTSIDE_BOTTOM -> if (odd) "right" else "left"
            PageNumberPos.INSIDE_TOP, PageNumberPos.INSIDE_BOTTOM -> if (odd) "left" else "right"
            else -> "center"
        }
        val x = def.marginLeft + def.marginGutter
        val h = if (top) def.marginHeader else def.marginFooter
        val y = if (top) def.marginTop else def.height - def.marginBottom - def.marginFooter
        sb.append("<div class=\"pn\" style=\"").box(x, y, def.bodyWidth, h).append(";text-align:").append(align).append(";line-height:").px(h).append("\">")
        sb.append("<span class=\"gen")
        if (pn.charShapeId >= 0 && doc.charShapes.containsKey(pn.charShapeId)) sb.append(" c").append(pn.charShapeId)
        sb.append("\">").text(text).append("</span></div>")
    }

    companion object {
        const val FN_ABOVE = 850
        const val FN_BELOW = 567
        const val FN_BETWEEN = 283
    }
}
