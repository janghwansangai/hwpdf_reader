package com.fastreader.app.core

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * HWPX(OWPML, zip + xml) 본문 텍스트 추출기.
 * Contents/sectionN.xml 을 SAX 로 스트리밍 파싱하므로 큰 문서도 메모리를 적게 쓴다.
 * 본문을 읽지 못하면 Preview/PrvText.txt 로 대체한다.
 */
object HwpxParser {

    private val SECTION_RE = Regex("Contents/section(\\d+)\\.xml", RegexOption.IGNORE_CASE)

    fun parse(input: InputStream): TextDocument = try {
        parseInternal(input)
    } catch (e: RuntimeException) {
        throw CorruptDocumentException("HWPX 파일이 손상되었습니다.", e)
    }

    private fun parseInternal(input: InputStream): TextDocument {
        val sections = sortedMapOf<Int, List<TextBlock>>()
        var preview: String? = null
        var encrypted = false
        var sawZipEntry = false
        var sectionError: Throwable? = null

        try {
            ZipInputStream(input.buffered()).use { zis ->
                while (true) {
                    val entry = try {
                        zis.nextEntry
                    } catch (e: ZipException) {
                        if (!sawZipEntry) throw CorruptDocumentException("HWPX(zip) 형식이 아닙니다.", e)
                        sectionError = e
                        null
                    } catch (e: IllegalArgumentException) {
                        // UTF-8 이 아닌 파일 이름 등 – 더 읽을 수 없으므로 여기까지
                        if (!sawZipEntry) throw CorruptDocumentException("HWPX 파일이 손상되었습니다.", e)
                        sectionError = e
                        null
                    } ?: break
                    sawZipEntry = true
                    val name = entry.name.replace('\\', '/').trimStart('/')
                    val m = SECTION_RE.matchEntire(name)
                    when {
                        m != null -> {
                            try {
                                sections[m.groupValues[1].toInt()] = parseSection(NonClosingStream(zis))
                            } catch (e: SAXException) {
                                // 암호화된 HWPX 는 섹션 내용이 XML 이 아니다.
                                sectionError = e
                            }
                        }
                        name.equals("Preview/PrvText.txt", ignoreCase = true) -> {
                            preview = readLimited(zis, 4 * 1024 * 1024).toString(Charsets.UTF_8)
                        }
                        name.equals("META-INF/manifest.xml", ignoreCase = true) -> {
                            val text = readLimited(zis, 1024 * 1024).toString(Charsets.UTF_8)
                            if (text.contains("encryption-data")) encrypted = true
                        }
                    }
                }
            }
        } catch (e: DocumentException) {
            throw e
        } catch (e: java.io.IOException) {
            if (sections.isEmpty() && preview == null) throw CorruptDocumentException("파일을 읽는 중 오류: ${e.message}", e)
            sectionError = e
        }

        if (!sawZipEntry) throw CorruptDocumentException("HWPX(zip) 형식이 아닙니다.")
        if (encrypted && sections.values.all { it.isEmpty() }) {
            throw EncryptedDocumentException("암호가 걸린 문서입니다.\n한글에서 암호를 해제한 뒤 다시 열어 주세요.")
        }

        val c = BlockCollector()
        sections.values.forEach { blocks -> blocks.forEach { c.add(it.text, it.kind) } }
        val doc = c.build(if (sectionError != null) "문서 일부를 해석하지 못했습니다." else null)
        if (!doc.isEmpty) return doc

        preview?.let { p ->
            val pc = BlockCollector()
            p.split("\r\n", "\n").forEach { pc.add(it) }
            val pd = pc.build("본문을 해석하지 못해 미리보기 텍스트만 표시합니다.")
            if (!pd.isEmpty) return pd
        }
        if (sections.isEmpty()) throw UnsupportedDocumentException("한글(HWPX) 문서가 아니거나 본문이 없습니다.")
        return doc
    }

    fun parseSection(input: InputStream): List<TextBlock> {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        trySet { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        trySet { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        trySet { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val handler = SectionHandler()
        factory.newSAXParser().parse(InputSource(input), handler)
        handler.finish()
        return handler.blocks
    }

    private inline fun trySet(block: () -> Unit) {
        try { block() } catch (_: Exception) {}
    }

    private sealed class Node {
        class Para : Node() { val sb = StringBuilder() }
        class Cell : Node() { val sb = StringBuilder() }
        class Table : Node() { var row: MutableList<String>? = null }
    }

    private class SectionHandler : DefaultHandler() {
        val blocks = ArrayList<TextBlock>()
        private val stack = ArrayList<Node>()
        private var inText = 0

        private fun local(qName: String, localName: String?): String {
            val n = if (!localName.isNullOrEmpty()) localName else qName
            val i = n.indexOf(':')
            return if (i >= 0) n.substring(i + 1) else n
        }

        private fun para(): Node.Para? = stack.lastOrNull() as? Node.Para

        /** 텍스트를 가장 가까운 셀 또는 본문으로 보낸다 */
        private fun deliver(text: String, kind: BlockKind) {
            for (i in stack.indices.reversed()) {
                when (val n = stack[i]) {
                    is Node.Cell -> {
                        if (text.isBlank()) return
                        if (n.sb.isNotEmpty()) n.sb.append('\n')
                        n.sb.append(text)
                        return
                    }
                    is Node.Para -> continue
                    is Node.Table -> continue
                }
            }
            blocks.add(TextBlock(text, kind))
        }

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
            when (local(qName, localName)) {
                "p" -> stack.add(Node.Para())
                "t" -> inText++
                "tab" -> if (inText > 0) para()?.sb?.append('\t')
                "lineBreak" -> if (inText > 0) para()?.sb?.append('\n')
                "fwSpace", "nbSpace" -> if (inText > 0) para()?.sb?.append(' ')
                "tbl" -> {
                    // 표 앞에 있던 문단 텍스트를 먼저 내보내 순서를 유지
                    val p = para()
                    if (p != null && p.sb.isNotBlank()) {
                        val text = p.sb.toString()
                        p.sb.setLength(0)
                        stack.removeAt(stack.size - 1)
                        deliver(text, BlockKind.PARAGRAPH)
                        stack.add(p)
                    }
                    stack.add(Node.Table())
                }
                "tr" -> (stack.lastOrNull() as? Node.Table)?.row = ArrayList()
                "tc" -> stack.add(Node.Cell())
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (local(qName, localName)) {
                "t" -> if (inText > 0) inText--
                "p" -> {
                    val idx = stack.indexOfLast { it is Node.Para }
                    if (idx >= 0) {
                        val p = stack[idx] as Node.Para
                        while (stack.size > idx) stack.removeAt(stack.size - 1)
                        val text = p.sb.toString()
                        if (text.isNotBlank() || stack.none { it is Node.Cell }) deliver(text, BlockKind.PARAGRAPH)
                    }
                }
                "tc" -> {
                    val idx = stack.indexOfLast { it is Node.Cell }
                    if (idx >= 0) {
                        val cell = stack[idx] as Node.Cell
                        while (stack.size > idx) stack.removeAt(stack.size - 1)
                        (stack.lastOrNull() as? Node.Table)?.row?.add(cell.sb.toString().trim().replace('\n', ' '))
                    }
                }
                "tr" -> {
                    val t = stack.lastOrNull() as? Node.Table ?: return
                    val row = t.row ?: return
                    t.row = null
                    if (row.all { it.isEmpty() }) return
                    val text = row.joinToString(TABLE_CELL_SEPARATOR)
                    stack.removeAt(stack.size - 1)
                    deliver(text, BlockKind.TABLE_ROW)
                    stack.add(t)
                }
                "tbl" -> {
                    val idx = stack.indexOfLast { it is Node.Table }
                    if (idx >= 0) while (stack.size > idx) stack.removeAt(stack.size - 1)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText > 0) para()?.sb?.append(ch, start, length)
        }

        fun finish() {
            // 닫히지 않은 문단(잘린 파일) 처리
            stack.filterIsInstance<Node.Para>().forEach { if (it.sb.isNotBlank()) blocks.add(TextBlock(it.sb.toString())) }
            stack.clear()
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, minOf(n, limit - out.size()))
        }
        return out.toByteArray()
    }

    /** SAX 파서가 zip 스트림을 닫아버리지 않도록 막는다. */
    private class NonClosingStream(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }
}
