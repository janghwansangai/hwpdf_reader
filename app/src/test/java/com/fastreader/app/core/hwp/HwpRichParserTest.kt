package com.fastreader.app.core.hwp

import com.fastreader.app.core.ByteArraySource
import com.fastreader.app.core.CorruptDocumentException
import com.fastreader.app.core.DocumentException
import com.fastreader.app.core.UnsupportedDocumentException
import com.fastreader.app.core.doc.AutoNumberKind
import com.fastreader.app.core.doc.BreakType
import com.fastreader.app.core.doc.FieldKind
import com.fastreader.app.core.doc.HorzRel
import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.LineType
import com.fastreader.app.core.doc.NumberType
import com.fastreader.app.core.doc.ObjAlign
import com.fastreader.app.core.doc.PageApply
import com.fastreader.app.core.doc.PageNumberPos
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.Picture
import com.fastreader.app.core.doc.Section
import com.fastreader.app.core.doc.Shape
import com.fastreader.app.core.doc.ShapeKind
import com.fastreader.app.core.doc.Table
import com.fastreader.app.core.doc.TextWrap
import com.fastreader.app.core.doc.VertRel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class HwpRichParserTest {

    // ================================================================ 샘플 문서

    private fun sample(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("samples/$name")!!.use { it.readBytes() }

    private fun parse(name: String) = HwpRichParser.parse(ByteArraySource(sample(name)), null)

    /** 문서 전체 문단 (표 셀/글상자/캡션/각주 포함, 문서 순서) */
    private fun allParagraphs(ps: List<Paragraph>): List<Paragraph> = buildList {
        for (p in ps) {
            add(p)
            for (i in p.content) when (i) {
                is Inline.Object -> {
                    i.obj.caption?.let { addAll(allParagraphs(it.paragraphs)) }
                    when (val o = i.obj) {
                        is Table -> o.cells.forEach { addAll(allParagraphs(it.paragraphs)) }
                        is Shape -> addAll(allParagraphs(o.paragraphs))
                        else -> {}
                    }
                }
                is Inline.Note -> addAll(allParagraphs(i.paragraphs))
                else -> {}
            }
        }
    }

    private fun Section.tables() = allParagraphs(paragraphs).flatMap { p -> p.content.filterIsInstance<Inline.Object>().mapNotNull { it.obj as? Table } }

    @Test
    fun tableSampleStructure() {
        val doc = parse("table.hwp")
        assertNull(doc.notice)
        val sec = doc.sections.single()
        assertEquals(5, sec.paragraphs.size)
        // A4 세로, 한글 기본 여백
        assertEquals(59528, sec.page.width)
        assertEquals(84188, sec.page.height)
        assertEquals(8504, sec.page.marginLeft)
        assertEquals(5668, sec.page.marginTop)
        assertFalse(sec.page.landscape)
        assertEquals(1, sec.columns.count)

        // 첫 문단: secd(0) cold(8) tbl(16) → 표만 Inline 으로 남는다
        val first = sec.paragraphs[0]
        val obj = first.content.single() as Inline.Object
        assertEquals(16, obj.start)
        val table = obj.obj as Table
        assertEquals(3, table.rowCount)
        assertEquals(3, table.colCount)
        assertEquals(9, table.cells.size)
        assertEquals(listOf(0 to 0, 1 to 0, 2 to 0, 0 to 1, 1 to 1, 2 to 1, 0 to 2, 1 to 2, 2 to 2), table.cells.map { it.col to it.row })
        assertEquals(listOf("ABC", "123"), table.cells[0].paragraphs.map { it.plainText })
        assertEquals("STR", table.cells[5].paragraphs.single().plainText)
        assertTrue(table.cells.all { it.width > 0 && it.height > 0 && it.vertAlign == 1 })
        assertFalse(table.layout.treatAsChar)

        // 캡션: "표 " + 자동 번호(표) + " "
        val cap = table.caption!!
        assertEquals(3, cap.side) // 아래
        val capPara = cap.paragraphs.single()
        assertEquals("표  ", capPara.plainText)
        val autoNum = capPara.content.filterIsInstance<Inline.AutoNumber>().single()
        assertEquals(AutoNumberKind.TABLE, autoNum.kind)
        assertEquals(2, autoNum.start)

        // 줄 배치: 원본 값 그대로
        val line = first.lines.single()
        assertEquals(0, line.textStart)
        assertEquals(1000, line.height)
        assertEquals(850, line.baseline)
        assertEquals(600, line.spacing)
        assertEquals(1000, sec.paragraphs[3].lines.single().height)
        assertTrue(sec.paragraphs[1].lines.single().vertPos > 0)

        // 빈 문단은 내용이 없다
        assertTrue(sec.paragraphs[1].content.isEmpty())
        assertEquals("", sec.paragraphs[1].plainText)
        // 두 번째 표: 1행 2열 빈 셀
        val t2 = sec.tables().last()
        assertEquals(1, t2.rowCount)
        assertEquals(2, t2.colCount)
    }

    @Test
    fun cellsStayInsideGrid() {
        val sec = parse("merging-cell.hwp").sections.single()
        val table = sec.tables().first()
        assertEquals(7, table.rowCount)
        assertEquals(7, table.colCount)
        assertEquals(49, table.cells.size)
        assertEquals("3,4", table.cells.single { it.row == 3 && it.col == 4 }.paragraphs.single().plainText)
        for (c in table.cells) {
            assertTrue(c.col + c.colSpan <= table.colCount)
            assertTrue(c.row + c.rowSpan <= table.rowCount)
        }
        // 셀은 행 순서
        assertEquals(table.cells.map { it.row }.sorted(), table.cells.map { it.row })
        assertTrue(allParagraphs(sec.paragraphs).any { it.plainText.startsWith("0,0") })
    }

    @Test
    fun distributionDocumentIsDecrypted() {
        val doc = parse("distribution.hwp")
        assertTrue(doc.notice!!.contains("배포용"))
        val texts = doc.sections.flatMap { allParagraphs(it.paragraphs) }.map { it.plainText }
        assertTrue(texts.any { it.contains("2025년") })
    }

    @Test
    fun positionsAreMonotonicAndCharShapesSplitRuns() {
        for (name in listOf("table.hwp", "distribution.hwp", "merging-cell.hwp")) {
            for (sec in parse(name).sections) for (p in allParagraphs(sec.paragraphs)) {
                var next = 0
                for (i in p.content) {
                    assertTrue("$name: ${i.start} < $next", i.start >= next)
                    next = if (i is Inline.Text) i.start + i.text.length else i.start + 1
                }
                for (ls in p.lines.zipWithNext()) assertTrue(ls.first.textStart <= ls.second.textStart)
            }
        }
    }

    @Test
    fun hwp3IsUnsupported() {
        val bytes = "HWP Document File V3.00 \u001a\u0001\u0002\u0003\u0004\u0005".toByteArray() + ByteArray(600)
        try {
            HwpRichParser.parse(ByteArraySource(bytes), null)
            fail()
        } catch (e: UnsupportedDocumentException) {
            assertTrue(e.message!!.contains("HWP 3.0"))
        }
    }

    /** 손상된 파일은 앱을 죽이거나 멈추게 하지 말고 DocumentException 으로 끝나야 한다. */
    @Test(timeout = 120_000)
    fun corruptedFilesFailGracefully() {
        val rnd = Random(7)
        for (name in listOf("table.hwp", "distribution.hwp", "merging-cell.hwp")) {
            val original = sample(name)
            repeat(200) { round ->
                val data = if (round % 3 == 0) {
                    original.copyOf(rnd.nextInt(original.size))
                } else {
                    original.copyOf().also { d -> repeat(1 + rnd.nextInt(30)) { d[rnd.nextInt(d.size)] = rnd.nextInt().toByte() } }
                }
                try {
                    HwpRichParser.parse(ByteArraySource(data)) { _, s -> s.readBytes() }
                } catch (_: DocumentException) {
                } catch (_: java.io.IOException) {
                } catch (e: Throwable) {
                    throw AssertionError("$name round $round: ${e.javaClass.name}: ${e.message}", e)
                }
            }
        }
    }

    // ================================================================ 합성 레코드

    private class W {
        val b = ByteArrayOutputStream()
        fun u8(v: Int) = apply { b.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
        fun u32(v: Int) = apply { u16(v); u16(v ushr 16) }
        fun str(s: String) = apply { u16(s.length); s.forEach { u16(it.code) } }
        fun dbl(d: Double) = apply { val l = java.lang.Double.doubleToLongBits(d); u32(l.toInt()); u32((l ushr 32).toInt()) }
        fun mat(vararg e: Double) = apply { e.forEach { dbl(it) } }
        fun raw(a: ByteArray) = apply { b.write(a) }
        fun bytes(): ByteArray = b.toByteArray()
    }

    private fun rec(tag: Int, level: Int, payload: ByteArray): ByteArray {
        val w = W()
        if (payload.size >= 0xFFF) w.u32(tag or (level shl 10) or (0xFFF shl 20)).u32(payload.size)
        else w.u32(tag or (level shl 10) or (payload.size shl 20))
        return w.raw(payload).bytes()
    }

    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

    /** 문단 (level) 과 그 자식 레코드 */
    private inner class P(val level: Int) {
        val chars = ArrayList<Int>()
        val ctrls = ArrayList<ByteArray>()
        var shapes = listOf(0 to 0)
        var lines = listOf<IntArray>()
        var last = false
        var breakBits = 0
        var paraShape = 0
        var style = 0

        fun text(s: String) = apply { s.forEach { chars.add(it.code) } }
        fun ch(c: Int) = apply { chars.add(c) }
        fun tab(width: Int, leader: Int, type: Int) = apply { chars.addAll(listOf(9, width and 0xFFFF, width ushr 16, leader or (type shl 8), 0, 0, 0, 9)) }
        fun ext(code: Int, id: String, ctrl: ByteArray?) = apply {
            val v = ctrlId(id)
            chars.addAll(listOf(code, v and 0xFFFF, v ushr 16, 0, 0, 0, 0, code))
            ctrl?.let { ctrls.add(it) }
        }
        fun fieldEnd(id: String) = apply {
            val v = ctrlId(id)
            chars.addAll(listOf(4, v and 0xFFFF, (v ushr 16) and 0xFF or 0x0800, 0, 0, 0, 0, 4))
        }

        fun build(): ByteArray {
            val all = chars + 13
            val o = ByteArrayOutputStream()
            o.write(rec(HwpTags.PARA_HEADER, level, W().u32(all.size or (if (last) Int.MIN_VALUE else 0)).u32(0).u16(paraShape).u8(style).u8(breakBits)
                .u16(shapes.size).u16(0).u16(lines.size).u32(0).u16(0).bytes()))
            o.write(rec(HwpTags.PARA_TEXT, level + 1, W().apply { all.forEach { u16(it) } }.bytes()))
            o.write(rec(HwpTags.PARA_CHAR_SHAPE, level + 1, W().apply { shapes.forEach { u32(it.first); u32(it.second) } }.bytes()))
            if (lines.isNotEmpty()) o.write(rec(HwpTags.PARA_LINE_SEG, level + 1, W().apply { lines.forEach { it.forEach { v -> u32(v) } } }.bytes()))
            ctrls.forEach { o.write(it) }
            return o.toByteArray()
        }
    }

    private fun lineSeg(start: Int, vpos: Int, flags: Int = 0x60000) = intArrayOf(start, vpos, 1000, 1000, 850, 600, 0, 42520, flags)

    /** 개체 공통 속성 */
    private fun W.common(prop: Int, y: Int, x: Int, w: Int, h: Int, z: Int = 0, desc: String = "") =
        u32(prop).u32(y).u32(x).u32(w).u32(h).u32(z).u16(10).u16(20).u16(30).u16(40).u32(0x1234).u32(0).str(desc)

    private fun simplePara(level: Int, text: String, last: Boolean = true) = P(level).text(text).also { it.last = last }.build()

    private fun listHeader(level: Int, extra: W.() -> Unit = {}) = rec(HwpTags.LIST_HEADER, level, W().u32(1).u32(0).apply(extra).bytes())

    private fun read(vararg parts: ByteArray, info: HwpDocInfo = HwpDocInfo.EMPTY): Section {
        val o = ByteArrayOutputStream()
        parts.forEach { o.write(it) }
        return HwpBodyReader.readSection(o.toByteArray(), info, HwpVersion(5, 1, 0, 0))
    }

    @Test
    fun inlineCharactersAndPositions() {
        val p = P(0).apply {
            text("가").tab(4000, 7, 2).text("나").ch(10).ch(30).ch(31).ch(24)
            ext(3, "%hlk", rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("%hlk")).u32(0).u8(0).str("http\\://example.com/a\\?b=1;1;0;0;").u32(7).bytes()))
            text("링크").fieldEnd("%hlk").text("끝")
            shapes = listOf(0 to 5, 12 to 7)
            lines = listOf(lineSeg(0, 0), lineSeg(11, 1600))
            last = true
            paraShape = 3
            style = 2
            breakBits = 0x04
        }
        val para = read(p.build()).paragraphs.single()
        assertEquals(3, para.paraShapeId)
        assertEquals(2, para.styleId)
        assertEquals(BreakType.PAGE, para.breakType)
        assertEquals("가\t나\n  -링크끝", para.plainText)
        val c = para.content
        assertEquals(Inline.Text(0, "가", 5), c[0])
        assertEquals(Inline.Tab(1, 5, 4000, LineType.CIRCLE), c[1])
        assertEquals(Inline.Text(9, "나", 5), c[2])
        assertEquals(Inline.LineBreak(10, 5), c[3])
        assertEquals(Inline.Text(11, " ", 5), c[4]) // 위치 12 에서 글자 모양이 바뀐다
        assertEquals(Inline.Text(12, " -", 7), c[5])
        val fs = c[6] as Inline.FieldStart
        assertEquals(14, fs.start)
        assertEquals(FieldKind.HYPERLINK, fs.kind)
        assertEquals("http://example.com/a?b=1", fs.url)
        assertEquals(Inline.Text(22, "링크", 7), c[7])
        assertEquals(Inline.FieldEnd(24, fs.id), c[8])
        assertEquals(Inline.Text(32, "끝", 7), c[9])
        assertEquals(10, c.size)
        assertEquals(2, para.lines.size)
        assertEquals(11, para.lines[1].textStart)
        assertEquals(1600, para.lines[1].vertPos)
        assertEquals(42520, para.lines[1].width)
    }

    @Test
    fun hyperlinkCommandParsing() {
        assertEquals("http://www.hancom.com", HwpControlReader.hyperlinkUrl("http\\://www.hancom.com;1;0;0;"))
        assertEquals("http://www.daum.net", HwpControlReader.hyperlinkUrl("www.daum.net|-;1;0;0"))
        assertEquals("mailto:a@b.c", HwpControlReader.hyperlinkUrl("mailto\\:a@b.c;2;0;0;"))
        assertNull(HwpControlReader.hyperlinkUrl(";1;0;0;"))
    }

    @Test
    fun sectionControlsFillSectionProperties() {
        val secd = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("secd")).u32(0x01 or 0x20).u16(1134).u16(0).u16(0).u32(8000)
            .u16(2).u16(5).u16(0).u16(0).u16(0).u16(0).bytes()) +
            rec(HwpTags.PAGE_DEF, 2, W().u32(59528).u32(84188).u32(8504).u32(8504).u32(5668).u32(4252).u32(4252).u32(4252).u32(0).u32(1).bytes())
        val cold = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("cold")).u16((2 shl 2) or (1 shl 12)).u16(1200).u16(0).u8(1).u8(1).u32(0x0000FF).bytes())
        val head = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("head")).u32(1).u32(1).bytes()) +
            listHeader(2) { u32(42520).u32(4252).u32(0) } + simplePara(2, "머리말")
        val pgnp = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("pgnp")).u32(2 or (5 shl 8)).u16(0).u16(0).u16(0).u16('-'.code).bytes())
        val fn = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("fn  ")).u32(3).u16(0).u16(')'.code).u32(0).u32(0).bytes()) +
            listHeader(2) { u32(0) } + simplePara(2, "각주 내용")
        val p0 = P(0).apply {
            ext(2, "secd", secd).ext(2, "cold", cold).ext(16, "head", head).ext(21, "pgnp", pgnp).text("본문").ext(17, "fn  ", fn)
            lines = listOf(lineSeg(0, 0))
        }.build()
        val foot = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("foot")).u32(0).u32(2).bytes()) + listHeader(2) { u32(42520).u32(4252).u32(0) } + simplePara(2, "꼬리말")
        val p1 = P(0).apply { ext(16, "foot", foot); last = true; lines = listOf(lineSeg(0, 1600)) }.build()
        val sec = read(p0, p1)

        // 가로 방향: 폭/높이를 바꿔 넣는다
        assertTrue(sec.page.landscape)
        assertEquals(84188, sec.page.width)
        assertEquals(59528, sec.page.height)
        assertEquals(2, sec.outlineNumberingId)
        assertEquals(5, sec.startPageNumber)
        assertTrue(sec.hideFirstHeader)
        assertFalse(sec.hideFirstFooter)
        assertTrue(sec.hideFirstPageNumber)
        assertEquals(2, sec.columns.count)
        assertEquals(1200, sec.columns.gap)
        assertEquals(LineType.SOLID, sec.columns.separator!!.type)
        assertEquals(0.12f, sec.columns.separator!!.widthMm)
        assertEquals(0xFF0000, sec.columns.separator!!.color)

        val h = sec.headers.single()
        assertEquals(PageApply.EVEN, h.apply)
        assertEquals(0, h.fromParagraph)
        assertEquals(42520, h.width)
        assertEquals("머리말", h.paragraphs.single().plainText)
        val f = sec.footers.single()
        assertEquals(PageApply.BOTH, f.apply)
        assertEquals(1, f.fromParagraph)
        assertEquals("꼬리말", f.paragraphs.single().plainText)

        val pn = sec.pageNumbering!!
        assertEquals(PageNumberPos.BOTTOM_CENTER, pn.position)
        assertEquals(NumberType.ROMAN_UPPER, pn.numberType)
        assertEquals("-", pn.dash)

        // 본문 문단: 구역/단/머리말/쪽번호 컨트롤은 Inline 이 아니고, 각주는 Note
        val content = sec.paragraphs[0].content
        assertEquals(Inline.Text(32, "본문", 0), content[0])
        val note = content[1] as Inline.Note
        assertEquals(34, note.start)
        assertFalse(note.endnote)
        assertEquals(3, note.number)
        assertEquals("각주 내용", note.paragraphs.single().plainText)
        assertEquals("본문", sec.paragraphs[0].plainText)
    }

    @Test
    fun pageHideAndNewNumberOnlyOnFirstPage() {
        fun pghd(flags: Int) = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("pghd")).u32(flags).bytes())
        fun nwno(n: Int) = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("nwno")).u32(0).u16(n).bytes())
        val p0 = P(0).apply { ext(21, "pghd", pghd(0x02)); lines = listOf(lineSeg(0, 0)) }.build()
        // 둘째 쪽 (vertPos 가 줄어듦): 감추기/새 번호는 구역 속성으로 옮기지 않는다
        val p1 = P(0).apply { ext(21, "pghd", pghd(0x01)).ext(21, "nwno", nwno(9)); last = true; lines = listOf(lineSeg(0, 0, 0x60001)) }.build()
        val sec = read(p0, p1)
        assertTrue(sec.hideFirstFooter)
        assertFalse(sec.hideFirstHeader)
        assertEquals(0, sec.startPageNumber)

        val q0 = P(0).apply { ext(21, "nwno", nwno(4)); last = true; lines = listOf(lineSeg(0, 0)) }.build()
        assertEquals(4, read(q0).startPageNumber)
    }

    private fun tableCtrl(level: Int, rows: Int, cols: Int, cells: List<IntArray>, cellText: (Int) -> ByteArray, caption: String? = null): ByteArray {
        val o = ByteArrayOutputStream()
        val prop = 0x01 or (2 shl 3) or (3 shl 8) or (if (caption != null) 1 shl 29 else 0) // 글자처럼, 문단 기준
        o.write(rec(HwpTags.CTRL_HEADER, level, W().u32(ctrlId("tbl ")).common(prop, 0, 0, 30000, 5000).bytes()))
        if (caption != null) {
            o.write(rec(HwpTags.LIST_HEADER, level + 1, W().u32(1).u32(0).u32(2).u32(8504).u16(850).u32(30000).bytes()))
            o.write(simplePara(level + 1, caption))
        }
        o.write(rec(HwpTags.TABLE, level + 1, W().u32(0x04).u16(rows).u16(cols).u16(0).u16(510).u16(510).u16(141).u16(141)
            .apply { repeat(rows) { u16(cols) } }.u16(3).u16(0).bytes()))
        cells.forEachIndexed { idx, c ->
            o.write(rec(HwpTags.LIST_HEADER, level + 1, W().u32(1).u32((2 shl 5) or (if (c[1] == 0) 1 shl 18 else 0)).u16(c[0]).u16(c[1]).u16(c[2]).u16(c[3])
                .u32(10000 * c[2]).u32(2000).u16(141).u16(141).u16(100).u16(100).u16(4).u32(0).bytes()))
            o.write(cellText(idx))
        }
        return o.toByteArray()
    }

    @Test
    fun tablesWithCaptionMergedAndNestedCells() {
        val inner = tableCtrl(3, 1, 1, listOf(intArrayOf(0, 0, 1, 1)), { simplePara(4, "안쪽") })
        val cells = listOf(intArrayOf(0, 0, 2, 1), intArrayOf(0, 1, 1, 1), intArrayOf(1, 1, 1, 1))
        val outer = tableCtrl(1, 2, 2, cells, { idx ->
            if (idx == 1) P(2).apply { ext(11, "tbl ", inner); last = true }.build() else simplePara(2, "셀$idx")
        }, caption = "표 제목")
        val sec = read(P(0).apply { text("앞").ext(11, "tbl ", outer).text("뒤"); last = true }.build())
        val p = sec.paragraphs.single()
        assertEquals("앞뒤", p.plainText)
        val t = (p.content[1] as Inline.Object).obj as Table
        assertEquals(1, p.content[1].start)
        assertEquals(Inline.Text(9, "뒤", 0), p.content[2])
        assertTrue(t.layout.treatAsChar)
        assertEquals(VertRel.PARA, t.layout.vertRelTo)
        assertEquals(HorzRel.PARA, t.layout.horzRelTo)
        assertEquals(30000, t.layout.width)
        assertEquals(10, t.layout.outMargin.left)
        assertEquals(40, t.layout.outMargin.bottom)
        assertEquals(2, t.rowCount)
        assertEquals(2, t.colCount)
        assertEquals(510, t.inMargin.left)
        assertEquals(141, t.inMargin.top)
        assertEquals(3, t.borderFillId)
        assertTrue(t.repeatHeader)
        assertEquals("표 제목", t.caption!!.paragraphs.single().plainText)
        assertEquals(2, t.caption!!.side)
        assertEquals(8504, t.caption!!.width)
        assertEquals(850, t.caption!!.gap)
        assertEquals(3, t.cells.size)
        val c0 = t.cells[0]
        assertEquals(2, c0.colSpan)
        assertEquals(20000, c0.width)
        assertEquals(2, c0.vertAlign)
        assertTrue(c0.header)
        assertEquals(4, c0.borderFillId)
        assertEquals(100, c0.margin.top)
        assertEquals("셀0", c0.paragraphs.single().plainText)
        val nested = (t.cells[1].paragraphs.single().content.single() as Inline.Object).obj as Table
        assertEquals("안쪽", nested.cells.single().paragraphs.single().plainText)
        assertEquals("셀2", t.cells[2].paragraphs.single().plainText)
    }

    /** SHAPE_COMPONENT 공통 부분 (컨트롤 바로 아래면 ID 두 번) */
    private fun W.shapeCommon(id: String, top: Boolean, offX: Int, offY: Int, orgW: Int, orgH: Int, curW: Int, curH: Int, mats: List<DoubleArray>) = apply {
        u32(ctrlId(id)); if (top) u32(ctrlId(id))
        u32(offX).u32(offY).u16(0).u16(1).u32(orgW).u32(orgH).u32(curW).u32(curH).u32(0).u16(0).u32(orgW / 2).u32(orgH / 2)
        u16((mats.size - 1) / 2)
        mats.forEach { mat(*it) }
    }

    private fun W.lineAndFill(color: Int, thick: Int, type: Int, fillBack: Int?) = apply {
        u32(color).u32(thick).u32(type).u8(0)
        if (fillBack == null) u32(0).u32(0) else u32(1).u32(fillBack).u32(0).u32(-1).u32(0).u8(0)
        u32(0).u32(0).u32(0).u32(0).u32(0).u8(0).u8(0) // 그림자, instid
    }

    @Test
    fun drawingObjects() {
        // 그림: 글자처럼 아님, 종이 기준, 글 앞으로
        val gsoProp = (0 shl 3) or (1 shl 5) or (0 shl 8) or (2 shl 10) or (3 shl 21)
        val pic = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("gso ")).common(gsoProp, 1000, 2000, 8460, 6748, 5, "그림입니다.").bytes()) +
            rec(HwpTags.SHAPE_COMPONENT, 2, W().shapeCommon("\$pic", true, 0, 0, 8460, 6748, 8460, 6748, listOf(identity, identity, identity)).bytes()) +
            rec(HwpTags.SHAPE_COMPONENT_PICTURE, 3, W().u32(0x0000FF).u32(33).u32(1).apply { repeat(8) { u32(0) } }
                .u32(100).u32(200).u32(10380).u32(8280).u16(1).u16(2).u16(3).u16(4).u8(-5).u8(6).u8(1).u16(3).u8(0).u32(0).u32(0).u32(10380).u32(8280).bytes())
        // 글상자가 있는 사각형: 현재 크기가 생성 크기의 2배 → 점 좌표도 2배
        val rect = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("gso ")).common(0x01, 0, 0, 4000, 2000).bytes()) +
            rec(HwpTags.SHAPE_COMPONENT, 2, W().shapeCommon("\$rec", true, 0, 0, 2000, 1000, 4000, 2000,
                listOf(identity, doubleArrayOf(2.0, 0.0, 0.0, 0.0, 2.0, 0.0), identity)).lineAndFill(0x332211, 283, 2, 0xFFFFFF).bytes()) +
            rec(HwpTags.LIST_HEADER, 3, W().u32(1).u32(1 shl 5).u16(283).u16(283).u16(141).u16(141).u32(3434).bytes()) +
            simplePara(3, "상자 글") +
            rec(HwpTags.SHAPE_COMPONENT_RECTANGLE, 3, W().u8(20).u32(0).u32(0).u32(2000).u32(0).u32(2000).u32(1000).u32(0).u32(1000).bytes())
        // 묶음: 자식 선은 (300,400) 으로 옮겨져 있다
        val con = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("gso ")).common(0x01, 0, 0, 5000, 5000).bytes()) +
            rec(HwpTags.SHAPE_COMPONENT, 2, W().shapeCommon("\$con", true, 0, 0, 5000, 5000, 5000, 5000, listOf(identity, identity, identity))
                .u16(2).u32(ctrlId("\$lin")).u32(ctrlId("\$ell")).u32(0).bytes()) +
            rec(HwpTags.SHAPE_COMPONENT, 3, W().shapeCommon("\$lin", false, 300, 400, 1000, 500, 1000, 500,
                listOf(doubleArrayOf(1.0, 0.0, 300.0, 0.0, 1.0, 400.0), identity, identity)).lineAndFill(0, 33, 1, null).bytes()) +
            rec(HwpTags.SHAPE_COMPONENT_LINE, 4, W().u32(0).u32(500).u32(1000).u32(0).u32(0).bytes()) +
            rec(HwpTags.SHAPE_COMPONENT, 3, W().shapeCommon("\$ell", false, 0, 0, 800, 800, 800, 800, listOf(identity, identity, identity)).lineAndFill(0, 33, 1, 0x00FF00).bytes()) +
            rec(HwpTags.SHAPE_COMPONENT_ELLIPSE, 4, ByteArray(60))
        val eq = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("eqed")).common(0x01, 0, 0, 3000, 1000).bytes()) +
            rec(HwpTags.EQEDIT, 2, W().u32(1).str("{a+b} over 2").u32(1200).u32(0x0000FF).u16(87).bytes())

        val bins = mapOf(3 to HwpBinItem(3, 1, "png", 0))
        val info = HwpDocInfo(1, 1, 1, 1, List(7) { emptyList() }, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), bins)
        val sec = read(P(0).apply { ext(11, "gso ", pic).ext(11, "gso ", rect).ext(11, "gso ", con).ext(11, "eqed", eq); last = true }.build(), info = info)
        val objs = sec.paragraphs.single().content.map { (it as Inline.Object).obj }
        assertEquals(listOf(0, 8, 16, 24), sec.paragraphs.single().content.map { it.start })

        val p = objs[0] as Picture
        assertEquals("bin3", p.imageId)
        assertFalse(p.layout.treatAsChar)
        assertEquals(VertRel.PAPER, p.layout.vertRelTo)
        assertEquals(HorzRel.PAPER, p.layout.horzRelTo)
        assertEquals(ObjAlign.CENTER, p.layout.vertAlign)
        assertEquals(ObjAlign.END, p.layout.horzAlign)
        assertEquals(TextWrap.IN_FRONT_OF_TEXT, p.layout.textWrap)
        assertEquals(1000, p.layout.vertOffset)
        assertEquals(2000, p.layout.horzOffset)
        assertEquals(5, p.layout.zOrder)
        assertEquals("그림입니다.", p.layout.description)
        assertEquals(listOf(100, 200, 10380, 8280), listOf(p.cropLeft, p.cropTop, p.cropRight, p.cropBottom))
        assertEquals(10380, p.imageWidth)
        assertEquals(8280, p.imageHeight)
        assertEquals(listOf(1, 2, 3, 4), listOf(p.inMargin.left, p.inMargin.right, p.inMargin.top, p.inMargin.bottom))
        assertEquals(-5, p.brightness)
        assertEquals(6, p.contrast)
        assertEquals(1, p.effect)
        assertEquals(LineType.SOLID, p.border.type)
        assertEquals(0xFF0000, p.border.color)

        val r = objs[1] as Shape
        assertEquals(ShapeKind.RECT, r.kind)
        assertEquals(20, r.cornerRadius)
        assertEquals(listOf(0, 0, 4000, 0, 4000, 2000, 0, 2000), r.points)
        assertEquals(LineType.DOT, r.line.type) // HWP 선 종류 2 = 점선
        assertEquals(0x112233, r.line.color)
        assertEquals(0xFFFFFF, r.fill!!.fillColor)
        assertEquals("상자 글", r.paragraphs.single().plainText)
        assertEquals(1, r.textVertAlign)
        assertEquals(141, r.textMargin.top)

        val c = objs[2] as Shape
        assertEquals(ShapeKind.CONTAINER, c.kind)
        assertEquals(2, c.children.size)
        val line = c.children[0] as Shape
        assertEquals(ShapeKind.LINE, line.kind)
        assertEquals(300, line.layout.horzOffset)
        assertEquals(400, line.layout.vertOffset)
        assertEquals(1000, line.layout.width)
        assertEquals(500, line.layout.height)
        assertEquals(listOf(0, 500, 1000, 0), line.points)
        assertNull(line.fill)
        val ell = c.children[1] as Shape
        assertEquals(ShapeKind.ELLIPSE, ell.kind)
        assertEquals(0x00FF00, ell.fill!!.fillColor)

        val e = objs[3] as com.fastreader.app.core.doc.Equation
        assertEquals("{a+b} over 2", e.script)
        assertEquals(1200, e.baseSize)
        assertEquals(0xFF0000, e.color)

        // DocInfo 에 없는 그림 ID 는 null
        val sec2 = read(P(0).apply { ext(11, "gso ", pic); last = true }.build())
        assertNull(((sec2.paragraphs.single().content.single() as Inline.Object).obj as Picture).imageId)
    }

    @Test
    fun rubyComposeAndAutoNumber() {
        val tdut = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("tdut")).str("本文").str("본문").u32(1).u32(0).u32(0).u32(0).u32(0).bytes())
        val tcps = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("tcps")).u16(2).u16('◯'.code).u16('가'.code).u8(1).u8(0).u8(0).u8(0).bytes())
        val atno = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("atno")).u32(0 or (2 shl 4)).u16(7).u16(0).u16(0).u16(0).bytes())
        val para = read(P(0).apply { ext(23, "tdut", tdut).ext(23, "tcps", tcps).ext(18, "atno", atno); last = true }.build()).paragraphs.single()
        assertEquals(Inline.Ruby(0, 0, "本文", "본문", below = true), para.content[0])
        assertEquals(Inline.Text(8, "가", 0), para.content[1])
        assertEquals(Inline.AutoNumber(16, 0, AutoNumberKind.PAGE, NumberType.ROMAN_UPPER, 7), para.content[2])
    }

    @Test
    fun deepNestingIsCut() {
        // 표 안의 표 ... 300 단계: 스택이 넘치지 않고 끝나야 한다
        var level = 600
        var cellPara = simplePara(level, "가장 안")
        while (level >= 2) {
            val tbl = tableCtrl(level - 1, 1, 1, listOf(intArrayOf(0, 0, 1, 1)), { cellPara })
            level -= 2
            cellPara = P(level).apply { ext(11, "tbl ", tbl); last = true }.build()
        }
        val sec = read(cellPara)
        var depth = 0
        var p: Paragraph? = sec.paragraphs.single()
        while (p != null) {
            val t = (p.content.firstOrNull() as? Inline.Object)?.obj as? Table ?: break
            p = t.cells.firstOrNull()?.paragraphs?.firstOrNull()
            depth++
        }
        assertTrue(depth in 10..HwpSectionReader.MAX_DEPTH + 1)
    }

    @Test
    fun brokenParagraphsAndControlsAreSkipped() {
        // 짝 없는 확장 컨트롤 문자, 크기가 맞지 않는 레코드, 엉뚱한 레벨
        val bad = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("tbl ")).u32(0).bytes()) // TABLE 레코드 없음
        val p = P(0).apply { text("가").ext(11, "tbl ", bad).ext(11, "gso ", null).text("나"); last = true }.build()
        val garbage = rec(HwpTags.PARA_TEXT, 0, byteArrayOf(1, 2, 3))
        val sec = read(garbage, p, rec(HwpTags.PARA_HEADER, 0, ByteArray(3)))
        assertEquals("가나", sec.paragraphs[0].plainText)
        assertEquals(Inline.Text(17, "나", 0), sec.paragraphs[0].content.last())
    }

    @Test(timeout = 60_000)
    fun fuzzedSectionsNeverThrowUnexpectedly() {
        val secd = rec(HwpTags.CTRL_HEADER, 1, W().u32(ctrlId("secd")).u32(0).u16(0).u16(0).u16(0).u32(8000).u16(1).u16(0).bytes()) +
            rec(HwpTags.PAGE_DEF, 2, W().apply { repeat(10) { u32(1000 * (it + 1)) } }.bytes())
        val cells = listOf(intArrayOf(0, 0, 1, 1), intArrayOf(1, 0, 1, 1))
        val tbl = tableCtrl(1, 1, 2, cells, { simplePara(2, "셀$it") }, caption = "캡션")
        val base = P(0).apply { ext(2, "secd", secd).text("본문").tab(100, 1, 1).ext(11, "tbl ", tbl); lines = listOf(lineSeg(0, 0)); last = true }.build()
        val rnd = Random(11)
        repeat(3000) { round ->
            val d = if (round % 4 == 0) base.copyOf(rnd.nextInt(base.size)) else base.copyOf().also { b ->
                repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(b.size)] = rnd.nextInt().toByte() }
            }
            try {
                HwpBodyReader.readSection(d, HwpDocInfo.EMPTY, HwpVersion(5, 0, 5, 0))
            } catch (_: CorruptDocumentException) {
            } catch (e: Throwable) {
                throw AssertionError("round $round: ${e.javaClass.name}: ${e.message}", e)
            }
        }
    }
}
