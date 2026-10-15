package com.fastreader.app.core.hwp

import com.fastreader.app.core.CompoundFile
import com.fastreader.app.core.CorruptDocumentException
import com.fastreader.app.core.DocumentException
import com.fastreader.app.core.EncryptedDocumentException
import com.fastreader.app.core.RandomAccessSource
import com.fastreader.app.core.UnsupportedDocumentException
import com.fastreader.app.core.doc.ImageInfo
import com.fastreader.app.core.doc.ImageSink
import com.fastreader.app.core.doc.RichDocument
import com.fastreader.app.core.doc.Section
import java.io.ByteArrayInputStream
import java.io.EOFException

/**
 * HWP 5.x → [RichDocument].
 * FileHeader(압축/암호/배포용) → DocInfo → BodyText/SectionN (배포용은 ViewText/SectionN 복호화) → BinData 그림.
 * 구역 하나가 손상되면 그 구역만 빼고 나머지를 살리며 [RichDocument.notice] 로 알린다.
 */
object HwpRichParser {

    private const val FLAG_COMPRESSED = 1
    private const val FLAG_PASSWORD = 1 shl 1
    private const val FLAG_DISTRIBUTION = 1 shl 2
    private val SECTION_RE = Regex("Section(\\d+)")
    private val BIN_RE = Regex("BIN([0-9A-Fa-f]{1,8})(\\..*)?", RegexOption.IGNORE_CASE)

    const val NOTICE_DISTRIBUTION = "배포용 문서입니다. (읽기 전용)"
    const val NOTICE_PARTIAL = "문서 일부를 해석하지 못했습니다."
    const val NOTICE_NO_STYLE = "문서 서식 정보를 읽지 못해 기본 모양으로 표시합니다."

    fun parse(src: RandomAccessSource, images: ImageSink?): RichDocument {
        val head = ByteArray(minOf(32L, src.size).toInt())
        src.readFully(0, head)
        if (String(head, Charsets.US_ASCII).startsWith("HWP Document File V3")) {
            throw UnsupportedDocumentException("한글 97 이전(HWP 3.0) 형식은 지원하지 않습니다.\n한글에서 HWP 5.0 이상 또는 HWPX로 다시 저장해 주세요.")
        }
        return try {
            parse(CompoundFile(src), images)
        } catch (e: DocumentException) {
            throw e
        } catch (e: EOFException) {
            throw CorruptDocumentException("HWP 파일이 손상되었습니다.", e)
        } catch (e: RuntimeException) {
            throw CorruptDocumentException("HWP 파일이 손상되었습니다.", e)
        } catch (e: StackOverflowError) {
            throw CorruptDocumentException("HWP 파일이 손상되었습니다.", null)
        }
    }

    private fun parse(cf: CompoundFile, images: ImageSink?): RichDocument {
        val fh = cf.read("FileHeader")
        if (fh.size < 40 || !String(fh, 0, 17, Charsets.US_ASCII).startsWith("HWP Document File")) {
            throw CorruptDocumentException("HWP 파일 헤더가 올바르지 않습니다.")
        }
        val version = HwpVersion.fromRaw(HwpBinary.le32(fh, 32))
        val props = HwpBinary.le32(fh, 36)
        val compressed = props and FLAG_COMPRESSED != 0
        val distribution = props and FLAG_DISTRIBUTION != 0
        if (props and FLAG_PASSWORD != 0) {
            throw EncryptedDocumentException("암호가 걸린 문서입니다.\n한글에서 암호를 해제한 뒤 다시 열어 주세요.")
        }
        val notices = ArrayList<String>()
        if (distribution) notices.add(NOTICE_DISTRIBUTION)

        // 문서 정보: 읽지 못하면 기본 모양으로라도 본문을 보여준다
        val info = try {
            var d = cf.read("DocInfo")
            if (compressed) d = HwpBinary.inflate(d)
            HwpDocInfoReader.read(d, version)
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Exception) {
            notices.add(NOTICE_NO_STYLE)
            HwpDocInfo.EMPTY
        }

        // 본문 구역
        val storage = if (distribution) "ViewText" else "BodyText"
        val paths = cf.children(storage)
            .mapNotNull { n -> SECTION_RE.matchEntire(n)?.groupValues?.get(1)?.toIntOrNull()?.let { it to n } }
            .sortedBy { it.first }
            .map { "$storage/${it.second}" }
        val sections = ArrayList<Section>(paths.size)
        var bodyError: Throwable? = null
        for (path in paths) {
            try {
                var d = cf.read(path)
                if (distribution) d = HwpBinary.decryptDistribution(d)
                if (compressed) d = HwpBinary.inflate(d)
                sections.add(HwpBodyReader.readSection(d, info, version))
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Exception) {
                if (bodyError == null) bodyError = e
            }
        }
        if (sections.isEmpty()) {
            val err = bodyError
            if (err is DocumentException) throw err
            throw CorruptDocumentException("본문을 해석하지 못했습니다." + (err?.message?.let { " ($it)" } ?: ""), err)
        }
        if (bodyError != null) notices.add(NOTICE_PARTIAL)

        val imageMap = readImages(cf, info, compressed, images)

        return RichDocument(
            charShapes = info.charShapes,
            paraShapes = info.paraShapes,
            borderFills = info.borderFills,
            numberings = info.numberings,
            bullets = info.bullets,
            styles = info.styles,
            sections = sections,
            images = imageMap,
            notice = notices.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }

    /**
     * BinData 스트림 → [ImageSink]. 경로는 BinData/BIN%04X.확장자 (hwplib HWPReader.binData).
     * 압축: 항목 설정(1 압축, 2 안 함) 또는 문서 설정(0). 압축 해제에 실패하면 원본 바이트를 쓴다(hwplib 과 같음).
     * 그림 하나가 깨져도 문서는 연다.
     */
    private fun readImages(cf: CompoundFile, info: HwpDocInfo, compressed: Boolean, sink: ImageSink?): Map<String, ImageInfo> {
        if (info.binItems.isEmpty()) return emptyMap()
        // ID → 스트림 이름 (대소문자/자릿수가 다른 파일 대비)
        val byId = HashMap<Int, String>()
        for (name in cf.children("BinData")) {
            val m = BIN_RE.matchEntire(name) ?: continue
            val id = m.groupValues[1].toIntOrNull(16) ?: continue
            if (cf.has("BinData/$name")) byId.putIfAbsent(id, "BinData/$name")
        }
        val out = LinkedHashMap<String, ImageInfo>()
        for (item in info.binItems.values.sortedBy { it.binId }) {
            if (item.type != 1) continue // 연결(LINK) / OLE 저장소(STORAGE) 는 그림 스트림이 없다
            val ext = item.extension.lowercase().ifEmpty { "bin" }
            if (ext == "ole") continue
            val path = item.streamPath.takeIf { cf.has(it) } ?: byId[item.binId] ?: continue
            val imageInfo = ImageInfo(item.imageId, ext)
            if (sink == null) {
                out[item.imageId] = imageInfo
                continue
            }
            try {
                val raw = cf.read(path)
                val inflate = when (item.compression) {
                    1 -> true
                    2 -> false
                    else -> compressed
                }
                val bytes = if (inflate) {
                    try {
                        HwpBinary.inflate(raw).takeIf { it.isNotEmpty() } ?: raw
                    } catch (e: Exception) {
                        raw
                    }
                } else raw
                ByteArrayInputStream(bytes).use { sink.put(imageInfo, it) }
                out[item.imageId] = imageInfo
            } catch (e: OutOfMemoryError) {
                // 너무 큰 그림 하나 때문에 문서 전체를 포기하지 않는다
            } catch (e: Exception) {
                // 깨진 그림은 건너뛴다 (빈 상자로 표시됨)
            }
        }
        return out
    }
}
