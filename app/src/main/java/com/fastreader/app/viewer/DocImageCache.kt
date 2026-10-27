package com.fastreader.app.viewer

import android.content.Context
import com.fastreader.app.core.doc.ImageInfo
import com.fastreader.app.core.doc.ImageSink
import java.io.File
import java.util.UUID

/**
 * 한글 문서 안의 그림을 문서별 캐시 폴더(cacheDir/docimg/<임의 이름>/)에 저장하고 WebView 요청에 내준다.
 * 문서를 닫으면 [delete] 로 폴더째 지운다.
 */
class DocImageCache private constructor(val dir: File) {

    /** 파서에 넘길 그림 저장 함수. 그림 하나가 실패해도 문서 열기는 계속된다 */
    val sink = ImageSink { info, data ->
        val name = fileName(info) ?: return@ImageSink
        val f = File(dir, name)
        try {
            var total = 0L
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = data.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_IMAGE_BYTES) throw java.io.IOException("image too large")
                    out.write(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            f.delete()
        }
    }

    /** WebView 가 요청한 파일 이름 → 파일 (경로 조작 차단) */
    fun file(requested: String): File? {
        if (!SAFE_NAME.matches(requested)) return null
        val f = File(dir, requested)
        return if (f.isFile) f else null
    }

    fun delete() {
        dir.deleteRecursively()
    }

    companion object {
        private const val ROOT = "docimg"
        private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
        private val SAFE_NAME = Regex("[A-Za-z0-9_-]{1,80}\\.[a-z0-9]{1,8}")

        fun create(context: Context): DocImageCache {
            val dir = File(File(context.cacheDir, ROOT), UUID.randomUUID().toString())
            dir.mkdirs()
            return DocImageCache(dir)
        }

        /** ImageInfo.path("img/<id>.<ext>") 의 파일 이름 부분 */
        fun fileName(info: ImageInfo): String? {
            val name = info.path.substringAfterLast('/')
            return if (SAFE_NAME.matches(name)) name else null
        }

        /** 오래된(앱이 죽어서 남은) 그림 폴더 정리 */
        fun cleanStale(context: Context, olderThanMs: Long = 6 * 60 * 60 * 1000L) {
            val now = System.currentTimeMillis()
            File(context.cacheDir, ROOT).listFiles()?.forEach {
                if (now - it.lastModified() > olderThanMs) it.deleteRecursively()
            }
        }
    }
}
