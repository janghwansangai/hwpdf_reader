package com.fastreader.app.core.hwpx

import com.fastreader.app.core.CorruptDocumentException
import com.fastreader.app.core.DocumentException
import com.fastreader.app.core.EncryptedDocumentException
import com.fastreader.app.core.doc.Align
import com.fastreader.app.core.doc.AutoNumberKind
import com.fastreader.app.core.doc.BreakType
import com.fastreader.app.core.doc.FieldKind
import com.fastreader.app.core.doc.HeadingType
import com.fastreader.app.core.doc.ImageInfo
import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.LineSpacingType
import com.fastreader.app.core.doc.LineType
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.Picture
import com.fastreader.app.core.doc.RichDocument
import com.fastreader.app.core.doc.Shape
import com.fastreader.app.core.doc.ShapeKind
import com.fastreader.app.core.doc.Table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class HwpxRichParserTest {

    private fun sample(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("samples/$name")!!.use { it.readBytes() }

    private fun parse(data: ByteArray, sink: MutableMap<String, ByteArray>? = null): RichDocument =
        HwpxRichParser.parse(data.inputStream(), sink?.let { m -> { info: ImageInfo, s: java.io.InputStream -> m[info.id] = s.readBytes() } })

    private fun zip(vararg entries: Pair<String, Any>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(if (data is String) data.toByteArray() else data as ByteArray)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun unzip(data: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(data.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out.add(e.name to z.readBytes())
            }
        }
        return out
    }

    private fun section(body: String) =
        """<?xml version="1.0" encoding="UTF-8"?><hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph" xmlns:hc="http://www.hancom.co.kr/hwpml/2011/core">$body</hs:sec>"""

    private fun header(refList: String) =
        """<?xml version="1.0" encoding="UTF-8"?><hh:head xmlns:hh="http://www.hancom.co.kr/hwpml/2011/head" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph" xmlns:hc="http://www.hancom.co.kr/hwpml/2011/core"><hh:refList>$refList</hh:refList></hh:head>"""

    /** 모든 문단(표 셀, 글상자, 각주 포함) */
    private fun allParagraphs(doc: RichDocument): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        fun walk(ps: List<Paragraph>) {
            for (p in ps) {
                out.add(p)
                for (i in p.content) when (i) {
                    is Inline.Object -> walkObj(i.obj, ::walk)
                    is Inline.Note -> walk(i.paragraphs)
                    else -> {}
                }
            }
        }
        for (s in doc.sections) {
            walk(s.paragraphs)
            (s.headers + s.footers).forEach { walk(it.paragraphs) }
        }
        return out
    }

    private fun walkObj(o: com.fastreader.app.core.doc.DrawObject, walk: (List<Paragraph>) -> Unit) {
        o.caption?.let { walk(it.paragraphs) }
        when (o) {
            is Table -> o.cells.forEach { walk(it.paragraphs) }
            is Shape -> {
                o.children.forEach { walkObj(it, walk) }
                walk(o.paragraphs)
            }
            else -> {}
        }
    }

    /** 줄 시작 위치는 글자 경계(글자 사이, 컨트롤 8칸의 앞/뒤)에 있어야 한다 */
    private fun assertLineStartsOnBoundaries(doc: RichDocument) {
        for (p in allParagraphs(doc)) {
            val bounds = HashSet<Int>().apply { add(0) }
            var end = 0
            for (i in p.content) when (i) {
                is Inline.Text -> {
                    for (k in 0..i.text.length) bounds.add(i.start + k)
                    end = maxOf(end, i.start + i.text.length)
                }
                is Inline.LineBreak -> {
                    bounds.add(i.start); bounds.add(i.start + 1); end = maxOf(end, i.start + 1)
                }
                else -> {
                    bounds.add(i.start); bounds.add(i.start + 8); end = maxOf(end, i.start + 8)
                }
            }
            var prev = -1
            for (l in p.lines) {
                assertTrue("line start ${l.textStart} in '${p.plainText}'", l.textStart in bounds)
                assertTrue(l.textStart <= maxOf(end, 0))
                assertTrue(l.textStart >= prev)
                prev = l.textStart
            }
            var last = -1
            for (i in p.content) {
                assertTrue("inline positions increase in '${p.plainText}'", i.start > last || (i.start == last && i !is Inline.Text))
                last = i.start
            }
        }
    }

    // ------------------------------------------------------------ 실제 샘플

    @Test
    fun tableSample() {
        val doc = parse(sample("table.hwpx"))
        assertNull(doc.notice)
        assertEquals(1, doc.sections.size)
        val s = doc.sections[0]
        assertEquals(59528, s.page.width)
        assertEquals(84188, s.page.height)
        assertEquals(8504, s.page.marginLeft)
        val texts = allParagraphs(doc).map { it.plainText }
        assertTrue(texts.contains("C반 기말고사"))
        assertTrue(texts.contains("개똥이"))
        val tables = s.paragraphs.flatMap { p -> p.content.filterIsInstance<Inline.Object>().map { it.obj } }.filterIsInstance<Table>()
        assertTrue(tables.isNotEmpty())
        val big = tables.maxByOrNull { it.cells.size }!!
        assertTrue(big.rowCount >= 5 && big.colCount == 4)
        // 셀 주소/병합이 표 범위 안에 있다
        for (c in big.cells) {
            assertTrue(c.row + c.rowSpan <= big.rowCount && c.col + c.colSpan <= big.colCount)
            assertTrue(c.width > 0 && c.height > 0)
        }
        val header = big.cells.first { it.paragraphs.firstOrNull()?.plainText == "이름" }
        assertEquals(0, header.col)
        // 셀 안 문단도 줄 배치가 있다
        assertTrue(header.paragraphs[0].lines.isNotEmpty())
        // 글자 모양: 글꼴 이름이 채워져 있다
        val cs = doc.charShapes[0]!!
        assertTrue(cs.fontNames[0].isNotEmpty())
        assertEquals(1000, cs.baseSize)
        assertLineStartsOnBoundaries(doc)
    }

    @Test
    fun rectInParaSample() {
        val doc = parse(sample("rect-in-para.hwpx"))
        val p0 = doc.sections[0].paragraphs[0]
        assertTrue(p0.plainText.startsWith("Start."))
        // secPr(8) + colPr(8) 뒤에서 글자가 시작한다
        val firstText = p0.content.first { it is Inline.Text } as Inline.Text
        assertEquals(16, firstText.start)
        val rects = p0.content.filterIsInstance<Inline.Object>().map { it.obj }.filterIsInstance<Shape>()
        assertTrue(rects.isNotEmpty())
        val r = rects[0]
        assertEquals(ShapeKind.RECT, r.kind)
        assertEquals("사각 시각한 사각형입니다. ", r.paragraphs[0].plainText)
        assertEquals(10775, r.layout.width)
        assertEquals(5950, r.layout.height)
        assertFalse(r.layout.treatAsChar)
        assertEquals(1, r.textVertAlign)
        assertEquals(0xFFFFFF, r.fill?.fillColor)
        // 사각형 개체는 앞 글자 바로 뒤 위치, 8칸
        val idx = p0.content.indexOfFirst { it is Inline.Object }
        val before = p0.content[idx - 1] as Inline.Text
        assertEquals(before.start + before.text.length, p0.content[idx].start)
        assertEquals(p0.content[idx].start + 8, p0.content[idx + 1].start)
        assertLineStartsOnBoundaries(doc)
    }

    @Test
    fun samplesSurviveCorruption() {
        val rnd = Random(7)
        for (name in listOf("table.hwpx", "rect-in-para.hwpx")) {
            val data = sample(name)
            val variants = ArrayList<ByteArray>()
            for (k in 1..20) variants.add(data.copyOf(data.size * k / 21))
            for (k in 0 until 30) variants.add(data.copyOf().also { d -> repeat(1 + k % 6) { val i = rnd.nextInt(d.size); d[i] = (d[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() } })
            val entries = unzip(data)
            for (k in 0 until 20) {
                val mod = entries.map { (n, d) ->
                    if (n.endsWith(".xml")) {
                        val x = d.copyOf(if (k % 2 == 0) d.size else rnd.nextInt(d.size + 1))
                        repeat(k) { if (x.isNotEmpty()) x[rnd.nextInt(x.size)] = "<>&\"/x9".random(kotlin.random.Random(k * 31 + it)).code.toByte() }
                        n to x
                    } else n to d
                }
                variants.add(zip(*mod.map { it.first to (it.second as Any) }.toTypedArray()))
            }
            for (v in variants) {
                try {
                    val doc = parse(v, HashMap())
                    allParagraphs(doc).forEach { it.plainText }
                } catch (e: DocumentException) {
                    // 허용
                } catch (e: Throwable) {
                    fail("$name: unexpected $e")
                }
            }
        }
    }

    // ------------------------------------------------------------ 위치 좌표

    @Test
    fun positionsFollowHwpCharCounting() {
        val sec = section(
            """<hp:p paraPrIDRef="1" styleIDRef="2" pageBreak="1"><hp:run charPrIDRef="3">""" +
                """<hp:secPr><hp:pagePr landscape="NARROWLY" width="59528" height="84188"><hp:margin left="1000" right="2000" top="3000" bottom="4000" header="500" footer="600" gutter="0"/></hp:pagePr></hp:secPr>""" +
                """<hp:ctrl><hp:colPr colCount="2" sameSz="1" sameGap="1134"/></hp:ctrl>""" +
                """<hp:t>가<hp:tab width="1200" leader="3" type="1"/>나<hp:lineBreak/>다<hp:nbSpace/><hp:fwSpace/><hp:titleMark ignore="0"/>라</hp:t></hp:run>""" +
                """<hp:run charPrIDRef="4"><hp:t>AB<hp:markpenBegin color="#FFFF00"/>C</hp:t><hp:t>D<hp:markpenEnd/>E</hp:t>""" +
                """<hp:ctrl><hp:fieldBegin id="77" type="HYPERLINK"><hp:parameters><hp:stringParam name="Path">https://example.com/a?b=1</hp:stringParam></hp:parameters></hp:fieldBegin></hp:ctrl>""" +
                """<hp:t>링크</hp:t><hp:ctrl><hp:fieldEnd beginIDRef="77"/></hp:ctrl><hp:t>😀끝</hp:t></hp:run>""" +
                """<hp:linesegarray><hp:lineseg textpos="0" vertpos="0" vertsize="1000" textheight="1000" baseline="850" spacing="600" horzpos="0" horzsize="42520" flags="393216"/>""" +
                """<hp:lineseg textpos="38" vertpos="1600" vertsize="1000" textheight="1000" baseline="850" spacing="600" horzpos="0" horzsize="42520" flags="0"/></hp:linesegarray></hp:p>""",
        )
        val doc = parse(zip("Contents/section0.xml" to sec))
        val s = doc.sections[0]
        // 가로 방향: 폭/높이를 바꿔 넣는다
        assertTrue(s.page.landscape)
        assertEquals(84188, s.page.width)
        assertEquals(59528, s.page.height)
        assertEquals(1000, s.page.marginLeft)
        assertEquals(600, s.page.marginFooter)
        assertEquals(2, s.columns.count)
        assertEquals(1134, s.columns.gap)

        val p = s.paragraphs[0]
        assertEquals(1, p.paraShapeId)
        assertEquals(2, p.styleId)
        assertEquals(BreakType.PAGE, p.breakType)
        val c = p.content
        // secPr 0..7, colPr 8..15
        assertEquals(Inline.Text(16, "가", 3), c[0])
        assertEquals(Inline.Tab(17, 3, 1200, LineType.DASH), c[1])
        assertEquals(Inline.Text(25, "나", 3), c[2])
        assertEquals(Inline.LineBreak(26, 3), c[3])
        // 묶음빈칸/고정폭빈칸은 1칸 글자, 제목 표시는 8칸
        assertEquals(Inline.Text(27, "다  ", 3), c[4])
        assertEquals(Inline.Text(38, "라", 3), c[5])
        assertEquals(Inline.Text(39, "AB", 4), c[6])
        // 형광펜은 자리를 차지하지 않고, 음영색만 다른 글자 모양을 새로 만든다
        val hl = c[7] as Inline.Text
        assertEquals(41, hl.start)
        assertEquals("CD", hl.text)
        assertEquals(0xFFFF00, doc.charShapes[hl.charShapeId]!!.shadeColor)
        assertEquals(Inline.Text(43, "E", 4), c[8])
        assertEquals(Inline.FieldStart(44, 77, FieldKind.HYPERLINK, "https://example.com/a?b=1"), c[9])
        assertEquals(Inline.Text(52, "링크", 4), c[10])
        assertEquals(Inline.FieldEnd(54, 77), c[11])
        // 보충 평면 문자는 UTF-16 두 칸 (HWP WCHAR 와 같다)
        assertEquals(Inline.Text(62, "😀끝", 4), c[12])
        assertEquals("가\t나\n다  라ABCDE링크😀끝", p.plainText)
        assertEquals(2, p.lines.size)
        assertEquals(38, p.lines[1].textStart)
        assertEquals(42520, p.lines[0].width)
        assertTrue(p.lines[0].isFirstInPage.not())
    }

    @Test
    fun objectsAndControls() {
        val sec = section(
            """<hp:p paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0">""" +
                """<hp:ctrl><hp:header id="1" applyPageType="ODD"><hp:subList textWidth="42520" textHeight="4252"><hp:p paraPrIDRef="5"><hp:run charPrIDRef="0"><hp:t>머리말</hp:t></hp:run></hp:p></hp:subList></hp:header>""" +
                """<hp:pageNum pos="BOTTOM_CENTER" formatType="ROMAN_SMALL" sideChar="-"/><hp:newNum num="7" numType="PAGE"/><hp:pageHiding hideHeader="1" hideFooter="0" hidePageNum="1"/></hp:ctrl>""" +
                """<hp:tbl rowCnt="1" colCnt="2" cellSpacing="0" borderFillIDRef="3" repeatHeader="1"><hp:sz width="20000" widthRelTo="ABSOLUTE" height="3000" heightRelTo="ABSOLUTE"/>""" +
                """<hp:pos treatAsChar="1" vertRelTo="PARA" horzRelTo="COLUMN" vertAlign="TOP" horzAlign="LEFT" vertOffset="0" horzOffset="0"/><hp:outMargin left="1" right="2" top="3" bottom="4"/><hp:inMargin left="510" right="510" top="141" bottom="141"/>""" +
                """<hp:tr><hp:tc header="1" hasMargin="0" borderFillIDRef="4"><hp:subList vertAlign="BOTTOM"><hp:p><hp:run charPrIDRef="0"><hp:t>A</hp:t></hp:run></hp:p></hp:subList><hp:cellAddr colAddr="0" rowAddr="0"/><hp:cellSpan colSpan="1" rowSpan="1"/><hp:cellSz width="10000" height="3000"/><hp:cellMargin left="1" right="1" top="1" bottom="1"/></hp:tc>""" +
                """<hp:tc hasMargin="1" borderFillIDRef="4"><hp:subList vertAlign="CENTER"><hp:p><hp:run charPrIDRef="0"><hp:ctrl><hp:footNote number="3"><hp:subList><hp:p><hp:run charPrIDRef="0"><hp:ctrl><hp:autoNum num="3" numType="FOOTNOTE"><hp:autoNumFormat type="CIRCLED_DIGIT"/></hp:autoNum></hp:ctrl><hp:t> 각주</hp:t></hp:run></hp:p></hp:subList></hp:footNote></hp:ctrl></hp:run></hp:p></hp:subList><hp:cellAddr colAddr="1" rowAddr="0"/><hp:cellSpan colSpan="1" rowSpan="1"/><hp:cellSz width="10000" height="3000"/><hp:cellMargin left="100" right="200" top="300" bottom="400"/></hp:tc></hp:tr></hp:tbl>""" +
                """<hp:pic id="5" zOrder="2" textWrap="SQUARE"><hp:offset x="0" y="0"/><hp:orgSz width="4000" height="2000"/><hp:curSz width="8000" height="4000"/><hp:imgClip left="0" right="30000" top="0" bottom="15000"/><hp:imgDim dimwidth="30000" dimheight="15000"/><hc:img binaryItemIDRef="image1" bright="10" contrast="-5" effect="GRAY_SCALE" alpha="0"/>""" +
                """<hp:sz width="8000" widthRelTo="ABSOLUTE" height="4000" heightRelTo="ABSOLUTE"/><hp:pos treatAsChar="0" vertRelTo="PAPER" horzRelTo="PAGE" vertAlign="CENTER" horzAlign="RIGHT" vertOffset="100" horzOffset="200"/>""" +
                """<hp:caption side="BOTTOM" width="8000" gap="850"><hp:subList><hp:p><hp:run charPrIDRef="0"><hp:t>그림 1</hp:t></hp:run></hp:p></hp:subList></hp:caption><hp:shapeComment>설명</hp:shapeComment></hp:pic>""" +
                """<hp:equation baseUnit="1200" textColor="#FF0000"><hp:sz width="3000" height="1000"/><hp:pos treatAsChar="1"/><hp:script>a over b</hp:script></hp:equation>""" +
                """<hp:dutmal posType="BOTTOM"><hp:mainText>본말</hp:mainText><hp:subText>덧말</hp:subText></hp:dutmal>""" +
                """<hp:compose circleType="SHAPE_CIRCLE" composeText="3"/>""" +
                """<hp:switch><hp:case hp:required-namespace="http://www.hancom.co.kr/hwpml/2016/ooxmlchart"><hp:chart/></hp:case><hp:default><hp:ole binaryItemIDRef="ole1"><hp:sz width="100" height="100"/></hp:ole></hp:default></hp:switch>""" +
                """<hp:t>끝</hp:t></hp:run></hp:p>""",
        )
        val doc = parse(zip("Contents/section0.xml" to sec))
        val s = doc.sections[0]
        assertEquals(1, s.headers.size)
        assertEquals("머리말", s.headers[0].paragraphs[0].plainText)
        assertEquals(com.fastreader.app.core.doc.PageApply.ODD, s.headers[0].apply)
        assertEquals(42520, s.headers[0].width)
        assertEquals(com.fastreader.app.core.doc.PageNumberPos.BOTTOM_CENTER, s.pageNumbering!!.position)
        assertEquals(com.fastreader.app.core.doc.NumberType.ROMAN_LOWER, s.pageNumbering!!.numberType)
        assertEquals("-", s.pageNumbering!!.dash)
        assertEquals(7, s.startPageNumber)
        assertTrue(s.hideFirstHeader && s.hideFirstPageNumber && !s.hideFirstFooter)

        val c = s.paragraphs[0].content
        // header, pageNum, newNum, pageHiding: 4 컨트롤 × 8
        val tbl = (c[0] as Inline.Object)
        assertEquals(32, tbl.start)
        val t = tbl.obj as Table
        assertEquals(1, t.rowCount)
        assertEquals(2, t.colCount)
        assertTrue(t.repeatHeader)
        assertTrue(t.layout.treatAsChar)
        assertEquals(20000, t.layout.width)
        assertEquals(4, t.layout.outMargin.bottom)
        // hasMargin=0 → 표 안쪽 여백, 1 → 셀 여백
        assertEquals(510, t.cells[0].margin.left)
        assertEquals(100, t.cells[1].margin.left)
        assertEquals(2, t.cells[0].vertAlign)
        assertEquals(1, t.cells[1].vertAlign)
        assertTrue(t.cells[0].header)
        val note = t.cells[1].paragraphs[0].content[0] as Inline.Note
        assertEquals(3, note.number)
        assertFalse(note.endnote)
        val an = note.paragraphs[0].content[0] as Inline.AutoNumber
        assertEquals(AutoNumberKind.FOOTNOTE, an.kind)
        assertEquals(com.fastreader.app.core.doc.NumberType.CIRCLED_DIGIT, an.numberType)

        val pic = (c[1] as Inline.Object)
        assertEquals(40, pic.start)
        val pp = pic.obj as Picture
        assertEquals("image1", pp.imageId)
        assertEquals(0, pp.cropRight) // 원본 전체 = 자르지 않음
        assertEquals(30000, pp.imageWidth)
        assertEquals(1, pp.effect)
        assertEquals(10, pp.brightness)
        assertEquals(8000, pp.layout.width)
        assertEquals(com.fastreader.app.core.doc.VertRel.PAPER, pp.layout.vertRelTo)
        assertEquals(com.fastreader.app.core.doc.HorzRel.PAGE, pp.layout.horzRelTo)
        assertEquals(com.fastreader.app.core.doc.ObjAlign.END, pp.layout.horzAlign)
        assertEquals(com.fastreader.app.core.doc.TextWrap.SQUARE, pp.layout.textWrap)
        assertEquals("설명", pp.layout.description)
        assertEquals("그림 1", pp.caption!!.paragraphs[0].plainText)
        assertEquals(3, pp.caption!!.side)

        val eq = (c[2] as Inline.Object).obj as com.fastreader.app.core.doc.Equation
        assertEquals("a over b", eq.script)
        assertEquals(1200, eq.baseSize)
        assertEquals(0xFF0000, eq.color)
        assertEquals(Inline.Ruby(56, 0, "본말", "덧말", true), c[3])
        assertEquals(Inline.Text(64, "③", 0), c[4])
        // switch: 지원하지 않는 case(차트) 대신 default(OLE) 하나만
        val ole = (c[5] as Inline.Object)
        assertEquals(72, ole.start)
        assertEquals(ShapeKind.OLE, (ole.obj as Shape).kind)
        assertEquals(Inline.Text(80, "끝", 0), c[6])
        assertEquals(7, c.size)
    }

    @Test
    fun containerChildrenUseTransforms() {
        val sec = section(
            """<hp:p><hp:run charPrIDRef="0"><hp:container zOrder="4"><hp:offset x="0" y="0"/><hp:orgSz width="17372" height="3250"/><hp:curSz width="0" height="0"/>""" +
                """<hp:renderingInfo><hc:transMatrix e1="1" e2="0" e3="0" e4="0" e5="1" e6="0"/><hc:scaMatrix e1="1" e2="0" e3="0" e4="0" e5="1" e6="0"/><hc:rotMatrix e1="1" e2="0" e3="0" e4="0" e5="1" e6="0"/></hp:renderingInfo>""" +
                """<hp:pic><hp:offset x="8650" y="300"/><hp:orgSz width="20400" height="6900"/><hp:curSz width="0" height="0"/><hp:renderingInfo><hc:transMatrix e1="1" e2="0" e3="8650" e4="0" e5="1" e6="300"/><hc:scaMatrix e1="1" e2="0" e3="0" e4="0" e5="1" e6="0"/><hc:rotMatrix e1="1" e2="0" e3="0" e4="0" e5="1" e6="0"/><hc:scaMatrix e1="0.427549" e2="0" e3="0" e4="0" e5="0.427536" e6="0"/><hc:rotMatrix e1="1" e2="0" e3="0" e4="0" e5="1" e6="0"/></hp:renderingInfo><hc:img binaryItemIDRef="image2"/></hp:pic>""" +
                """<hp:line><hp:offset x="3050" y="1575"/><hp:orgSz width="10200" height="200"/><hp:renderingInfo><hc:transMatrix e1="1" e2="0" e3="3050" e4="0" e5="1" e6="1575"/></hp:renderingInfo><hc:startPt x="0" y="0"/><hc:endPt x="10200" y="200"/></hp:line>""" +
                """<hp:sz width="17372" height="3250"/><hp:pos treatAsChar="0" vertRelTo="PAPER" horzRelTo="PAPER" vertOffset="15300" horzOffset="11314"/></hp:container></hp:run></hp:p>""",
        )
        val doc = parse(zip("Contents/section0.xml" to sec))
        val box = (doc.sections[0].paragraphs[0].content[0] as Inline.Object).obj as Shape
        assertEquals(ShapeKind.CONTAINER, box.kind)
        assertEquals(17372, box.layout.width)
        assertEquals(15300, box.layout.vertOffset)
        val pic = box.children[0] as Picture
        assertEquals(8650, pic.layout.horzOffset)
        assertEquals(300, pic.layout.vertOffset)
        assertEquals(8722, pic.layout.width) // 20400 × 0.427549
        val line = box.children[1] as Shape
        assertEquals(ShapeKind.LINE, line.kind)
        assertEquals(3050, line.layout.horzOffset)
        assertEquals(listOf(0, 0, 10200, 200), line.points)
    }

    // ------------------------------------------------------------ 머리 정보

    @Test
    fun headerUnitsAndSwitch() {
        val head = header(
            """<hh:fontfaces><hh:fontface lang="HANGUL"><hh:font id="0" face="함초롬돋움"/><hh:font id="1" face="함초롬바탕"/></hh:fontface><hh:fontface lang="LATIN"><hh:font id="0" face="Arial"/></hh:fontface></hh:fontfaces>""" +
                """<hh:borderFills><hh:borderFill id="2"><hh:slash type="CENTER"/><hh:backSlash type="NONE"/><hh:leftBorder type="DOUBLE_SLIM" width="0.4 mm" color="#FF0000"/><hh:rightBorder type="NONE" width="0.1 mm" color="#000000"/><hh:topBorder type="SOLID" width="0.12 mm" color="#000000"/><hh:bottomBorder type="DASH" width="0.12 mm" color="#000000"/><hh:diagonal type="SOLID" width="0.12 mm" color="#0000FF"/><hc:fillBrush><hc:winBrush faceColor="#E3DCC1" hatchColor="#999999" alpha="0"/></hc:fillBrush></hh:borderFill></hh:borderFills>""" +
                """<hh:charProperties><hh:charPr id="7" height="1200" textColor="#112233" shadeColor="none" symMark="DOT_ABOVE" borderFillIDRef="2"><hh:fontRef hangul="1" latin="0" hanja="1" japanese="1" other="1" symbol="1" user="1"/><hh:ratio hangul="90" latin="100" hanja="100" japanese="100" other="100" symbol="100" user="100"/><hh:spacing hangul="-5" latin="0" hanja="0" japanese="0" other="0" symbol="0" user="0"/><hh:relSz hangul="100" latin="100" hanja="100" japanese="100" other="100" symbol="100" user="100"/><hh:offset hangul="0" latin="0" hanja="0" japanese="0" other="0" symbol="0" user="0"/><hh:bold/><hh:underline type="BOTTOM" shape="DASH" color="#00FF00"/><hh:strikeout shape="3D" color="#000000"/><hh:supscript/></hh:charPr>""" +
                """<hh:charPr id="8" height="1000" textColor="#000000" shadeColor="#FFFF00"><hh:strikeout shape="SOLID" color="#FF0000"/><hh:italic/></hh:charPr></hh:charProperties>""" +
                """<hh:paraProperties><hh:paraPr id="1"><hh:align horizontal="CENTER"/><hh:heading type="NONE" idRef="0" level="0"/><hh:breakSetting keepWithNext="1" pageBreakBefore="0"/>""" +
                """<hp:switch><hp:case hp:required-namespace="http://www.hancom.co.kr/hwpml/2016/HwpUnitChar"><hh:margin><hc:intent value="-1310" unit="HWPUNIT"/><hc:left value="1000" unit="HWPUNIT"/><hc:right value="0" unit="HWPUNIT"/><hc:prev value="500" unit="HWPUNIT"/><hc:next value="80" unit="CHAR"/></hh:margin><hh:lineSpacing type="FIXED" value="1300" unit="HWPUNIT"/></hp:case>""" +
                """<hp:default><hh:margin><hc:intent value="-2620" unit="HWPUNIT"/><hc:left value="2000" unit="HWPUNIT"/><hc:right value="0" unit="HWPUNIT"/><hc:prev value="1000" unit="HWPUNIT"/><hc:next value="161" unit="HWPUNIT"/></hh:margin><hh:lineSpacing type="FIXED" value="2600" unit="HWPUNIT"/></hp:default></hp:switch>""" +
                """<hh:border borderFillIDRef="2" offsetLeft="10" offsetRight="20" offsetTop="30" offsetBottom="40"/></hh:paraPr>""" +
                // switch 없는 옛 형식: 2배 값
                """<hh:paraPr id="2"><hh:align horizontal="DISTRIBUTE_SPACE"/><hh:margin><hc:intent value="0"/><hc:left value="3000"/><hc:right value="0"/><hc:prev value="0"/><hc:next value="1000"/></hh:margin><hh:lineSpacing type="PERCENT" value="180"/></hh:paraPr>""" +
                // 개요 8 수준은 hp10 case 에 있다
                """<hh:paraPr id="3"><hp:switch><hp:case hp:required-namespace="http://www.hancom.co.kr/hwpml/2016/paragraph"><hh:heading type="OUTLINE" idRef="0" level="8"/></hp:case><hp:default><hh:heading type="NONE" idRef="0" level="0"/></hp:default></hp:switch></hh:paraPr></hh:paraProperties>""" +
                """<hh:numberings><hh:numbering id="1" start="0"><hh:paraHead start="1" level="1" align="LEFT" numFormat="DIGIT" charPrIDRef="4294967295">^1.</hh:paraHead><hh:paraHead start="3" level="3" numFormat="HANGUL_SYLLABLE" charPrIDRef="7">(^3)</hh:paraHead></hh:numbering></hh:numberings>""" +
                """<hh:bullets><hh:bullet id="1" char="●" useImage="0"><hh:paraHead level="0" charPrIDRef="8"/></hh:bullet></hh:bullets>""" +
                """<hh:styles><hh:style id="0" type="PARA" name="바탕글" engName="Normal" paraPrIDRef="1" charPrIDRef="7"/></hh:styles>""",
        )
        val doc = parse(zip("Contents/section0.xml" to section("<hp:p/>"), "Contents/header.xml" to head))
        val cs = doc.charShapes[7]!!
        assertEquals(listOf("함초롬바탕", "Arial", "", "", "", "", ""), cs.fontNames)
        assertEquals(1200, cs.baseSize)
        assertEquals(0x112233, cs.textColor)
        assertNull(cs.shadeColor)
        assertEquals(90, cs.ratio(com.fastreader.app.core.doc.Lang.HANGUL))
        assertEquals(-5, cs.spacing(com.fastreader.app.core.doc.Lang.HANGUL))
        assertTrue(cs.bold && cs.superscript && !cs.italic)
        assertEquals(com.fastreader.app.core.doc.UnderlinePos.BOTTOM, cs.underline)
        assertEquals(LineType.DASH, cs.underlineStyle)
        assertEquals(0x00FF00, cs.underlineColor)
        assertFalse("shape=3D 는 취소선 없음", cs.strike)
        assertEquals(1, cs.emphasis)
        assertEquals(2, cs.borderFillId)
        val cs8 = doc.charShapes[8]!!
        assertTrue(cs8.strike && cs8.italic)
        assertEquals(0xFF0000, cs8.strikeColor)
        assertEquals(0xFFFF00, cs8.shadeColor)

        val p1 = doc.paraShapes[1]!!
        assertEquals(Align.CENTER, p1.align)
        assertEquals(-1310, p1.indent)
        assertEquals(1000, p1.marginLeft)
        assertEquals(500, p1.spacingBefore)
        assertEquals(80, p1.spacingAfter)
        assertEquals(LineSpacingType.FIXED, p1.lineSpacingType)
        assertEquals(1300, p1.lineSpacing)
        assertTrue(p1.keepWithNext)
        assertEquals(2, p1.borderFillId)
        assertEquals(40, p1.borderOffsetBottom)
        val p2 = doc.paraShapes[2]!!
        assertEquals(Align.DISTRIBUTE_SPACE, p2.align)
        assertEquals(1500, p2.marginLeft)
        assertEquals(500, p2.spacingAfter)
        assertEquals(180, p2.lineSpacing)
        val p3 = doc.paraShapes[3]!!
        assertEquals(HeadingType.OUTLINE, p3.headingType)
        assertEquals(8, p3.headingLevel)

        val bf = doc.borderFills[2]!!
        assertEquals(LineType.DOUBLE, bf.left.type)
        assertEquals(0.4f, bf.left.widthMm, 0.0001f)
        assertEquals(0xFF0000, bf.left.color)
        assertFalse(bf.right.visible)
        assertEquals(LineType.DASH, bf.bottom.type)
        assertEquals(0x0000FF, bf.diagonal!!.color)
        assertNull(bf.backDiagonal)
        assertEquals(0xE3DCC1, bf.fillColor)
        assertEquals(-1, bf.patternType)

        val n = doc.numberings[1]!!
        assertEquals(3, n.levels.size)
        assertEquals("^1.", n.levels[0].format)
        assertEquals(-1, n.levels[0].charShapeId)
        assertEquals("", n.levels[1].format)
        assertEquals("(^3)", n.levels[2].format)
        assertEquals(3, n.levels[2].startNumber)
        assertEquals(com.fastreader.app.core.doc.NumberType.HANGUL_SYLLABLE, n.levels[2].numberType)
        assertEquals("●", doc.bullets[1]!!.char)
        assertEquals(8, doc.bullets[1]!!.charShapeId)
        assertEquals("바탕글", doc.styles[0]!!.name)
        assertEquals(7, doc.styles[0]!!.charShapeId)
    }

    // ------------------------------------------------------------ 패키지

    @Test
    fun entryOrderAndImages() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)
        val hpf = """<opf:package xmlns:opf="http://www.idpf.org/2007/opf/"><opf:manifest>""" +
            """<opf:item id="section1" href="Contents/section1.xml" media-type="application/xml"/><opf:item id="section0" href="Contents/section0.xml" media-type="application/xml"/>""" +
            """<opf:item id="pic.A" href="BinData/image1.png" media-type="image/png"/><opf:item id="image2" href="BinData/image2.jpg" media-type="image/jpg"/></opf:manifest>""" +
            """<opf:spine><opf:itemref idref="section1"/><opf:itemref idref="section0"/></opf:spine></opf:package>"""
        val s0 = section("""<hp:p><hp:run charPrIDRef="0"><hp:t>둘째</hp:t><hp:pic><hp:sz width="10" height="10"/><hc:img binaryItemIDRef="pic.A"/></hp:pic></hp:run></hp:p>""")
        val s1 = section("""<hp:p><hp:run charPrIDRef="0"><hp:t>첫째</hp:t><hp:pic><hp:sz width="10" height="10"/><hc:img binaryItemIDRef="image2"/></hp:pic></hp:run></hp:p>""")
        val head = header("""<hh:charProperties><hh:charPr id="0" height="1500"/></hh:charProperties>""")
        // 그림과 구역이 매니페스트/머리 정보보다 먼저 온다
        val data = zip(
            "BinData/image1.png" to png, "Contents/section0.xml" to s0, "BinData/image2.jpg" to jpg,
            "Contents/section1.xml" to s1, "Contents/header.xml" to head, "Contents/content.hpf" to hpf,
        )
        val stored = HashMap<String, ByteArray>()
        val doc = parse(data, stored)
        assertEquals(listOf("첫째", "둘째"), doc.sections.map { it.paragraphs[0].plainText.take(2) })
        assertEquals(1500, doc.charShapes[0]!!.baseSize)
        // 매니페스트보다 먼저 온 그림은 파일 이름으로 저장되고, 매니페스트 ID 로도 찾을 수 있다
        assertEquals(png.toList(), stored["image1"]!!.toList())
        val pa = (doc.sections[1].paragraphs[0].content[1] as Inline.Object).obj as Picture
        assertEquals("pic_A", pa.imageId)
        assertEquals("img/image1.png", doc.images[pa.imageId]!!.path)
        val pb = (doc.sections[0].paragraphs[0].content[1] as Inline.Object).obj as Picture
        assertEquals("image2", pb.imageId)
        assertEquals("image/jpeg", doc.images["image2"]!!.mimeType)
        assertEquals(jpg.toList(), stored["image2"]!!.toList())

        // 매니페스트가 먼저면 처음부터 매니페스트 ID 로 저장
        val stored2 = HashMap<String, ByteArray>()
        parse(zip("Contents/content.hpf" to hpf, "BinData/image1.png" to png, "Contents/section0.xml" to s0), stored2)
        assertEquals(setOf("pic_A"), stored2.keys)
    }

    @Test
    fun encryptedDocument() {
        val manifest = """<odf:manifest xmlns:odf="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"><odf:file-entry odf:full-path="Contents/section0.xml" odf:media-type="application/xml"><odf:encryption-data odf:checksum-type="SHA1/1K" odf:checksum="x"><odf:algorithm odf:algorithm-name="AES"/></odf:encryption-data></odf:file-entry></odf:manifest>"""
        val garbage = ByteArray(2000) { (it * 7919 % 251).toByte() }
        try {
            parse(zip("mimetype" to "application/hwp+zip", "Contents/section0.xml" to garbage, "Contents/header.xml" to garbage, "Preview/PrvText.txt" to garbage, "META-INF/manifest.xml" to manifest))
            fail("expected EncryptedDocumentException")
        } catch (e: EncryptedDocumentException) {
        }
    }

    @Test
    fun previewFallbackAndBrokenInput() {
        val doc = parse(zip("mimetype" to "application/hwp+zip", "Contents/section0.xml" to "<broken", "Preview/PrvText.txt" to "미리보기 문장\r\n둘째 줄"))
        assertNotNull(doc.notice)
        assertEquals(listOf("미리보기 문장", "둘째 줄"), doc.sections[0].paragraphs.map { it.plainText })

        for (bad in listOf(ByteArray(0), "not a zip".toByteArray(), ByteArray(4096) { it.toByte() })) {
            try {
                parse(bad)
                fail("expected DocumentException")
            } catch (e: CorruptDocumentException) {
            }
        }
    }

    @Test
    fun doctypeAndEntitiesAreRejected() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/hostname"><!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>""" +
            """<hs:sec xmlns:hs="s" xmlns:hp="p"><hp:p><hp:run><hp:t>&e;&b;</hp:t></hp:run></hp:p></hs:sec>"""
        try {
            val doc = parse(zip("Contents/section0.xml" to xxe, "Preview/PrvText.txt" to "대체"))
            assertEquals("대체", doc.sections[0].paragraphs[0].plainText)
        } catch (e: DocumentException) {
        }
        // 주석/처리 지시가 앞에 있어도 정상 문서는 읽는다
        val ok = "﻿<?xml version=\"1.0\"?><!-- c --><?pi x?>" + section("""<hp:p><hp:run charPrIDRef="0"><hp:t>정상</hp:t></hp:run></hp:p>""").substringAfter("?>")
        assertEquals("정상", parse(zip("Contents/section0.xml" to ok)).sections[0].paragraphs[0].plainText)
    }

    @Test
    fun truncatedSectionKeepsReadParagraphs() {
        val full = section(
            """<hp:p><hp:run charPrIDRef="0"><hp:t>하나</hp:t></hp:run></hp:p><hp:p><hp:run charPrIDRef="0"><hp:t>둘</hp:t></hp:run></hp:p>""" +
                """<hp:p><hp:run charPrIDRef="0"><hp:t>셋은 잘린다</hp:t></hp:run></hp:p>""",
        )
        val cut = full.substring(0, full.indexOf("잘린다"))
        val doc = parse(zip("Contents/section0.xml" to cut))
        assertNotNull(doc.notice)
        val texts = doc.sections[0].paragraphs.map { it.plainText }
        // 잘린 셋째 문단은 SAX 가 넘겨준 데까지만 남는다
        assertEquals(listOf("하나", "둘"), texts.take(2))
        assertTrue(texts.size <= 3)
    }

    @Test
    fun absurdValuesAreClamped() {
        val sec = section(
            """<hp:p paraPrIDRef="4294967295"><hp:run charPrIDRef="abc"><hp:tbl rowCnt="2000000000" colCnt="-3"><hp:sz width="999999999999" height="-1"/>""" +
                """<hp:tr><hp:tc><hp:cellAddr colAddr="2000000000" rowAddr="-5"/><hp:cellSpan colSpan="2000000000" rowSpan="0"/><hp:cellSz width="1e9" height="NaN"/></hp:tc></hp:tr></hp:tbl>""" +
                """<hp:t>x</hp:t></hp:run><hp:linesegarray><hp:lineseg textpos="9" vertpos="1"/><hp:lineseg textpos="3" vertpos="2"/></hp:linesegarray></hp:p>""",
        )
        val doc = parse(zip("Contents/section0.xml" to sec))
        val p = doc.sections[0].paragraphs[0]
        assertEquals(-1, p.paraShapeId)
        val t = (p.content[0] as Inline.Object).obj as Table
        assertTrue(t.rowCount in 1..65535 && t.colCount in 1..65535)
        for (c in t.cells) assertTrue(c.row + c.rowSpan <= t.rowCount && c.col + c.colSpan <= t.colCount)
        assertTrue(t.layout.width <= 20_000_000)
        assertEquals(Inline.Text(8, "x", 0), p.content[1])
        // 줄 위치는 단조 증가로 고친다
        assertEquals(listOf(9, 9), p.lines.map { it.textStart })
    }
}
