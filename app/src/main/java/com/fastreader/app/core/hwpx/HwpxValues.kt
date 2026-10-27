package com.fastreader.app.core.hwpx

import com.fastreader.app.core.doc.Align
import com.fastreader.app.core.doc.Border
import com.fastreader.app.core.doc.Gradient
import com.fastreader.app.core.doc.HorzRel
import com.fastreader.app.core.doc.LineType
import com.fastreader.app.core.doc.NumberType
import com.fastreader.app.core.doc.ObjAlign
import com.fastreader.app.core.doc.PageNumberPos
import com.fastreader.app.core.doc.TextWrap
import com.fastreader.app.core.doc.VertRel

/** HWPX 속성 문자열 → 모델 값 변환 (참고: hwpxlib enumtype, hwp2hwpx ValueConvertor) */
internal object HwpxValues {

    /** "#RRGGBB" → 0xRRGGBB. "none"/빈 값/형식 오류 → null. "#AARRGGBB" 는 아래 24비트만 쓴다 */
    fun color(s: String?): Int? {
        if (s == null) return null
        val t = s.trim()
        if (t.isEmpty() || t.equals("none", ignoreCase = true)) return null
        val hex = if (t.startsWith("#")) t.substring(1) else t
        if (hex.length != 6 && hex.length != 8) return null
        val v = hex.toLongOrNull(16) ?: return null
        return (v and 0xFFFFFF).toInt()
    }

    /** "0.12 mm" → 0.12. 숫자만 있으면 mm 로 본다 */
    fun widthMm(s: String?, def: Float = 0.12f): Float {
        if (s == null) return def
        val t = s.trim()
        var end = 0
        while (end < t.length && (t[end].isDigit() || t[end] == '.' || t[end] == '-')) end++
        val v = t.substring(0, end).toFloatOrNull() ?: return def
        if (!v.isFinite() || v < 0f) return def
        return minOf(v, 50f)
    }

    /**
     * 테두리/선 종류 이름 (hwpxlib LineType2/LineType3 + HWP 의 3D/물결 선) → 화면에 그려지는 모양.
     * 테두리·도형 선(LineType2)은 한글이 파선을 "DOT", 점선을 "DASH" 로 적는다(한글 미리보기 그림으로 확인,
     * hwp2hwpx ValueConvertor 의 "hwpx 포맷 오류" 주석). 밑줄/취소선(LineType3)은 이름 그대로라 [stroke] = true 로 부른다.
     */
    fun lineType(s: String?, def: LineType = LineType.NONE, stroke: Boolean = false): LineType = when (s?.trim()?.uppercase()) {
        null, "" -> def
        "NONE" -> LineType.NONE
        "SOLID" -> LineType.SOLID
        "DOT" -> if (stroke) LineType.DOT else LineType.DASH
        "DASH" -> if (stroke) LineType.DASH else LineType.DOT
        "DASH_DOT" -> LineType.DASH_DOT
        "DASH_DOT_DOT" -> LineType.DASH_DOT_DOT
        "LONG_DASH" -> LineType.LONG_DASH
        "CIRCLE" -> LineType.CIRCLE
        "DOUBLE_SLIM", "DOUBLE" -> LineType.DOUBLE
        "SLIM_THICK" -> LineType.THIN_THICK
        "THICK_SLIM" -> LineType.THICK_THIN
        "SLIM_THICK_SLIM" -> LineType.THIN_THICK_THIN
        "WAVE" -> LineType.WAVE
        "DOUBLEWAVE", "DOUBLE_WAVE" -> LineType.DOUBLE_WAVE
        "THICK_3D" -> LineType.THICK_3D
        "THICK_3D_REVERS_LIGHT", "THICK_3D_REVERSE_LIGHT" -> LineType.THICK_3D_REV
        "3D" -> LineType.SOLID_3D
        "3D_REVERS_LIGHT", "3D_REVERSE_LIGHT" -> LineType.SOLID_3D_REV
        "THICK" -> LineType.SOLID
        else -> def
    }

    /** 탭의 leader 처럼 숫자로 적힌 선 종류 (HWP 선 종류 값과 같다: 2 = 파선, 3 = 점선) */
    fun lineTypeIndex(i: Int): LineType = when (i) {
        0 -> LineType.NONE
        1 -> LineType.SOLID
        2 -> LineType.DASH
        3 -> LineType.DOT
        4 -> LineType.DASH_DOT
        5 -> LineType.DASH_DOT_DOT
        6 -> LineType.LONG_DASH
        7 -> LineType.CIRCLE
        8 -> LineType.DOUBLE
        9 -> LineType.THIN_THICK
        10 -> LineType.THICK_THIN
        11 -> LineType.THIN_THICK_THIN
        else -> LineType.NONE
    }

    fun border(a: XAttrs, defType: LineType = LineType.NONE): Border =
        Border(lineType(a["type"], defType), widthMm(a["width"]), color(a["color"]) ?: 0)

    /** 그리기 개체 선(hp:lineShape). width 는 HWPUNIT. 굵기 0 이하는 HWP 파서와 같이 선 없음 */
    fun lineShape(a: XAttrs): Border {
        val w = a.int("width", 0).coerceIn(0, 100_000)
        val c = color(a["color"]) ?: 0
        val t = lineType(a["style"], LineType.SOLID)
        if (t == LineType.NONE || w <= 0) return Border(LineType.NONE, 0f, c)
        return Border(t, w * 25.4f / 7200f, c)
    }

    fun align(s: String?): Align = when (s?.trim()?.uppercase()) {
        "LEFT" -> Align.LEFT
        "RIGHT" -> Align.RIGHT
        "CENTER" -> Align.CENTER
        "DISTRIBUTE" -> Align.DISTRIBUTE
        "DISTRIBUTE_SPACE" -> Align.DISTRIBUTE_SPACE
        else -> Align.JUSTIFY
    }

    /** 번호 모양 (hwpxlib NumberType1/2). 이름 오타 변형도 받는다 */
    fun numberType(s: String?): NumberType = when (s?.trim()?.uppercase()) {
        "DIGIT" -> NumberType.DIGIT
        "CIRCLED_DIGIT" -> NumberType.CIRCLED_DIGIT
        "ROMAN_CAPITAL" -> NumberType.ROMAN_UPPER
        "ROMAN_SMALL" -> NumberType.ROMAN_LOWER
        "LATIN_CAPITAL" -> NumberType.LATIN_UPPER
        "LATIN_SMALL" -> NumberType.LATIN_LOWER
        "CIRCLED_LATIN_CAPITAL", "CIRCLED_LATIN_CAPTION" -> NumberType.CIRCLED_LATIN_UPPER
        "CIRCLED_LATIN_SMALL" -> NumberType.CIRCLED_LATIN_LOWER
        "HANGUL_SYLLABLE" -> NumberType.HANGUL_SYLLABLE
        "CIRCLED_HANGUL_SYLLABLE" -> NumberType.CIRCLED_HANGUL_SYLLABLE
        "HANGUL_JAMO" -> NumberType.HANGUL_JAMO
        "CIRCLED_HANGUL_JAMO" -> NumberType.CIRCLED_HANGUL_JAMO
        "HANGUL_PHONETIC" -> NumberType.HANGUL_DIGIT
        "IDEOGRAPH" -> NumberType.HANJA_DIGIT
        "CIRCLED_IDEOGRAPH" -> NumberType.CIRCLED_HANJA_DIGIT
        "DECAGON_CIRCLE" -> NumberType.HANJA_GAP
        "DECAGON_CIRCLE_HANJA", "DECAGON_CIRCLE_HANGUL" -> NumberType.HANJA_GAP_LOWER
        "SYMBOL", "USER_CHAR" -> NumberType.SYMBOL
        else -> NumberType.DIGIT
    }

    fun vertRel(s: String?): VertRel = when (s?.trim()?.uppercase()) {
        "PAPER" -> VertRel.PAPER
        "PAGE" -> VertRel.PAGE
        else -> VertRel.PARA
    }

    fun horzRel(s: String?): HorzRel = when (s?.trim()?.uppercase()) {
        "PAPER" -> HorzRel.PAPER
        "PAGE" -> HorzRel.PAGE
        "PARA" -> HorzRel.PARA
        else -> HorzRel.COLUMN
    }

    fun objAlign(s: String?): ObjAlign = when (s?.trim()?.uppercase()) {
        "CENTER" -> ObjAlign.CENTER
        "BOTTOM", "RIGHT" -> ObjAlign.END
        "INSIDE" -> ObjAlign.INSIDE
        "OUTSIDE" -> ObjAlign.OUTSIDE
        else -> ObjAlign.START
    }

    fun textWrap(s: String?): TextWrap = when (s?.trim()?.uppercase()) {
        "SQUARE" -> TextWrap.SQUARE
        "TIGHT" -> TextWrap.TIGHT
        "THROUGH" -> TextWrap.THROUGH
        "BEHIND_TEXT" -> TextWrap.BEHIND_TEXT
        "IN_FRONT_OF_TEXT" -> TextWrap.IN_FRONT_OF_TEXT
        else -> TextWrap.TOP_AND_BOTTOM
    }

    /** 0 위, 1 가운데, 2 아래 */
    fun vertAlign3(s: String?, def: Int = 0): Int = when (s?.trim()?.uppercase()) {
        "TOP" -> 0
        "CENTER" -> 1
        "BOTTOM" -> 2
        else -> def
    }

    fun pageNumberPos(s: String?): PageNumberPos = when (s?.trim()?.uppercase()) {
        "TOP_LEFT" -> PageNumberPos.TOP_LEFT
        "TOP_CENTER" -> PageNumberPos.TOP_CENTER
        "TOP_RIGHT" -> PageNumberPos.TOP_RIGHT
        "BOTTOM_LEFT" -> PageNumberPos.BOTTOM_LEFT
        "BOTTOM_CENTER" -> PageNumberPos.BOTTOM_CENTER
        "BOTTOM_RIGHT" -> PageNumberPos.BOTTOM_RIGHT
        "OUTSIDE_TOP" -> PageNumberPos.OUTSIDE_TOP
        "OUTSIDE_BOTTOM" -> PageNumberPos.OUTSIDE_BOTTOM
        "INSIDE_TOP" -> PageNumberPos.INSIDE_TOP
        "INSIDE_BOTTOM" -> PageNumberPos.INSIDE_BOTTOM
        else -> PageNumberPos.NONE
    }

    /** 강조점 (HWP 코드와 같은 번호) */
    fun emphasis(s: String?): Int = when (s?.trim()?.uppercase()) {
        "DOT_ABOVE" -> 1
        "RING_ABOVE" -> 2
        "TILDE" -> 3
        "CARON" -> 4
        "SIDE" -> 5
        "COLON" -> 6
        "GRAVE_ACCENT" -> 7
        "ACUTE_ACCENT" -> 8
        "CIRCUMFLEX" -> 9
        "MACRON" -> 10
        "HOOK_ABOVE" -> 11
        "DOT_BELOW" -> 12
        else -> 0
    }

    /** 무늬 종류 (HWP 와 같은 번호, -1 = 없음) */
    fun hatchStyle(s: String?): Int = when (s?.trim()?.uppercase()) {
        "HORIZONTAL" -> 0
        "VERTICAL" -> 1
        "BACK_SLASH" -> 2
        "SLASH" -> 3
        "CROSS" -> 4
        "CROSS_DIAGONAL" -> 5
        else -> -1
    }

    fun gradationType(s: String?): Int = when (s?.trim()?.uppercase()) {
        "RADIAL" -> 1
        "CONICAL" -> 2
        "SQUARE" -> 3
        else -> 0
    }

    /** 그림 ID: 매니페스트 항목 ID 를 [A-Za-z0-9_-] 로 */
    fun imageId(ref: String?): String? {
        val t = ref?.trim() ?: return null
        if (t.isEmpty()) return null
        val sb = StringBuilder(minOf(t.length, 64))
        for (c in t) {
            if (sb.length >= 64) break
            sb.append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') c else '_')
        }
        return sb.toString()
    }
}

/** hc:fillBrush 의 내용 */
internal class FillData {
    var faceColor: Int? = null
    var hatchColor: Int? = null
    var hatchStyle = -1
    var gradient: Gradient? = null
    var imageId: String? = null
    var present = false
}

/** hc:fillBrush 노드 (머리 정보의 borderFill 과 본문 도형이 같이 쓴다) */
internal class FillBrushNode(private val fill: FillData) : XNode() {
    override fun child(name: String, a: XAttrs): XNode? {
        when (name) {
            "winBrush" -> {
                fill.present = true
                fill.faceColor = HwpxValues.color(a["faceColor"])
                fill.hatchStyle = HwpxValues.hatchStyle(a["hatchStyle"])
                fill.hatchColor = if (fill.hatchStyle >= 0) HwpxValues.color(a["hatchColor"]) else null
                return null
            }
            "gradation" -> {
                fill.present = true
                return GradationNode(
                    HwpxValues.gradationType(a["type"]), a.int("angle"), a.int("centerX"), a.int("centerY"),
                )
            }
            "imgBrush" -> {
                fill.present = true
                return ImgBrushNode()
            }
        }
        return null
    }

    private inner class GradationNode(val type: Int, val angle: Int, val cx: Int, val cy: Int) : XNode() {
        private val colors = ArrayList<Int>()

        override fun child(name: String, a: XAttrs): XNode? {
            if (name == "color" && colors.size < 64) HwpxValues.color(a["value"])?.let { colors.add(it) }
            return null
        }

        override fun end() {
            if (colors.isNotEmpty()) fill.gradient = Gradient(type, angle, cx, cy, colors.toList())
        }
    }

    private inner class ImgBrushNode : XNode() {
        override fun child(name: String, a: XAttrs): XNode? {
            if (name == "img") fill.imageId = HwpxValues.imageId(a["binaryItemIDRef"])
            return null
        }
    }
}
