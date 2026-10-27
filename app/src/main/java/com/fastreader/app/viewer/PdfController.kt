package com.fastreader.app.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import com.fastreader.app.core.CorruptDocumentException
import com.fastreader.app.core.EncryptedDocumentException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * PdfRenderer 래퍼.
 * - PdfRenderer 는 스레드 안전하지 않고 한 번에 한 페이지만 열 수 있으므로 전용 단일 스레드에서만 접근한다.
 * - 렌더링 결과는 메모리 한도 내에서 LRU 캐시에 보관한다.
 * - close() 는 진행 중인 렌더링이 끝난 뒤 같은 스레드에서 실행되어 "Already closed" 크래시를 막는다.
 */
class PdfController private constructor(
    private val renderer: PdfRenderer,
    private val file: SeekableFile,
    private val executor: java.util.concurrent.ExecutorService,
) : Closeable {

    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

    val pageCount: Int = renderer.pageCount

    /** 페이지 크기(pt). 아직 모르면 0 */
    private val widths = IntArray(pageCount)
    private val heights = IntArray(pageCount)

    @Volatile
    private var closed = false

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** 페이지 높이/너비 비율. 모르는 페이지는 첫 페이지 비율로 추정. */
    fun aspectRatio(index: Int): Float {
        val w = widths.getOrElse(index) { 0 }
        if (w > 0) return heights[index].toFloat() / w
        if (widths.isNotEmpty() && widths[0] > 0) return heights[0].toFloat() / widths[0]
        return 1.414f
    }

    fun cached(index: Int, width: Int): Bitmap? = cache.get(key(index, width))

    suspend fun render(index: Int, targetWidth: Int): Bitmap? {
        if (closed || index !in 0 until pageCount || targetWidth <= 0) return null
        cache.get(key(index, targetWidth))?.let { return it }
        return withContext(dispatcher) {
            coroutineContext.ensureActive()
            if (closed) return@withContext null
            cache.get(key(index, targetWidth))?.let { return@withContext it }
            renderer.openPage(index).use { page ->
                widths[index] = page.width
                heights[index] = page.height
                var w = targetWidth
                var h = (targetWidth.toLong() * page.height / page.width.coerceAtLeast(1)).toInt()
                // 아주 긴 페이지 등으로 인한 OOM 방지
                val pixels = w.toLong() * h
                if (pixels > MAX_PIXELS) {
                    val s = Math.sqrt(MAX_PIXELS.toDouble() / pixels)
                    w = (w * s).toInt()
                    h = (h * s).toInt()
                }
                if (w <= 0 || h <= 0) return@withContext null
                val bmp = try {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    cache.evictAll()
                    Bitmap.createBitmap(w / 2, h / 2, Bitmap.Config.ARGB_8888)
                }
                bmp.eraseColor(Color.WHITE) // 투명 배경이 다크 모드에서 검게 보이는 문제 방지
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key(index, targetWidth), bmp)
                bmp
            }
        }
    }

    /** 첫 몇 페이지의 크기를 미리 읽어 스크롤 위치가 튀지 않게 한다. */
    suspend fun preloadSizes(limit: Int) = withContext(dispatcher) {
        for (i in 0 until minOf(limit, pageCount)) {
            coroutineContext.ensureActive()
            if (closed) break
            if (widths[i] > 0) continue
            renderer.openPage(i).use { p ->
                widths[i] = p.width
                heights[i] = p.height
            }
        }
    }

    fun trimMemory() {
        cache.evictAll()
    }

    override fun close() {
        if (closed) return
        closed = true
        cache.evictAll()
        executor.execute {
            try {
                renderer.close() // 소유한 pfd 도 함께 닫힌다
            } catch (_: Exception) {
            }
            file.deleteTempFile()
        }
        executor.shutdown()
    }

    private fun key(index: Int, width: Int) = "$index@$width"

    companion object {
        private const val MAX_PIXELS = 8_000_000L

        private fun cacheBytes(): Int {
            val max = Runtime.getRuntime().maxMemory()
            return (max / 5).coerceIn(16L * 1024 * 1024, 256L * 1024 * 1024).toInt()
        }

        /** IO 스레드에서 호출. 실패 시 사용자 메시지를 가진 예외를 던진다. */
        fun open(file: SeekableFile): PdfController {
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render").apply { priority = Thread.NORM_PRIORITY - 1 } }
            val renderer = try {
                PdfRenderer(file.pfd)
            } catch (e: SecurityException) {
                executor.shutdown()
                file.close()
                throw EncryptedDocumentException("암호가 걸린 PDF 입니다.\n'공유 / 다른 앱으로 열기'로 다른 앱에서 열어 주세요.")
            } catch (e: IOException) {
                executor.shutdown()
                file.close()
                throw CorruptDocumentException("손상되었거나 지원하지 않는 PDF 입니다.", e)
            } catch (e: IllegalArgumentException) {
                executor.shutdown()
                file.close()
                throw CorruptDocumentException("PDF 를 열 수 없습니다.", e)
            }
            if (renderer.pageCount <= 0) {
                renderer.close()
                file.deleteTempFile()
                executor.shutdown()
                throw CorruptDocumentException("페이지가 없는 PDF 입니다.")
            }
            return PdfController(renderer, file, executor)
        }
    }
}
