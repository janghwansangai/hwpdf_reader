package com.fastreader.app.core.hwp

import com.fastreader.app.core.CorruptDocumentException
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** HWP 파일 버전 (FileHeader 의 MM.nn.PP.rr) */
data class HwpVersion(val major: Int, val minor: Int, val build: Int, val revision: Int) : Comparable<HwpVersion> {
    private val packed get() = (major shl 24) or (minor shl 16) or (build shl 8) or revision

    override fun compareTo(other: HwpVersion) = packed.compareTo(other.packed)

    /** 이 버전이 a.b.c.d 이상인가 */
    fun atLeast(a: Int, b: Int, c: Int, d: Int) = this >= HwpVersion(a, b, c, d)

    companion object {
        fun fromRaw(raw: Int) = HwpVersion((raw ushr 24) and 0xFF, (raw ushr 16) and 0xFF, (raw ushr 8) and 0xFF, raw and 0xFF)
    }
}

/** HWP 레코드 1개. data 의 [offset, offset+size) 가 레코드 본문 */
class HwpRecord(val tag: Int, val level: Int, val data: ByteArray, val offset: Int, val size: Int) {
    fun reader() = HwpReader(data, offset, offset + size)
}

object HwpTags {
    const val BEGIN = 0x10

    // DocInfo
    const val DOCUMENT_PROPERTIES = BEGIN
    const val ID_MAPPINGS = BEGIN + 1
    const val BIN_DATA = BEGIN + 2
    const val FACE_NAME = BEGIN + 3
    const val BORDER_FILL = BEGIN + 4
    const val CHAR_SHAPE = BEGIN + 5
    const val TAB_DEF = BEGIN + 6
    const val NUMBERING = BEGIN + 7
    const val BULLET = BEGIN + 8
    const val PARA_SHAPE = BEGIN + 9
    const val STYLE = BEGIN + 10
    const val DOC_DATA = BEGIN + 11
    const val DISTRIBUTE_DOC_DATA = BEGIN + 12
    const val COMPATIBLE_DOCUMENT = BEGIN + 14
    const val LAYOUT_COMPATIBILITY = BEGIN + 15

    // BodyText
    const val PARA_HEADER = BEGIN + 50
    const val PARA_TEXT = BEGIN + 51
    const val PARA_CHAR_SHAPE = BEGIN + 52
    const val PARA_LINE_SEG = BEGIN + 53
    const val PARA_RANGE_TAG = BEGIN + 54
    const val CTRL_HEADER = BEGIN + 55
    const val LIST_HEADER = BEGIN + 56
    const val PAGE_DEF = BEGIN + 57
    const val FOOTNOTE_SHAPE = BEGIN + 58
    const val PAGE_BORDER_FILL = BEGIN + 59
    const val SHAPE_COMPONENT = BEGIN + 60
    const val TABLE = BEGIN + 61
    const val SHAPE_COMPONENT_LINE = BEGIN + 62
    const val SHAPE_COMPONENT_RECTANGLE = BEGIN + 63
    const val SHAPE_COMPONENT_ELLIPSE = BEGIN + 64
    const val SHAPE_COMPONENT_ARC = BEGIN + 65
    const val SHAPE_COMPONENT_POLYGON = BEGIN + 66
    const val SHAPE_COMPONENT_CURVE = BEGIN + 67
    const val SHAPE_COMPONENT_OLE = BEGIN + 68
    const val SHAPE_COMPONENT_PICTURE = BEGIN + 69
    const val SHAPE_COMPONENT_CONTAINER = BEGIN + 70
    const val CTRL_DATA = BEGIN + 71
    const val EQEDIT = BEGIN + 72
    const val SHAPE_COMPONENT_TEXTART = BEGIN + 74
    const val FORM_OBJECT = BEGIN + 75
    const val MEMO_SHAPE = BEGIN + 76
    const val MEMO_LIST = BEGIN + 77
    const val CHART_DATA = BEGIN + 79
    const val VIDEO_DATA = BEGIN + 82
    const val SHAPE_COMPONENT_UNKNOWN = BEGIN + 99
}

/** 컨트롤 ID (4글자, 예: "tbl ") 를 HWP 에 저장된 정수 형태로 */
fun ctrlId(s: String): Int {
    require(s.length == 4)
    return (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code
}

fun ctrlIdToString(id: Int): String =
    String(charArrayOf(((id ushr 24) and 0xFF).toChar(), ((id ushr 16) and 0xFF).toChar(), ((id ushr 8) and 0xFF).toChar(), (id and 0xFF).toChar()))

object HwpBinary {

    /** 레코드 목록. 잘린 레코드에서 멈추고 그때까지 읽은 레코드를 돌려준다. */
    fun records(data: ByteArray): List<HwpRecord> {
        val out = ArrayList<HwpRecord>()
        var pos = 0
        val len = data.size
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
            if (size < 0 || size > len - pos) break
            out.add(HwpRecord(tag, level, data, pos, size))
            pos += size
        }
        return out
    }

    fun inflate(data: ByteArray, maxBytes: Int = 256 * 1024 * 1024): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream((data.size.toLong() * 4).coerceIn(1024, 16L * 1024 * 1024).toInt())
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buf)
                } catch (e: DataFormatException) {
                    if (out.size() > 0) break
                    throw CorruptDocumentException("압축 해제 실패: ${e.message}", e)
                }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > maxBytes) throw CorruptDocumentException("데이터가 너무 큽니다.")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /** 배포용 문서 ViewText 스트림 복호화 (첫 레코드 DISTRIBUTE_DOC_DATA 에서 AES 키를 얻는다) */
    fun decryptDistribution(data: ByteArray): ByteArray {
        if (data.size < 4 + 256) throw CorruptDocumentException("배포용 문서 데이터가 손상되었습니다.")
        val header = le32(data, 0)
        if ((header and 0x3FF) != HwpTags.DISTRIBUTE_DOC_DATA || ((header ushr 20) and 0xFFF) != 256) {
            throw CorruptDocumentException("배포용 문서 정보를 찾을 수 없습니다.")
        }
        val dist = data.copyOfRange(4, 4 + 256)
        var seed = le32(dist, 0)
        fun rand(): Int {
            seed = seed * 214013 + 2531011
            return (seed ushr 16) and 0x7FFF
        }
        var value = 0
        var count = 0
        for (i in dist.indices) {
            if (count == 0) {
                value = rand() and 0xFF
                count = (rand() and 0x0F) + 1
            }
            if (i >= 4) dist[i] = (dist[i].toInt() xor value).toByte()
            count--
        }
        val keyOffset = 4 + (dist[0].toInt() and 0x0F)
        val key = dist.copyOfRange(keyOffset, keyOffset + 16)
        val body = 4 + 256
        val encLen = (data.size - body) / 16 * 16
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data, body, encLen)
    }

    fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}

/**
 * 레코드 본문을 읽는 순차 리더. 범위를 넘어 읽으면 예외 대신 0/빈 값을 돌려주고 [overrun] 을 표시해
 * 버전마다 필드 수가 다른 레코드도 안전하게 읽을 수 있다.
 */
class HwpReader(private val data: ByteArray, start: Int, private val end: Int) {
    var pos: Int = start
        private set
    var overrun = false
        private set

    val remaining get() = (end - pos).coerceAtLeast(0)
    val atEnd get() = pos >= end

    private fun need(n: Int): Boolean {
        if (n < 0 || pos + n > end) {
            overrun = true
            pos = end
            return false
        }
        return true
    }

    fun skip(n: Int) {
        if (need(n)) pos += n
    }

    fun u8(): Int = if (need(1)) data[pos++].toInt() and 0xFF else 0
    fun s8(): Int = if (need(1)) data[pos++].toInt() else 0
    fun u16(): Int = if (need(2)) HwpBinary.le16(data, pos).also { pos += 2 } else 0
    fun s16(): Int = if (need(2)) HwpBinary.le16(data, pos).toShort().toInt().also { pos += 2 } else 0
    fun s32(): Int = if (need(4)) HwpBinary.le32(data, pos).also { pos += 4 } else 0
    fun u32(): Long = s32().toLong() and 0xFFFFFFFFL

    /** COLORREF(0x00BBGGRR) → 0xRRGGBB */
    fun color(): Int {
        val v = s32()
        return ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v ushr 16) and 0xFF)
    }

    /** 원본 COLORREF 값 그대로 (상위 바이트 포함) */
    fun rawColor(): Int = s32()

    fun bytes(n: Int): ByteArray = if (need(n)) data.copyOfRange(pos, pos + n).also { pos += n } else ByteArray(0)

    /** WCHAR 개수(2바이트 길이) + UTF-16LE 문자열 */
    fun hwpString(): String {
        val len = u16()
        return utf16(len)
    }

    fun utf16(chars: Int): String {
        val n = chars * 2
        if (!need(n)) return ""
        val s = String(data, pos, n, Charsets.UTF_16LE)
        pos += n
        return s
    }
}
