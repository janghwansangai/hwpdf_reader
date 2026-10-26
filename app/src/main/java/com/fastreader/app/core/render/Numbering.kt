package com.fastreader.app.core.render

import com.fastreader.app.core.doc.HeadingType
import com.fastreader.app.core.doc.NumberType
import com.fastreader.app.core.doc.Numbering
import com.fastreader.app.core.doc.NumberingLevel
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.RichDocument

/** 번호 모양(NumberType)으로 수 쓰기. 한글 문서의 문단 번호/쪽 번호/각주 번호에 공통 */
internal object NumberFormat {

    private const val HANGUL_CONSONANTS = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ"

    // 가나다…하 의 초성 (ㄱ ㄴ ㄷ ㄹ ㅁ ㅂ ㅅ ㅇ ㅈ ㅊ ㅋ ㅌ ㅍ ㅎ) → 유니코드 초성 번호
    private val CHOSEONG = intArrayOf(0, 2, 3, 5, 6, 7, 9, 11, 12, 14, 15, 16, 17, 18)

    // 가 → 거 → 고 → 구 → 그 → 기 순서 (중성 번호)
    private val JUNGSEONG = intArrayOf(0, 4, 8, 13, 18, 20)

    private const val HANJA_DIGITS = "〇一二三四五六七八九"
    private const val HANGUL_DIGITS = "영일이삼사오육칠팔구"
    private const val GAP_HANGUL = "갑을병정무기경신임계"
    private const val GAP_HANJA = "甲乙丙丁戊己庚辛壬癸"
    private val ROMAN_UPPER = arrayOf("Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ", "Ⅺ", "Ⅻ")
    private val ROMAN_LOWER = arrayOf("ⅰ", "ⅱ", "ⅲ", "ⅳ", "ⅴ", "ⅵ", "ⅶ", "ⅷ", "ⅸ", "ⅹ", "ⅺ", "ⅻ")
    private val SYMBOLS = arrayOf("*", "†", "‡", "§", "¶", "**", "††", "‡‡", "§§", "¶¶")

    fun format(n: Int, type: NumberType): String {
        if (n <= 0) return if (type == NumberType.DIGIT || n < 0) n.toString() else "0"
        return when (type) {
            NumberType.DIGIT -> n.toString()
            NumberType.CIRCLED_DIGIT -> circledDigit(n)
            NumberType.ROMAN_UPPER -> if (n <= 12) ROMAN_UPPER[n - 1] else roman(n)
            NumberType.ROMAN_LOWER -> if (n <= 12) ROMAN_LOWER[n - 1] else roman(n).lowercase()
            NumberType.LATIN_UPPER -> latin(n, 'A')
            NumberType.LATIN_LOWER -> latin(n, 'a')
            NumberType.CIRCLED_LATIN_UPPER -> if (n <= 26) (0x24B6 + n - 1).toChar().toString() else latin(n, 'A')
            NumberType.CIRCLED_LATIN_LOWER -> if (n <= 26) (0x24D0 + n - 1).toChar().toString() else latin(n, 'a')
            NumberType.HANGUL_SYLLABLE -> hangulSyllable(n)
            NumberType.CIRCLED_HANGUL_SYLLABLE -> if (n <= 14) (0x326E + n - 1).toChar().toString() else hangulSyllable(n)
            NumberType.HANGUL_JAMO -> HANGUL_CONSONANTS[(n - 1) % 14].toString()
            NumberType.CIRCLED_HANGUL_JAMO -> if (n <= 14) (0x3260 + n - 1).toChar().toString() else HANGUL_CONSONANTS[(n - 1) % 14].toString()
            NumberType.HANGUL_DIGIT -> sinoKorean(n, HANGUL_DIGITS, "십", "백", "천", "만")
            NumberType.HANJA_DIGIT -> sinoKorean(n, HANJA_DIGITS, "十", "百", "千", "萬")
            NumberType.CIRCLED_HANJA_DIGIT -> if (n <= 10) (0x3280 + n - 1).toChar().toString() else sinoKorean(n, HANJA_DIGITS, "十", "百", "千", "萬")
            NumberType.HANJA_GAP -> GAP_HANGUL[(n - 1) % 10].toString()
            NumberType.HANJA_GAP_LOWER -> GAP_HANJA[(n - 1) % 10].toString()
            NumberType.SYMBOL -> SYMBOLS[(n - 1) % SYMBOLS.size]
        }
    }

    private fun circledDigit(n: Int): String = when (n) {
        in 1..20 -> (0x2460 + n - 1).toChar().toString()
        in 21..35 -> (0x3251 + n - 21).toChar().toString()
        in 36..50 -> (0x32B1 + n - 36).toChar().toString()
        else -> n.toString()
    }

    private fun roman(n0: Int): String {
        if (n0 >= 4000) return n0.toString()
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var n = n0
        val sb = StringBuilder()
        for (i in vals.indices) while (n >= vals[i]) {
            sb.append(syms[i]); n -= vals[i]
        }
        return sb.toString()
    }

    /** A..Z, AA..ZZ … (한글 프로그램과 같이 26 이후는 같은 글자를 겹친다) */
    private fun latin(n: Int, base: Char): String {
        val c = (base + (n - 1) % 26).toString()
        val times = ((n - 1) / 26 + 1).coerceAtMost(8)
        return c.repeat(times)
    }

    private fun hangulSyllable(n: Int): String {
        val i = (n - 1) % (14 * JUNGSEONG.size)
        val cho = CHOSEONG[i % 14]
        val jung = JUNGSEONG[i / 14]
        return (0xAC00 + (cho * 21 + jung) * 28).toChar().toString()
    }

    /** 일, 이, … 십, 십일, 이십, 백 … (한자도 같은 방식) */
    private fun sinoKorean(n: Int, digits: String, ten: String, hundred: String, thousand: String, tenThousand: String): String {
        if (n >= 100_000_000) return n.toString()
        val sb = StringBuilder()
        fun part(v: Int) {
            val units = arrayOf(thousand, hundred, ten, "")
            val ds = intArrayOf(v / 1000 % 10, v / 100 % 10, v / 10 % 10, v % 10)
            for (k in 0 until 4) {
                val d = ds[k]
                if (d == 0) continue
                if (!(d == 1 && k < 3)) sb.append(digits[d])
                sb.append(units[k])
            }
        }
        val high = n / 10000
        if (high > 0) {
            if (high > 1) part(high)
            sb.append(tenThousand)
        }
        part(n % 10000)
        return sb.toString()
    }

    /**
     * 글머리표 글자. 한글은 Wingdings/Symbol 글꼴의 문자를 사용자 영역(U+F0xx)으로 저장하므로
     * 흔히 쓰는 것을 같은 모양의 유니코드 문자로 바꾼다.
     */
    fun bulletChar(raw: String): String {
        if (raw.isEmpty()) return "•"
        val c = raw[0]
        if (c.code !in 0xE000..0xF8FF) return raw
        val code = c.code and 0xFF
        return WINGDINGS[code] ?: when (code) {
            in 0x20..0x7E -> "•"
            else -> "•"
        }
    }

    private val WINGDINGS: Map<Int, String> = mapOf(
        0x6C to "●", 0x6D to "❍", 0x6E to "■", 0x6F to "□", 0x70 to "◻", 0x71 to "❑", 0x72 to "❒",
        0x73 to "⬧", 0x74 to "⧫", 0x75 to "◆", 0x76 to "❖", 0x77 to "⬥", 0x78 to "⌧", 0x7A to "⌘",
        0x9E to "·", 0x9F to "•", 0xA0 to "▪", 0xA1 to "○", 0xA2 to "⭕", 0xA4 to "◉", 0xA5 to "◎",
        0xA7 to "▪", 0xA8 to "◻", 0xAA to "✦", 0xAB to "★", 0xAC to "✶", 0xB2 to "◇", 0xD8 to "➢",
        0xD9 to "⮙", 0xDA to "⮛", 0xDF to "🡐", 0xE0 to "🡒", 0xE8 to "➔", 0xEF to "⇦", 0xF0 to "⇨",
        0xFB to "✗", 0xFC to "✓", 0xFD to "☒", 0xFE to "☑", 0x2A to "✉", 0x46 to "☞", 0x4A to "☺",
        0x3C to "🖫", 0xB7 to "•", 0x8C to "➀", 0x81 to "②", 0xF8 to "⇧",
    )
}

/** 문단 머리(번호/글머리표) 표시 정보 */
internal class Heading(
    val text: String,
    /** 번호 글자 모양 ID (-1 = 문단 첫 글자 모양) */
    val charShapeId: Int,
    /** 번호와 본문 사이 거리 (HWPUNIT) */
    val gap: Int,
    /** 그림 글머리표 */
    val imageId: String? = null,
)

/**
 * 문서 순서대로 문단을 넘겨 받아 문단 번호를 매긴다 (번호 ID 별 수준 카운터).
 * 같은 번호 ID 를 쓰는 문단은 문서 전체에서 이어서 센다. 상위 수준 번호가 나오면 하위 수준은 다시 시작.
 */
internal class NumberingCounter(private val doc: RichDocument) {
    private val counters = HashMap<Int, IntArray>()

    fun heading(p: Paragraph, outlineNumberingId: Int): Heading? {
        val ps = doc.paraShapes[p.paraShapeId] ?: return null
        return when (ps.headingType) {
            HeadingType.NONE -> null
            HeadingType.BULLET -> {
                val b = doc.bullets[ps.headingId] ?: return Heading("•", -1, 0)
                if (b.imageId != null && doc.images[b.imageId]?.displayable == true) Heading("", b.charShapeId, 0, b.imageId)
                else Heading(NumberFormat.bulletChar(b.char), b.charShapeId, 0)
            }
            HeadingType.NUMBER -> number(ps.headingId, ps.headingLevel, doc.numberings[ps.headingId])
            HeadingType.OUTLINE -> {
                val id = outlineNumberingId
                number(-1 - id, ps.headingLevel, doc.numberings[id] ?: DEFAULT_OUTLINE)
            }
        }
    }

    private fun number(counterId: Int, level0: Int, numbering: Numbering?): Heading? {
        val levels = (numbering ?: DEFAULT_OUTLINE).levels
        if (levels.isEmpty()) return null
        val level = level0.coerceIn(0, MAX_LEVEL - 1)
        val c = counters.getOrPut(counterId) { IntArray(MAX_LEVEL) }
        val lv = levels.getOrNull(level)
        val start = startOf(numbering, lv)
        c[level] = if (c[level] == 0) start else c[level] + 1
        for (k in level + 1 until MAX_LEVEL) c[k] = 0
        val format = lv?.format ?: return null
        if (format.isEmpty()) return null
        val sb = StringBuilder()
        var i = 0
        while (i < format.length) {
            val ch = format[i]
            if (ch == '^' && i + 1 < format.length) {
                val nx = format[i + 1]
                // ^10 (10 수준) 은 번호 수준이 10개일 때만 그렇게 읽는다
                val ten = nx == '1' && i + 2 < format.length && format[i + 2] == '0' && levels.size >= 10
                val n = when {
                    ten -> 9
                    nx in '1'..'9' -> nx - '1'
                    nx == '0' || nx == 'A' || nx == 'a' -> 9
                    else -> -1
                }
                if (n >= 0) {
                    val lvN = levels.getOrNull(n)
                    val value = if (c[n] == 0) startOf(numbering, lvN) else c[n]
                    sb.append(NumberFormat.format(value, lvN?.numberType ?: NumberType.DIGIT))
                    i += if (ten) 3 else 2
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return Heading(sb.toString(), lv.charShapeId, lv.textOffset.coerceIn(0, 6000))
    }

    private fun startOf(numbering: Numbering?, lv: NumberingLevel?): Int {
        val s = lv?.startNumber ?: 0
        return if (s > 0) s else (numbering?.startNumber?.takeIf { it > 0 } ?: 1)
    }

    companion object {
        const val MAX_LEVEL = 10

        /** 한글 기본 개요 번호 모양 */
        val DEFAULT_OUTLINE = Numbering(
            listOf(
                NumberingLevel("^1.", NumberType.DIGIT, textOffset = 500),
                NumberingLevel("^2.", NumberType.HANGUL_SYLLABLE, textOffset = 500),
                NumberingLevel("^3)", NumberType.DIGIT, textOffset = 500),
                NumberingLevel("^4)", NumberType.HANGUL_SYLLABLE, textOffset = 500),
                NumberingLevel("(^5)", NumberType.DIGIT, textOffset = 500),
                NumberingLevel("(^6)", NumberType.HANGUL_SYLLABLE, textOffset = 500),
                NumberingLevel("^7", NumberType.CIRCLED_DIGIT, textOffset = 500),
                NumberingLevel("^8", NumberType.CIRCLED_HANGUL_SYLLABLE, textOffset = 500),
                NumberingLevel("^9", NumberType.HANGUL_JAMO, textOffset = 500),
                NumberingLevel("^10", NumberType.ROMAN_LOWER, textOffset = 500),
            ),
        )
    }
}
