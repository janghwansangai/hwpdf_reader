package com.fastreader.app.core.hwpx

import com.fastreader.app.core.doc.Align
import com.fastreader.app.core.doc.Border
import com.fastreader.app.core.doc.BorderFill
import com.fastreader.app.core.doc.Bullet
import com.fastreader.app.core.doc.CharShape
import com.fastreader.app.core.doc.HeadingType
import com.fastreader.app.core.doc.LineSpacingType
import com.fastreader.app.core.doc.LineType
import com.fastreader.app.core.doc.Numbering
import com.fastreader.app.core.doc.NumberingLevel
import com.fastreader.app.core.doc.ParaShape
import com.fastreader.app.core.doc.Style
import com.fastreader.app.core.doc.UnderlinePos

/** Contents/header.xml 에서 읽은 문서 공통 모양 정보 */
internal class HwpxHeader(
    val charShapes: Map<Int, CharShape>,
    val paraShapes: Map<Int, ParaShape>,
    val borderFills: Map<Int, BorderFill>,
    val numberings: Map<Int, Numbering>,
    val bullets: Map<Int, Bullet>,
    val styles: Map<Int, Style>,
    /** hh:beginNum 의 시작 쪽 번호 */
    val startPage: Int,
) {
    companion object {
        val EMPTY = HwpxHeader(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), 1)
    }
}

/**
 * header.xml 읽기.
 *
 * 단위 규칙 (샘플의 줄 배치 값과 hwp2hwpx ForParaProperties 로 확인):
 * - paraPr 의 margin(intent/left/right/prev/next)과 lineSpacing(PERCENT 가 아닐 때)은
 *   `hp:switch` 의 `hp:case`(HwpUnitChar) 안 값이 실제 HWPUNIT, `hp:default` 와 switch 없는 옛 형식 값은 2배.
 *   (문단 사이 vertpos 간격 = 앞 문단 next + 뒤 문단 prev, 첫 줄 horzpos = left 로 확인)
 * - hp:case 안에서 unit="CHAR" 인 값은 글자 단위: 샘플에서 80 → 600 HWPUNIT(바탕글 15pt)이었으므로
 *   값 × 바탕글 크기 / 200 으로 근사한다. (hp:default 쪽은 2배 값의 최하위 비트를 1로 둔 값이다)
 * - charPr height 는 1/100 pt, 색은 "#RRGGBB".
 */
internal class HwpxHeaderReader {
    private val fontFaces = Array(7) { HashMap<Int, String>() }
    private val rawCharPrs = ArrayList<RawCharPr>()
    private val paraShapes = HashMap<Int, ParaShape>()
    private val borderFills = HashMap<Int, BorderFill>()
    private val rawNumberings = HashMap<Int, RawNumbering>()
    private val bullets = HashMap<Int, Bullet>()
    private val styles = HashMap<Int, Style>()
    private var startPage = 1

    /** 글자 단위(CHAR) 여백이 있는 문단 모양: ID → 5개 값(intent left right prev next, 글자 단위가 아니면 null) */
    private val charUnitMargins = HashMap<Int, Array<Int?>>()

    val root: XNode = object : XNode() {
        override fun child(name: String, a: XAttrs): XNode? = if (name == "head") HeadNode() else throw StopParsingException()
    }

    fun build(): HwpxHeader {
        val charShapes = HashMap<Int, CharShape>(rawCharPrs.size * 2)
        for (r in rawCharPrs) charShapes[r.id] = r.toCharShape(fontFaces)

        // 글자 단위 여백: 바탕글(스타일 0)의 글자 크기 기준
        if (charUnitMargins.isNotEmpty()) {
            val base = styles[0]?.let { charShapes[it.charShapeId]?.baseSize } ?: 1000
            for ((id, raw) in charUnitMargins) {
                val ps = paraShapes[id] ?: continue
                fun v(i: Int, cur: Int) = raw[i]?.let { XAttrs.clampLen((it.toLong() * base / 200).toInt()) } ?: cur
                paraShapes[id] = ps.copy(
                    indent = v(0, ps.indent), marginLeft = v(1, ps.marginLeft), marginRight = v(2, ps.marginRight),
                    spacingBefore = v(3, ps.spacingBefore), spacingAfter = v(4, ps.spacingAfter),
                )
            }
        }

        val numberings = HashMap<Int, Numbering>(rawNumberings.size * 2)
        for ((id, n) in rawNumberings) numberings[id] = n.build(charShapes)
        return HwpxHeader(charShapes, paraShapes, borderFills, numberings, bullets, styles, startPage)
    }

    private fun checkCount(map: Map<*, *>) {
        if (map.size >= MAX_ITEMS) throw LimitExceededException("모양 정보가 너무 많습니다.")
    }

    private inner class HeadNode : XNode() {
        override fun child(name: String, a: XAttrs): XNode? = when (name) {
            "beginNum" -> {
                startPage = a.int("page", 1)
                null
            }
            "refList" -> RefListNode()
            else -> null
        }
    }

    private inner class RefListNode : XNode() {
        override fun child(name: String, a: XAttrs): XNode? = when (name) {
            "fontfaces" -> ListNode("fontface") { FontfaceNode(langIndex(it["lang"])) }
            "borderFills" -> ListNode("borderFill") { BorderFillNode(it.int("id")) }
            "charProperties" -> ListNode("charPr") { CharPrNode(it) }
            "paraProperties" -> ListNode("paraPr") { ParaPrNode(it.int("id")) }
            "numberings" -> ListNode("numbering") { NumberingNode(it.int("id"), it.int("start", 1)) }
            "bullets" -> ListNode("bullet") { BulletNode(it) }
            "styles" -> ListNode("style") {
                checkCount(styles)
                styles[it.int("id")] = Style(
                    name = it.str("name").take(256),
                    englishName = it.str("engName").take(256),
                    paraShapeId = it.int("paraPrIDRef"),
                    charShapeId = it.int("charPrIDRef"),
                )
                null
            }
            else -> null
        }
    }

    /** 같은 이름의 자식들만 받는 목록 노드 */
    private class ListNode(private val item: String, private val make: (XAttrs) -> XNode?) : XNode() {
        override fun child(name: String, a: XAttrs): XNode? = if (name == item) make(a) else null
    }

    private fun langIndex(s: String?): Int = when (s?.trim()?.uppercase()) {
        "HANGUL" -> 0
        "LATIN" -> 1
        "HANJA" -> 2
        "JAPANESE" -> 3
        "OTHER" -> 4
        "SYMBOL" -> 5
        "USER" -> 6
        else -> -1
    }

    private inner class FontfaceNode(private val lang: Int) : XNode() {
        override fun child(name: String, a: XAttrs): XNode? {
            if (name == "font" && lang >= 0) {
                val faces = fontFaces[lang]
                if (faces.size < MAX_ITEMS) faces[a.int("id")] = a.str("face").take(128)
            }
            return null
        }
    }

    // ------------------------------------------------------------ charPr

    private class RawCharPr(val id: Int) {
        var height = 1000
        var textColor = 0
        var shadeColor: Int? = null
        var borderFillId = 0
        var emphasis = 0
        val fontRef = IntArray(7)
        val ratio = IntArray(7) { 100 }
        val spacing = IntArray(7)
        val relSz = IntArray(7) { 100 }
        val offset = IntArray(7)
        var bold = false
        var italic = false
        var underline = UnderlinePos.NONE
        var underlineStyle = LineType.SOLID
        var underlineColor: Int? = null
        var strike = false
        var strikeStyle = LineType.SOLID
        var strikeColor: Int? = null
        var outline = false
        var shadow = false
        var shadowColor: Int? = null
        var emboss = false
        var engrave = false
        var sup = false
        var sub = false

        fun toCharShape(faces: Array<HashMap<Int, String>>) = CharShape(
            fontNames = List(7) { faces[it][fontRef[it]] ?: "" },
            ratios = ratio.toList(),
            spacings = spacing.toList(),
            relSizes = relSz.toList(),
            offsets = offset.toList(),
            baseSize = height,
            bold = bold,
            italic = italic,
            underline = underline,
            underlineStyle = underlineStyle,
            underlineColor = underlineColor,
            strike = strike,
            strikeStyle = strikeStyle,
            strikeColor = strikeColor,
            outline = outline,
            shadow = shadow,
            shadowColor = shadowColor,
            emboss = emboss,
            engrave = engrave,
            superscript = sup,
            subscript = sub,
            emphasis = emphasis,
            textColor = textColor,
            shadeColor = shadeColor,
            borderFillId = borderFillId,
        )
    }

    private inner class CharPrNode(a: XAttrs) : XNode() {
        private val r = RawCharPr(a.int("id")).apply {
            height = a.int("height", 1000).coerceIn(1, 1_000_000)
            textColor = HwpxValues.color(a["textColor"]) ?: 0
            shadeColor = HwpxValues.color(a["shadeColor"])
            borderFillId = a.int("borderFillIDRef")
            emphasis = HwpxValues.emphasis(a["symMark"])
        }

        private fun langs(a: XAttrs, into: IntArray, lo: Int, hi: Int) {
            for (i in 0 until 7) into[i] = a.int(LANG_ATTRS[i], into[i]).coerceIn(lo, hi)
        }

        override fun child(name: String, a: XAttrs): XNode? {
            when (name) {
                "fontRef" -> langs(a, r.fontRef, Int.MIN_VALUE, Int.MAX_VALUE)
                "ratio" -> langs(a, r.ratio, 1, 1000)
                "spacing" -> langs(a, r.spacing, -100, 100)
                "relSz" -> langs(a, r.relSz, 1, 1000)
                "offset" -> langs(a, r.offset, -100, 100)
                "bold" -> r.bold = true
                "italic" -> r.italic = true
                "emboss" -> r.emboss = true
                "engrave" -> r.engrave = true
                "supscript", "superscript" -> r.sup = true
                "subscript" -> r.sub = true
                "underline" -> {
                    val shape = HwpxValues.lineType(a["shape"], LineType.SOLID, stroke = true)
                    when (a["type"]?.trim()?.uppercase()) {
                        "BOTTOM" -> r.underline = UnderlinePos.BOTTOM
                        "TOP" -> r.underline = UnderlinePos.TOP
                        // 가운데 밑줄은 취소선처럼 그린다
                        "CENTER" -> if (!r.strike) {
                            r.strike = true
                            r.strikeStyle = shape
                            r.strikeColor = HwpxValues.color(a["color"])
                        }
                    }
                    r.underlineStyle = shape
                    r.underlineColor = HwpxValues.color(a["color"])
                }
                "strikeout" -> {
                    // 한글은 취소선이 없을 때도 shape="3D" 를 쓰는 경우가 있다 (보도자료 샘플 전체 글자가 3D)
                    val shape = a["shape"]?.trim()?.uppercase()
                    if (shape != null && shape != "NONE" && shape != "3D" && shape.isNotEmpty()) {
                        r.strike = true
                        r.strikeStyle = HwpxValues.lineType(shape, LineType.SOLID, stroke = true)
                        r.strikeColor = HwpxValues.color(a["color"])
                    }
                }
                "outline" -> r.outline = (a["type"]?.trim()?.uppercase() ?: "NONE") != "NONE"
                "shadow" -> {
                    r.shadow = (a["type"]?.trim()?.uppercase() ?: "NONE") != "NONE"
                    r.shadowColor = HwpxValues.color(a["color"])
                }
            }
            return null
        }

        override fun end() {
            if (rawCharPrs.size >= MAX_ITEMS) throw LimitExceededException("모양 정보가 너무 많습니다.")
            rawCharPrs.add(r)
        }
    }

    // ------------------------------------------------------------ paraPr

    private inner class ParaPrNode(private val id: Int) : XNode() {
        private var align = Align.JUSTIFY
        private var headingType = HeadingType.NONE
        private var headingId = 0
        private var headingLevel = 0
        private var headingFromCase = false
        private var keepWithNext = false
        private var pageBreakBefore = false
        private val margin = IntArray(5) // intent left right prev next
        private val charUnit = arrayOfNulls<Int>(5)
        private var marginPrio = 0
        private var lsType = LineSpacingType.PERCENT
        private var lsValue = 160
        private var lsPrio = 0
        private var borderFillId = 0
        private val borderOffsets = IntArray(4)

        override fun child(name: String, a: XAttrs): XNode? {
            when (name) {
                "align" -> align = HwpxValues.align(a["horizontal"])
                "heading" -> if (a.inCase || !headingFromCase) {
                    headingFromCase = a.inCase
                    headingType = when (a["type"]?.trim()?.uppercase()) {
                        "OUTLINE" -> HeadingType.OUTLINE
                        "NUMBER" -> HeadingType.NUMBER
                        "BULLET" -> HeadingType.BULLET
                        else -> HeadingType.NONE
                    }
                    headingId = a.int("idRef")
                    headingLevel = a.int("level").coerceIn(0, 9)
                }
                "breakSetting" -> {
                    keepWithNext = a.bool("keepWithNext")
                    pageBreakBefore = a.bool("pageBreakBefore")
                }
                "margin" -> {
                    // hp:case 값이 실제 값, 그 밖(hp:default, switch 없음)은 2배 값
                    val prio = if (a.inCase) 2 else 1
                    if (prio >= marginPrio) {
                        marginPrio = prio
                        margin.fill(0)
                        charUnit.fill(null)
                        return MarginNode(a.inCase)
                    }
                }
                "lineSpacing" -> {
                    val prio = if (a.inCase) 2 else 1
                    if (prio >= lsPrio) {
                        lsPrio = prio
                        lsType = when (a["type"]?.trim()?.uppercase()) {
                            "FIXED" -> LineSpacingType.FIXED
                            "BETWEEN_LINES" -> LineSpacingType.BETWEEN_LINES
                            "AT_LEAST" -> LineSpacingType.AT_LEAST
                            else -> LineSpacingType.PERCENT
                        }
                        val v = a.int("value", if (lsType == LineSpacingType.PERCENT) 160 else 0)
                        lsValue = if (lsType == LineSpacingType.PERCENT) v.coerceIn(0, 10_000)
                        else XAttrs.clampLen(if (a.inCase) v else v / 2)
                    }
                }
                "border" -> {
                    borderFillId = a.int("borderFillIDRef")
                    borderOffsets[0] = a.len("offsetLeft")
                    borderOffsets[1] = a.len("offsetRight")
                    borderOffsets[2] = a.len("offsetTop")
                    borderOffsets[3] = a.len("offsetBottom")
                }
            }
            return null
        }

        private inner class MarginNode(private val inCase: Boolean) : XNode() {
            override fun child(name: String, a: XAttrs): XNode? {
                val i = when (name) {
                    "intent", "indent" -> 0
                    "left" -> 1
                    "right" -> 2
                    "prev" -> 3
                    "next" -> 4
                    else -> -1
                }
                if (i < 0) return null
                val v = a.len("value")
                if (!inCase) {
                    margin[i] = v / 2
                } else if (a["unit"]?.trim()?.uppercase() == "CHAR") {
                    // 바탕글 크기를 알아야 하므로 build() 에서 바꾼다. 그때까지는 10pt 기준
                    charUnit[i] = v
                    margin[i] = XAttrs.clampLen((v.toLong() * 1000 / 200).toInt())
                } else {
                    margin[i] = v
                }
                return null
            }
        }

        override fun end() {
            checkCount(paraShapes)
            if (charUnit.any { it != null }) charUnitMargins[id] = charUnit.copyOf() else charUnitMargins.remove(id)
            paraShapes[id] = ParaShape(
                align = align,
                marginLeft = margin[1],
                marginRight = margin[2],
                indent = margin[0],
                spacingBefore = margin[3],
                spacingAfter = margin[4],
                lineSpacingType = lsType,
                lineSpacing = lsValue,
                headingType = headingType,
                headingId = headingId,
                headingLevel = headingLevel,
                borderFillId = borderFillId,
                borderOffsetLeft = borderOffsets[0],
                borderOffsetRight = borderOffsets[1],
                borderOffsetTop = borderOffsets[2],
                borderOffsetBottom = borderOffsets[3],
                keepWithNext = keepWithNext,
                pageBreakBefore = pageBreakBefore,
            )
        }
    }

    // ------------------------------------------------------------ borderFill

    private inner class BorderFillNode(private val id: Int) : XNode() {
        private var left = Border()
        private var right = Border()
        private var top = Border()
        private var bottom = Border()
        private var diagonal = Border()
        private var slash = false
        private var backSlash = false
        private val fill = FillData()

        override fun child(name: String, a: XAttrs): XNode? {
            when (name) {
                "leftBorder" -> left = HwpxValues.border(a)
                "rightBorder" -> right = HwpxValues.border(a)
                "topBorder" -> top = HwpxValues.border(a)
                "bottomBorder" -> bottom = HwpxValues.border(a)
                "diagonal" -> diagonal = HwpxValues.border(a, LineType.SOLID)
                "slash" -> slash = (a["type"]?.trim()?.uppercase() ?: "NONE") != "NONE"
                "backSlash" -> backSlash = (a["type"]?.trim()?.uppercase() ?: "NONE") != "NONE"
                "fillBrush" -> return FillBrushNode(fill)
            }
            return null
        }

        override fun end() {
            checkCount(borderFills)
            borderFills[id] = BorderFill(
                left = left, right = right, top = top, bottom = bottom,
                diagonal = if (slash && diagonal.visible) diagonal else null,
                backDiagonal = if (backSlash && diagonal.visible) diagonal else null,
                fillColor = fill.faceColor,
                patternColor = fill.hatchColor,
                patternType = fill.hatchStyle,
                gradient = fill.gradient,
                imageFillId = fill.imageId,
            )
        }
    }

    // ------------------------------------------------------------ numbering / bullet

    private class ParaHeadData(a: XAttrs) {
        val level = a.int("level", 1)
        val start = a.int("start", 1)
        val align = HwpxValues.align(a["align"]).let { if (it == Align.JUSTIFY) Align.LEFT else it }
        val numberType = HwpxValues.numberType(a["numFormat"])
        val charShapeId = a.int("charPrIDRef", -1).let { if (it < 0 || it == 0xFFFF) -1 else it }
        val widthAdjust = a.len("widthAdjust")
        val textOffset = a.len("textOffset")

        /** 본문과의 거리가 글자 크기에 대한 % 인가 (HWP 와 같이 HWPUNIT 으로 바꿔 넣는다) */
        val textOffsetPercent = a["textOffsetType"]?.trim()?.uppercase() != "HWPUNIT"
        val format = StringBuilder()

        fun toLevel(charShapes: Map<Int, CharShape>) = NumberingLevel(
            format = format.toString(),
            numberType = numberType,
            startNumber = start,
            charShapeId = charShapeId,
            align = align,
            widthAdjust = widthAdjust,
            textOffset = if (!textOffsetPercent) textOffset else {
                val size = charShapes[charShapeId]?.baseSize ?: 1000
                XAttrs.clampLen((textOffset.toLong() * size / 100).toInt())
            },
        )
    }

    private class RawNumbering(val heads: List<ParaHeadData>, val start: Int) {
        fun build(charShapes: Map<Int, CharShape>): Numbering {
            val maxLevel = heads.maxOfOrNull { it.level }?.coerceIn(1, 10) ?: 0
            val levels = ArrayList<NumberingLevel>(maxLevel)
            for (lv in 1..maxLevel) {
                levels.add(heads.firstOrNull { it.level == lv }?.toLevel(charShapes) ?: NumberingLevel(""))
            }
            return Numbering(levels, start)
        }
    }

    private inner class ParaHeadNode(val d: ParaHeadData) : XNode() {
        override fun text(ch: CharArray, start: Int, length: Int) {
            val n = minOf(length, 256 - d.format.length)
            if (n > 0) d.format.append(ch, start, n)
        }
    }

    private inner class NumberingNode(private val id: Int, private val start: Int) : XNode() {
        private val heads = ArrayList<ParaHeadData>()

        override fun child(name: String, a: XAttrs): XNode? {
            if (name != "paraHead" || heads.size >= 16) return null
            val d = ParaHeadData(a)
            heads.add(d)
            return ParaHeadNode(d)
        }

        override fun end() {
            checkCount(rawNumberings)
            rawNumberings[id] = RawNumbering(heads, start)
        }
    }

    private inner class BulletNode(a: XAttrs) : XNode() {
        private val id = a.int("id")
        private val char = a.str("char").take(8)
        private val useImage = a.bool("useImage")
        private var charShapeId = -1
        private var imageId: String? = null

        override fun child(name: String, a: XAttrs): XNode? {
            when (name) {
                "paraHead" -> charShapeId = ParaHeadData(a).charShapeId
                "img" -> imageId = HwpxValues.imageId(a["binaryItemIDRef"])
            }
            return null
        }

        override fun end() {
            checkCount(bullets)
            bullets[id] = Bullet(char, charShapeId, if (useImage) imageId else null)
        }
    }

    companion object {
        private val LANG_ATTRS = arrayOf("hangul", "latin", "hanja", "japanese", "other", "symbol", "user")
        private const val MAX_ITEMS = 200_000
    }
}
