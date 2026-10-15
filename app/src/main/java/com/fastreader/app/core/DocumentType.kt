package com.fastreader.app.core

enum class DocumentType { PDF, HWP, HWPX, UNKNOWN }

object DocumentTypeDetector {

    /**
     * 파일 앞부분(매직 넘버)으로 형식을 판별한다. content:// URI 는 파일 이름이 없거나
     * 확장자가 틀린 경우가 많아(메신저 첨부 등) 이름보다 내용이 우선이다.
     */
    fun detect(head: ByteArray, fileName: String?, mimeType: String?): DocumentType {
        if (head.size >= 4) {
            if (CompoundFile.isCompoundFile(head)) return DocumentType.HWP
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                head[2].toInt() == 3 && head[3].toInt() == 4
            ) return DocumentType.HWPX
            if (indexOf(head, PDF_MAGIC, 1024) >= 0) return DocumentType.PDF
            if (String(head, 0, minOf(head.size, 32), Charsets.US_ASCII).startsWith("HWP Document File")) {
                return DocumentType.HWP // HWP 3.0 – 파서에서 안내 메시지 출력
            }
        }
        return fromName(fileName) ?: fromMime(mimeType) ?: DocumentType.UNKNOWN
    }

    fun fromName(name: String?): DocumentType? {
        val n = name?.lowercase() ?: return null
        return when {
            n.endsWith(".pdf") -> DocumentType.PDF
            n.endsWith(".hwpx") -> DocumentType.HWPX
            n.endsWith(".hwp") -> DocumentType.HWP
            else -> null
        }
    }

    fun fromMime(mime: String?): DocumentType? {
        val m = mime?.lowercase() ?: return null
        return when {
            m == "application/pdf" -> DocumentType.PDF
            m.contains("hwpx") || m == "application/hwp+zip" -> DocumentType.HWPX
            m.contains("hwp") -> DocumentType.HWP
            else -> null
        }
    }

    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

    private fun indexOf(data: ByteArray, pattern: ByteArray, limit: Int): Int {
        val end = minOf(data.size, limit) - pattern.size
        outer@ for (i in 0..end) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}
