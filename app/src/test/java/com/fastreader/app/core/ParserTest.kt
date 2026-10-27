package com.fastreader.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ParserTest {

    private fun sample(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("samples/$name")!!.use { it.readBytes() }

    private fun TextDocument.rows() = blocks.filter { it.kind == BlockKind.TABLE_ROW }.map { it.text }

    @Test
    fun hwpTableRowsAreGrouped() {
        val doc = HwpParser.parse(ByteArraySource(sample("table.hwp")))
        assertEquals("표", doc.blocks.first().text)
        assertEquals(
            listOf(
                listOf("ABC 123", "DEF", "GHI"),
                listOf("LMN", "OPQ", "STR"),
                listOf("UVM", "123", "456"),
            ).map { it.joinToString(TABLE_CELL_SEPARATOR) },
            doc.rows(),
        )
    }

    @Test
    fun hwpMergedCellsKeepRowOrder() {
        val rows = HwpParser.parse(ByteArraySource(sample("merging-cell.hwp"))).rows()
        assertEquals(7, rows.size)
        assertTrue(rows[0].startsWith("0,0"))
        assertTrue(rows[6].startsWith("6,0"))
    }

    @Test
    fun hwpDistributionDocumentIsDecrypted() {
        val doc = HwpParser.parse(ByteArraySource(sample("distribution.hwp")))
        assertNotNull(doc.notice)
        assertTrue(doc.blocks.any { it.text.contains("2025년") })
    }

    @Test
    fun hwpxTableAndParagraphOrder() {
        val doc = HwpxParser.parse(sample("table.hwpx").inputStream())
        assertEquals("C반 기말고사", doc.blocks.first().text)
        val rows = doc.rows()
        assertEquals(listOf("이름", "국어", "영어", "수학").joinToString(TABLE_CELL_SEPARATOR), rows.first())
        assertTrue(rows.any { it.startsWith("개똥이") })
    }

    @Test
    fun hwpxTextInsideShapes() {
        val doc = HwpxParser.parse(sample("rect-in-para.hwpx").inputStream())
        assertTrue(doc.blocks.any { it.text.contains("사각형") })
        assertTrue(doc.blocks.any { it.text.startsWith("Start.") })
    }

    @Test
    fun hwpxFallsBackToPreviewText() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/hwp+zip".toByteArray())
            z.putNextEntry(ZipEntry("Contents/section0.xml")); z.write("<broken".toByteArray())
            z.putNextEntry(ZipEntry("Preview/PrvText.txt")); z.write("미리보기 문장".toByteArray())
        }
        val doc = HwpxParser.parse(zip.toByteArray().inputStream())
        assertEquals("미리보기 문장", doc.blocks.single().text)
        assertNotNull(doc.notice)
    }

    @Test
    fun paraTextControlCharactersAreRemoved() {
        // "가" + 탭(8 wchar) + "나" + 표 컨트롤(11, 8 wchar) + "다" + 문단끝(13)
        val chars = intArrayOf(0xAC00, 9, 0, 0, 0, 0, 0, 0, 9, 0xB098, 11, 0, 0, 0, 0, 0, 0, 11, 0xB2E4, 13)
        val bytes = ByteArray(chars.size * 2)
        chars.forEachIndexed { i, c -> bytes[i * 2] = c.toByte(); bytes[i * 2 + 1] = (c shr 8).toByte() }
        assertEquals("가\t나다", HwpParser.decodeParaText(bytes, 0, bytes.size))
    }

    @Test
    fun detectsTypeByContentNotName() {
        val pdf = "%PDF-1.7\n".toByteArray()
        assertEquals(DocumentType.PDF, DocumentTypeDetector.detect(pdf, "download.bin", "application/octet-stream"))
        assertEquals(DocumentType.HWP, DocumentTypeDetector.detect(sample("table.hwp").copyOf(1024), "x.pdf", null))
        assertEquals(DocumentType.HWPX, DocumentTypeDetector.detect(sample("table.hwpx").copyOf(1024), null, null))
        assertEquals(DocumentType.HWPX, DocumentTypeDetector.detect(ByteArray(0), "보고서.HWPX", null))
        assertEquals(DocumentType.UNKNOWN, DocumentTypeDetector.detect("hello".toByteArray(), "a.txt", "text/plain"))
    }

    @Test
    fun hwp3IsReportedAsUnsupported() {
        val bytes = "HWP Document File V3.00 \u001a\u0001\u0002\u0003\u0004\u0005".toByteArray() + ByteArray(600)
        try {
            HwpParser.parse(ByteArraySource(bytes))
            fail()
        } catch (e: UnsupportedDocumentException) {
            assertTrue(e.message!!.contains("HWP 3.0"))
        }
    }

    /** 손상된 파일은 앱을 죽이거나 멈추게 하지 말고 DocumentException 으로 끝나야 한다. */
    @Test
    fun corruptedFilesFailGracefully() {
        val rnd = Random(42)
        for (name in listOf("table.hwp", "distribution.hwp", "table.hwpx")) {
            val original = sample(name)
            repeat(150) { round ->
                val data = if (round % 3 == 0) {
                    original.copyOf(rnd.nextInt(original.size)) // 잘린 파일
                } else {
                    original.copyOf().also { d -> repeat(1 + rnd.nextInt(20)) { d[rnd.nextInt(d.size)] = rnd.nextInt().toByte() } }
                }
                try {
                    if (name.endsWith(".hwpx")) HwpxParser.parse(data.inputStream())
                    else HwpParser.parse(ByteArraySource(data))
                } catch (_: DocumentException) {
                    // 기대한 결과
                } catch (e: java.io.IOException) {
                    // 스트림 오류도 ViewModel 에서 메시지로 처리된다
                } catch (e: Throwable) {
                    throw AssertionError("$name round $round: ${e.javaClass.name}: ${e.message}", e)
                }
            }
        }
    }
}
