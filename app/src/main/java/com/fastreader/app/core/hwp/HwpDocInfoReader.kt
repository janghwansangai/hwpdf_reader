package com.fastreader.app.core.hwp

import com.fastreader.app.core.CorruptDocumentException
import com.fastreader.app.core.doc.Align
import com.fastreader.app.core.doc.Border
import com.fastreader.app.core.doc.BorderFill
import com.fastreader.app.core.doc.Bullet
import com.fastreader.app.core.doc.CharShape
import com.fastreader.app.core.doc.Gradient
import com.fastreader.app.core.doc.HeadingType
import com.fastreader.app.core.doc.LineSpacingType
import com.fastreader.app.core.doc.LineType
import com.fastreader.app.core.doc.NumberType
import com.fastreader.app.core.doc.Numbering
import com.fastreader.app.core.doc.NumberingLevel
import com.fastreader.app.core.doc.ParaShape
import com.fastreader.app.core.doc.Style
import com.fastreader.app.core.doc.UnderlinePos

/**
 * HWP DocInfo 스트림(압축 해제된 바이트) → [HwpDocInfo].
 *
 * 레코드 배치는 hwplib(reader/docinfo) 와 같다. 버전마다 뒤에 붙는 필드는 버전과 남은 길이를 함께 보고 읽는다.
 * 손상된 레코드는 기본값으로 채워 ID 번호가 밀리지 않게 하고, 읽은 데까지는 살린다.
 *
 * ID 규칙: CharShape/ParaShape/Style 은 0부터, BorderFill/Numbering/Bullet/BinData 는 1부터 (레코드 순서).
 */
object HwpDocInfoReader {

    /** 테두리 굵기 번호(0..15) → mm */
    private val BORDER_WIDTH_MM = floatArrayOf(
        0.1f, 0.12f, 0.15f, 0.2f, 0.25f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 5.0f,
    )

    private val LINE_TYPES = LineType.values()
    private val ALIGNS = Align.values()
    private val LINE_SPACING_TYPES = LineSpacingType.values()
    private val HEADING_TYPES = HeadingType.values()
    private val NUMBER_TYPES = NumberType.values()

    private const val NONE_COLOR = -1 // 0xFFFFFFFF

    /** 종류별 최대 개수. 본문은 대부분 16비트 ID 로 참조하므로 그 이상은 쓸 일이 없다 (손상 파일 메모리 보호) */
    private const val MAX_ITEMS = 65536

    /** 글자 크기 상한 (한글 최대 4096pt) */
    private const val MAX_BASE_SIZE = 409600

    fun read(data: ByteArray, version: HwpVersion): HwpDocInfo {
        return try {
            Parser(version).parse(data)
        } catch (e: RuntimeException) {
            throw CorruptDocumentException("문서 정보(DocInfo)가 손상되었습니다.", e)
        }
    }

    /**
     * HWP 번호 모양 값 → [NumberType].
     * 0..16 은 순서가 같다(15 갑을병, 16 甲乙丙). 0x80(기호)/0x81(사용자 문자)는 SYMBOL. 모르는 값은 DIGIT.
     * (문단 번호, 쪽 번호, 각주/미주 번호 모양에 공통)
     */
    fun numberType(code: Int): NumberType = when (code) {
        in 0..16 -> NUMBER_TYPES[code]
        0x80, 0x81 -> NumberType.SYMBOL
        else -> NumberType.DIGIT
    }

    /** 테두리선 종류(0 없음, 1 실선, 2 파선, 3 점선 …, 17) → [LineType]. 모르는 값은 실선 (hwplib 과 같음) */
    fun borderLineType(code: Int): LineType = if (code in LINE_TYPES.indices) LINE_TYPES[code] else LineType.SOLID

    /** 밑줄/취소선 모양(0 실선, 1 파선, 2 점선 …, 16) → [LineType]. 모르는 값은 실선 */
    fun strokeLineType(code: Int): LineType = if (code in 0 until LINE_TYPES.size - 1) LINE_TYPES[code + 1] else LineType.SOLID

    fun borderWidthMm(code: Int): Float = if (code in BORDER_WIDTH_MM.indices) BORDER_WIDTH_MM[code] else BORDER_WIDTH_MM[0]

    /** COLORREF(0x00BBGGRR) → 0xRRGGBB */
    fun rgb(colorRef: Int): Int = ((colorRef and 0xFF) shl 16) or (colorRef and 0xFF00) or ((colorRef ushr 16) and 0xFF)

    // ------------------------------------------------------------------------------------------------

    private class RawCharShape(val shape: CharShape, val faceIds: IntArray)

    private class RawLevel(
        val prop: Int,
        val widthAdjust: Int,
        val textOffset: Int,
        val charShapeId: Int,
        val format: String,
        var start: Int = 1,
    )

    private class RawNumbering(val levels: List<RawLevel>, val start: Int)

    private class Parser(private val version: HwpVersion) {
        private var sectionCount = 1
        private var startPage = 1
        private var startFootnote = 1
        private var startEndnote = 1

        /** ID_MAPPINGS 의 언어별 글꼴 수. null 이면 ID_MAPPINGS 를 못 읽음 */
        private var faceCounts: IntArray? = null
        private val faceNames = ArrayList<String>()
        private val charShapes = ArrayList<RawCharShape>()
        private val paraShapes = ArrayList<ParaShape>()
        private val borderFills = ArrayList<BorderFill>()
        private val numberings = ArrayList<RawNumbering?>()
        private val bullets = ArrayList<Bullet>()
        private val styles = ArrayList<Style>()
        private val binItems = ArrayList<HwpBinItem>()

        fun parse(data: ByteArray): HwpDocInfo {
            // HwpBinary.records 와 같은 분해. 목록을 만들지 않고 바로 처리한다
            var pos = 0
            val len = data.size
            while (pos + 4 <= len) {
                val header = HwpBinary.le32(data, pos)
                pos += 4
                var size = (header ushr 20) and 0xFFF
                if (size == 0xFFF) {
                    if (pos + 4 > len) break
                    size = HwpBinary.le32(data, pos)
                    pos += 4
                }
                if (size < 0 || size > len - pos) break // 잘린 레코드: 읽은 데까지만
                record(header and 0x3FF, HwpReader(data, pos, pos + size))
                pos += size
            }
            return build()
        }

        private fun record(tag: Int, r: HwpReader) {
            when (tag) {
                HwpTags.DOCUMENT_PROPERTIES -> guard({}) { documentProperties(r) }
                HwpTags.ID_MAPPINGS -> guard({}) { idMappings(r) }
                HwpTags.BIN_DATA -> if (binItems.size < MAX_ITEMS) {
                    binItems.add(guard({ HwpBinItem(binItems.size + 1, 0, "", 0) }) { binData(r, binItems.size + 1) })
                }
                HwpTags.FACE_NAME -> if (faceNames.size < MAX_ITEMS * 7) faceNames.add(guard({ "" }) { faceName(r) })
                HwpTags.BORDER_FILL -> if (borderFills.size < MAX_ITEMS) borderFills.add(guard({ BorderFill.NONE }) { borderFill(r) })
                HwpTags.CHAR_SHAPE -> if (charShapes.size < MAX_ITEMS) {
                    charShapes.add(guard({ RawCharShape(CharShape.DEFAULT, IntArray(7)) }) { charShape(r) })
                }
                HwpTags.NUMBERING -> if (numberings.size < MAX_ITEMS) numberings.add(guard({ null }) { numbering(r) })
                HwpTags.BULLET -> if (bullets.size < MAX_ITEMS) bullets.add(guard({ Bullet("") }) { bullet(r) })
                HwpTags.PARA_SHAPE -> if (paraShapes.size < MAX_ITEMS) paraShapes.add(guard({ ParaShape.DEFAULT }) { paraShape(r) })
                HwpTags.STYLE -> if (styles.size < MAX_ITEMS) styles.add(guard({ Style("") }) { style(r) })
            }
        }

        /** 레코드 하나를 읽다 생긴 예외는 그 레코드만 기본값으로 */
        private inline fun <T> guard(fallback: () -> T, block: () -> T): T = try {
            block()
        } catch (e: RuntimeException) {
            fallback()
        }

        private fun build(): HwpDocInfo {
            val groups = faceGroups()
            val cs = HashMap<Int, CharShape>(charShapes.size * 2)
            charShapes.forEachIndexed { id, raw ->
                val names = List(7) { lang -> groups[lang].getOrElse(raw.faceIds[lang]) { "" } }
                cs[id] = raw.shape.copy(fontNames = names)
            }
            val nums = HashMap<Int, Numbering>(numberings.size * 2)
            numberings.forEachIndexed { i, raw -> nums[i + 1] = if (raw == null) Numbering(emptyList()) else numbering(raw, cs) }
            return HwpDocInfo(
                sectionCount = sectionCount,
                startPage = startPage,
                startFootnote = startFootnote,
                startEndnote = startEndnote,
                faceNames = groups,
                charShapes = cs,
                paraShapes = indexed(paraShapes, 0),
                borderFills = indexed(borderFills, 1),
                numberings = nums,
                bullets = indexed(bullets, 1),
                styles = indexed(styles, 0),
                binItems = indexed(binItems, 1),
            )
        }

        private fun <T> indexed(list: List<T>, first: Int): Map<Int, T> {
            val m = HashMap<Int, T>(list.size * 2)
            list.forEachIndexed { i, v -> m[i + first] = v }
            return m
        }

        /** FACE_NAME 레코드를 ID_MAPPINGS 의 언어별 개수대로 7묶음으로 나눈다 */
        private fun faceGroups(): List<List<String>> {
            val counts = faceCounts
            if (counts == null) {
                // ID_MAPPINGS 가 없으면 보통 문서처럼 7개 언어가 같은 수라고 보고 나눈다
                val n = faceNames.size
                if (n == 0 || n % 7 != 0) return List(7) { emptyList() }
                return List(7) { faceNames.subList(it * n / 7, (it + 1) * n / 7).toList() }
            }
            var pos = 0
            return List(7) { lang ->
                val end = (pos.toLong() + counts[lang]).coerceAtMost(faceNames.size.toLong()).toInt()
                faceNames.subList(pos, end).toList().also { pos = end }
            }
        }

        // -------------------------------------------------------------------------------- records

        private fun documentProperties(r: HwpReader) {
            if (r.remaining < 2) return
            sectionCount = r.u16()
            if (r.remaining >= 6) {
                startPage = r.u16()
                startFootnote = r.u16()
                startEndnote = r.u16()
            }
        }

        private fun idMappings(r: HwpReader) {
            if (r.remaining < 32) return
            r.s32() // binData
            val counts = IntArray(7)
            for (i in 0 until 7) counts[i] = r.s32().coerceAtLeast(0)
            faceCounts = counts
        }

        private fun binData(r: HwpReader, binId: Int): HwpBinItem {
            val prop = r.u16()
            val type = (prop and 0xF).let { if (it in 0..2) it else 0 }
            val compression = ((prop ushr 4) and 0x3).let { if (it == 3) 0 else it }
            val ext = if (type == 0) {
                val abs = r.hwpString()
                val rel = r.hwpString()
                extensionOf(abs).ifEmpty { extensionOf(rel) }
            } else {
                r.u16() // 스트림 번호 (보통 binId 와 같다)
                sanitizeExtension(r.hwpString())
            }
            return HwpBinItem(binId, type, ext, compression)
        }

        private fun extensionOf(path: String): String {
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            val dot = name.lastIndexOf('.')
            return if (dot < 0) "" else sanitizeExtension(name.substring(dot + 1))
        }

        /** 확장자는 파일 이름/경로에 쓰이므로 영문/숫자만 남긴다 */
        private fun sanitizeExtension(s: String): String {
            val sb = StringBuilder()
            for (c in s) {
                if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')) sb.append(c.lowercaseChar())
                if (sb.length >= 16) break
            }
            return sb.toString()
        }

        /** 속성(1) + 이름. 뒤따르는 대체 글꼴/글꼴 유형 정보/기본 글꼴은 쓰지 않는다 */
        private fun faceName(r: HwpReader): String {
            r.u8()
            return r.hwpString().trimEnd('\u0000')
        }

        private fun borderFill(r: HwpReader): BorderFill {
            val prop = r.u16()
            val left = border(r)
            val right = border(r)
            val top = border(r)
            val bottom = border(r)
            val diagonal = border(r)
            // 대각선 모양: bit 2-4 (/), 5-7 (\). 값 2,3,6,7 (가운데 선이 있는 경우)만 표시 (hwplib 과 같음)
            val slash = (prop ushr 2) and 7
            val backSlash = (prop ushr 5) and 7

            var fillColor: Int? = null
            var patternColor: Int? = null
            var patternType = -1
            var gradient: Gradient? = null
            var imageId: String? = null
            val fillType = r.s32()
            if (fillType != 0) {
                if (fillType and 1 != 0) {
                    fillColor = colorOrNull(r.s32())
                    val pc = r.s32()
                    val pt = r.s32()
                    if (pt in 0..5) {
                        patternType = pt
                        patternColor = colorOrNull(pc)
                    }
                }
                if (fillType and 4 != 0) gradient = gradient(r)
                if (fillType and 2 != 0) {
                    r.u8() // 채우기 방식
                    r.skip(3) // 밝기, 명암, 효과
                    val bin = r.u16()
                    if (bin > 0) imageId = "bin$bin"
                }
            }
            return BorderFill(
                left = left, right = right, top = top, bottom = bottom,
                diagonal = if (slash and 2 != 0) diagonal else null,
                backDiagonal = if (backSlash and 2 != 0) diagonal else null,
                fillColor = fillColor,
                patternColor = patternColor,
                patternType = patternType,
                gradient = gradient,
                imageFillId = imageId,
            )
        }

        private fun border(r: HwpReader): Border {
            val type = r.u8()
            val width = r.u8()
            val color = r.s32()
            return Border(borderLineType(type), borderWidthMm(width), rgb(color))
        }

        private fun gradient(r: HwpReader): Gradient {
            val type = r.s8()
            val angle = r.s32()
            val cx = r.s32()
            val cy = r.s32()
            r.s32() // 번짐 정도
            val count = r.u32()
            // 손상된 개수로 오래 돌지 않도록 남은 길이로 제한
            if (count > 2) r.skip((count.coerceAtMost((r.remaining / 4).toLong()) * 4).toInt())
            val n = count.coerceAtMost((r.remaining / 4).toLong()).toInt()
            val colors = List(n) { rgb(r.s32()) }
            return Gradient(type = if (type in 1..4) type - 1 else 0, angle = angle, centerX = cx, centerY = cy, colors = colors)
        }

        private fun charShape(r: HwpReader): RawCharShape {
            val faceIds = IntArray(7) { r.u16() }
            val ratios = List(7) { r.u8() }
            val spacings = List(7) { r.s8() }
            val relSizes = List(7) { r.u8() }
            val offsets = List(7) { r.s8() }
            val baseSize = r.s32()
            val prop = r.s32()
            r.s8() // 그림자 간격 x
            r.s8() // 그림자 간격 y
            val textColor = r.s32()
            val underlineColor = r.s32()
            val shadeColor = r.s32()
            val shadowColor = r.s32()
            val borderFillId = if (version.atLeast(5, 0, 2, 1) && r.remaining >= 2) r.u16() else 0
            val strikeColor = if (version.atLeast(5, 0, 3, 0) && r.remaining >= 4) r.s32() else null

            val underlineKind = (prop ushr 2) and 3 // 0 없음, 1 아래, 2 가운데(취소선), 3 위
            val strike = (prop ushr 18) and 7 != 0 || underlineKind == 2
            val shape = CharShape(
                fontNames = List(7) { "" },
                ratios = ratios.map { if (it == 0) 100 else it },
                spacings = spacings,
                relSizes = relSizes.map { if (it == 0) 100 else it },
                offsets = offsets,
                baseSize = if (baseSize > 0) baseSize.coerceAtMost(MAX_BASE_SIZE) else 1000,
                italic = prop and 1 != 0,
                bold = prop and 2 != 0,
                underline = when (underlineKind) {
                    1 -> UnderlinePos.BOTTOM
                    3 -> UnderlinePos.TOP
                    else -> UnderlinePos.NONE
                },
                underlineStyle = strokeLineType((prop ushr 4) and 0xF),
                underlineColor = rgb(underlineColor),
                strike = strike,
                strikeStyle = strokeLineType((prop ushr 26) and 0xF),
                strikeColor = strikeColor?.let { rgb(it) },
                outline = (prop ushr 8) and 7 != 0,
                shadow = (prop ushr 11) and 3 != 0,
                shadowColor = rgb(shadowColor),
                emboss = prop and (1 shl 13) != 0,
                engrave = prop and (1 shl 14) != 0,
                superscript = prop and (1 shl 15) != 0,
                subscript = prop and (1 shl 16) != 0,
                emphasis = (prop ushr 21) and 0xF,
                textColor = rgb(textColor),
                // 흰색 음영은 "없음"으로 본다 (어두운 테마에서 흰 상자가 생기지 않게)
                shadeColor = if (shadeColor == NONE_COLOR || rgb(shadeColor) == 0xFFFFFF) null else rgb(shadeColor),
                borderFillId = borderFillId,
            )
            return RawCharShape(shape, faceIds)
        }

        private fun paraShape(r: HwpReader): ParaShape {
            val p1 = r.s32()
            // 여백/들여쓰기/문단 간격은 2배로 저장되어 있다
            val left = r.s32() / 2
            val right = r.s32() / 2
            val indent = r.s32() / 2
            val before = r.s32() / 2
            val after = r.s32() / 2
            val lineSpace = r.s32()
            r.u16() // 탭 정의 ID
            val headId = r.u16()
            val borderFillId = r.u16()
            val offL = r.s16()
            val offR = r.s16()
            val offT = r.s16()
            val offB = r.s16()
            if (version.atLeast(5, 0, 1, 7) && r.remaining >= 4) r.s32() // 속성 2
            var lsType = p1 and 3
            var ls = lineSpace
            if (version.atLeast(5, 0, 2, 5) && r.remaining >= 8) {
                lsType = r.s32() and 0x1F
                ls = r.s32()
            }
            // 수준: 속성1 bit 25-27 은 0..6 까지만 담기므로 8~10 수준은 뒤의 UINT32 에 있다
            var headingLevel = (p1 ushr 25) and 7
            if (r.remaining >= 4) {
                val l2 = r.s32()
                if (l2 in headingLevel + 1..9) headingLevel = l2
            }
            val type = if (lsType in LINE_SPACING_TYPES.indices) LINE_SPACING_TYPES[lsType] else LineSpacingType.PERCENT
            val spacing = when {
                type != LineSpacingType.PERCENT -> ls / 2
                ls > 0 -> ls
                else -> 160
            }
            return ParaShape(
                align = ((p1 ushr 2) and 7).let { if (it < ALIGNS.size) ALIGNS[it] else Align.JUSTIFY },
                marginLeft = left,
                marginRight = right,
                indent = indent,
                spacingBefore = before,
                spacingAfter = after,
                lineSpacingType = type,
                lineSpacing = spacing,
                headingType = HEADING_TYPES[(p1 ushr 23) and 3],
                headingId = headId,
                headingLevel = headingLevel,
                borderFillId = borderFillId,
                borderOffsetLeft = offL,
                borderOffsetRight = offR,
                borderOffsetTop = offT,
                borderOffsetBottom = offB,
                keepWithNext = p1 and (1 shl 17) != 0,
                pageBreakBefore = p1 and (1 shl 19) != 0,
            )
        }

        /** 문단 머리 정보 (12바이트) + 번호 서식 */
        private fun level(r: HwpReader, withFormat: Boolean): RawLevel {
            val prop = r.s32()
            val widthAdjust = r.u16()
            val textOffset = r.u16()
            val charShapeId = r.s32().let { if (it < 0) -1 else it }
            val format = if (withFormat) r.hwpString().replace("\u0000", "") else ""
            return RawLevel(prop, widthAdjust, textOffset, charShapeId, format)
        }

        private fun numbering(r: HwpReader): RawNumbering {
            val levels = ArrayList<RawLevel>(10)
            repeat(7) { levels.add(level(r, true)) }
            val start = r.u16()
            if (version.atLeast(5, 0, 2, 5) && r.remaining >= 4) {
                for (i in 0 until 7) if (r.remaining >= 4) levels[i].start = r.s32()
            }
            // 8~10 수준 (한글 2010 이후)
            if (r.remaining >= 12) {
                for (i in 0 until 3) {
                    if (r.remaining < 12) break
                    levels.add(level(r, true))
                }
                for (i in 7 until levels.size) if (r.remaining >= 4) levels[i].start = r.s32()
            }
            return RawNumbering(levels, start)
        }

        private fun numbering(raw: RawNumbering, charShapes: Map<Int, CharShape>): Numbering =
            Numbering(raw.levels.map { numberingLevel(it, charShapes) }, raw.start)

        private fun numberingLevel(l: RawLevel, charShapes: Map<Int, CharShape>): NumberingLevel = NumberingLevel(
            format = l.format,
            numberType = numberType((l.prop ushr 5) and 0x1F),
            startNumber = l.start,
            charShapeId = l.charShapeId,
            align = when (l.prop and 3) {
                1 -> Align.CENTER
                2 -> Align.RIGHT
                else -> Align.LEFT
            },
            widthAdjust = l.widthAdjust,
            textOffset = textOffset(l, charShapes),
        )

        /**
         * 본문과의 거리. bit4 = 0 이면 글자 크기에 대한 % 이므로 번호 글자 모양(없으면 10pt)의 크기로 HWPUNIT 으로 바꾼다.
         * (기준 크기 1/100 pt 는 HWPUNIT 과 같은 단위)
         */
        private fun textOffset(l: RawLevel, charShapes: Map<Int, CharShape>): Int {
            if (l.prop and (1 shl 4) != 0) return l.textOffset
            val size = charShapes[l.charShapeId]?.baseSize ?: 1000
            return (l.textOffset.toLong() * size / 100).toInt()
        }

        private fun bullet(r: HwpReader): Bullet {
            val head = level(r, false)
            val ch = r.u16()
            var imageId: String? = null
            if (r.remaining >= 4) {
                val image = r.s32()
                r.skip(3) // 명암, 밝기, 효과
                val bin = r.u16()
                if (image == 1 && bin > 0) imageId = "bin$bin"
            }
            return Bullet(char = if (ch == 0) "" else ch.toChar().toString(), charShapeId = head.charShapeId, imageId = imageId)
        }

        private fun style(r: HwpReader): Style {
            val name = r.hwpString()
            val englishName = r.hwpString()
            r.u8() // 속성 (문단/글자 스타일)
            r.u8() // 다음 스타일
            r.s16() // 언어
            val paraShapeId = r.u16()
            val charShapeId = r.u16()
            return Style(name = name, englishName = englishName, paraShapeId = paraShapeId, charShapeId = charShapeId)
        }

        private fun colorOrNull(colorRef: Int): Int? = if (colorRef == NONE_COLOR) null else rgb(colorRef)
    }
}
