package com.fastreader.app.core.hwpx

import com.fastreader.app.core.CorruptDocumentException
import com.fastreader.app.core.DocumentException
import com.fastreader.app.core.EncryptedDocumentException
import com.fastreader.app.core.UnsupportedDocumentException
import com.fastreader.app.core.doc.CharShape
import com.fastreader.app.core.doc.ImageInfo
import com.fastreader.app.core.doc.ImageSink
import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.PageDef
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.RichDocument
import com.fastreader.app.core.doc.Section
import org.xml.sax.SAXException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * HWPX(OWPML: zip + XML) → [RichDocument].
 *
 * zip 을 [ZipInputStream] 으로 한 번만 훑는다. 항목 순서는 정해져 있지 않으므로
 * - header.xml / sectionN.xml 은 만나는 대로 바로 SAX 로 해석한다(구역 해석에 머리 정보가 필요 없게 ID 만 담는다).
 * - BinData 그림은 만나는 대로 [ImageSink] 로 흘려보낸다. 매니페스트(content.hpf)를 아직 못 읽었으면
 *   파일 이름(예: image1)을 ID 로 쓰고, 끝에서 매니페스트 항목 ID 로 별칭을 단다(한글은 둘이 같다).
 * - 구역 순서는 content.hpf 의 spine, 없으면 sectionN 의 번호 순서.
 * - 형광펜(markpen)은 원래 글자 모양에 음영색만 바꾼 글자 모양을 새로 만들어 표현한다.
 */
object HwpxRichParser {

    fun parse(input: InputStream, images: ImageSink?): RichDocument = try {
        Parser(images).run(input)
    } catch (e: DocumentException) {
        throw e
    } catch (e: OutOfMemoryError) {
        throw CorruptDocumentException("문서가 너무 커서 열 수 없습니다.", e)
    } catch (e: StackOverflowError) {
        throw CorruptDocumentException("HWPX 파일이 손상되었습니다.", e)
    } catch (e: IOException) {
        throw CorruptDocumentException(readError(e), e)
    } catch (e: RuntimeException) {
        throw CorruptDocumentException("HWPX 파일이 손상되었습니다.", e)
    }

    private fun readError(e: IOException) =
        if (e.message.isNullOrBlank()) "파일을 읽는 중 오류가 났습니다. 파일이 잘렸거나 손상되었을 수 있습니다." else "파일을 읽는 중 오류: ${e.message}"

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "tif", "tiff", "wmf", "emf")
    private val SECTION_NUM = Regex("section(\\d+)\\.xml$")

    private const val MAX_ENTRIES = 50_000
    private const val MAX_XML_ENTRY = 300L * 1024 * 1024
    private const val MAX_XML_TOTAL = 1024L * 1024 * 1024
    private const val MAX_SMALL_XML = 32L * 1024 * 1024
    private const val MAX_IMAGE = 64L * 1024 * 1024
    private const val MAX_IMAGE_TOTAL = 1024L * 1024 * 1024
    private const val MAX_PREVIEW = 4 * 1024 * 1024

    private class ParsedSection(val path: String, val order: Int, val section: Section)

    private class Parser(private val sink: ImageSink?) {
        private val ctx = HwpxParseContext()
        private var header: HwpxHeader? = null
        private var manifest: HwpxManifest? = null
        private val encryption = HwpxEncryptionInfo()
        private val sections = ArrayList<ParsedSection>()
        private var failedSections = 0
        private var preview: String? = null
        private val images = LinkedHashMap<String, ImageInfo>()
        private val putIdByPath = HashMap<String, String>()
        private var partial = false
        private var tooLarge = false
        private var xmlBytes = 0L
        private var imageBytes = 0L

        fun run(input: InputStream): RichDocument {
            var sawEntry = false
            var entries = 0
            try {
                ZipInputStream(input.buffered(64 * 1024)).use { zis ->
                    while (true) {
                        val entry = try {
                            zis.nextEntry
                        } catch (e: ZipException) {
                            if (!sawEntry) throw CorruptDocumentException("HWPX(zip) 형식이 아닙니다.", e)
                            partial = true
                            null
                        } catch (e: IOException) {
                            if (!sawEntry) throw CorruptDocumentException(readError(e), e)
                            partial = true
                            null
                        } catch (e: IllegalArgumentException) {
                            // UTF-8 이 아닌 파일 이름 등 – 더 읽을 수 없다
                            if (!sawEntry) throw CorruptDocumentException("HWPX 파일이 손상되었습니다.", e)
                            partial = true
                            null
                        } ?: break
                        sawEntry = true
                        if (++entries > MAX_ENTRIES) {
                            partial = true
                            break
                        }
                        if (entry.isDirectory) continue
                        handleEntry(HwpxManifest.normalize(entry.name), zis)
                    }
                }
            } catch (e: DocumentException) {
                throw e
            } catch (e: IOException) {
                // 닫는 중 오류 등. 읽은 것은 살린다
                if (!sawEntry) throw CorruptDocumentException(readError(e), e)
                partial = true
            }
            if (!sawEntry) throw CorruptDocumentException("HWPX(zip) 형식이 아닙니다.")
            return assemble()
        }

        private fun handleEntry(name: String, zis: ZipInputStream) {
            when {
                name == "meta-inf/manifest.xml" -> parseSmallXml(zis, encryption.root)
                name.endsWith(".hpf") -> {
                    val m = HwpxManifest()
                    parseSmallXml(zis, m.root)
                    if (m.items.isNotEmpty() || manifest == null) manifest = m
                }
                name.startsWith("contents/") && name.endsWith(".xml") -> parseContentXml(name, zis)
                name.startsWith("bindata/") -> putImage(name, zis)
                name == "preview/prvtext.txt" -> preview = readText(zis, MAX_PREVIEW)
            }
        }

        private fun parseSmallXml(zis: ZipInputStream, root: XNode) {
            try {
                HwpxXml.parse(LimitedStream(zis, MAX_SMALL_XML), root)
            } catch (e: LimitExceededException) {
                partial = true
            } catch (e: SAXException) {
                partial = true
            } catch (e: IOException) {
                partial = true
            } catch (e: RuntimeException) {
                partial = true
            }
        }

        /** Contents/ 아래 XML: 루트 요소(head / sec)를 보고 해석기를 고른다 */
        private fun parseContentXml(name: String, zis: ZipInputStream) {
            val isHeader = name.endsWith("header.xml")
            if (!isHeader && tooLarge) return
            if (!isHeader && name.substringAfterLast('/').startsWith("masterpage")) return
            if (xmlBytes > MAX_XML_TOTAL) {
                tooLarge = true
                return
            }
            val headerReader = HwpxHeaderReader()
            val sectionReader = HwpxSectionReader(ctx)
            var kind = 0 // 1 header, 2 section
            val root = object : XNode() {
                override fun child(name: String, a: XAttrs): XNode? = when (name) {
                    "head" -> {
                        kind = 1
                        headerReader.root.child(name, a)
                    }
                    "sec" -> {
                        kind = 2
                        sectionReader.root.child(name, a)
                    }
                    else -> throw StopParsingException()
                }
            }
            val limited = LimitedStream(zis, MAX_XML_ENTRY)
            var failed = false
            try {
                if (!HwpxXml.parse(limited, root)) partial = true
            } catch (e: LimitExceededException) {
                tooLarge = true
            } catch (e: EntryTooLargeException) {
                tooLarge = true
            } catch (e: SAXException) {
                // SAX 가 처리기 예외를 감싸 던질 수 있다
                if (e.exception is LimitExceededException || e.exception is EntryTooLargeException) tooLarge = true else failed = true
            } catch (e: IOException) {
                failed = true
            } catch (e: RuntimeException) {
                failed = true
            }
            xmlBytes += limited.count
            when (kind) {
                1 -> if (header == null || !failed) {
                    if (failed) partial = true
                    header = try {
                        headerReader.build()
                    } catch (e: RuntimeException) {
                        partial = true
                        null
                    }
                }
                2 -> {
                    if (failed) partial = true
                    sections.add(ParsedSection(name, sections.size, sectionReader.build()))
                }
                else -> if (failed && SECTION_NUM.containsMatchIn(name)) failedSections++
            }
        }

        private fun putImage(name: String, zis: ZipInputStream) {
            val ext = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            if (ext !in IMAGE_EXTS) return
            if (name in encryption.encryptedPaths) return
            if (imageBytes > MAX_IMAGE_TOTAL) return
            val base = manifest?.itemByPath(name)?.id?.let { HwpxValues.imageId(it) }
                ?: HwpxValues.imageId(name.substringAfterLast('/').substringBeforeLast('.')) ?: "img"
            var id = base
            var n = 2
            while (images.containsKey(id)) id = base + "_" + n++
            val info = ImageInfo(id, ext)
            if (sink != null) {
                val limited = LimitedStream(zis, MAX_IMAGE)
                try {
                    sink.put(info, limited)
                } catch (e: IOException) {
                    return
                } catch (e: RuntimeException) {
                    return
                } finally {
                    imageBytes += limited.count
                }
            }
            images[id] = info
            putIdByPath[name] = id
        }

        private fun readText(input: InputStream, limit: Int): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            try {
                while (out.size() < limit) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, minOf(n, limit - out.size()))
                }
            } catch (e: IOException) {
                partial = true
            }
            return out.toByteArray().toString(Charsets.UTF_8)
        }

        private fun assemble(): RichDocument {
            val h = header ?: HwpxHeader.EMPTY

            // 암호 문서: 본문을 하나도 읽지 못했으면 (미리보기도 암호화되어 있으므로 쓰지 않는다)
            if (encryption.encrypted && sections.all { it.section.paragraphs.isEmpty() }) {
                throw EncryptedDocumentException("암호가 걸린 문서입니다.\n한글에서 암호를 해제한 뒤 다시 열어 주세요.")
            }
            if (sections.isEmpty()) {
                previewDocument()?.let { return it }
                if (failedSections > 0 || partial) throw CorruptDocumentException("HWPX 파일이 손상되었습니다.")
                throw UnsupportedDocumentException("한글(HWPX) 문서가 아니거나 본문이 없습니다.")
            }

            // 구역 순서: spine → 파일 번호 → 읽은 순서
            val spine = manifest?.spinePaths().orEmpty()
            val spineIndex = HashMap<String, Int>()
            spine.forEachIndexed { i, p -> spineIndex.putIfAbsent(p, i) }
            val ordered = sections.sortedWith(
                compareBy<ParsedSection>(
                    { spineIndex[it.path] ?: Int.MAX_VALUE },
                    { SECTION_NUM.find(it.path)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE },
                    { it.order },
                ),
            )

            // 형광펜 글자 모양
            val charShapes: Map<Int, CharShape> = if (ctx.highlights.isEmpty()) h.charShapes else {
                val m = HashMap(h.charShapes)
                for ((key, id) in ctx.highlights) {
                    val base = (key ushr 32).toInt()
                    val color = (key and 0xFFFFFFFFL).toInt()
                    if (!m.containsKey(id)) m[id] = (h.charShapes[base] ?: CharShape.DEFAULT).copy(shadeColor = color)
                }
                m
            }

            // 매니페스트 ID 와 다른 이름으로 저장된 그림에 별칭
            val imageMap = LinkedHashMap(images)
            manifest?.items?.forEach { item ->
                val putId = putIdByPath[HwpxManifest.normalize(item.href)] ?: return@forEach
                val key = HwpxValues.imageId(item.id) ?: return@forEach
                if (!imageMap.containsKey(key)) imageMap[key] = images.getValue(putId)
            }

            val notice = when {
                tooLarge -> "문서가 너무 커서 앞부분만 표시합니다."
                partial || failedSections > 0 -> "문서 일부를 해석하지 못했습니다."
                else -> null
            }
            return RichDocument(
                charShapes = charShapes,
                paraShapes = h.paraShapes,
                borderFills = h.borderFills,
                numberings = h.numberings,
                bullets = h.bullets,
                styles = h.styles,
                sections = ordered.map { it.section },
                images = imageMap,
                notice = notice,
            )
        }

        /** 본문을 못 읽었을 때 Preview/PrvText.txt 로 만든 문서 */
        private fun previewDocument(): RichDocument? {
            val p = preview ?: return null
            val lines = p.split("\r\n", "\n")
            if (lines.all { it.isBlank() }) return null
            val paras = lines.map { line ->
                Paragraph(0, content = if (line.isEmpty()) emptyList() else listOf(Inline.Text(0, line, 0)))
            }
            return RichDocument(
                charShapes = emptyMap(), paraShapes = emptyMap(), borderFills = emptyMap(), numberings = emptyMap(),
                bullets = emptyMap(), styles = emptyMap(),
                sections = listOf(Section(PageDef(), paragraphs = paras)),
                images = emptyMap(),
                notice = "본문을 해석하지 못해 미리보기 텍스트만 표시합니다.",
            )
        }
    }
}
