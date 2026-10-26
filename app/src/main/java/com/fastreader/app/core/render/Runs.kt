package com.fastreader.app.core.render

import com.fastreader.app.core.doc.AutoNumberKind
import com.fastreader.app.core.doc.CharShape
import com.fastreader.app.core.doc.FieldKind
import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.Lang
import com.fastreader.app.core.doc.LineType
import com.fastreader.app.core.doc.NumberType
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.RichDocument
import com.fastreader.app.core.render.Html.attr
import com.fastreader.app.core.render.Html.num
import com.fastreader.app.core.render.Html.px
import com.fastreader.app.core.render.Html.pxd
import com.fastreader.app.core.render.Html.text

/** 렌더링에 공통으로 쓰는 문서 정보 (읽기 전용) */
internal class Ctx(val doc: RichDocument) {
    val index = DocIndex(doc)
    val geom = Geom(doc)

    /** 언어별 크기/자간/위치가 다른 글자 모양 (글자를 언어별로 나눠 써야 함) */
    private val mixedLangs: Set<Int> = doc.charShapes.filter { (_, cs) -> !uniform(cs) }.keys

    fun mixed(cs: Int) = cs in mixedLangs

    fun charShape(id: Int): CharShape = doc.charShapes[id] ?: CharShape.DEFAULT

    private fun uniform(cs: CharShape): Boolean {
        for (l in Lang.entries) {
            if (cs.relSize(l) != cs.relSize(Lang.HANGUL) || cs.spacing(l) != cs.spacing(Lang.HANGUL) || cs.offset(l) != cs.offset(Lang.HANGUL)) return false
        }
        return true
    }

    /** 문단 첫 글자 모양 */
    fun firstCharShape(p: Paragraph): Int {
        for (i in p.content) when (i) {
            is Inline.Text -> return i.charShapeId
            is Inline.Tab -> return i.charShapeId
            is Inline.LineBreak -> return i.charShapeId
            is Inline.Object -> return i.charShapeId
            is Inline.Note -> return i.charShapeId
            is Inline.AutoNumber -> return i.charShapeId
            is Inline.Ruby -> return i.charShapeId
            else -> {}
        }
        return -1
    }
}

/** 한 줄(또는 한 문단)에 들어가는 인라인 조각. Text 는 [a, b) 글자 범위 */
internal class Seg(val inl: Inline, val a: Int, val b: Int)

/** 줄을 넘어 이어지는 상태 (열린 하이퍼링크) */
internal class RunState {
    var linkId = Int.MIN_VALUE
    var linkUrl: String? = null
}

/** 자동 번호에 필요한 쪽 정보 */
internal class PageInfoForNumbers(val number: Int, val total: Int, val numberType: NumberType = NumberType.DIGIT)

/**
 * 인라인 내용을 HTML 로 쓴다. 원본 보기(줄 단위)와 읽기 모드(문단 단위)에 같이 쓴다.
 * 검색 위치 계산을 위해 문단 plainText 에 들어가는 글자(Text, 탭, 줄바꿈)만 DOM 글자로 쓰고,
 * 그 밖의 생성 글자(번호, 각주 번호, 덧말 …)는 class="gen" 안에 쓴다.
 */
internal class RunWriter(
    private val ctx: Ctx,
    private val page: Boolean,
    /** 개체를 쓰는 함수 (모드별) */
    private val objectWriter: (StringBuilder, Inline.Object, Int) -> Unit,
    private val numbers: () -> PageInfoForNumbers?,
) {

    fun openLink(sb: StringBuilder, st: RunState) {
        val u = st.linkUrl ?: return
        sb.append("<a class=\"lk\" href=\"").attr(u).append("\">")
    }

    fun closeLink(sb: StringBuilder, st: RunState) {
        if (st.linkUrl != null) sb.append("</a>")
    }

    /**
     * 조각 목록 쓰기.
     * @param trailFrom 줄 끝 공백이 시작하는 위치 (segIndex, charIndex). 그 뒤 공백은 폭 0 상자에 넣는다 (원본 보기)
     * @param lineRatio 줄 전체에 적용한 장평 (%). 조각 장평이 다르면 그 조각만 따로 가로 비율
     */
    fun write(sb: StringBuilder, segs: List<Seg>, st: RunState, trailSeg: Int = -1, trailChar: Int = -1, lineRatio: Int = 100, depth: Int = 0) {
        for ((k, s) in segs.withIndex()) {
            when (val inl = s.inl) {
                is Inline.Text -> {
                    if (k == trailSeg) {
                        if (trailChar > s.a) text(sb, inl, s.a, trailChar, lineRatio)
                        sb.append("<span class=\"w c").append(inl.charShapeId).append("\">").text(inl.text, trailChar, s.b).append("</span>")
                    } else text(sb, inl, s.a, s.b, lineRatio)
                }
                is Inline.Tab -> tab(sb, inl)
                is Inline.LineBreak -> {
                    if (page) sb.append("<span class=\"n\">\n</span>") else sb.append('\n')
                }
                is Inline.Object -> objectWriter(sb, inl, depth)
                is Inline.Note -> {
                    sb.append("<span class=\"c").append(inl.charShapeId).append("\"><sup class=\"gen fr\">")
                        .text(noteNumber(inl)).append(")</sup></span>")
                }
                is Inline.AutoNumber -> autoNumber(sb, inl)
                is Inline.FieldStart -> {
                    if (inl.kind == FieldKind.HYPERLINK) {
                        val u = Html.safeUrl(inl.url)
                        if (u != null) {
                            closeLink(sb, st)
                            st.linkId = inl.id
                            st.linkUrl = u
                            openLink(sb, st)
                        }
                    }
                }
                is Inline.FieldEnd -> {
                    if (st.linkUrl != null && (inl.id == st.linkId || st.linkId == Int.MIN_VALUE)) {
                        closeLink(sb, st)
                        st.linkUrl = null
                        st.linkId = Int.MIN_VALUE
                    }
                }
                is Inline.Ruby -> {
                    sb.append("<span class=\"gen rb c").append(inl.charShapeId).append("\">").text(inl.base)
                        .append("<span class=\"rt").append(if (inl.below) " rbl" else "").append("\">").text(inl.annotation).append("</span></span>")
                }
            }
        }
    }

    /** 링크 상태만 따라가기 (쓰지 않는 앞부분) */
    fun track(inl: Inline, st: RunState) {
        when (inl) {
            is Inline.FieldStart -> if (inl.kind == FieldKind.HYPERLINK) {
                val u = Html.safeUrl(inl.url)
                if (u != null) {
                    st.linkId = inl.id; st.linkUrl = u
                }
            }
            is Inline.FieldEnd -> if (st.linkUrl != null && (inl.id == st.linkId || st.linkId == Int.MIN_VALUE)) {
                st.linkUrl = null; st.linkId = Int.MIN_VALUE
            }
            else -> {}
        }
    }

    private fun text(sb: StringBuilder, t: Inline.Text, a: Int, b: Int, lineRatio: Int) {
        if (b <= a) return
        val cs = t.charShapeId
        val shape = ctx.charShape(cs)
        if (!ctx.mixed(cs) && (!page || ratioOf(shape, t.text, a, b) == lineRatio)) {
            sb.append("<span class=\"c").append(cs).append("\">").text(t.text, a, b, keepNewline = !page).append("</span>")
            return
        }
        // 언어별로 나눠 쓴다 (공백은 앞 글자 언어에 붙인다)
        var i = a
        while (i < b) {
            var lang = Css.langOf(t.text[i])
            var j = i + 1
            if (t.text[i] == ' ') {
                // 공백으로 시작하면 다음 글자 언어
                while (j < b && t.text[j] == ' ') j++
                lang = if (j < b) Css.langOf(t.text[j]) else Lang.HANGUL
            }
            while (j < b && (t.text[j] == ' ' || Css.langOf(t.text[j]) == lang)) j++
            val r = if (page) shape.ratio(lang).coerceIn(10, 500) else 100
            sb.append("<span class=\"c").append(cs)
            if (ctx.mixed(cs) && lang != Lang.HANGUL) sb.append(" L").append(lang.ordinal)
            if (page && r != lineRatio) {
                val q = r / lineRatio.toDouble()
                sb.append(" rq\" data-q=\"").num(q).append("\" style=\"transform:scaleX(").num(q).append(")")
            }
            sb.append("\">").text(t.text, i, j, keepNewline = !page).append("</span>")
            i = j
        }
    }

    private fun tab(sb: StringBuilder, t: Inline.Tab) {
        if (t.width > 0 && page) {
            sb.append("<span class=\"t c").append(t.charShapeId)
            if (t.leader != LineType.NONE) sb.append(" ld ld").append(leaderClass(t.leader))
            sb.append("\" style=\"width:").px(t.width.coerceAtMost(200000)).append("\">\t</span>")
        } else {
            sb.append("<span class=\"c").append(t.charShapeId).append(" tt\">\t</span>")
        }
    }

    private fun leaderClass(t: LineType) = when (t) {
        LineType.DOT, LineType.CIRCLE -> "d"
        LineType.DASH, LineType.LONG_DASH, LineType.DASH_DOT, LineType.DASH_DOT_DOT -> "a"
        else -> "s"
    }

    private fun noteNumber(n: Inline.Note): String {
        // 각주 번호 모양은 각주 안 자동 번호에 있다
        var type = NumberType.DIGIT
        for (p in n.paragraphs) for (i in p.content) if (i is Inline.AutoNumber && (i.kind == AutoNumberKind.FOOTNOTE || i.kind == AutoNumberKind.ENDNOTE)) {
            type = i.numberType
            return NumberFormat.format(n.number, type)
        }
        return NumberFormat.format(n.number, type)
    }

    private fun autoNumber(sb: StringBuilder, a: Inline.AutoNumber) {
        val nums = numbers()
        val txt = when (a.kind) {
            AutoNumberKind.PAGE -> nums?.let { NumberFormat.format(it.number, a.numberType) } ?: ""
            AutoNumberKind.TOTAL_PAGES -> nums?.let { NumberFormat.format(it.total, a.numberType) } ?: ""
            AutoNumberKind.FOOTNOTE, AutoNumberKind.ENDNOTE -> NumberFormat.format(a.number, a.numberType) + ")"
            else -> NumberFormat.format(a.number, a.numberType)
        }
        if (txt.isEmpty()) return
        sb.append("<span class=\"gen c").append(a.charShapeId).append("\">").text(txt).append("</span>")
    }

    /** 문단 머리 (번호/글머리표) */
    fun heading(sb: StringBuilder, h: Heading, p: Paragraph) {
        val cs = if (h.charShapeId >= 0 && ctx.doc.charShapes.containsKey(h.charShapeId)) h.charShapeId else ctx.firstCharShape(p)
        sb.append("<span class=\"gen hd")
        if (cs >= 0) sb.append(" c").append(cs)
        sb.append("\">")
        val img = h.imageId?.let { ctx.doc.images[it] }
        if (img != null) sb.append("<img class=\"bi\" alt=\"\" src=\"").attr(img.path).append("\">")
        else sb.text(h.text)
        sb.append("</span>")
        if (page) {
            val gap = if (h.gap > 0) h.gap else 0
            if (gap > 0) sb.append("<span class=\"gen hg\" style=\"width:").px(gap).append("\"></span>")
            else sb.append("<span class=\"gen hg\" style=\"width:0.5em\"></span>")
        } else sb.append("<span class=\"gen\"> </span>")
    }

    companion object {
        /** 조각의 장평 (첫 글자가 아닌 글자 언어 기준) */
        fun ratioOf(cs: CharShape, text: String, a: Int, b: Int): Int {
            for (i in a until b) {
                val c = text[i]
                if (c != ' ') return cs.ratio(Css.langOf(c)).coerceIn(10, 500)
            }
            return cs.ratio(Lang.HANGUL).coerceIn(10, 500)
        }

        /** 줄 조각들의 대표 장평 (글자 수가 가장 많은 값) */
        fun lineRatio(ctx: Ctx, segs: List<Seg>): Int {
            var best = 100
            var bestN = -1
            val counts = HashMap<Int, Int>(4)
            for (s in segs) {
                val t = s.inl as? Inline.Text ?: continue
                val r = ratioOf(ctx.charShape(t.charShapeId), t.text, s.a, s.b)
                val n = (counts[r] ?: 0) + (s.b - s.a)
                counts[r] = n
                if (n > bestN) {
                    bestN = n; best = r
                }
            }
            return best
        }
    }
}

/** 문단 내용을 줄별 조각으로 나누기 */
internal object Segments {

    /** 문단 전체를 줄 [from, to) 별 조각 목록으로 (원본 보기). 각 줄의 plainText 시작 위치도 함께 */
    class LineSegs(val segs: List<List<Seg>>, val plainStart: IntArray, val endsWithBreak: BooleanArray)

    fun split(p: Paragraph, ls: LineStarts, from: Int, to: Int, st: RunState, rw: RunWriter?): LineSegs {
        val n = maxOf(0, to - from)
        val out = ArrayList<ArrayList<Seg>>(n)
        repeat(n) { out.add(ArrayList()) }
        val plainStart = IntArray(n) { -1 }
        val endsWithBreak = BooleanArray(n)
        var plain = 0
        for (inl in p.content) {
            if (inl is Inline.Text) {
                val len = inl.text.length
                var a = 0
                while (a < len) {
                    val pos = (inl.start.toLong() + a).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val li = ls.lineOf(pos)
                    val lineEnd = ls.end(li)
                    val b = if (lineEnd == Int.MAX_VALUE) len else (lineEnd.toLong() - inl.start).coerceIn(a + 1L, len.toLong()).toInt()
                    if (li in from until to) {
                        val k = li - from
                        if (plainStart[k] < 0) plainStart[k] = plain
                        out[k].add(Seg(inl, a, b))
                    }
                    plain += b - a
                    a = b
                }
                continue
            }
            val li = ls.lineOf(inl.start)
            if (li < from) {
                rw?.track(inl, st)
            } else if (li < to) {
                val k = li - from
                if (plainStart[k] < 0) plainStart[k] = plain
                out[k].add(Seg(inl, 0, 1))
                if (inl is Inline.LineBreak) endsWithBreak[k] = true
            }
            if (inl is Inline.Tab || inl is Inline.LineBreak) plain++
        }
        // 내용 없는 줄은 다음 내용의 위치 (없으면 문단 끝)
        var next = plain
        for (k in n - 1 downTo 0) {
            if (plainStart[k] < 0) plainStart[k] = next else next = plainStart[k]
        }
        return LineSegs(out, plainStart, endsWithBreak)
    }

    /** 문단 전체를 한 덩어리로 (읽기 모드) */
    fun whole(p: Paragraph): List<Seg> = p.content.map { if (it is Inline.Text) Seg(it, 0, it.text.length) else Seg(it, 0, 1) }

    /** 줄 끝 공백 위치 (조각 index, 글자 index). 없으면 (-1,-1) */
    fun trailing(segs: List<Seg>): Pair<Int, Int> {
        for (k in segs.indices.reversed()) {
            val s = segs[k]
            when (val inl = s.inl) {
                is Inline.LineBreak, is Inline.FieldStart, is Inline.FieldEnd -> continue
                is Inline.Text -> {
                    var i = s.b
                    while (i > s.a && (inl.text[i - 1] == ' ' || inl.text[i - 1] == '　')) i--
                    return if (i < s.b) k to i else -1 to -1
                }
                else -> return -1 to -1
            }
        }
        return -1 to -1
    }
}

/** 공용 단위 도우미 */
internal fun StringBuilder.box(x: Int, y: Int, w: Int, h: Int): StringBuilder =
    append("left:").px(x).append(";top:").px(y).append(";width:").px(maxOf(0, w)).append(";height:").px(maxOf(0, h))

internal fun StringBuilder.boxd(x: Double, y: Double, w: Double, h: Double): StringBuilder =
    append("left:").pxd(x).append(";top:").pxd(y).append(";width:").pxd(maxOf(0.0, w)).append(";height:").pxd(maxOf(0.0, h))
