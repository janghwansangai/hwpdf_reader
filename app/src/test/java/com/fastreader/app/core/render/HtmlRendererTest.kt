package com.fastreader.app.core.render

import com.fastreader.app.core.ByteArraySource
import com.fastreader.app.core.doc.CharShape
import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.LineSeg
import com.fastreader.app.core.doc.MasterPage
import com.fastreader.app.core.doc.MasterPageType
import com.fastreader.app.core.doc.PageDef
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.ParaShape
import com.fastreader.app.core.doc.RichDocument
import com.fastreader.app.core.doc.Section
import com.fastreader.app.core.hwp.HwpRichParser
import com.fastreader.app.core.hwpx.HwpxRichParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlRendererTest {

    private fun sample(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("samples/$name")!!.use { it.readBytes() }

    private fun parse(name: String): RichDocument =
        if (name.endsWith(".hwpx")) HwpxRichParser.parse(sample(name).inputStream(), null)
        else HwpRichParser.parse(ByteArraySource(sample(name)), null)

    private fun doc(vararg texts: String): RichDocument {
        val paras = texts.mapIndexed { i, t ->
            Paragraph(
                0, content = listOf(Inline.Text(0, t, 0)),
                lines = listOf(LineSeg(0, i * 2000, 1000, 1000, 850, 600, 0, 40000, 0x60000)),
            )
        }
        return RichDocument(
            charShapes = mapOf(0 to CharShape.DEFAULT), paraShapes = mapOf(0 to ParaShape.DEFAULT), borderFills = emptyMap(),
            numberings = emptyMap(), bullets = emptyMap(), styles = emptyMap(),
            sections = listOf(Section(PageDef(), paragraphs = paras)), images = emptyMap(),
        )
    }

    @Test
    fun allSamplesRenderInBothModes() {
        for (name in listOf("table.hwp", "distribution.hwp", "merging-cell.hwp", "table.hwpx", "rect-in-para.hwpx")) {
            val r = HtmlRenderer(parse(name))
            assertTrue(name, r.pageCount >= 1)
            val page = r.shellHtml(ViewMode.PAGE)
            val flow = r.shellHtml(ViewMode.FLOW, RenderOptions(darkMode = true))
            assertTrue(page.contains("data-mode=\"page\"") && page.contains("window.FR = {"))
            // 원본 보기의 가운데/오른쪽 정렬 줄
            assertTrue(page.contains(".l.ac{text-align:center}") && page.contains(".l.ar{text-align:right}"))
            assertTrue(flow.contains("data-mode=\"flow\"") && flow.contains("class=\"dk\""))
            for (i in 0 until r.pageCount) assertFalse(r.pageHtml(i).contains("err\""))
            for (i in 0 until r.flowChunkCount) assertTrue(r.flowChunkHtml(i).isNotEmpty())
        }
    }

    @Test
    fun tableCellsAreRealTablesInReadingMode() {
        val r = HtmlRenderer(parse("table.hwp"))
        val flow = r.flowChunkHtml(0)
        assertTrue(flow.contains("<table"))
        assertTrue(flow.contains("ABC") && flow.contains("STR"))
        assertTrue(r.pageHtml(0).contains("class=\"td"))
    }

    @Test
    fun searchFindsTextAndPage() {
        val r = HtmlRenderer(doc("첫째 문장입니다", "둘째 문장, 문장"))
        val hits = r.search("문장")
        assertEquals(3, hits.size)
        assertEquals("p1", hits[1].paraKey)
        assertEquals(3, hits[1].offset)
        assertEquals(0, hits[1].page)
        assertTrue(r.search("  ").isEmpty())
        assertTrue(r.plainText().startsWith("첫째 문장입니다\n둘째"))
    }

    @Test
    fun documentTextIsEscaped() {
        val r = HtmlRenderer(doc("<script>alert(1)</script> & \"q\""))
        val page = r.pageHtml(0)
        val flow = r.flowChunkHtml(0)
        for (h in listOf(page, flow)) {
            assertFalse(h.contains("<script>"))
            assertTrue(h.contains("&lt;script&gt;"))
        }
        // 쉘 안 조각도 스크립트를 끝내지 못한다
        assertFalse(r.standaloneHtml(ViewMode.PAGE).contains("</script> &"))
    }

    @Test
    fun hyperlinksOnlyAllowSafeSchemes() {
        assertEquals("https://example.com/a", Html.safeUrl(" https://example.com/a "))
        assertEquals(null, Html.safeUrl("javascript:alert(1)"))
        assertEquals(null, Html.safeUrl("file:///etc/passwd"))
    }

    @Test
    fun equationScriptBecomesReadableHtml() {
        val sb = StringBuilder()
        EquationHtml.write(sb, "{a+b} over 2 times sqrt {x^2} + alpha")
        val h = sb.toString()
        assertTrue(h.contains("class=\"ef\""))
        assertTrue(h.contains("×") && h.contains("√") && h.contains("α") && h.contains("<sup>"))
        val ab = StringBuilder()
        EquationHtml.write(ab, "(a+b)^2 = a^2 + 2ab + b^2")
        assertEquals("(<i>a</i>+<i>b</i>)<sup>2</sup>=<i>a</i><sup>2</sup>+2<i>ab</i>+<i>b</i><sup>2</sup>", ab.toString())
        val bad = StringBuilder()
        EquationHtml.write(bad, "{{{{ <b> over")
        assertFalse(bad.toString().contains("<b>"))
    }

    @Test
    fun whiteTextStaysReadableInReadingMode() {
        val base = doc("흰 글자")
        val white = CharShape.DEFAULT.copy(textColor = 0xFFFFFF)
        val para = base.sections[0].paragraphs[0]
        val d = RichDocument(
            charShapes = mapOf(0 to white), paraShapes = base.paraShapes, borderFills = emptyMap(),
            numberings = emptyMap(), bullets = emptyMap(), styles = emptyMap(),
            sections = listOf(Section(PageDef(), paragraphs = listOf(para))), images = emptyMap(),
        )
        val r = HtmlRenderer(d)
        // 밝은 읽기 모드: 기본 글자색, 어두운 칸 안에서만 흰색
        val flow = r.shellHtml(ViewMode.FLOW)
        assertTrue(flow.contains(".dkb .c0{color:#ffffff}"))
        assertFalse(Regex("(?<!dkb )\\.c0\\{[^}]*color:#ffffff").containsMatchIn(flow))
        // 원본 보기는 원래 색 그대로
        assertTrue(Regex("\\.c0\\{[^}]*color:#ffffff").containsMatchIn(r.shellHtml(ViewMode.PAGE)))
    }

    @Test
    fun masterPageIsDrawnOnEveryPage() {
        val base = doc("본문")
        val mp = base.sections[0].paragraphs[0].let { p ->
            Paragraph(0, content = listOf(Inline.Text(0, "바탕쪽글자", 0)), lines = p.lines)
        }
        val d = RichDocument(
            charShapes = base.charShapes, paraShapes = base.paraShapes, borderFills = emptyMap(),
            numberings = emptyMap(), bullets = emptyMap(), styles = emptyMap(),
            sections = listOf(base.sections[0].copy(masterPages = listOf(MasterPage(MasterPageType.BOTH, listOf(mp))))),
            images = emptyMap(),
        )
        val page = HtmlRenderer(d).pageHtml(0)
        assertTrue(page.contains("바탕쪽글자") && page.indexOf("바탕쪽글자") < page.indexOf("본문"))
        // 바탕쪽 글자는 검색 대상(본문)이 아니다
        assertTrue(HtmlRenderer(d).search("바탕쪽").isEmpty())
    }

    @Test
    fun pageNumbersAndKeysAreConsistent() {
        val r = HtmlRenderer(parse("distribution.hwp"))
        val shell = r.shellHtml(ViewMode.PAGE)
        assertTrue(shell.contains("FR_META={keys:[0"))
        assertEquals(r.pageCount, Regex("class=\"pg\"").findAll(shell).count())
    }
}
