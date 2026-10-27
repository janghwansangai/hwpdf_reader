package com.fastreader.app.viewer

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fastreader.app.core.DocumentException
import com.fastreader.app.core.DocumentType
import com.fastreader.app.core.DocumentTypeDetector
import com.fastreader.app.core.FileChannelSource
import com.fastreader.app.core.HwpParser
import com.fastreader.app.core.HwpxParser
import com.fastreader.app.core.TextDocument
import com.fastreader.app.core.EncryptedDocumentException
import com.fastreader.app.core.UnsupportedDocumentException
import com.fastreader.app.core.doc.RichDocument
import com.fastreader.app.core.hwp.HwpRichParser
import com.fastreader.app.core.hwpx.HwpxRichParser
import com.fastreader.app.core.render.HtmlRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream

sealed interface ViewerState {
    data object Loading : ViewerState
    class Pdf(val info: DocumentInfo, val pdf: PdfController) : ViewerState
    class Text(val info: DocumentInfo, val type: DocumentType, val doc: TextDocument) : ViewerState

    /** 한글 문서 원본 모양 보기 (WebView) */
    class Rich(
        val info: DocumentInfo,
        val type: DocumentType,
        val renderer: HtmlRenderer,
        val images: DocImageCache,
        val notice: String?,
    ) : ViewerState
    class Failed(val info: DocumentInfo?, val message: String) : ViewerState
}

/**
 * 문서 로딩과 결과 보관. 화면 회전/다크모드 전환에도 다시 읽지 않는다.
 * 모든 파일 IO 와 파싱은 백그라운드에서 수행하고, 어떤 예외도 앱을 종료시키지 않도록 메시지로 바꾼다.
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state

    var currentUri: Uri? = null
        private set
    private var loadJob: Job? = null

    fun open(uri: Uri, force: Boolean = false) {
        if (!force && uri == currentUri && _state.value !is ViewerState.Failed) return
        loadJob?.cancel()
        releaseCurrent()
        currentUri = uri
        _state.value = ViewerState.Loading
        loadJob = viewModelScope.launch {
            // 결과를 받기 전에 취소되면 열린 PdfRenderer 가 누수되므로 끝까지 받은 뒤 정리한다.
            val result = try {
                withContext(Dispatchers.IO + NonCancellable) { load(uri) }
            } catch (e: Throwable) {
                Log.w(TAG, "open failed: $uri", e)
                ViewerState.Failed(null, messageFor(e))
            }
            if (!isActive || currentUri != uri) {
                release(result)
                return@launch
            }
            _state.value = result
        }
    }

    private fun load(uri: Uri): ViewerState {
        val ctx = getApplication<Application>()
        val info = DocumentOpener.queryInfo(ctx, uri)
        return try {
            val head = DocumentOpener.readHead(ctx, uri)
            if (head.isEmpty()) return ViewerState.Failed(info, "빈 파일입니다.")
            when (val type = DocumentTypeDetector.detect(head, info.displayName, info.mimeType)) {
                DocumentType.PDF -> ViewerState.Pdf(info, PdfController.open(DocumentOpener.openSeekable(ctx, uri)))
                DocumentType.HWP -> rich(info, type) { images ->
                    DocumentOpener.openSeekable(ctx, uri).use { f ->
                        // FileInputStream 의 채널은 소유하지 않는다 – pfd 는 SeekableFile 이 닫는다.
                        HwpRichParser.parse(FileChannelSource(FileInputStream(f.pfd.fileDescriptor).channel), images.sink)
                    }
                } ?: run {
                    val file = DocumentOpener.openSeekable(ctx, uri)
                    val doc = file.use { f ->
                        val channel = FileInputStream(f.pfd.fileDescriptor).channel
                        HwpParser.parse(FileChannelSource(channel))
                    }
                    ViewerState.Text(info, type, doc.withFallbackNotice())
                }
                DocumentType.HWPX -> rich(info, type) { images ->
                    DocumentOpener.openInput(ctx, uri).use { HwpxRichParser.parse(it.buffered(), images.sink) }
                } ?: run {
                    val doc = DocumentOpener.openInput(ctx, uri).use { HwpxParser.parse(it) }
                    ViewerState.Text(info, type, doc.withFallbackNotice())
                }
                DocumentType.UNKNOWN -> ViewerState.Failed(
                    info,
                    "지원하지 않는 파일 형식입니다.\nPDF, HWP, HWPX 파일만 열 수 있습니다.",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "load failed: ${info.displayName}", e)
            ViewerState.Failed(info, messageFor(e))
        }
    }

    /**
     * 원본 모양 파서 + 렌더러. 암호/미지원 문서는 그대로 오류로 알리고, 그 밖의 실패는 null 을 돌려
     * 기존 텍스트 보기로 대체한다 (원본을 못 그려도 내용은 보이게).
     */
    private fun rich(info: DocumentInfo, type: DocumentType, parse: (DocImageCache) -> RichDocument): ViewerState? {
        val images = DocImageCache.create(getApplication())
        return try {
            val doc = parse(images)
            val renderer = HtmlRenderer(doc)
            // 쪽 나누기/덩어리 계산을 미리 해 둬서 화면 전환 때 기다리지 않게
            renderer.pageCount
            renderer.flowChunkCount
            ViewerState.Rich(info, type, renderer, images, doc.notice)
        } catch (e: CancellationException) {
            images.delete()
            throw e
        } catch (e: EncryptedDocumentException) {
            images.delete()
            throw e
        } catch (e: UnsupportedDocumentException) {
            images.delete()
            throw e
        } catch (e: Throwable) {
            images.delete()
            Log.w(TAG, "rich view failed, falling back to text: ${info.displayName}", e)
            null
        }
    }

    private fun TextDocument.withFallbackNotice(): TextDocument =
        TextDocument(blocks, listOfNotNull("원본 모양을 그리지 못해 텍스트만 표시합니다.", notice).joinToString("\n"))

    private fun messageFor(e: Throwable): String = when (e) {
        is DocumentException -> e.message ?: "문서를 열 수 없습니다."
        is OutOfMemoryError -> "문서가 너무 커서 메모리가 부족합니다.\n다른 앱을 종료한 뒤 다시 시도해 주세요."
        is SecurityException -> "파일 접근 권한이 없습니다.\n파일을 다시 선택해 주세요."
        is java.io.FileNotFoundException -> "파일을 찾을 수 없습니다."
        is java.io.IOException -> "파일을 읽는 중 오류가 발생했습니다.\n(${e.message ?: e.javaClass.simpleName})"
        else -> "문서를 여는 중 오류가 발생했습니다.\n(${e.javaClass.simpleName}: ${e.message ?: ""})"
    }

    private fun releaseCurrent() {
        release(_state.value)
    }

    private fun release(state: ViewerState) {
        when (state) {
            is ViewerState.Pdf -> state.pdf.close()
            is ViewerState.Rich -> state.images.delete()
            else -> {}
        }
    }

    init {
        DocImageCache.cleanStale(app)
    }

    fun trimMemory() {
        (_state.value as? ViewerState.Pdf)?.pdf?.trimMemory()
    }

    override fun onCleared() {
        loadJob?.cancel()
        releaseCurrent()
    }

    companion object {
        private const val TAG = "ViewerViewModel"
    }
}
