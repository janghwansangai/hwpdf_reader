package com.fastreader.app.viewer

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fastreader.app.core.render.BASE_URL
import com.fastreader.app.core.render.HtmlRenderer
import com.fastreader.app.core.render.RenderOptions
import com.fastreader.app.core.render.SearchHit
import com.fastreader.app.core.render.ViewMode
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.util.concurrent.Executors

/**
 * 한글 문서(원본 보기/읽기 모드)를 보여주는 WebView 래퍼.
 * - HTML 은 [HtmlRenderer] 가 만들고, 쪽/덩어리는 자바스크립트가 필요할 때 브리지로 요청한다.
 * - 그림 요청만 문서별 캐시 폴더에서 응답하고, 그 밖의 네트워크/파일 접근은 모두 막는다.
 * - WebView 렌더러 프로세스가 죽어도 앱을 종료시키지 않고 새 WebView 로 다시 연다.
 * (androidx 를 쓰지 않는다: android.* 만으로 컴파일 확인할 수 있게)
 */
class RichDocView(private val container: ViewGroup, private val listener: Listener) {

    interface Listener {
        fun onReady(mode: String)
        fun onPosition(json: String)
        fun onLink(url: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rich-shell") }
    private var web: WebView? = null

    @Volatile
    private var renderer: HtmlRenderer? = null

    @Volatile
    private var images: DocImageCache? = null

    private var mode = ViewMode.PAGE
    private var options = RenderOptions()
    private var loadToken = 0
    private var destroyed = false

    /** 마지막으로 받은 위치 (렌더러 프로세스가 죽었을 때 복원용) */
    var lastPosition: String? = null
        private set

    val currentMode: ViewMode get() = mode

    fun load(renderer: HtmlRenderer, images: DocImageCache?, mode: ViewMode, options: RenderOptions) {
        if (destroyed) return
        this.renderer = renderer
        this.images = images
        this.mode = mode
        this.options = options
        val token = ++loadToken
        val view = ensureWebView()
        view.setBackgroundColor(backgroundColor())
        // 쉘 HTML 은 큰 문서에서 수십 ms 걸릴 수 있어 백그라운드에서 만든다
        worker.execute {
            val html = try {
                renderer.shellHtml(mode, options)
            } catch (e: RuntimeException) {
                "<!doctype html><meta charset=\"utf-8\"><body style=\"font:16px sans-serif;padding:24px\">문서를 표시하지 못했습니다.</body>"
            } catch (e: OutOfMemoryError) {
                "<!doctype html><meta charset=\"utf-8\"><body style=\"font:16px sans-serif;padding:24px\">메모리가 부족해 문서를 표시하지 못했습니다.</body>"
            }
            main.post {
                if (destroyed || token != loadToken) return@post
                web?.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
            }
        }
    }

    fun goToPage(index: Int) = eval("FR.goToPage(" + index.coerceAtLeast(0) + ")")

    fun goToProgress(fraction: Float) = eval("FR.goToProgress(" + fraction.coerceIn(0f, 1f) + ")")

    fun restore(stateJson: String) = eval("FR.restore(" + JSONObject.quote(stateJson) + ")")

    fun showHit(hit: SearchHit) {
        val o = JSONObject()
            .put("paraKey", hit.paraKey)
            .put("offset", hit.offset)
            .put("length", hit.length)
            .put("page", hit.page)
            .put("chunk", hit.chunk)
        eval("FR.showHit(" + JSONObject.quote(o.toString()) + ")")
    }

    fun clearHits() = eval("FR.clearHits()")

    fun setTextScale(scale: Float) {
        options = options.copy(textScale = scale)
        eval("FR.setTextScale(" + scale.coerceIn(0.6f, 3f) + ")")
    }

    /** 현재 위치 JSON (FR.state()) 을 비동기로 받는다. 실패하면 null */
    fun requestState(callback: (String?) -> Unit) {
        val w = web ?: return callback(lastPosition)
        w.evaluateJavascript("window.FR ? FR.state() : null") { result ->
            val s = try {
                JSONTokener(result ?: "null").nextValue() as? String
            } catch (e: Exception) {
                null
            }
            callback(s ?: lastPosition)
        }
    }

    fun onPause() {
        web?.onPause()
    }

    fun onResume() {
        web?.onResume()
    }

    fun destroy() {
        destroyed = true
        loadToken++
        renderer = null
        worker.shutdownNow()
        destroyWebView()
    }

    // ------------------------------------------------------------------ 내부

    private fun eval(js: String) {
        web?.evaluateJavascript("if(window.FR){$js}", null)
    }

    private fun backgroundColor(): Int = when {
        mode == ViewMode.PAGE && options.darkMode -> Color.rgb(0x2B, 0x2C, 0x2F)
        mode == ViewMode.PAGE -> Color.rgb(0xE8, 0xEA, 0xED)
        options.darkMode -> Color.rgb(0x14, 0x14, 0x14)
        else -> Color.WHITE
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun ensureWebView(): WebView {
        web?.let { return it }
        val w = WebView(container.context)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = false
        }
        w.isVerticalScrollBarEnabled = true
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = WebView.OVER_SCROLL_NEVER
        w.addJavascriptInterface(Bridge(), "FRBridge")
        w.webViewClient = Client()
        container.addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w
        return w
    }

    private fun destroyWebView() {
        val w = web ?: return
        web = null
        try {
            (w.parent as? ViewGroup)?.removeView(w)
            w.stopLoading()
            w.webViewClient = WebViewClient()
            w.removeJavascriptInterface("FRBridge")
            w.destroy()
        } catch (e: Exception) {
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (url.startsWith(BASE_URL + "img/")) {
                val name = request.url.lastPathSegment ?: ""
                val f = images?.file(name)
                if (f != null) {
                    return try {
                        WebResourceResponse(mimeOf(name), null, FileInputStream(f))
                    } catch (e: Exception) {
                        notFound()
                    }
                }
            }
            // 문서 HTML 외의 모든 요청 차단 (네트워크/파일)
            return notFound()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (request.isForMainFrame && !url.startsWith(BASE_URL)) main.post { listener.onLink(url) }
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // 메모리 부족 등으로 WebView 프로세스가 죽음: 새 WebView 로 다시 열고 위치 복원
            if (view !== web) return true
            destroyWebView()
            val r = renderer
            if (!destroyed && r != null) {
                main.post {
                    if (!destroyed) load(r, images, mode, options)
                }
            }
            return true
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    /** 자바스크립트 → Android. 브리지 호출은 백그라운드 스레드에서 온다 */
    private inner class Bridge {
        @JavascriptInterface
        fun pageHtml(index: Int): String = try {
            renderer?.pageHtml(index) ?: ""
        } catch (e: Throwable) {
            ""
        }

        @JavascriptInterface
        fun flowHtml(index: Int): String = try {
            renderer?.flowChunkHtml(index) ?: ""
        } catch (e: Throwable) {
            ""
        }

        @JavascriptInterface
        fun onReady(m: String) {
            main.post {
                if (destroyed) return@post
                // 렌더러 프로세스 재시작 뒤라면 마지막 위치로
                listener.onReady(m)
            }
        }

        @JavascriptInterface
        fun onPosition(json: String) {
            main.post {
                if (destroyed) return@post
                lastPosition = json
                listener.onPosition(json)
            }
        }

        @JavascriptInterface
        fun onLink(url: String) {
            main.post { if (!destroyed) listener.onLink(url) }
        }
    }
}
