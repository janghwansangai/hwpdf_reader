package com.fastreader.app.viewer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.fastreader.app.core.DocumentException
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

data class DocumentInfo(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val size: Long,
)

/** 위치 이동(seek)이 가능한 파일 디스크립터. 필요하면 캐시에 복사한 임시 파일을 쓴다. */
class SeekableFile(val pfd: ParcelFileDescriptor, private val tempFile: File?) : Closeable {
    override fun close() {
        try {
            pfd.close()
        } catch (_: IOException) {
        }
        tempFile?.delete()
    }

    /** 소유권을 넘긴 뒤(PdfRenderer 등) 임시파일만 지울 때 사용 */
    fun deleteTempFile() {
        tempFile?.delete()
    }
}

object DocumentOpener {

    fun queryInfo(context: Context, uri: Uri): DocumentInfo {
        val cr = context.contentResolver
        var name: String? = null
        var size = -1L
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (_: Exception) {
                // 일부 앱의 ContentProvider 는 query 를 지원하지 않는다.
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { size = File(it).length() }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "문서"
        }
        val mime = try {
            cr.getType(uri)
        } catch (_: Exception) {
            null
        }
        return DocumentInfo(uri, name!!, mime, size)
    }

    fun openInput(context: Context, uri: Uri): InputStream =
        wrapErrors { context.contentResolver.openInputStream(uri) }
            ?: throw DocumentException("파일을 열 수 없습니다.")

    fun readHead(context: Context, uri: Uri, max: Int = 1024): ByteArray {
        openInput(context, uri).use { input ->
            val buf = ByteArray(max)
            var off = 0
            while (off < max) {
                val n = input.read(buf, off, max - off)
                if (n < 0) break
                off += n
            }
            return buf.copyOf(off)
        }
    }

    /**
     * PdfRenderer 와 HWP(OLE) 파서는 임의 위치 읽기가 필요하다.
     * 클라우드/메신저 앱이 넘겨주는 파이프 형태의 디스크립터는 seek 이 안 되므로 캐시에 복사한다.
     */
    fun openSeekable(context: Context, uri: Uri): SeekableFile {
        val pfd = try {
            wrapErrors { context.contentResolver.openFileDescriptor(uri, "r") }
        } catch (e: DocumentException) {
            null
        }
        if (pfd != null) {
            if (isSeekable(pfd)) return SeekableFile(pfd, null)
            try {
                pfd.close()
            } catch (_: IOException) {
            }
        }
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val tmp = File.createTempFile("doc", ".tmp", dir)
        try {
            openInput(context, uri).use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
            return SeekableFile(ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY), tmp)
        } catch (e: Throwable) {
            tmp.delete()
            if (e is IOException && e.message?.contains("ENOSPC") == true) {
                throw DocumentException("저장 공간이 부족해 파일을 열 수 없습니다.", e)
            }
            throw e
        }
    }

    /** 이전 실행에서 남은 임시 파일 정리 */
    fun cleanCache(context: Context) {
        File(context.cacheDir, CACHE_DIR).listFiles()?.forEach { it.delete() }
    }

    private fun isSeekable(pfd: ParcelFileDescriptor): Boolean {
        if (pfd.statSize < 0) return false
        return try {
            Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET)
            true
        } catch (_: ErrnoException) {
            false
        }
    }

    private inline fun <T> wrapErrors(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw DocumentException("파일 접근 권한이 없습니다.\n파일을 다시 선택해 주세요.", e)
    } catch (e: FileNotFoundException) {
        throw DocumentException("파일을 찾을 수 없습니다.\n삭제되었거나 이동되었을 수 있습니다.", e)
    }

    private const val CACHE_DIR = "open"
}
