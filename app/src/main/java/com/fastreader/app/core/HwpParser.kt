package com.fastreader.app.core

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * HWP 5.x 본문 텍스트 추출기.
 * - 일반/압축 문서: BodyText/SectionN
 * - 배포용 문서: ViewText/SectionN (AES-128 복호화)
 * - 표는 행 단위로 묶어 "셀 │ 셀" 형태로 보여준다.
 * - 본문 해석에 실패하면 PrvText(미리보기 텍스트)로 대체한다.
 */
object HwpParser {

    private const val TAG_BEGIN = 0x10
    private const val TAG_PARA_HEADER = TAG_BEGIN + 50
    private const val TAG_PARA_TEXT = TAG_BEGIN + 51
    private const val TAG_LIST_HEADER = TAG_BEGIN + 56
    private const val TAG_TABLE = TAG_BEGIN + 61
    private const val TAG_DISTRIBUTE_DOC_DATA = TAG_BEGIN + 12

    private const val FLAG_COMPRESSED = 1
    private const val FLAG_PASSWORD = 1 shl 1
    private const val FLAG_DISTRIBUTION = 1 shl 2

    fun parse(src: RandomAccessSource): TextDocument {
        val head = ByteArray(minOf(32L, src.size).toInt())
        src.readFully(0, head)
        if (String(head, Charsets.US_ASCII).startsWith("HWP Document File V3")) {
            throw UnsupportedDocumentException("한글 97 이전(HWP 3.0) 형식은 지원하지 않습니다.\n한글에서 HWP 5.0 이상 또는 HWPX로 다시 저장해 주세요.")
        }
        return try {
            parse(CompoundFile(src))
        } catch (e: RuntimeException) {
            // 손상된 파일에서 생길 수 있는 범위 오류 등은 모두 '손상된 문서'로 안내
            throw CorruptDocumentException("HWP 파일이 손상되었습니다.", e)
        }
    }

    fun parse(cf: CompoundFile): TextDocument {
        val fh = cf.read("FileHeader")
        if (fh.size < 40 || !String(fh, 0, 17, Charsets.US_ASCII).startsWith("HWP Document File")) {
            throw CorruptDocumentException("HWP 파일 헤더가 올바르지 않습니다.")
        }
        val props = le32(fh, 36)
        val compressed = props and FLAG_COMPRESSED != 0
        val distribution = props and FLAG_DISTRIBUTION != 0
        if (props and FLAG_PASSWORD != 0) {
            throw EncryptedDocumentException("암호가 걸린 문서입니다.\n한글에서 암호를 해제한 뒤 다시 열어 주세요.")
        }

        val storage = if (distribution) "ViewText" else "BodyText"
        val sections = cf.children(storage)
            .mapNotNull { n -> SECTION_RE.matchEntire(n)?.groupValues?.get(1)?.toIntOrNull()?.let { it to n } }
            .sortedBy { it.first }
            .map { "$storage/${it.second}" }

        val collector = BlockCollector()
        var bodyError: Throwable? = null
        for (path in sections) {
            try {
                var data = cf.read(path)
                if (distribution) data = decryptDistribution(data)
                if (compressed) data = inflate(data)
                extractSection(data, collector)
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) throw e
                bodyError = e
                break
            }
        }
        val doc = collector.build(
            notice = if (distribution) "배포용 문서입니다. (읽기 전용)" else null,
        )
        if (bodyError == null && !doc.isEmpty) return doc

        // 본문을 못 읽은 경우 미리보기 텍스트로 대체
        val preview = readPreview(cf)
        if (preview != null && !preview.isEmpty) {
            val msg = "본문 일부를 해석하지 못해 미리보기 텍스트만 표시합니다."
            return if (!doc.isEmpty && bodyError != null) {
                // 앞부분은 읽혔다면 읽힌 만큼 보여주는 것이 미리보기보다 낫다
                TextDocument(doc.blocks, "문서 뒷부분을 해석하지 못했습니다.")
            } else TextDocument(preview.blocks, msg)
        }
        if (bodyError != null) {
            if (!doc.isEmpty) return TextDocument(doc.blocks, "문서 뒷부분을 해석하지 못했습니다.")
            throw CorruptDocumentException("본문을 해석하지 못했습니다: ${bodyError.message}", bodyError)
        }
        return doc
    }

    fun readPreview(cf: CompoundFile): TextDocument? {
        if (!cf.has("PrvText")) return null
        return try {
            val text = String(cf.read("PrvText"), Charsets.UTF_16LE)
            val c = BlockCollector()
            text.split("\r\n", "\n").forEach { c.add(it.trimEnd('\u0000')) }
            c.build()
        } catch (e: Exception) {
            null
        }
    }

    private class TableCtx(val level: Int) {
        var currentRow = -1
        val cells = ArrayList<StringBuilder>()
        var cell: StringBuilder? = null
    }

    private fun extractSection(data: ByteArray, out: BlockCollector) {
        val tables = ArrayList<TableCtx>()
        var pos = 0
        val len = data.size

        fun flushRow(t: TableCtx, into: (String) -> Unit) {
            if (t.cells.isEmpty()) return
            val cells = t.cells.map { it.toString().trim().replace('\n', ' ') }
            t.cells.clear()
            t.cell = null
            if (cells.any { it.isNotEmpty() }) into(cells.joinToString(TABLE_CELL_SEPARATOR))
        }

        fun emit(text: String, kind: BlockKind) {
            val t = tables.lastOrNull()
            val cell = t?.cell
            if (cell != null) {
                if (cell.isNotEmpty()) cell.append('\n')
                cell.append(text)
            } else {
                out.add(text, kind)
            }
        }

        fun closeTable() {
            val t = tables.removeAt(tables.size - 1)
            // 중첩 표는 바깥 셀의 내용으로, 최상위 표는 행 블록으로
            flushRow(t) { emit(it, BlockKind.TABLE_ROW) }
        }

        while (pos + 4 <= len) {
            val header = le32(data, pos)
            pos += 4
            val tag = header and 0x3FF
            val level = (header ushr 10) and 0x3FF
            var size = (header ushr 20) and 0xFFF
            if (size == 0xFFF) {
                if (pos + 4 > len) break
                size = le32(data, pos)
                pos += 4
            }
            if (size < 0 || pos + size > len) break // 잘린 레코드 – 읽은 데까지만

            while (tables.isNotEmpty() && level < tables.last().level) closeTable()

            when (tag) {
                TAG_TABLE -> tables.add(TableCtx(level))
                TAG_LIST_HEADER -> {
                    val t = tables.lastOrNull()
                    if (t != null && level == t.level && size >= 12) {
                        // LIST_HEADER(셀): 문단수(4) 속성(4) 열주소(2) 행주소(2) ...
                        val row = le16(data, pos + 10)
                        if (row != t.currentRow) {
                            flushRow(t) { emitOuter(tables, t, out, it) }
                            t.currentRow = row
                        }
                        val sb = StringBuilder()
                        t.cells.add(sb)
                        t.cell = sb
                    }
                }
                TAG_PARA_TEXT -> {
                    val text = decodeParaText(data, pos, size)
                    emit(text, BlockKind.PARAGRAPH)
                }
                TAG_PARA_HEADER -> {
                    // 문단 끝 문자만 있는 빈 문단은 PARA_TEXT 레코드가 없다.
                    if (size >= 4 && (le32(data, pos) and 0x7FFFFFFF) <= 1 && tables.lastOrNull()?.cell == null) {
                        out.add("")
                    }
                }
            }
            pos += size
        }
        while (tables.isNotEmpty()) closeTable()
    }

    /** 표 t 의 행을 t 바깥(상위 표의 셀 또는 본문)으로 내보낸다. */
    private fun emitOuter(tables: List<TableCtx>, t: TableCtx, out: BlockCollector, row: String) {
        val idx = tables.indexOf(t)
        val parentCell = if (idx > 0) tables[idx - 1].cell else null
        if (parentCell != null) {
            if (parentCell.isNotEmpty()) parentCell.append('\n')
            parentCell.append(row)
        } else {
            out.add(row, BlockKind.TABLE_ROW)
        }
    }

    /** PARA_TEXT 의 UTF-16 문자열에서 제어문자를 걸러낸다. */
    internal fun decodeParaText(data: ByteArray, start: Int, size: Int): String {
        val sb = StringBuilder(size / 2)
        var i = 0
        val n = size / 2
        while (i < n) {
            val c = le16(data, start + i * 2)
            if (c >= 32) {
                sb.append(c.toChar())
                i++
                continue
            }
            when (c) {
                9 -> { sb.append('\t'); i += 8 }               // 탭 (inline, 8 wchar)
                10 -> { sb.append('\n'); i++ }                 // 줄 바꿈
                13 -> i++                                      // 문단 끝
                24 -> { sb.append('-'); i++ }                  // 하이픈
                30, 31 -> { sb.append(' '); i++ }              // 묶음/고정폭 빈칸
                0, 25, 26, 27, 28, 29 -> i++                   // 기타 문자형 제어
                else -> i += 8                                 // 확장/인라인 제어 (8 wchar)
            }
        }
        return sb.toString()
    }

    // ---- 배포용 문서 ----

    internal fun decryptDistribution(data: ByteArray): ByteArray {
        if (data.size < 4 + 256) throw CorruptDocumentException("배포용 문서 데이터가 손상되었습니다.")
        val header = le32(data, 0)
        val tag = header and 0x3FF
        val size = (header ushr 20) and 0xFFF
        if (tag != TAG_DISTRIBUTE_DOC_DATA || size != 256) {
            throw CorruptDocumentException("배포용 문서 정보를 찾을 수 없습니다.")
        }
        val dist = data.copyOfRange(4, 4 + 256)
        deobfuscate(dist)
        val offset = 4 + (dist[0].toInt() and 0x0F)
        val key = dist.copyOfRange(offset, offset + 16)

        val body = 4 + 256
        val encLen = (data.size - body) / 16 * 16
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data, body, encLen)
    }

    private fun deobfuscate(data: ByteArray) {
        var seed = le32(data, 0)
        fun rand(): Int {
            seed = seed * 214013 + 2531011
            return (seed ushr 16) and 0x7FFF
        }
        var value = 0
        var count = 0
        for (i in data.indices) {
            if (count == 0) {
                value = rand() and 0xFF
                count = (rand() and 0x0F) + 1
            }
            if (i >= 4) data[i] = (data[i].toInt() xor value).toByte()
            count--
        }
    }

    // ---- 유틸 ----

    internal fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream((data.size.toLong() * 4).coerceIn(1024, 16L * 1024 * 1024).toInt())
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buf)
                } catch (e: DataFormatException) {
                    if (out.size() > 0) break // 뒷부분 손상: 읽은 데까지
                    throw CorruptDocumentException("압축 해제 실패: ${e.message}", e)
                }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buf, 0, n)
                if (out.size() > MAX_SECTION_BYTES) throw CorruptDocumentException("본문이 너무 큽니다.")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private const val MAX_SECTION_BYTES = 256 * 1024 * 1024
    private val SECTION_RE = Regex("Section(\\d+)")

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
