package com.fastreader.app.core.hwp

import com.fastreader.app.core.ByteArraySource
import com.fastreader.app.core.CompoundFile
import com.fastreader.app.core.CorruptDocumentException
import com.fastreader.app.core.doc.Align
import com.fastreader.app.core.doc.HeadingType
import com.fastreader.app.core.doc.LineSpacingType
import com.fastreader.app.core.doc.LineType
import com.fastreader.app.core.doc.NumberType
import com.fastreader.app.core.doc.UnderlinePos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class HwpDocInfoReaderTest {

    // ------------------------------------------------------------------ 샘플 파일

    private fun sample(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("samples/$name")!!.use { it.readBytes() }

    /** 샘플 HWP 의 (압축 해제된) DocInfo 와 버전 */
    private fun docInfoOf(name: String): Pair<ByteArray, HwpVersion> {
        val cf = CompoundFile(ByteArraySource(sample(name)))
        val fh = cf.read("FileHeader")
        val version = HwpVersion.fromRaw(HwpBinary.le32(fh, 32))
        val compressed = HwpBinary.le32(fh, 36) and 1 != 0
        val raw = cf.read("DocInfo")
        return (if (compressed) HwpBinary.inflate(raw) else raw) to version
    }

    private fun readSample(name: String): HwpDocInfo {
        val (data, version) = docInfoOf(name)
        return HwpDocInfoReader.read(data, version)
    }

    @Test
    fun tableSampleMatchesHwplib() {
        // 기대값은 hwplib 1.1.11 로 읽은 값(단위 변환 후)과 같다
        val d = readSample("table.hwp")
        assertEquals(1, d.sectionCount)
        assertEquals(1, d.startPage)
        assertEquals(7, d.faceNames.size)
        d.faceNames.forEach { assertEquals(listOf("함초롬돋움", "함초롬바탕"), it) }
        assertEquals(5, d.charShapes.size)
        assertEquals(12, d.paraShapes.size)
        assertEquals(3, d.borderFills.size)
        assertEquals(setOf(1, 2, 3), d.borderFills.keys)
        assertEquals(14, d.styles.size)

        val cs0 = d.charShapes.getValue(0)
        assertEquals(List(7) { "함초롬바탕" }, cs0.fontNames)
        assertEquals(1000, cs0.baseSize)
        assertNull(cs0.shadeColor)

        val ps1 = d.paraShapes.getValue(1)
        assertEquals(Align.JUSTIFY, ps1.align)
        assertEquals(-1310, ps1.indent)
        assertEquals(LineSpacingType.PERCENT, ps1.lineSpacingType)
        assertEquals(130, ps1.lineSpacing)
        val ps4 = d.paraShapes.getValue(4)
        assertEquals(7000, ps4.marginLeft)
        assertEquals(HeadingType.OUTLINE, ps4.headingType)
        assertEquals(6, ps4.headingLevel)

        val bf3 = d.borderFills.getValue(3)
        listOf(bf3.left, bf3.right, bf3.top, bf3.bottom).forEach {
            assertEquals(LineType.SOLID, it.type)
            assertEquals(0.12f, it.widthMm)
            assertEquals(0, it.color)
        }
        assertEquals(LineType.NONE, d.borderFills.getValue(1).left.type)

        val st0 = d.styles.getValue(0)
        assertEquals("바탕글", st0.name)
        assertEquals("Normal", st0.englishName)
        assertEquals(3, st0.paraShapeId)

        // 5.0.3.4 문서도 8~10 수준을 가진다
        val num = d.numberings.getValue(1)
        assertEquals(10, num.levels.size)
        assertEquals("^1.", num.levels[0].format)
        assertEquals(NumberType.DIGIT, num.levels[0].numberType)
        assertEquals(-1, num.levels[0].charShapeId)
        assertEquals(500, num.levels[0].textOffset) // 50% × 10pt
        assertEquals("^7", num.levels[6].format)
        assertEquals(NumberType.CIRCLED_DIGIT, num.levels[6].numberType)
    }

    @Test
    fun olderNumberingHasSevenLevels() {
        val d = readSample("merging-cell.hwp")
        assertEquals(7, d.numberings.getValue(1).levels.size)
        assertEquals(13, d.paraShapes.size)
    }

    @Test
    fun distributionDocInfoIsNotEncrypted() {
        val d = readSample("distribution.hwp")
        assertEquals(37, d.charShapes.size)
        assertEquals(34, d.paraShapes.size)
        assertEquals(8, d.borderFills.size)
        assertEquals(25, d.styles.size)
        assertEquals("굴림", d.faceNames[0][0])
        assertEquals("HCI Poppy", d.faceNames[1].last())
        val cs7 = d.charShapes.getValue(7)
        assertTrue(cs7.bold)
        assertEquals(1300, cs7.baseSize)
        assertEquals(List(7) { "돋움" }, cs7.fontNames)
        val cs8 = d.charShapes.getValue(8)
        assertEquals("휴먼명조", cs8.fontNames[0])
        assertEquals("HCI Poppy", cs8.fontNames[1])
        assertEquals("명조", cs8.fontNames[6])
        val bf7 = d.borderFills.getValue(7)
        assertEquals(LineType.NONE, bf7.top.type)
        assertEquals(0.5f, bf7.top.widthMm)
        assertEquals(0xECE9D8, bf7.top.color)
        assertEquals(0.2f, bf7.left.widthMm)
        val ps3 = d.paraShapes.getValue(3)
        assertEquals(5000, ps3.marginLeft)
        assertEquals(-744, ps3.indent)
    }

    // ------------------------------------------------------------------ 직접 만든 레코드

    private class Rec(val tag: Int, val level: Int = 0) {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v); u8(v ushr 8) }
        fun s32(v: Int) = apply { u16(v); u16(v ushr 16) }
        fun str(s: String) = apply {
            u16(s.length)
            s.forEach { u16(it.code) }
        }
        fun bytes() = out.toByteArray()
    }

    private fun stream(vararg recs: Rec): ByteArray {
        val out = ByteArrayOutputStream()
        fun w32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        for (r in recs) {
            val body = r.bytes()
            if (body.size >= 0xFFF) {
                w32(r.tag or (r.level shl 10) or (0xFFF shl 20))
                w32(body.size)
            } else {
                w32(r.tag or (r.level shl 10) or (body.size shl 20))
            }
            out.write(body)
        }
        return out.toByteArray()
    }

    private val v5025 = HwpVersion(5, 0, 2, 5)
    private val v5110 = HwpVersion(5, 1, 1, 0)

    private fun idMappings(faceCounts: List<Int>) = Rec(HwpTags.ID_MAPPINGS, 1).apply {
        s32(0)
        faceCounts.forEach { s32(it) }
        repeat(7) { s32(0) }
    }

    private fun faceName(name: String, prop: Int = 0) = Rec(HwpTags.FACE_NAME, 1).apply {
        u8(prop)
        str(name)
        if (prop and 0x80 != 0) { u8(1); str("대체$name") }
        if (prop and 0x40 != 0) repeat(10) { u8(it) }
        if (prop and 0x20 != 0) str("기본$name")
    }

    private fun charShape(
        faceIds: List<Int> = List(7) { 0 },
        prop: Int = 0,
        textColor: Int = 0,
        underlineColor: Int = 0,
        shadeColor: Int = -1,
        shadowColor: Int = 0,
        borderFillId: Int = 2,
        strikeColor: Int? = 0x0000FF,
        baseSize: Int = 1000,
    ) = Rec(HwpTags.CHAR_SHAPE, 1).apply {
        faceIds.forEach { u16(it) }
        listOf(100, 90, 100, 100, 100, 100, 100).forEach { u8(it) }
        listOf(0, -5, 0, 0, 0, 0, 3).forEach { u8(it) }
        listOf(100, 100, 120, 100, 100, 100, 100).forEach { u8(it) }
        listOf(0, 0, 0, -10, 0, 0, 0).forEach { u8(it) }
        s32(baseSize)
        s32(prop)
        u8(10); u8(10)
        s32(textColor); s32(underlineColor); s32(shadeColor); s32(shadowColor)
        u16(borderFillId)
        if (strikeColor != null) s32(strikeColor)
    }

    @Test
    fun faceNamesAreGroupedByIdMappingsCounts() {
        val data = stream(
            idMappings(listOf(2, 1, 1, 1, 1, 1, 1)),
            faceName("바탕", 0x80 or 0x40 or 0x20), // 대체/유형/기본 글꼴 정보가 붙어 있어도 이름만
            faceName("돋움", 0x40),
            faceName("Arial", 0x20),
            faceName("한자"),
            faceName("일본"),
            faceName("기타"),
            faceName("기호"),
            faceName("사용자"),
            charShape(faceIds = listOf(1, 0, 0, 0, 0, 0, 5)),
        )
        val d = HwpDocInfoReader.read(data, v5110)
        assertEquals(listOf("바탕", "돋움"), d.faceNames[0])
        assertEquals(listOf("Arial"), d.faceNames[1])
        assertEquals(listOf("사용자"), d.faceNames[6])
        // 범위를 벗어난 글꼴 ID 는 빈 이름
        assertEquals(listOf("돋움", "Arial", "한자", "일본", "기타", "기호", ""), d.charShapes.getValue(0).fontNames)
    }

    @Test
    fun charShapeAttributeBitsAndColors() {
        val prop = 1 or // 기울임
            (1 shl 1) or // 진하게
            (3 shl 2) or // 밑줄: 글자 위
            (2 shl 4) or // 밑줄 모양: 점선
            (1 shl 8) or // 외곽선
            (2 shl 11) or // 그림자
            (1 shl 13) or // 양각
            (1 shl 15) or // 위 첨자
            (1 shl 18) or // 취소선
            (3 shl 21) or // 강조점
            (7 shl 26) // 취소선 모양: 이중 실선
        val data = stream(
            charShape(prop = prop, textColor = 0x00332211, underlineColor = 0x000000FF, shadeColor = 0x0000FFFF, shadowColor = 0x00C0C0C0, borderFillId = 7, strikeColor = 0x00FF0000),
            charShape(shadeColor = -1),
            charShape(shadeColor = 0x00FFFFFF, prop = (2 shl 2) or (1 shl 16) or (1 shl 14)),
        )
        val d = HwpDocInfoReader.read(data, v5110)
        val c = d.charShapes.getValue(0)
        assertTrue(c.italic)
        assertTrue(c.bold)
        assertEquals(UnderlinePos.TOP, c.underline)
        assertEquals(LineType.DOT, c.underlineStyle)
        assertEquals(0xFF0000, c.underlineColor)
        assertTrue(c.outline)
        assertTrue(c.shadow)
        assertEquals(0xC0C0C0, c.shadowColor)
        assertTrue(c.emboss)
        assertFalse(c.engrave)
        assertTrue(c.superscript)
        assertFalse(c.subscript)
        assertTrue(c.strike)
        assertEquals(LineType.DOUBLE, c.strikeStyle)
        assertEquals(0x0000FF, c.strikeColor)
        assertEquals(3, c.emphasis)
        assertEquals(0x112233, c.textColor)
        assertEquals(0xFFFF00, c.shadeColor)
        assertEquals(7, c.borderFillId)
        assertEquals(listOf(100, 90, 100, 100, 100, 100, 100), c.ratios)
        assertEquals(listOf(0, -5, 0, 0, 0, 0, 3), c.spacings)
        assertEquals(listOf(100, 100, 120, 100, 100, 100, 100), c.relSizes)
        assertEquals(listOf(0, 0, 0, -10, 0, 0, 0), c.offsets)

        val plain = d.charShapes.getValue(1)
        assertNull(plain.shadeColor) // 0xFFFFFFFF = 없음
        assertFalse(plain.strike)
        assertEquals(UnderlinePos.NONE, plain.underline)

        val c2 = d.charShapes.getValue(2)
        assertNull(c2.shadeColor) // 흰색 음영도 없음으로
        assertTrue(c2.strike) // 밑줄 위치 '가운데' = 취소선
        assertEquals(UnderlinePos.NONE, c2.underline)
        assertTrue(c2.subscript)
        assertTrue(c2.engrave)
    }

    @Test
    fun charShapeVersionDependentFields() {
        // 5.0.2.1 미만: 테두리 ID 없음, 5.0.3.0 미만: 취소선 색 없음
        val old = HwpDocInfoReader.read(stream(charShape(borderFillId = 9, strikeColor = 0x00FF00)), HwpVersion(5, 0, 2, 0))
        assertEquals(0, old.charShapes.getValue(0).borderFillId)
        assertNull(old.charShapes.getValue(0).strikeColor)
        val mid = HwpDocInfoReader.read(stream(charShape(borderFillId = 9, strikeColor = 0x00FF00)), HwpVersion(5, 0, 2, 5))
        assertEquals(9, mid.charShapes.getValue(0).borderFillId)
        assertNull(mid.charShapes.getValue(0).strikeColor)
        val new = HwpDocInfoReader.read(stream(charShape(borderFillId = 9, strikeColor = 0x00FF00)), HwpVersion(5, 0, 3, 0))
        assertEquals(0x00FF00, new.charShapes.getValue(0).strikeColor)
        // 레코드가 짧으면 버전이 높아도 읽지 않는다
        val short = HwpDocInfoReader.read(stream(charShape(strikeColor = null)), v5110)
        assertNull(short.charShapes.getValue(0).strikeColor)
        assertEquals(2, short.charShapes.getValue(0).borderFillId)
    }

    private fun paraShape(
        p1: Int,
        left: Int = 0,
        right: Int = 0,
        indent: Int = 0,
        before: Int = 0,
        after: Int = 0,
        lineSpace: Int = 160,
        headId: Int = 0,
        borderFillId: Int = 0,
        offsets: List<Int> = listOf(0, 0, 0, 0),
        p3: Int? = null,
        lineSpace2: Int = 0,
        level: Int? = null,
    ) = Rec(HwpTags.PARA_SHAPE, 1).apply {
        s32(p1); s32(left); s32(right); s32(indent); s32(before); s32(after); s32(lineSpace)
        u16(0); u16(headId); u16(borderFillId)
        offsets.forEach { u16(it) }
        s32(0) // 속성 2
        if (p3 != null) { s32(p3); s32(lineSpace2) }
        if (level != null) s32(level)
    }

    @Test
    fun paraShapeValuesAreHalved() {
        val p1 = 1 /* 고정 값 (속성1) */ or (3 shl 2) /* 가운데 */ or (2 shl 23) /* 번호 */ or (4 shl 25) or (1 shl 17) or (1 shl 19)
        val data = stream(
            paraShape(p1, left = 4000, right = 2001, indent = -3001, before = 1000, after = 600, lineSpace = 3000, headId = 3, borderFillId = 2,
                offsets = listOf(100, 200, 300, 400), p3 = 1, lineSpace2 = 3560, level = 0),
            paraShape(0, lineSpace = 150, p3 = 0, lineSpace2 = 180),
            // 8~10 수준: 속성1 에는 6, 뒤의 UINT32 에 실제 수준
            paraShape(1 shl 23 or (6 shl 25), p3 = 0, lineSpace2 = 160, level = 8),
            paraShape(3 shl 2 or (2), lineSpace = 2000, p3 = 3, lineSpace2 = 1200), // 최소, 가운데(3)
        )
        val d = HwpDocInfoReader.read(data, v5110)
        val p = d.paraShapes.getValue(0)
        assertEquals(Align.CENTER, p.align)
        assertEquals(2000, p.marginLeft)
        assertEquals(1000, p.marginRight)
        assertEquals(-1500, p.indent)
        assertEquals(500, p.spacingBefore)
        assertEquals(300, p.spacingAfter)
        assertEquals(LineSpacingType.FIXED, p.lineSpacingType)
        assertEquals(1780, p.lineSpacing)
        assertEquals(HeadingType.NUMBER, p.headingType)
        assertEquals(3, p.headingId)
        assertEquals(4, p.headingLevel)
        assertEquals(2, p.borderFillId)
        // 테두리 간격은 반으로 나누지 않는다 (hwp2hwpx 와 같음)
        assertEquals(listOf(100, 200, 300, 400), listOf(p.borderOffsetLeft, p.borderOffsetRight, p.borderOffsetTop, p.borderOffsetBottom))
        assertTrue(p.keepWithNext)
        assertTrue(p.pageBreakBefore)

        val q = d.paraShapes.getValue(1)
        assertEquals(Align.JUSTIFY, q.align)
        assertEquals(LineSpacingType.PERCENT, q.lineSpacingType)
        assertEquals(180, q.lineSpacing) // 5.0.2.5 이상은 lineSpace2
        assertFalse(q.keepWithNext)

        val r = d.paraShapes.getValue(2)
        assertEquals(HeadingType.OUTLINE, r.headingType)
        assertEquals(8, r.headingLevel)

        val s = d.paraShapes.getValue(3)
        assertEquals(LineSpacingType.AT_LEAST, s.lineSpacingType)
        assertEquals(600, s.lineSpacing)
    }

    @Test
    fun oldParaShapeUsesFirstLineSpacingField() {
        // 5.0.2.5 미만: 속성1 bit0-1 과 첫 줄 간격 값
        val data = stream(paraShape(2 /* 여백만 */, lineSpace = 800, p3 = 3, lineSpace2 = 9999))
        val p = HwpDocInfoReader.read(data, HwpVersion(5, 0, 2, 2)).paraShapes.getValue(0)
        assertEquals(LineSpacingType.BETWEEN_LINES, p.lineSpacingType)
        assertEquals(400, p.lineSpacing)
    }

    private fun Rec.border(type: Int, width: Int, color: Int) = apply { u8(type); u8(width); s32(color) }

    @Test
    fun borderFillBordersAndFills() {
        val solid = Rec(HwpTags.BORDER_FILL, 1).apply {
            u16((2 shl 2) or (7 shl 5)) // '/' 와 '\' 대각선
            border(1, 1, 0x000000FF)
            border(2, 10, 0)
            border(3, 15, 0)
            border(0, 99, 0) // 모르는 굵기 → 0.1mm
            border(8, 3, 0x00008000) // 대각선
            s32(1) // 단색/무늬
            s32(0x00F0E0D0); s32(0x00000000); s32(4)
            s32(0)
        }
        val gradientAndImage = Rec(HwpTags.BORDER_FILL, 1).apply {
            u16(0)
            repeat(5) { border(0, 0, 0) }
            s32(2 or 4) // 그림 + 그러데이션
            u8(2); s32(45); s32(10); s32(20); s32(0) // 원형, 각도, 중심, 번짐
            s32(3); s32(0); s32(50); s32(100) // 색 3개 → 변화 위치 3개
            s32(0x000000FF); s32(0x0000FF00); s32(0x00FF0000)
            u8(5); u8(0); u8(0); u8(0); u16(3) // 그림: 크기에 맞춤, BinData 3
            s32(0)
        }
        val none = Rec(HwpTags.BORDER_FILL, 1).apply {
            u16(0)
            repeat(5) { border(0, 0, 0) }
            s32(1)
            s32(-1); s32(0x00999999); s32(-1) // 면색 없음, 무늬 없음
            s32(0)
        }
        val d = HwpDocInfoReader.read(stream(solid, gradientAndImage, none), v5110)
        assertEquals(setOf(1, 2, 3), d.borderFills.keys)
        val a = d.borderFills.getValue(1)
        assertEquals(LineType.SOLID, a.left.type)
        assertEquals(0.12f, a.left.widthMm)
        assertEquals(0xFF0000, a.left.color)
        assertEquals(LineType.DASH, a.right.type)
        assertEquals(1.0f, a.right.widthMm)
        assertEquals(LineType.DOT, a.top.type)
        assertEquals(5.0f, a.top.widthMm)
        assertEquals(LineType.NONE, a.bottom.type)
        assertEquals(0.1f, a.bottom.widthMm)
        assertEquals(LineType.DOUBLE, a.diagonal!!.type)
        assertEquals(0.2f, a.diagonal!!.widthMm)
        assertEquals(0x008000, a.diagonal!!.color)
        assertEquals(a.diagonal, a.backDiagonal)
        assertEquals(0xD0E0F0, a.fillColor)
        assertEquals(4, a.patternType)
        assertEquals(0x000000, a.patternColor)
        assertNull(a.gradient)
        assertNull(a.imageFillId)

        val b = d.borderFills.getValue(2)
        assertNull(b.diagonal)
        assertNull(b.backDiagonal)
        assertNull(b.fillColor)
        assertEquals(-1, b.patternType)
        val g = b.gradient!!
        assertEquals(1, g.type) // HWP 2(원형) → 모델 1
        assertEquals(45, g.angle)
        assertEquals(10, g.centerX)
        assertEquals(20, g.centerY)
        assertEquals(listOf(0xFF0000, 0x00FF00, 0x0000FF), g.colors)
        assertEquals("bin3", b.imageFillId)

        val c = d.borderFills.getValue(3)
        assertNull(c.fillColor)
        assertNull(c.patternColor)
        assertEquals(-1, c.patternType)
    }

    private fun Rec.head(prop: Int, widthAdjust: Int = 0, textOffset: Int = 50, charShapeId: Int = -1) = apply {
        s32(prop); u16(widthAdjust); u16(textOffset); s32(charShapeId)
    }

    @Test
    fun numberingLevelsStartNumbersAndOffsets() {
        val rec = Rec(HwpTags.NUMBERING, 1).apply {
            for (lv in 1..7) {
                // 수준 1: 가운데, 원문자 숫자, 본문과의 거리 HWPUNIT(bit4) 1000
                if (lv == 1) head((1) or (1 shl 4) or (1 shl 5), widthAdjust = 12, textOffset = 1000, charShapeId = 0)
                else head((lv - 1) shl 5)
                str("^$lv.")
            }
            u16(5)
            for (lv in 1..7) s32(lv * 10)
            for (lv in 8..10) { head(15 shl 5, charShapeId = 1); str("^$lv)") }
            for (lv in 8..10) s32(lv * 100)
        }
        val data = stream(
            charShape(baseSize = 1000),
            charShape(baseSize = 2000),
            rec,
            Rec(HwpTags.BULLET, 1).apply { head(0, charShapeId = 1); u16('●'.code); s32(0); u8(0); u8(0); u8(0); u16(0); u16(0) },
            Rec(HwpTags.BULLET, 1).apply { head(0); u16(0xF06C); s32(1); u8(0); u8(0); u8(0); u16(2); u16('✓'.code) },
        )
        val d = HwpDocInfoReader.read(data, v5110)
        val n = d.numberings.getValue(1)
        assertEquals(5, n.startNumber)
        assertEquals(10, n.levels.size)
        val l1 = n.levels[0]
        assertEquals("^1.", l1.format)
        assertEquals(Align.CENTER, l1.align)
        assertEquals(NumberType.CIRCLED_DIGIT, l1.numberType)
        assertEquals(12, l1.widthAdjust)
        assertEquals(1000, l1.textOffset) // HWPUNIT 그대로
        assertEquals(0, l1.charShapeId)
        assertEquals(10, l1.startNumber)
        val l3 = n.levels[2]
        assertEquals(NumberType.ROMAN_UPPER, l3.numberType)
        assertEquals(Align.LEFT, l3.align)
        assertEquals(-1, l3.charShapeId)
        assertEquals(500, l3.textOffset) // 50% × 10pt(기본)
        assertEquals(30, l3.startNumber)
        val l9 = n.levels[8]
        assertEquals("^9)", l9.format)
        assertEquals(NumberType.HANJA_GAP, l9.numberType)
        assertEquals(1000, l9.textOffset) // 50% × 20pt (글자 모양 1)
        assertEquals(900, l9.startNumber)

        val b1 = d.bullets.getValue(1)
        assertEquals("●", b1.char)
        assertEquals(1, b1.charShapeId)
        assertNull(b1.imageId)
        val b2 = d.bullets.getValue(2)
        assertEquals("\uF06C", b2.char)
        assertEquals("bin2", b2.imageId)
    }

    @Test
    fun oldNumberingWithoutStartNumbers() {
        val rec = Rec(HwpTags.NUMBERING, 1).apply {
            for (lv in 1..7) { head(8 shl 5); str("^$lv") }
            u16(1)
        }
        val n = HwpDocInfoReader.read(stream(rec), HwpVersion(5, 0, 2, 2)).numberings.getValue(1)
        assertEquals(7, n.levels.size)
        assertTrue(n.levels.all { it.startNumber == 1 && it.numberType == NumberType.HANGUL_SYLLABLE })
    }

    @Test
    fun numberTypeCodes() {
        assertEquals(NumberType.DIGIT, HwpDocInfoReader.numberType(0))
        assertEquals(NumberType.HANGUL_DIGIT, HwpDocInfoReader.numberType(12))
        assertEquals(NumberType.HANJA_GAP_LOWER, HwpDocInfoReader.numberType(16))
        assertEquals(NumberType.SYMBOL, HwpDocInfoReader.numberType(0x80))
        assertEquals(NumberType.SYMBOL, HwpDocInfoReader.numberType(0x81))
        assertEquals(NumberType.DIGIT, HwpDocInfoReader.numberType(17))
    }

    @Test
    fun stylesBinDataAndDocumentProperties() {
        val data = stream(
            Rec(HwpTags.DOCUMENT_PROPERTIES).apply { u16(3); u16(5); u16(2); u16(7); u16(1); u16(1); u16(1); s32(0); s32(0); s32(0) },
            Rec(HwpTags.ID_MAPPINGS).apply { repeat(18) { s32(0) } },
            Rec(HwpTags.BIN_DATA, 1).apply { u16(0); str("C:\\그림\\사진.PNG"); str("사진.PNG") },
            Rec(HwpTags.BIN_DATA, 1).apply { u16(1 or (2 shl 4)); u16(2); str("JPG") },
            Rec(HwpTags.BIN_DATA, 1).apply { u16(2 or (1 shl 4)); u16(3); str("OLE") },
            Rec(HwpTags.BIN_DATA, 1).apply { u16(1); u16(4); str("../../x") },
            Rec(HwpTags.STYLE, 1).apply { str("바탕글"); str("Normal"); u8(0); u8(0); u16(0x412); u16(3); u16(1); u16(0) },
            Rec(HwpTags.STYLE, 1).apply { str("개요 1"); str(""); u8(0); u8(1); u16(0x412); u16(10); u16(2); u16(0) },
        )
        val d = HwpDocInfoReader.read(data, v5110)
        assertEquals(3, d.sectionCount)
        assertEquals(5, d.startPage)
        assertEquals(2, d.startFootnote)
        assertEquals(7, d.startEndnote)

        assertEquals(setOf(1, 2, 3, 4), d.binItems.keys)
        val link = d.binItems.getValue(1)
        assertEquals(0, link.type)
        assertEquals("png", link.extension)
        val jpg = d.binItems.getValue(2)
        assertEquals(1, jpg.type)
        assertEquals(2, jpg.compression)
        assertEquals("jpg", jpg.extension)
        assertEquals("BinData/BIN0002.jpg", jpg.streamPath)
        assertEquals("bin2", jpg.imageId)
        val ole = d.binItems.getValue(3)
        assertEquals(2, ole.type)
        assertEquals(1, ole.compression)
        assertEquals("ole", ole.extension)
        assertEquals("x", d.binItems.getValue(4).extension) // 경로 문자는 없앤다

        assertEquals("바탕글", d.styles.getValue(0).name)
        assertEquals("Normal", d.styles.getValue(0).englishName)
        assertEquals(3, d.styles.getValue(0).paraShapeId)
        assertEquals(1, d.styles.getValue(0).charShapeId)
        assertEquals("개요 1", d.styles.getValue(1).name)
        assertEquals("", d.styles.getValue(1).englishName)
        assertEquals(10, d.styles.getValue(1).paraShapeId)
    }

    @Test
    fun brokenRecordKeepsIdNumbering() {
        // 잘린 CHAR_SHAPE(4바이트) 뒤의 글자 모양도 원래 번호(1)를 유지하고, 잘린 값은 기본값으로
        val data = stream(
            Rec(HwpTags.CHAR_SHAPE, 1).apply { s32(0) },
            charShape(baseSize = 1500),
        )
        val d = HwpDocInfoReader.read(data, v5110)
        assertEquals(2, d.charShapes.size)
        val broken = d.charShapes.getValue(0)
        assertEquals(1000, broken.baseSize)
        assertEquals(List(7) { 100 }, broken.ratios)
        assertEquals(List(7) { 100 }, broken.relSizes)
        assertEquals(1500, d.charShapes.getValue(1).baseSize)
    }

    @Test
    fun hugeGradientColorCountDoesNotHang() {
        val rec = Rec(HwpTags.BORDER_FILL, 1).apply {
            u16(0)
            repeat(5) { border(1, 1, 0) }
            s32(4)
            u8(1); s32(0); s32(0); s32(0); s32(0)
            s32(-1) // 색 개수 0xFFFFFFFF
            repeat(4) { s32(0x00112233) }
        }
        val t = System.nanoTime()
        val d = HwpDocInfoReader.read(stream(rec), v5110)
        assertTrue(System.nanoTime() - t < 1_000_000_000L)
        assertTrue(d.borderFills.getValue(1).gradient!!.colors.size <= 4)
    }

    @Test
    fun corruptInputIsSalvagedOrReportedAsCorrupt() {
        val (data, version) = docInfoOf("table.hwp")
        val rnd = Random(7)
        fun check(b: ByteArray, v: HwpVersion) {
            try {
                val d = HwpDocInfoReader.read(b, v)
                d.charShapes.values.forEach { assertEquals(7, it.fontNames.size) }
                assertEquals(7, d.faceNames.size)
            } catch (e: CorruptDocumentException) {
                // 허용
            } catch (e: Throwable) {
                fail("예상하지 못한 예외: $e")
            }
        }
        for (n in 0..data.size step 7) check(data.copyOf(n), version)
        repeat(500) {
            val b = data.copyOf()
            repeat(1 + rnd.nextInt(16)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            check(b, if (rnd.nextBoolean()) version else HwpVersion(5, 0, 0, 0))
        }
        repeat(200) {
            val b = ByteArray(rnd.nextInt(2048))
            rnd.nextBytes(b)
            check(b, version)
        }
        // 빈 스트림
        val empty = HwpDocInfoReader.read(ByteArray(0), version)
        assertEquals(1, empty.sectionCount)
        assertTrue(empty.charShapes.isEmpty())
    }

    @Test
    fun truncatedStreamKeepsLeadingRecords() {
        val (data, version) = docInfoOf("distribution.hwp")
        val full = HwpDocInfoReader.read(data, version)
        val half = HwpDocInfoReader.read(data.copyOf(data.size / 2), version)
        assertEquals(full.faceNames, half.faceNames)
        assertTrue(half.charShapes.isNotEmpty())
        half.charShapes.forEach { (id, cs) -> assertEquals(full.charShapes[id], cs) }
    }
}
