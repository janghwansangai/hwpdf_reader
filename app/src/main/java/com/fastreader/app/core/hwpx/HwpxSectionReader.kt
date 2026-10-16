package com.fastreader.app.core.hwpx

import com.fastreader.app.core.doc.AutoNumberKind
import com.fastreader.app.core.doc.Border
import com.fastreader.app.core.doc.BorderFill
import com.fastreader.app.core.doc.BreakType
import com.fastreader.app.core.doc.Caption
import com.fastreader.app.core.doc.Cell
import com.fastreader.app.core.doc.ColumnDef
import com.fastreader.app.core.doc.DrawObject
import com.fastreader.app.core.doc.Equation
import com.fastreader.app.core.doc.FieldKind
import com.fastreader.app.core.doc.HeaderFooter
import com.fastreader.app.core.doc.HorzRel
import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.Insets
import com.fastreader.app.core.doc.LineSeg
import com.fastreader.app.core.doc.LineType
import com.fastreader.app.core.doc.NumberType
import com.fastreader.app.core.doc.ObjAlign
import com.fastreader.app.core.doc.ObjectLayout
import com.fastreader.app.core.doc.PageApply
import com.fastreader.app.core.doc.PageDef
import com.fastreader.app.core.doc.PageNumbering
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.Picture
import com.fastreader.app.core.doc.Section
import com.fastreader.app.core.doc.Shape
import com.fastreader.app.core.doc.ShapeKind
import com.fastreader.app.core.doc.Table
import com.fastreader.app.core.doc.TextWrap
import com.fastreader.app.core.doc.VertRel

/** 문서 전체에서 공유하는 파싱 상태 (한도, 형광펜 글자 모양, 각주 번호) */
internal class HwpxParseContext {
    var paragraphs = 0
        private set
    var chars = 0L
        private set
    var objects = 0
        private set

    fun countParagraph() {
        if (++paragraphs > MAX_PARAGRAPHS) throw LimitExceededException("문단이 너무 많습니다.")
    }

    fun countChars(n: Int) {
        chars += n
        if (chars > MAX_CHARS) throw LimitExceededException("글자가 너무 많습니다.")
    }

    fun countObject() {
        if (++objects > MAX_OBJECTS) throw LimitExceededException("개체가 너무 많습니다.")
    }

    /** 형광펜(markpen): (원래 글자 모양, 색) → 새 글자 모양 ID. 실제 CharShape 는 머리 정보를 읽은 뒤 만든다 */
    val highlights = LinkedHashMap<Long, Int>()

    fun highlightShape(base: Int, color: Int): Int {
        val key = (base.toLong() shl 32) or (color.toLong() and 0xFFFFFFFFL)
        return highlights.getOrPut(key) {
            if (highlights.size >= 10_000) return base
            HIGHLIGHT_ID_BASE + highlights.size
        }
    }

    var footnoteCount = 0
    var endnoteCount = 0

    companion object {
        const val MAX_PARAGRAPHS = 600_000
        const val MAX_CHARS = 40_000_000L
        const val MAX_OBJECTS = 300_000
        const val HIGHLIGHT_ID_BASE = 0x40000000
    }
}

/** 한 문단을 만드는 도구. 위치(pos)는 HWP WCHAR 좌표: 일반 글자 1, 탭/컨트롤/개체 8, 줄바꿈 1 */
internal class ParaBuilder(
    private val ctx: HwpxParseContext,
    private val paraShapeId: Int,
    private val styleId: Int,
    private val breakType: BreakType,
) {
    var pos = 0
        private set
    private val content = ArrayList<Inline>(4)
    private var lines: ArrayList<LineSeg>? = null
    private val sb = StringBuilder()
    private var textStart = 0
    private var textShape = 0

    /** 형광펜 색 (-1 = 없음) */
    var markpen = -1

    private fun shape(base: Int) = if (markpen >= 0) ctx.highlightShape(base, markpen) else base

    fun text(shapeId: Int, ch: CharArray, start: Int, length: Int) {
        var runStart = start
        val end = start + length
        for (i in start until end) {
            val c = ch[i]
            if (c == '\n' || c == '\t' || c == '\r') {
                if (i > runStart) appendChars(shapeId, ch, runStart, i - runStart)
                when (c) {
                    '\n' -> lineBreak(shapeId)
                    '\t' -> tab(shapeId, 0, LineType.NONE)
                }
                runStart = i + 1
            }
        }
        if (end > runStart) appendChars(shapeId, ch, runStart, end - runStart)
    }

    private fun appendChars(shapeId: Int, ch: CharArray, start: Int, length: Int) {
        val sid = shape(shapeId)
        if (sb.isNotEmpty() && sid != textShape) flushText()
        if (sb.isEmpty()) {
            textStart = pos
            textShape = sid
        }
        ctx.countChars(length)
        sb.append(ch, start, length)
        pos += length
    }

    fun char(shapeId: Int, c: Char) {
        val sid = shape(shapeId)
        if (sb.isNotEmpty() && sid != textShape) flushText()
        if (sb.isEmpty()) {
            textStart = pos
            textShape = sid
        }
        ctx.countChars(1)
        sb.append(c)
        pos += 1
    }

    /** 문단에 글자 없이 끼워 넣은 문자열 (글자 겹침 등). 위치는 [at] 부터 */
    fun addTextAt(at: Int, shapeId: Int, text: String) {
        if (text.isEmpty()) return
        flushText()
        ctx.countChars(text.length)
        content.add(Inline.Text(at, text, shape(shapeId)))
    }

    private fun flushText() {
        if (sb.isEmpty()) return
        content.add(Inline.Text(textStart, sb.toString(), textShape))
        sb.setLength(0)
    }

    fun tab(shapeId: Int, width: Int, leader: LineType) {
        flushText()
        content.add(Inline.Tab(pos, shape(shapeId), width, leader))
        pos += 8
    }

    fun lineBreak(shapeId: Int) {
        flushText()
        content.add(Inline.LineBreak(pos, shape(shapeId)))
        pos += 1
    }

    /** 컨트롤/개체 자리(8칸)를 잡고 시작 위치를 돌려준다 */
    fun reserve(n: Int = 8): Int {
        flushText()
        val p = pos
        pos += n
        return p
    }

    fun add(inline: Inline) {
        flushText()
        content.add(inline)
    }

    fun styleShape(shapeId: Int) = shape(shapeId)

    fun addLine(seg: LineSeg) {
        val l = lines ?: ArrayList<LineSeg>(2).also { lines = it }
        if (l.size >= 100_000) return
        // 위치는 단조 증가해야 한다
        val prev = l.lastOrNull()?.textStart ?: 0
        l.add(if (seg.textStart < prev) seg.copy(textStart = prev) else seg)
    }

    fun build(): Paragraph {
        flushText()
        content.trimToSize()
        return Paragraph(paraShapeId, styleId, breakType, content, lines ?: emptyList())
    }
}

/**
 * Contents/sectionN.xml → [Section].
 *
 * 개체/컨트롤은 시작 태그에서 8칸 자리를 잡고 끝 태그에서 Inline 을 만든다. 그래서 개체 안의 문단(표 셀, 글상자 등)은
 * 바깥 문단의 위치에 영향을 주지 않는다. 위치 규칙은 hwp2hwpx ForChars 와 같은 짝 문서(.hwp/.hwpx)로 확인했다.
 */
internal class HwpxSectionReader(private val ctx: HwpxParseContext) {
    private var page: PageDef? = null
    private var columns: ColumnDef? = null
    private val paragraphs = ArrayList<Paragraph>()
    private val headers = ArrayList<HeaderFooter>()
    private val footers = ArrayList<HeaderFooter>()
    private var pageNumbering: PageNumbering? = null
    private var startPage = 0
    private var newNumPage = 0
    private var outlineNumberingId = 0
    private var hideFirstHeader = false
    private var hideFirstFooter = false
    private var hideFirstPageNumber = false
    private var secPrSeen = false

    /** 루트가 hs:sec 이었는가 */
    var isSection = false
        private set

    val root: XNode = object : XNode() {
        override fun child(name: String, a: XAttrs): XNode? {
            if (name != "sec") throw StopParsingException()
            isSection = true
            return SecNode()
        }
    }

    val paragraphCount get() = paragraphs.size

    fun build(): Section = Section(
        page = page ?: PageDef(),
        columns = columns ?: ColumnDef(),
        paragraphs = paragraphs,
        headers = headers,
        footers = footers,
        pageNumbering = pageNumbering,
        startPageNumber = if (startPage > 0) startPage else newNumPage,
        outlineNumberingId = outlineNumberingId,
        hideFirstHeader = hideFirstHeader,
        hideFirstFooter = hideFirstFooter,
        hideFirstPageNumber = hideFirstPageNumber,
    )

    /** 지금 읽는 최상위 문단 번호 */
    private val topIndex get() = paragraphs.size

    /** 상대 크기(widthRelTo)의 기준 폭 */
    private fun refWidth(rel: String): Int {
        val p = page ?: PageDef()
        val c = columns ?: ColumnDef()
        return when (rel) {
            "PAPER" -> p.width
            "PAGE" -> p.bodyWidth
            else -> if (c.count <= 1) p.bodyWidth else ((p.bodyWidth - c.gap * (c.count - 1)) / c.count).coerceAtLeast(0)
        }
    }

    private fun refHeight(rel: String): Int {
        val p = page ?: PageDef()
        return if (rel == "PAPER") p.height else p.bodyHeight
    }

    private inner class SecNode : XNode() {
        override fun child(name: String, a: XAttrs): XNode? =
            if (name == "p") ParaNode(a, paragraphs) else null
    }

    /** 문단 목록을 담는 hp:subList (셀, 글상자, 머리말, 각주, 캡션) */
    private inner class SubListNode(private val out: MutableList<Paragraph>) : XNode() {
        override fun child(name: String, a: XAttrs): XNode? =
            if (name == "p") ParaNode(a, out) else null
    }

    private inner class ParaNode(a: XAttrs, private val out: MutableList<Paragraph>) : XNode() {
        val pb: ParaBuilder

        init {
            ctx.countParagraph()
            val brk = when {
                a.bool("pageBreak") -> BreakType.PAGE
                a.bool("columnBreak") -> BreakType.COLUMN
                else -> BreakType.NONE
            }
            pb = ParaBuilder(ctx, a.int("paraPrIDRef"), a.int("styleIDRef"), brk)
        }

        override fun child(name: String, a: XAttrs): XNode? = when (name) {
            "run" -> RunNode(pb, a.int("charPrIDRef"))
            "linesegarray" -> LineSegArrayNode(pb)
            else -> null
        }

        override fun end() {
            out.add(pb.build())
        }
    }

    private class LineSegArrayNode(private val pb: ParaBuilder) : XNode() {
        override fun child(name: String, a: XAttrs): XNode? {
            if (name == "lineseg") {
                pb.addLine(
                    LineSeg(
                        textStart = a.int("textpos").coerceAtLeast(0),
                        vertPos = a.len("vertpos"),
                        height = a.len("vertsize"),
                        textHeight = a.len("textheight"),
                        baseline = a.len("baseline"),
                        spacing = a.len("spacing"),
                        horzStart = a.len("horzpos"),
                        width = a.len("horzsize"),
                        flags = a.int("flags"),
                    ),
                )
            }
            return null
        }
    }

    private inner class RunNode(private val pb: ParaBuilder, private val charPr: Int) : XNode() {
        override fun child(name: String, a: XAttrs): XNode? {
            when (name) {
                "t" -> return TNode(pb, a["charPrIDRef"]?.let { XAttrs.parseInt(it, charPr) } ?: charPr)
                "ctrl" -> return CtrlNode(pb, charPr)
                "secPr" -> {
                    pb.reserve()
                    if (secPrSeen) return null
                    secPrSeen = true
                    return SecPrNode(a)
                }
                "markpenBegin" -> {
                    pb.markpen = HwpxValues.color(a["color"]) ?: 0xFFFF00
                    return null
                }
                "markpenEnd" -> {
                    pb.markpen = -1
                    return null
                }
            }
            val start = pb.reserve()
            return objectNode(name, a) { obj -> pb.add(Inline.Object(start, charPr, obj)) }
                ?: textLikeObject(name, a, start)
        }

        /** 글자로 바꿔 넣는 개체 (글자 겹침, 덧말). 아니면 null (알 수 없는 요소는 8칸 컨트롤로 본다) */
        private fun textLikeObject(name: String, a: XAttrs, start: Int): XNode? = when (name) {
            "compose" -> ComposeNode(pb, start, charPr, a)
            "dutmal" -> DutmalNode(pb, start, charPr, a)
            else -> null
        }
    }

    /** 글자 겹침: 겹친 글자를 8칸 자리의 처음에 글자로 넣는다. 글자 모양은 첫 글자의 것(hp:charPr) */
    private inner class ComposeNode(private val pb: ParaBuilder, private val start: Int, private val charPr: Int, a: XAttrs) : XNode() {
        private val text = composeDisplay(a.str("circleType"), a.str("composeText"))
        private var shape = -1
        private var seen = false

        override fun child(name: String, a: XAttrs): XNode? {
            if (name == "charPr" && !seen) {
                seen = true
                val id = a.int("prIDRef", -1)
                if (id >= 0 && id != 0xFFFF) shape = id
            }
            return null
        }

        override fun end() = pb.addTextAt(start, if (shape >= 0) shape else charPr, text)
    }

    private inner class TNode(private val pb: ParaBuilder, private val charPr: Int) : XNode() {
        override fun text(ch: CharArray, start: Int, length: Int) = pb.text(charPr, ch, start, length)

        override fun child(name: String, a: XAttrs): XNode? {
            when (name) {
                "tab" -> pb.tab(
                    charPr,
                    a.len("width").coerceAtLeast(0),
                    a["leader"]?.let { l -> l.trim().toIntOrNull()?.let { HwpxValues.lineTypeIndex(it) } ?: HwpxValues.lineType(l) } ?: LineType.NONE,
                )
                "lineBreak" -> pb.lineBreak(charPr)
                "nbSpace" -> pb.char(charPr, ' ')
                "fwSpace" -> pb.char(charPr, ' ')
                "hyphen", "hypen" -> pb.char(charPr, '-')
                "titleMark" -> pb.reserve()
                "unknownch" -> pb.reserve(1)
                "markpenBegin" -> pb.markpen = HwpxValues.color(a["color"]) ?: 0xFFFF00
                "markpenEnd" -> pb.markpen = -1
                // insertBegin/End, deleteBegin/End 등 변경 추적 표시는 자리를 차지하지 않는다
            }
            return null
        }
    }

    // ------------------------------------------------------------ 구역 정의

    private inner class SecPrNode(a: XAttrs) : XNode() {
        private var pd: PageDef? = null

        init {
            outlineNumberingId = a.int("outlineShapeIDRef")
        }

        override fun child(name: String, a: XAttrs): XNode? {
            when (name) {
                "startNum" -> startPage = a.int("page").coerceIn(0, 1_000_000)
                "visibility" -> {
                    hideFirstHeader = a.bool("hideFirstHeader")
                    hideFirstFooter = a.bool("hideFirstFooter")
                    hideFirstPageNumber = a.bool("hideFirstPageNum")
                }
                "pagePr" -> {
                    val landscape = a.str("landscape").trim().uppercase() == "NARROWLY"
                    val w = a.len("width", 59528).coerceIn(1000, 2_000_000)
                    val h = a.len("height", 84188).coerceIn(1000, 2_000_000)
                    pd = PageDef(width = if (landscape) h else w, height = if (landscape) w else h, landscape = landscape)
                    page = pd
                    return PageMarginNode()
                }
            }
            return null
        }

        private inner class PageMarginNode : XNode() {
            override fun child(name: String, a: XAttrs): XNode? {
                if (name == "margin") {
                    val d = pd ?: PageDef()
                    page = d.copy(
                        marginLeft = a.len("left", d.marginLeft).coerceAtLeast(0),
                        marginRight = a.len("right", d.marginRight).coerceAtLeast(0),
                        marginTop = a.len("top", d.marginTop).coerceAtLeast(0),
                        marginBottom = a.len("bottom", d.marginBottom).coerceAtLeast(0),
                        marginHeader = a.len("header", d.marginHeader).coerceAtLeast(0),
                        marginFooter = a.len("footer", d.marginFooter).coerceAtLeast(0),
                        marginGutter = a.len("gutter", d.marginGutter).coerceAtLeast(0),
                    )
                }
                return null
            }
        }
    }

    // ------------------------------------------------------------ 컨트롤 (hp:ctrl 의 자식 하나 = 8칸)

    private inner class CtrlNode(private val pb: ParaBuilder, private val charPr: Int) : XNode() {
        override fun child(name: String, a: XAttrs): XNode? {
            val start = pb.reserve()
            when (name) {
                "colPr" -> if (columns == null) return ColPrNode(a)
                "header", "footer" -> return HeaderFooterNode(name == "header", a)
                "footNote", "endNote" -> {
                    val endnote = name == "endNote"
                    val number = a.int("number", 0).let { n ->
                        if (endnote) {
                            if (n > 0) ctx.endnoteCount = n else ctx.endnoteCount++
                            ctx.endnoteCount
                        } else {
                            if (n > 0) ctx.footnoteCount = n else ctx.footnoteCount++
                            ctx.footnoteCount
                        }
                    }
                    return NoteNode { paras -> pb.add(Inline.Note(start, pb.styleShape(charPr), endnote, number, paras)) }
                }
                "autoNum" -> return AutoNumNode(a) { kind, type, num -> pb.add(Inline.AutoNumber(start, pb.styleShape(charPr), kind, type, num)) }
                "newNum" -> if (a.str("numType").trim().uppercase() == "PAGE" && topIndex == 0 && newNumPage == 0) {
                    newNumPage = a.int("num").coerceIn(0, 1_000_000)
                }
                "pageNum" -> if (pageNumbering == null) {
                    pageNumbering = PageNumbering(
                        position = HwpxValues.pageNumberPos(a["pos"]),
                        numberType = HwpxValues.numberType(a["formatType"]),
                        dash = a.str("sideChar").take(4),
                        fromParagraph = topIndex,
                    )
                }
                "pageHiding" -> if (topIndex == 0) {
                    if (a.bool("hideHeader")) hideFirstHeader = true
                    if (a.bool("hideFooter")) hideFirstFooter = true
                    if (a.bool("hidePageNum")) hideFirstPageNumber = true
                }
                "fieldBegin" -> return FieldBeginNode(a) { id, kind, url -> pb.add(Inline.FieldStart(start, id, kind, url)) }
                "fieldEnd" -> pb.add(Inline.FieldEnd(start, a.int("beginIDRef")))
                // bookmark, indexmark, pageNumCtrl, hiddenComment(숨은 설명은 보이지 않음) 등은 자리만 차지
            }
            return null
        }
    }

    private inner class FieldBeginNode(a: XAttrs, private val done: (Int, FieldKind, String?) -> Unit) : XNode() {
        private val id = a.int("id")
        private val type = a.str("type").trim().uppercase()
        private var path: String? = null
        private var command: String? = null

        override fun child(name: String, a: XAttrs): XNode? = when (name) {
            "parameters" -> object : XNode() {
                override fun child(name: String, a: XAttrs): XNode? {
                    if (name != "stringParam") return null
                    return when (a["name"]) {
                        "Path" -> object : TextCollector(4096) {
                            override fun end() {
                                path = sb.toString()
                            }
                        }
                        "Command" -> object : TextCollector(4096) {
                            override fun end() {
                                command = sb.toString()
                            }
                        }
                        else -> null
                    }
                }
            }
            else -> null // 메모 필드의 subList 등은 본문이 아니다
        }

        override fun end() {
            val kind = when (type) {
                "HYPERLINK" -> FieldKind.HYPERLINK
                "CLICK_HERE" -> FieldKind.CLICK_HERE
                else -> FieldKind.OTHER
            }
            val url = if (kind == FieldKind.HYPERLINK) (normalizeUrl(path) ?: commandUrl(command)) else null
            done(id, kind, url)
        }
    }

    /**
     * 글자 겹침(compose)을 보여 줄 글자. 모양 겹침(SHAPE_*)은 첫 글자 뒤에 모양 표시 문자가 붙어 있으므로 첫 글자만 쓰고,
     * 원 모양이면 가능한 경우 유니코드 원문자(①, ⓐ, ㉮, ㉠)로 바꾼다. 위치 8칸을 넘지 않게 자른다.
     */
    private fun composeDisplay(circleType: String, text: String): String {
        val type = circleType.trim().uppercase()
        if (!type.startsWith("SHAPE")) return text.take(8)
        val num = text.takeWhile { it.isDigit() }
        val first = if (num.isNotEmpty()) num else text.take(1)
        if (type == "SHAPE_CIRCLE") {
            num.toIntOrNull()?.let { n -> if (n in 1..20) return (0x2460 + n - 1).toChar().toString() }
            val c = first.firstOrNull() ?: return ""
            when (c) {
                in 'a'..'z' -> return (0x24D0 + (c - 'a')).toChar().toString()
                in 'A'..'Z' -> return (0x24B6 + (c - 'A')).toChar().toString()
            }
            val ga = "가나다라마바사아자차카타파하".indexOf(c)
            if (ga >= 0) return (0x326E + ga).toChar().toString()
            val jamo = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ".indexOf(c)
            if (jamo >= 0) return (0x3260 + jamo).toChar().toString()
        }
        return first.take(8)
    }

    /**
     * Command 의 "http\://a.b/c;1;0;0;" 꼴에서 주소만 꺼낸다 (HWP 파서와 같은 규칙).
     * 주소 안의 ':', ';', '?' 등은 '\' 로 이스케이프되고, "주소|대상" 이면 '|' 앞까지. "www." 로 시작하면 http:// 를 붙인다.
     */
    private fun commandUrl(cmd: String?): String? {
        if (cmd.isNullOrBlank()) return null
        val sb = StringBuilder()
        var i = 0
        while (i < cmd.length) {
            val c = cmd[i]
            if (c == '\\' && i + 1 < cmd.length) {
                sb.append(cmd[i + 1])
                i += 2
                continue
            }
            if (c == ';' || c == '|') break
            sb.append(c)
            i++
        }
        return normalizeUrl(sb.toString())
    }

    private fun normalizeUrl(s: String?): String? {
        val url = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (url.startsWith("www.", ignoreCase = true)) "http://$url" else url
    }

    private inner class ColPrNode(a: XAttrs) : XNode() {
        private val count = a.int("colCount", 1).coerceIn(1, 255)
        private val sameSz = a.bool("sameSz", true)
        private val gap = a.len("sameGap").coerceAtLeast(0)
        private val widths = ArrayList<Int>()
        private val gaps = ArrayList<Int>()
        private var separator: Border? = null

        override fun child(name: String, a: XAttrs): XNode? {
            when (name) {
                "colSz" -> if (widths.size < 255) {
                    widths.add(a.len("width").coerceAtLeast(0))
                    gaps.add(a.len("gap").coerceAtLeast(0))
                }
                "colLine" -> separator = HwpxValues.border(a).takeIf { it.visible }
            }
            return null
        }

        override fun end() {
            columns = ColumnDef(
                count = count,
                gap = if (sameSz || gaps.isEmpty()) gap else gaps.first(),
                widths = if (sameSz) emptyList() else widths.toList(),
                separator = separator,
            )
        }
    }

    private inner class HeaderFooterNode(private val isHeader: Boolean, a: XAttrs) : XNode() {
        private val apply = when (a.str("applyPageType").trim().uppercase()) {
            "EVEN" -> PageApply.EVEN
            "ODD" -> PageApply.ODD
            else -> PageApply.BOTH
        }
        private val from = topIndex
        private val paras = ArrayList<Paragraph>()
        private var w = 0
        private var h = 0

        override fun child(name: String, a: XAttrs): XNode? {
            if (name != "subList") return null
            w = a.len("textWidth").coerceAtLeast(0)
            h = a.len("textHeight").coerceAtLeast(0)
            return SubListNode(paras)
        }

        override fun end() {
            val hf = HeaderFooter(apply, paras, w, h, from)
            if (isHeader) headers.add(hf) else footers.add(hf)
        }
    }

    private inner class NoteNode(private val done: (List<Paragraph>) -> Unit) : XNode() {
        private val paras = ArrayList<Paragraph>()

        override fun child(name: String, a: XAttrs): XNode? = if (name == "subList") SubListNode(paras) else null

        override fun end() = done(paras)
    }

    private class AutoNumNode(a: XAttrs, private val done: (AutoNumberKind, NumberType, Int) -> Unit) : XNode() {
        private val kind = when (a.str("numType").trim().uppercase()) {
            "TOTAL_PAGE", "TOTAL_PAGES" -> AutoNumberKind.TOTAL_PAGES
            "FOOTNOTE" -> AutoNumberKind.FOOTNOTE
            "ENDNOTE" -> AutoNumberKind.ENDNOTE
            "PICTURE" -> AutoNumberKind.PICTURE
            "TABLE" -> AutoNumberKind.TABLE
            "EQUATION" -> AutoNumberKind.EQUATION
            else -> AutoNumberKind.PAGE
        }
        private val num = a.int("num")
        private var type = NumberType.DIGIT

        override fun child(name: String, a: XAttrs): XNode? {
            if (name == "autoNumFormat") type = HwpxValues.numberType(a["type"])
            return null
        }

        override fun end() = done(kind, type, num)
    }

    private class DutmalNode(private val pb: ParaBuilder, private val start: Int, private val charPr: Int, a: XAttrs) : XNode() {
        private val below = a.str("posType").trim().uppercase() == "BOTTOM"
        private var main = ""
        private var sub = ""

        override fun child(name: String, a: XAttrs): XNode? = when (name) {
            "mainText" -> object : TextCollector(4096) {
                override fun end() {
                    main = sb.toString()
                }
            }
            "subText" -> object : TextCollector(4096) {
                override fun end() {
                    sub = sb.toString()
                }
            }
            else -> null
        }

        override fun end() {
            pb.add(Inline.Ruby(start, pb.styleShape(charPr), main, sub, below))
        }
    }

    // ------------------------------------------------------------ 개체

    /** 개체 요소면 처리 노드를, 아니면 null */
    private fun objectNode(name: String, a: XAttrs, deliver: (DrawObject) -> Unit): XNode? {
        val node: ObjNode = when (name) {
            "tbl" -> TableNode(a)
            "pic" -> PictureNode(a)
            "rect" -> ShapeNode(a, ShapeKind.RECT)
            "ellipse" -> ShapeNode(a, ShapeKind.ELLIPSE)
            "line", "connectLine" -> ShapeNode(a, ShapeKind.LINE)
            "arc" -> ShapeNode(a, ShapeKind.ARC)
            "polygon" -> ShapeNode(a, ShapeKind.POLYGON)
            "curve" -> ShapeNode(a, ShapeKind.CURVE)
            "container" -> ContainerNode(a)
            "ole" -> ShapeNode(a, ShapeKind.OLE)
            // 글맵시 글자의 줄바꿈은 제어 문자 그림(␍␊)으로 적혀 있다
            "textart" -> ShapeNode(a, ShapeKind.TEXT_ART).also {
                it.text = a.str("text").take(4096).replace("␍␊", "\n").replace('␊', '\n').replace('␍', '\n')
            }
            "equation" -> EquationNode(a)
            "chart" -> ShapeNode(a, ShapeKind.OTHER)
            // 동영상은 미리보기 그림(imageIDRef)으로 보여 준다
            "video" -> PictureNode(a, a["imageIDRef"])
            // 양식 개체: 글자(단추 이름, 선택 값, 입력 글자)와 바탕색만
            "btn", "radioBtn", "checkBtn" -> ShapeNode(a, ShapeKind.OTHER).also { it.form(a, a.str("caption")) }
            "comboBox", "listBox" -> ShapeNode(a, ShapeKind.OTHER).also { it.form(a, a.str("selectedValue")) }
            "edit", "scrollBar" -> ShapeNode(a, ShapeKind.OTHER).also { it.form(a, "") }
            else -> return null
        }
        ctx.countObject()
        node.deliver = deliver
        return node
    }

    /** 2D 변환 행렬 [e1 e2 e3; e4 e5 e6] (x' = e1 x + e2 y + e3, y' = e4 x + e5 y + e6) */
    private class Matrix(val m: DoubleArray) {
        fun times(o: Matrix): Matrix {
            val a = m
            val b = o.m
            return Matrix(
                doubleArrayOf(
                    a[0] * b[0] + a[1] * b[3], a[0] * b[1] + a[1] * b[4], a[0] * b[2] + a[1] * b[5] + a[2],
                    a[3] * b[0] + a[4] * b[3], a[3] * b[1] + a[4] * b[4], a[3] * b[2] + a[4] * b[5] + a[5],
                ),
            )
        }

        fun x(px: Double, py: Double) = m[0] * px + m[1] * py + m[2]
        fun y(px: Double, py: Double) = m[3] * px + m[4] * py + m[5]

        companion object {
            val IDENTITY = Matrix(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0))
        }
    }

    /** 개체 공통 (hp:sz, hp:pos, hp:outMargin, hp:caption, 도형 요소의 offset/orgSz/curSz/renderingInfo) */
    private abstract inner class ObjNode(a: XAttrs) : XNode() {
        var deliver: ((DrawObject) -> Unit)? = null

        /** 묶음 개체의 자식이면 true (hp:pos 대신 변환 행렬로 위치를 정한다) */
        var inContainer = false

        private val zOrder = a.int("zOrder")
        private val textWrap = HwpxValues.textWrap(a["textWrap"])
        private var szW = -1
        private var szH = -1
        private var szWRel = "ABSOLUTE"
        private var szHRel = "ABSOLUTE"
        private var treatAsChar = true
        private var vertRel = VertRel.PARA
        private var horzRel = HorzRel.COLUMN
        private var vertAlign = ObjAlign.START
        private var horzAlign = ObjAlign.START
        private var vertOffset = 0
        private var horzOffset = 0
        private var outMargin = Insets.ZERO
        private var caption: Caption? = null
        private var description = ""
        protected var orgW = 0
        protected var orgH = 0
        private var curW = 0
        private var curH = 0
        private var offX = 0
        private var offY = 0
        private val matrices = ArrayList<Matrix>(3)

        // 그리기 개체(도형) 공통
        protected var line: Border? = null
        protected val fill = FillData()
        protected val paragraphs = ArrayList<Paragraph>()
        protected var textMargin: Insets? = null
        protected var textVertAlign = 0

        override fun child(name: String, a: XAttrs): XNode? {
            when (name) {
                "sz" -> {
                    szW = a.len("width").coerceAtLeast(0)
                    szH = a.len("height").coerceAtLeast(0)
                    szWRel = a.str("widthRelTo", "ABSOLUTE").trim().uppercase()
                    szHRel = a.str("heightRelTo", "ABSOLUTE").trim().uppercase()
                }
                "pos" -> {
                    treatAsChar = a.bool("treatAsChar", true)
                    vertRel = HwpxValues.vertRel(a["vertRelTo"])
                    horzRel = HwpxValues.horzRel(a["horzRelTo"])
                    vertAlign = HwpxValues.objAlign(a["vertAlign"])
                    horzAlign = HwpxValues.objAlign(a["horzAlign"])
                    vertOffset = a.len("vertOffset")
                    horzOffset = a.len("horzOffset")
                }
                "outMargin" -> outMargin = insets(a)
                "caption" -> return CaptionNode(a)
                "shapeComment" -> return object : TextCollector(2048) {
                    override fun end() {
                        description = sb.toString().trim()
                    }
                }
                "offset" -> {
                    offX = a.len("x")
                    offY = a.len("y")
                }
                "orgSz" -> {
                    orgW = a.len("width").coerceAtLeast(0)
                    orgH = a.len("height").coerceAtLeast(0)
                }
                "curSz" -> {
                    curW = a.len("width").coerceAtLeast(0)
                    curH = a.len("height").coerceAtLeast(0)
                }
                "renderingInfo" -> return object : XNode() {
                    override fun child(name: String, a: XAttrs): XNode? {
                        if ((name == "transMatrix" || name == "scaMatrix" || name == "rotMatrix") && matrices.size < 64) {
                            val m = DoubleArray(6) { i -> a.double("e${i + 1}", if (i == 0 || i == 4) 1.0 else 0.0) }
                            // 이동량은 부호 없는 32비트로 적히기도 한다 (4294967162 = -134)
                            m[2] = signed32(m[2])
                            m[5] = signed32(m[5])
                            matrices.add(Matrix(m))
                        }
                        return null
                    }
                }
                "lineShape" -> line = HwpxValues.lineShape(a)
                "fillBrush" -> return FillBrushNode(fill)
                "drawText" -> return object : XNode() {
                    override fun child(name: String, a: XAttrs): XNode? = when (name) {
                        "subList" -> {
                            textVertAlign = HwpxValues.vertAlign3(a["vertAlign"])
                            SubListNode(paragraphs)
                        }
                        "textMargin" -> {
                            textMargin = insets(a)
                            null
                        }
                        else -> null
                    }
                }
                else -> return childExtra(name, a)
            }
            return null
        }

        open fun childExtra(name: String, a: XAttrs): XNode? = null

        private fun signed32(v: Double): Double = if (v > Int.MAX_VALUE && v <= 4294967295.0) v - 4294967296.0 else v

        /** 원래 크기 (orgSz, 없으면 curSz/sz) */
        protected fun orgWidth() = if (orgW > 0) orgW else if (curW > 0) curW else maxOf(szW, 0)
        protected fun orgHeight() = if (orgH > 0) orgH else if (curH > 0) curH else maxOf(szH, 0)

        /** 원래 좌표 → 묶음/개체 좌표 변환 */
        protected fun composite(): Matrix {
            if (matrices.isEmpty()) {
                // 행렬이 없으면 offset + (cur/org) 비율
                val sx = if (orgW > 0 && curW > 0) curW.toDouble() / orgW else 1.0
                val sy = if (orgH > 0 && curH > 0) curH.toDouble() / orgH else 1.0
                return Matrix(doubleArrayOf(sx, 0.0, if (inContainer) offX.toDouble() else 0.0, 0.0, sy, if (inContainer) offY.toDouble() else 0.0))
            }
            var r = Matrix.IDENTITY
            for (m in matrices) r = r.times(m)
            return r
        }

        /** 원래 크기 사각형을 변환한 경계 상자 [x, y, w, h] */
        fun bbox(): DoubleArray {
            val m = composite()
            val w = orgWidth().toDouble()
            val h = orgHeight().toDouble()
            val xs = doubleArrayOf(m.x(0.0, 0.0), m.x(w, 0.0), m.x(w, h), m.x(0.0, h))
            val ys = doubleArrayOf(m.y(0.0, 0.0), m.y(w, 0.0), m.y(w, h), m.y(0.0, h))
            val x0 = xs.min()
            val y0 = ys.min()
            return doubleArrayOf(x0, y0, xs.max() - x0, ys.max() - y0)
        }

        /** 개체 안 점(원래 좌표)을 개체 상자 기준 좌표로 */
        protected fun mapPoints(raw: List<Int>): List<Int> {
            if (raw.isEmpty()) return raw
            val m = composite()
            val b = bbox()
            val lay = layoutBox()
            val sx = if (b[2] > 0.5) lay[2] / b[2] else 1.0
            val sy = if (b[3] > 0.5) lay[3] / b[3] else 1.0
            val out = ArrayList<Int>(raw.size)
            var i = 0
            while (i + 1 < raw.size) {
                val px = raw[i].toDouble()
                val py = raw[i + 1].toDouble()
                out.add(XAttrs.clampLen(Math.round((m.x(px, py) - b[0]) * sx).toInt()))
                out.add(XAttrs.clampLen(Math.round((m.y(px, py) - b[1]) * sy).toInt()))
                i += 2
            }
            return out
        }

        /** 크기 기준(widthRelTo 등)을 무시하고 sz 를 그대로 쓰는 개체 (수식은 내용으로 크기가 정해진다) */
        protected open val absoluteSize = false

        /** 이 개체의 배치 상자 [x, y, w, h]: 묶음 자식이면 변환 결과, 아니면 hp:sz */
        protected fun layoutBox(): DoubleArray {
            if (inContainer) return bbox()
            // widthRelTo/heightRelTo 가 ABSOLUTE 가 아니면 sz 는 기준 길이의 1/100 % 이다 (회전한 다각형 샘플로 확인: 5259 = 단 폭의 52.59%).
            // 도형/그림은 변환 행렬로 실제 크기를 알 수 있으므로 그것을 쓰고, 표 등은 쪽/단 크기로 계산한다.
            // 100% 를 넘는 값은 비율일 수 없으므로 HWPUNIT 으로 본다 (수식 샘플: COLUMN 기준인데 값은 실제 폭).
            val wRel = !absoluteSize && szWRel != "ABSOLUTE" && szW in 0..10_000
            val hRel = !absoluteSize && szHRel != "ABSOLUTE" && szH in 0..10_000
            val box = if (matrices.isNotEmpty() && (wRel || hRel)) bbox() else null
            val w = when {
                wRel -> box?.get(2)?.let { Math.round(it).toInt() } ?: percentOf(szW, refWidth(szWRel))
                szW > 0 -> szW
                curW > 0 -> curW
                else -> orgW
            }
            val h = when {
                hRel -> box?.get(3)?.let { Math.round(it).toInt() } ?: percentOf(szH, refHeight(szHRel))
                szH > 0 -> szH
                curH > 0 -> curH
                else -> orgH
            }
            return doubleArrayOf(0.0, 0.0, XAttrs.clampLen(w).toDouble(), XAttrs.clampLen(h).toDouble())
        }

        private fun percentOf(v: Int, ref: Int) = (v.toLong() * ref / 10_000).toInt()

        /** 묶음 안에서의 위치 보정 (묶음 자신의 상자 왼쪽 위) */
        var parentOrigin: DoubleArray = doubleArrayOf(0.0, 0.0)

        protected fun layout(): ObjectLayout {
            val box = layoutBox()
            fun i(v: Double) = XAttrs.clampLen(Math.round(v).toInt())
            return if (inContainer) {
                ObjectLayout(
                    treatAsChar = false,
                    vertRelTo = VertRel.PARA,
                    horzRelTo = HorzRel.PARA,
                    vertOffset = i(box[1] - parentOrigin[1]),
                    horzOffset = i(box[0] - parentOrigin[0]),
                    width = i(box[2]),
                    height = i(box[3]),
                    zOrder = zOrder,
                    textWrap = TextWrap.IN_FRONT_OF_TEXT,
                    description = description,
                )
            } else {
                ObjectLayout(
                    treatAsChar = treatAsChar,
                    vertRelTo = vertRel,
                    horzRelTo = horzRel,
                    vertAlign = vertAlign,
                    horzAlign = horzAlign,
                    vertOffset = vertOffset,
                    horzOffset = horzOffset,
                    width = i(box[2]),
                    height = i(box[3]),
                    zOrder = zOrder,
                    outMargin = outMargin,
                    textWrap = textWrap,
                    description = description,
                )
            }
        }

        protected fun fillOrNull(): BorderFill? = if (!fill.present) null else BorderFill(
            fillColor = fill.faceColor,
            patternColor = fill.hatchColor,
            patternType = fill.hatchStyle,
            gradient = fill.gradient,
            imageFillId = fill.imageId,
        )

        abstract fun build(): DrawObject

        override fun end() {
            if (!inContainer) deliver?.invoke(build())
        }

        protected fun caption() = caption

        private inner class CaptionNode(a: XAttrs) : XNode() {
            private val side = when (a.str("side").trim().uppercase()) {
                "LEFT" -> 0
                "RIGHT" -> 1
                "TOP" -> 2
                else -> 3
            }
            private val width = a.len("width").coerceAtLeast(0)
            private val gap = a.len("gap")
            private val paras = ArrayList<Paragraph>()

            override fun child(name: String, a: XAttrs): XNode? = if (name == "subList") SubListNode(paras) else null

            override fun end() {
                caption = Caption(side, width, gap, paras)
            }
        }
    }

    private fun insets(a: XAttrs) = Insets(
        a.len("left").coerceAtLeast(0), a.len("right").coerceAtLeast(0),
        a.len("top").coerceAtLeast(0), a.len("bottom").coerceAtLeast(0),
    )

    // ------------------------------------------------------------ 표

    private inner class TableNode(a: XAttrs) : ObjNode(a) {
        private val rowCnt = a.int("rowCnt", 0)
        private val colCnt = a.int("colCnt", 0)
        private val cellSpacing = a.len("cellSpacing").coerceAtLeast(0)
        private val borderFillId = a.int("borderFillIDRef")
        private val repeatHeader = a.bool("repeatHeader")
        private var inMargin = Insets.ZERO
        private val cells = ArrayList<CellData>()

        override fun childExtra(name: String, a: XAttrs): XNode? = when (name) {
            "inMargin" -> {
                inMargin = insets(a)
                null
            }
            "tr" -> TrNode(trCount++)
            else -> null
        }

        private var trCount = 0

        /** 행. 셀 주소(cellAddr)가 빠진 셀은 행 번호와 행 안 순서로 채운다 */
        private inner class TrNode(val rowIndex: Int) : XNode() {
            var nextCol = 0

            override fun child(name: String, a: XAttrs): XNode? = if (name == "tc") TcNode(a, this) else null
        }

        private inner class CellData(a: XAttrs) {
            val header = a.bool("header")
            val hasMargin = a.bool("hasMargin")
            val borderFillId = a.int("borderFillIDRef")
            var vertAlign = 1
            var col = -1
            var row = -1
            var colSpan = 1
            var rowSpan = 1
            var width = 0
            var height = 0
            var margin: Insets? = null
            val paras = ArrayList<Paragraph>()
        }

        private inner class TcNode(a: XAttrs, private val tr: TrNode) : XNode() {
            private val d = CellData(a)

            override fun child(name: String, a: XAttrs): XNode? {
                when (name) {
                    "subList" -> {
                        d.vertAlign = HwpxValues.vertAlign3(a["vertAlign"], 1)
                        return SubListNode(d.paras)
                    }
                    "cellAddr" -> {
                        d.col = a.int("colAddr", -1)
                        d.row = a.int("rowAddr", -1)
                    }
                    "cellSpan" -> {
                        d.colSpan = a.int("colSpan", 1)
                        d.rowSpan = a.int("rowSpan", 1)
                    }
                    "cellSz" -> {
                        d.width = a.len("width").coerceAtLeast(0)
                        d.height = a.len("height").coerceAtLeast(0)
                    }
                    "cellMargin" -> d.margin = insets(a)
                }
                return null
            }

            override fun end() {
                if (d.row < 0 || d.col < 0) {
                    d.row = tr.rowIndex
                    d.col = tr.nextCol
                }
                tr.nextCol = d.col + d.colSpan.coerceIn(1, MAX_GRID)
                if (cells.size < MAX_CELLS) cells.add(d)
            }
        }

        override fun build(): DrawObject {
            // 정상 문서: 선언된 행/열 수 = 셀들의 (주소 + 병합 수) 최댓값.
            // 손상 문서: HWP 파서와 같이 셀이 실제로 차지하는 범위로 맞추되 한도(MAX_GRID)를 넘지 않게 한다
            val valid = cells.filter { it.row in 0 until MAX_GRID && it.col in 0 until MAX_GRID }
            var rows = rowCnt.coerceIn(0, MAX_GRID)
            var cols = colCnt.coerceIn(0, MAX_GRID)
            if (valid.isNotEmpty()) {
                val rowEnd = valid.maxOf { it.row + maxOf(1, it.rowSpan).coerceAtMost(MAX_GRID) }
                val colEnd = valid.maxOf { it.col + maxOf(1, it.colSpan).coerceAtMost(MAX_GRID) }
                rows = maxOf(valid.maxOf { it.row + 1 }, minOf(rowCnt, rowEnd)).coerceAtMost(MAX_GRID)
                cols = maxOf(valid.maxOf { it.col + 1 }, minOf(colCnt, colEnd)).coerceAtMost(MAX_GRID)
            }
            val built = ArrayList<Cell>(valid.size)
            for (d in valid) {
                if (d.row >= rows || d.col >= cols) continue
                built.add(
                    Cell(
                        col = d.col, row = d.row,
                        colSpan = d.colSpan.coerceIn(1, cols - d.col),
                        rowSpan = d.rowSpan.coerceIn(1, rows - d.row),
                        width = d.width, height = d.height,
                        // hasMargin=0 이면 표의 안쪽 여백을 쓴다 (샘플의 셀 줄 폭 = 셀 폭 - 표 안쪽 여백 으로 확인)
                        margin = if (d.hasMargin) d.margin ?: inMargin else inMargin,
                        borderFillId = d.borderFillId,
                        vertAlign = d.vertAlign,
                        paragraphs = d.paras,
                        header = d.header,
                    ),
                )
            }
            return Table(
                layout = layout(), caption = caption(),
                rowCount = rows, colCount = cols, cellSpacing = cellSpacing, inMargin = inMargin,
                borderFillId = borderFillId, cells = built, repeatHeader = repeatHeader,
            )
        }
    }

    // ------------------------------------------------------------ 그림

    private inner class PictureNode(a: XAttrs, poster: String? = null) : ObjNode(a) {
        private var imageId: String? = HwpxValues.imageId(poster)
        private var clip = IntArray(4) // left top right bottom
        private var dimW = 0
        private var dimH = 0
        private var effect = 0
        private var brightness = 0
        private var contrast = 0
        private var alpha = 0
        private var inMargin = Insets.ZERO

        override fun childExtra(name: String, a: XAttrs): XNode? {
            when (name) {
                "img" -> {
                    imageId = HwpxValues.imageId(a["binaryItemIDRef"])
                    brightness = a.int("bright").coerceIn(-100, 100)
                    contrast = a.int("contrast").coerceIn(-100, 100)
                    alpha = a.int("alpha").coerceIn(0, 255)
                    effect = when (a.str("effect").trim().uppercase()) {
                        "GRAY_SCALE" -> 1
                        "BLACK_WHITE" -> 2
                        "PATTERN8X8" -> 3
                        else -> 0
                    }
                }
                "imgClip" -> clip = intArrayOf(a.len("left"), a.len("top"), a.len("right"), a.len("bottom"))
                "imgDim" -> {
                    dimW = a.len("dimwidth").coerceAtLeast(0)
                    dimH = a.len("dimheight").coerceAtLeast(0)
                }
                "inMargin" -> inMargin = insets(a)
            }
            return null
        }

        override fun build(): DrawObject {
            // 자르기 영역이 원본 전체면 자르지 않음(0)
            val full = clip[0] <= 0 && clip[1] <= 0 && (dimW <= 0 || clip[2] >= dimW) && (dimH <= 0 || clip[3] >= dimH)
            return Picture(
                layout = layout(), caption = caption(), imageId = imageId,
                cropLeft = if (full) 0 else clip[0], cropTop = if (full) 0 else clip[1],
                cropRight = if (full) 0 else clip[2], cropBottom = if (full) 0 else clip[3],
                imageWidth = dimW, imageHeight = dimH,
                border = line ?: Border(),
                effect = effect, brightness = brightness, contrast = contrast, alpha = alpha,
                inMargin = inMargin,
            )
        }
    }

    // ------------------------------------------------------------ 도형

    private inner class ShapeNode(a: XAttrs, private val kind: ShapeKind) : ObjNode(a) {
        var text = ""
        private val corner = a.int("ratio").coerceIn(0, 100)
        private val points = ArrayList<Int>()

        private fun pt(a: XAttrs) {
            if (points.size < 20_000) {
                points.add(a.len("x"))
                points.add(a.len("y"))
            }
        }

        override fun childExtra(name: String, a: XAttrs): XNode? {
            when (name) {
                // 선/연결선
                "startPt", "endPt" -> if (kind == ShapeKind.LINE) pt(a)
                // 다각형
                "pt" -> if (kind == ShapeKind.POLYGON) pt(a)
                // 사각형의 네 꼭짓점
                "pt0", "pt1", "pt2", "pt3" -> if (kind == ShapeKind.RECT) pt(a)
                // 호: 중심, 축1, 축2
                "center", "ax1", "ax2" -> if (kind == ShapeKind.ARC) pt(a)
                // 곡선: 선분마다 (x1,y1) 을 넣고 마지막 끝점을 더한다
                "seg" -> if (kind == ShapeKind.CURVE && points.size < 20_000) {
                    if (points.isEmpty()) {
                        points.add(a.len("x1"))
                        points.add(a.len("y1"))
                    }
                    points.add(a.len("x2"))
                    points.add(a.len("y2"))
                }
                // 양식 개체의 글자
                "text" -> if (kind == ShapeKind.OTHER) return object : TextCollector(1024) {
                    override fun end() {
                        if (text.isEmpty()) text = sb.toString()
                    }
                }
            }
            return null
        }

        /** 양식 개체 (단추, 입력 상자 등): HWP 파서와 같이 회색 테두리 + 바탕색 */
        private var formBack: Int? = null
        private var isForm = false

        fun form(a: XAttrs, label: String) {
            isForm = true
            text = label.take(1024)
            formBack = HwpxValues.color(a["backColor"])
        }

        override fun build(): DrawObject = Shape(
            layout = layout(), caption = caption(), kind = kind,
            line = line ?: when {
                isForm -> Border(LineType.SOLID, 0.12f, 0x808080)
                kind == ShapeKind.OTHER || kind == ShapeKind.OLE -> Border()
                else -> Border(LineType.SOLID, 0.12f, 0)
            },
            fill = fillOrNull() ?: formBack?.let { BorderFill(fillColor = it) },
            cornerRadius = if (kind == ShapeKind.RECT) corner else 0,
            points = mapPoints(points),
            paragraphs = paragraphs,
            textMargin = textMargin ?: Insets(283, 283, 283, 283),
            textVertAlign = textVertAlign,
            text = text,
        )
    }

    private inner class ContainerNode(a: XAttrs) : ObjNode(a) {
        private val children = ArrayList<ObjNode>()

        override fun childExtra(name: String, a: XAttrs): XNode? {
            if (children.size >= 10_000) return null
            val node = objectNode(name, a) {} as? ObjNode ?: return null
            node.inContainer = true
            children.add(node)
            return node
        }

        override fun build(): DrawObject {
            val origin = if (inContainer) nestedOrigin() else doubleArrayOf(0.0, 0.0)
            val objs = ArrayList<DrawObject>(children.size)
            for (c in children) {
                c.parentOrigin = origin
                try {
                    objs.add(c.build())
                } catch (e: LimitExceededException) {
                    throw e
                } catch (_: RuntimeException) {
                }
            }
            return Shape(layout = layout(), caption = caption(), kind = ShapeKind.CONTAINER, line = Border(), children = objs)
        }

        /**
         * 안쪽 묶음의 자식 좌표 기준점. 자식 좌표가 바깥 묶음 기준인지 이 묶음 기준인지 샘플로 확인하지 못했으므로
         * 자식들의 경계 상자가 어느 쪽 가정에서 이 묶음 상자 안에 더 잘 들어가는지로 고른다 (같으면 바깥 기준).
         */
        private fun nestedOrigin(): DoubleArray {
            val me = bbox()
            if (children.isEmpty()) return doubleArrayOf(me[0], me[1])
            var x0 = Double.MAX_VALUE
            var y0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE
            var y1 = -Double.MAX_VALUE
            for (c in children) {
                val b = try {
                    c.bbox()
                } catch (_: RuntimeException) {
                    continue
                }
                x0 = minOf(x0, b[0]); y0 = minOf(y0, b[1])
                x1 = maxOf(x1, b[0] + b[2]); y1 = maxOf(y1, b[1] + b[3])
            }
            if (x0 > x1) return doubleArrayOf(me[0], me[1])
            fun overflow(ox: Double, oy: Double): Double =
                maxOf(0.0, ox - x0) + maxOf(0.0, x1 - (ox + me[2])) + maxOf(0.0, oy - y0) + maxOf(0.0, y1 - (oy + me[3]))
            return if (overflow(0.0, 0.0) < overflow(me[0], me[1])) doubleArrayOf(0.0, 0.0) else doubleArrayOf(me[0], me[1])
        }
    }

    private inner class EquationNode(a: XAttrs) : ObjNode(a) {
        override val absoluteSize = true
        private val baseSize = a.int("baseUnit", 1000).coerceIn(100, 100_000)
        private val color = HwpxValues.color(a["textColor"]) ?: 0
        private var script = ""

        override fun childExtra(name: String, a: XAttrs): XNode? =
            if (name == "script") object : TextCollector(64 * 1024) {
                override fun end() {
                    script = sb.toString()
                }
            } else null

        override fun build(): DrawObject = Equation(layout(), caption(), script, baseSize, color)
    }

    companion object {
        private const val MAX_CELLS = 200_000
        /** 표 한 방향의 최대 칸 수 (HWP 파서와 같음) */
        private const val MAX_GRID = 4096
    }
}
