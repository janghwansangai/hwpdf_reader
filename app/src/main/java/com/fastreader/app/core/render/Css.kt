package com.fastreader.app.core.render

import com.fastreader.app.core.doc.Align
import com.fastreader.app.core.doc.Border
import com.fastreader.app.core.doc.BorderFill
import com.fastreader.app.core.doc.CharShape
import com.fastreader.app.core.doc.Lang
import com.fastreader.app.core.doc.LineSpacingType
import com.fastreader.app.core.doc.LineType
import com.fastreader.app.core.doc.ParaShape
import com.fastreader.app.core.doc.RichDocument
import com.fastreader.app.core.doc.UnderlinePos
import com.fastreader.app.core.render.Html.num
import com.fastreader.app.core.render.Html.pxd

/**
 * 문서 모양(글자/문단/테두리) → CSS.
 *
 * 클래스 이름
 * - `.c{id}`  글자 모양. 언어별 값이 다른 글자 모양은 `.c{id}.L{lang}` 를 추가로 만든다.
 * - `.q{id}`  문단 모양 (읽기 모드 전용)
 * - `.k{id}`  테두리 (원본 보기: 셀 경계에 걸치는 덮개 요소용)
 * - `.g{id}`  배경 (면색/그러데이션/그림)
 */
internal class Css(private val doc: RichDocument, private val mode: ViewMode, private val dark: Boolean) {

    private val flowDark = dark && mode == ViewMode.FLOW

    fun build(sb: StringBuilder) {
        for ((id, cs) in doc.charShapes) charRule(sb, id, cs)
        if (mode == ViewMode.FLOW) for ((id, ps) in doc.paraShapes) paraRule(sb, id, ps)
        for ((id, bf) in doc.borderFills) borderRules(sb, id, bf)
    }

    // ------------------------------------------------------------ 글자 모양

    private fun charRule(sb: StringBuilder, id: Int, cs: CharShape) {
        sb.append(".c").append(id).append('{')
        sb.append("font-family:").append(fontFamily(cs)).append(';')
        val pt = sizePt(cs, Lang.HANGUL)
        fontSize(sb, pt)
        val sp = cs.spacing(Lang.HANGUL)
        if (sp != 0) letterSpacing(sb, sp)
        if (cs.bold || heavyFont(cs.fontName(Lang.HANGUL))) sb.append("font-weight:700;")
        if (cs.italic) sb.append("font-style:italic;")
        val color = textColor(cs.textColor)
        if (color != null) sb.append("color:").append(color).append(';')
        decorations(sb, cs)
        val shade = cs.shadeColor ?: doc.borderFills[cs.borderFillId]?.fillColor
        if (shade != null) sb.append("background-color:").append(bgColor(shade)).append(';')
        val off = cs.offset(Lang.HANGUL)
        when {
            cs.superscript -> {
                // 위 첨자: 작게, 위로
                fontSize(sb, pt * 0.7)
                sb.append("position:relative;top:-0.55em;")
            }
            cs.subscript -> {
                fontSize(sb, pt * 0.7)
                sb.append("position:relative;top:0.25em;")
            }
            off != 0 -> sb.append("position:relative;top:").num(-off / 100.0).append("em;")
        }
        if (cs.outline) sb.append("-webkit-text-stroke:0.04em currentColor;-webkit-text-fill-color:transparent;")
        when {
            cs.emboss -> sb.append("text-shadow:-0.04em -0.04em 0 #fff,0.04em 0.04em 0 #888;")
            cs.engrave -> sb.append("text-shadow:0.04em 0.04em 0 #fff,-0.04em -0.04em 0 #888;")
            cs.shadow -> sb.append("text-shadow:0.08em 0.08em 0 ").append(Html.color(cs.shadowColor ?: 0xB2B2B2)).append(';')
        }
        if (cs.emphasis in 1..6) {
            val mark = when (cs.emphasis) {
                1 -> "filled dot"; 2 -> "open dot"; 3 -> "filled circle"; 4 -> "open circle"; 5 -> "filled triangle"; else -> "filled sesame"
            }
            sb.append("-webkit-text-emphasis:").append(mark).append(";text-emphasis:").append(mark).append(';')
        }
        sb.append("}\n")

        // 언어별로 크기/자간/위치가 다르면 언어 클래스를 따로
        if (!uniformLangs(cs)) {
            for (lang in Lang.entries) {
                if (lang == Lang.HANGUL) continue
                val lpt = sizePt(cs, lang)
                val lsp = cs.spacing(lang)
                val loff = cs.offset(lang)
                if (lpt == pt && lsp == sp && loff == off) continue
                sb.append(".c").append(id).append(".L").append(lang.ordinal).append('{')
                if (lpt != pt) fontSize(sb, if (cs.superscript || cs.subscript) lpt * 0.7 else lpt)
                if (lsp != sp) letterSpacing(sb, lsp)
                if (loff != off && !cs.superscript && !cs.subscript) sb.append("position:relative;top:").num(-loff / 100.0).append("em;")
                sb.append("}\n")
            }
        }
    }

    private fun fontSize(sb: StringBuilder, pt: Double) {
        sb.append("font-size:")
        if (mode == ViewMode.PAGE) sb.pxd(pt * 96.0 / 72.0) else sb.num(pt / 10.0).append("rem")
        sb.append(';')
    }

    private fun letterSpacing(sb: StringBuilder, percent: Int) {
        // 읽기 모드에서는 지나친 자간을 줄인다
        val v = if (mode == ViewMode.FLOW) (percent / 100.0).coerceIn(-0.08, 0.3) else percent / 100.0
        sb.append("letter-spacing:").num(v).append("em;")
    }

    private fun decorations(sb: StringBuilder, cs: CharShape) {
        val under = cs.underline != UnderlinePos.NONE && cs.underlineStyle != LineType.NONE
        val strike = cs.strike && cs.strikeStyle != LineType.NONE
        if (!under && !strike) return
        sb.append("text-decoration-line:")
        if (under) sb.append(if (cs.underline == UnderlinePos.TOP) "overline" else "underline")
        if (strike) sb.append(if (under) " line-through" else "line-through")
        sb.append(';')
        val style = if (under) cs.underlineStyle else cs.strikeStyle
        sb.append("text-decoration-style:").append(decorationStyle(style)).append(';')
        val c = if (under) cs.underlineColor else cs.strikeColor
        if (c != null && c != cs.textColor) {
            val css = textColor(c)
            if (css != null) sb.append("text-decoration-color:").append(css).append(';')
        }
        if (under && cs.underline == UnderlinePos.BOTTOM) sb.append("text-underline-offset:0.12em;")
    }

    private fun decorationStyle(t: LineType): String = when (t) {
        LineType.DOT, LineType.CIRCLE -> "dotted"
        LineType.DASH, LineType.LONG_DASH, LineType.DASH_DOT, LineType.DASH_DOT_DOT -> "dashed"
        LineType.DOUBLE, LineType.THIN_THICK, LineType.THICK_THIN, LineType.THIN_THICK_THIN -> "double"
        LineType.WAVE, LineType.DOUBLE_WAVE -> "wavy"
        else -> "solid"
    }

    private fun uniformLangs(cs: CharShape): Boolean {
        val pt = cs.relSize(Lang.HANGUL)
        val sp = cs.spacing(Lang.HANGUL)
        val off = cs.offset(Lang.HANGUL)
        for (l in Lang.entries) {
            if (cs.relSize(l) != pt || cs.spacing(l) != sp || cs.offset(l) != off) return false
        }
        return true
    }

    // ------------------------------------------------------------ 문단 모양 (읽기 모드)

    private fun paraRule(sb: StringBuilder, id: Int, ps: ParaShape) {
        sb.append(".q").append(id).append('{')
        sb.append("text-align:").append(
            when (ps.align) {
                Align.LEFT -> "left"
                Align.RIGHT -> "right"
                Align.CENTER -> "center"
                Align.JUSTIFY, Align.DISTRIBUTE, Align.DISTRIBUTE_SPACE -> "justify"
            },
        ).append(';')
        // 여백/들여쓰기는 화면 폭에 맞게 줄인다 (1000 HWPUNIT = 10pt → 0.6rem)
        val ml = ps.marginLeft.coerceAtLeast(0)
        val mr = ps.marginRight.coerceAtLeast(0)
        val ind = ps.indent
        // 내어쓰기: 둘째 줄부터 |indent| 만큼 들어가므로 왼쪽 여백에 더하고 첫 줄을 그만큼 당긴다
        val left = ml + (if (ind < 0) -ind else 0)
        if (left > 0) sb.append("padding-left:min(").num(left / 1000.0 * FLOW_INDENT).append("rem,35%);")
        if (mr > 0) sb.append("padding-right:min(").num(mr / 1000.0 * FLOW_INDENT).append("rem,20%);")
        if (ind != 0) sb.append("text-indent:").num(ind / 1000.0 * FLOW_INDENT).append("rem;")
        if (ps.spacingBefore > 0) sb.append("margin-top:").num((ps.spacingBefore / 1000.0 * 0.8).coerceAtMost(4.0)).append("rem;")
        if (ps.spacingAfter > 0) sb.append("margin-bottom:").num((ps.spacingAfter / 1000.0 * 0.8).coerceAtMost(4.0)).append("rem;")
        sb.append("line-height:").append(flowLineHeight(ps)).append(';')
        sb.append("}\n")
    }

    private fun flowLineHeight(ps: ParaShape): String = when (ps.lineSpacingType) {
        LineSpacingType.PERCENT -> Html.fmt((ps.lineSpacing.coerceIn(50, 500) / 100.0).coerceAtLeast(1.15))
        LineSpacingType.FIXED -> "max(1.15em," + Html.fmt(ps.lineSpacing.coerceIn(100, 20000) / 1000.0) + "rem)"
        LineSpacingType.BETWEEN_LINES -> "calc(1.15em + " + Html.fmt(ps.lineSpacing.coerceIn(0, 10000) / 1000.0) + "rem)"
        LineSpacingType.AT_LEAST -> "max(1.5em," + Html.fmt(ps.lineSpacing.coerceIn(0, 20000) / 1000.0) + "rem)"
    }

    // ------------------------------------------------------------ 테두리/배경

    private fun borderRules(sb: StringBuilder, id: Int, bf: BorderFill) {
        if (hasBorder(bf)) {
            sb.append(".k").append(id).append('{')
            if (mode == ViewMode.PAGE) {
                // 셀/문단 경계 가운데에 선이 오도록 바깥으로 절반씩 내민다 (이웃 셀의 선과 정확히 겹침)
                sb.append("top:").pxd(-bwPx(bf.top) / 2).append(";left:").pxd(-bwPx(bf.left) / 2)
                    .append(";right:").pxd(-bwPx(bf.right) / 2).append(";bottom:").pxd(-bwPx(bf.bottom) / 2).append(';')
            }
            side(sb, "top", bf.top)
            side(sb, "right", bf.right)
            side(sb, "bottom", bf.bottom)
            side(sb, "left", bf.left)
            sb.append("}\n")
        }
        val bg = background(bf)
        if (bg != null) sb.append(".g").append(id).append('{').append(bg).append("}\n")
    }

    private fun side(sb: StringBuilder, name: String, b: Border) {
        sb.append("border-").append(name).append(':')
        if (!b.visible) {
            sb.append("none;")
            return
        }
        val style = borderStyle(b.type)
        var w = bwPx(b)
        if (style == "double") w = w.coerceAtLeast(3.0)
        sb.pxd(w).append(' ').append(style).append(' ').append(lineColor(b.color)).append(';')
    }

    fun hasBorder(bf: BorderFill) = bf.left.visible || bf.right.visible || bf.top.visible || bf.bottom.visible

    /** 배경 CSS 선언 (없으면 null) */
    fun background(bf: BorderFill): String? {
        val g = bf.gradient
        if (g != null && g.colors.size >= 2) {
            val cols = g.colors.joinToString(",") { bgColor(it) }
            return if (g.type == 1 || g.type == 3) "background:radial-gradient(circle at ${g.centerX.coerceIn(0, 100)}% ${g.centerY.coerceIn(0, 100)}%,$cols);"
            else "background:linear-gradient(${(g.angle + 180) % 360}deg,$cols);"
        }
        val img = bf.imageFillId?.let { doc.images[it] }
        if (img != null && img.displayable) {
            val sb = StringBuilder("background-image:url(\"").append(img.path).append("\");background-size:100% 100%;")
            bf.fillColor?.let { sb.append("background-color:").append(bgColor(it)).append(';') }
            return sb.toString()
        }
        val fill = bf.fillColor
        if (fill != null) {
            val pc = bf.patternColor
            if (bf.patternType in 0..5 && pc != null) {
                val angle = when (bf.patternType) { 0 -> "0deg"; 1 -> "90deg"; 2 -> "45deg"; 3 -> "-45deg"; else -> "0deg" }
                val c2 = bgColor(pc)
                val base = bgColor(fill)
                return if (bf.patternType >= 4) "background:repeating-linear-gradient(0deg,$c2 0 1px,transparent 1px 4px),repeating-linear-gradient(90deg,$c2 0 1px,$base 1px 4px);"
                else "background:repeating-linear-gradient($angle,$c2 0 1px,$base 1px 4px);"
            }
            return "background-color:" + bgColor(fill) + ";"
        }
        return null
    }

    // ------------------------------------------------------------ 색

    /** 글자색. 읽기 모드 어두운 테마에서는 검정에 가까운 색을 테마 글자색으로, 어두운 색은 밝게 */
    fun textColor(rgb: Int): String? {
        if (!flowDark) return if (mode == ViewMode.FLOW && rgb == 0) null else Html.color(rgb)
        val l = luminance(rgb)
        if (l < 0.06) return null // 기본(테마) 글자색
        if (l < 0.35) return Html.color(lighten(rgb))
        return Html.color(rgb)
    }

    /** 선 색 (읽기 모드 어두운 테마에서 검은 선 → 밝은 회색) */
    fun lineColor(rgb: Int): String {
        if (!flowDark) return Html.color(rgb)
        return if (luminance(rgb) < 0.35) Html.color(lighten(rgb, 0.62)) else Html.color(rgb)
    }

    /** 배경색 (읽기 모드 어두운 테마에서 밝은 바탕 → 어둡게) */
    fun bgColor(rgb: Int): String {
        if (!flowDark) return Html.color(rgb)
        val l = luminance(rgb)
        return if (l > 0.3) Html.color(darken(rgb)) else Html.color(rgb)
    }

    companion object {
        /** 읽기 모드 여백 축소 비율 (1000 HWPUNIT 당 rem) */
        const val FLOW_INDENT = 0.6

        private val SERIF_HINTS = listOf(
            "바탕", "명조", "궁서", "신명", "batang", "myeongjo", "gungsuh", "serif", "times", "roman", "mincho",
            "song", "ming", "georgia", "garamond", "palatino", "cambria", "book", "해서", "필기", "붓", "신문", "옛",
        )
        private val SANS_HINTS = listOf("sans", "고딕", "돋움", "굴림", "gothic", "dotum", "gulim")
        private val HEAVY_HINTS = listOf("헤드라인", "견고딕", "견명조", "굵은", "bold", "black", "heavy", "extrabold")

        const val SERIF_STACK = "\"Noto Serif KR\",\"Noto Serif CJK KR\",serif"
        const val SANS_STACK = "\"Noto Sans KR\",\"Noto Sans CJK KR\",sans-serif"

        fun isSerif(name: String): Boolean {
            val n = name.lowercase()
            if (SANS_HINTS.any { n.contains(it) }) return false
            return SERIF_HINTS.any { n.contains(it) }
        }

        fun heavyFont(name: String): Boolean {
            val n = name.lowercase()
            return HEAVY_HINTS.any { n.contains(it) }
        }

        /** 원본 글꼴 이름(영문 글꼴이 다르면 먼저) + 대체 글꼴 */
        fun fontFamily(cs: CharShape): String {
            val hangul = cs.fontName(Lang.HANGUL).trim()
            val latin = cs.fontName(Lang.LATIN).trim()
            val sb = StringBuilder()
            fun add(n: String) {
                if (n.isEmpty() || n.length > 64) return
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(Html.cssString(n))
            }
            if (latin.isNotEmpty() && latin != hangul) add(latin)
            add(hangul)
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(if (isSerif(hangul.ifEmpty { latin })) SERIF_STACK else SANS_STACK)
            return sb.toString()
        }

        /** 글자 크기 (pt) = 기준 크기 × 언어별 상대 크기 */
        fun sizePt(cs: CharShape, lang: Lang): Double {
            val base = cs.baseSize.coerceIn(100, 409600) / 100.0
            val rel = cs.relSize(lang).coerceIn(10, 500) / 100.0
            return base * rel
        }

        fun borderStyle(t: LineType): String = when (t) {
            LineType.NONE -> "none"
            LineType.DOT, LineType.CIRCLE -> "dotted"
            LineType.DASH, LineType.LONG_DASH, LineType.DASH_DOT, LineType.DASH_DOT_DOT -> "dashed"
            LineType.DOUBLE, LineType.THIN_THICK, LineType.THICK_THIN, LineType.THIN_THICK_THIN -> "double"
            LineType.THICK_3D -> "ridge"
            LineType.THICK_3D_REV -> "groove"
            LineType.SOLID_3D -> "outset"
            LineType.SOLID_3D_REV -> "inset"
            else -> "solid"
        }

        /** 선 굵기 mm → px (0.1mm 도 보이도록 최소 0.5px) */
        fun bwPx(b: Border): Double = if (!b.visible) 0.0 else (b.widthMm.toDouble().coerceIn(0.05, 10.0) * 96.0 / 25.4).coerceAtLeast(0.5)

        fun luminance(rgb: Int): Double {
            fun ch(v: Int): Double {
                val c = v / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch((rgb shr 16) and 0xFF) + 0.7152 * ch((rgb shr 8) and 0xFF) + 0.0722 * ch(rgb and 0xFF)
        }

        /** HSL 명도를 뒤집어 밝게 (색상은 유지) */
        fun lighten(rgb: Int, minL: Double = 0.7): Int {
            val hsl = toHsl(rgb)
            hsl[2] = (1.0 - hsl[2]).coerceAtLeast(minL)
            return fromHsl(hsl)
        }

        fun darken(rgb: Int): Int {
            val hsl = toHsl(rgb)
            hsl[1] = hsl[1] * 0.6
            hsl[2] = (0.1 + (1.0 - hsl[2]) * 0.5).coerceIn(0.1, 0.32)
            return fromHsl(hsl)
        }

        private fun toHsl(rgb: Int): DoubleArray {
            val r = ((rgb shr 16) and 0xFF) / 255.0
            val g = ((rgb shr 8) and 0xFF) / 255.0
            val b = (rgb and 0xFF) / 255.0
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val l = (max + min) / 2
            if (max == min) return doubleArrayOf(0.0, 0.0, l)
            val d = max - min
            val s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
            val h = when (max) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } / 6
            return doubleArrayOf(h, s, l)
        }

        private fun fromHsl(hsl: DoubleArray): Int {
            val (h, s, l) = Triple(hsl[0], hsl[1], hsl[2])
            if (s == 0.0) {
                val v = (l * 255).toInt().coerceIn(0, 255)
                return (v shl 16) or (v shl 8) or v
            }
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            fun hue(t0: Double): Double {
                var t = t0
                if (t < 0) t += 1.0
                if (t > 1) t -= 1.0
                return when {
                    t < 1.0 / 6 -> p + (q - p) * 6 * t
                    t < 0.5 -> q
                    t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                    else -> p
                }
            }
            val r = (hue(h + 1.0 / 3) * 255).toInt().coerceIn(0, 255)
            val g = (hue(h) * 255).toInt().coerceIn(0, 255)
            val b = (hue(h - 1.0 / 3) * 255).toInt().coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or b
        }

        /** 글자 하나의 언어 구분 (한글 프로그램의 언어별 글꼴 선택과 비슷하게) */
        fun langOf(c: Char): Lang {
            val code = c.code
            return when {
                code in 0xAC00..0xD7A3 || code in 0x1100..0x11FF || code in 0x3130..0x318F || code in 0xA960..0xA97F || code in 0xD7B0..0xD7FF -> Lang.HANGUL
                code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF -> Lang.HANJA
                code in 0x3040..0x30FF || code in 0x31F0..0x31FF -> Lang.JAPANESE
                code < 0x0250 || code in 0x1E00..0x1EFF -> Lang.LATIN
                code in 0xE000..0xF8FF -> Lang.USER
                code in 0x2000..0x2BFF || code in 0x3000..0x303F || code in 0x3200..0x33FF || code in 0xFF00..0xFFEF || code in 0x25A0..0x25FF -> Lang.SYMBOL
                else -> Lang.OTHER
            }
        }
    }
}
