package com.fastreader.app.core.render

import com.fastreader.app.core.doc.RichDocument

/**
 * RichDocument → HTML.
 *
 * 두 가지 보기를 만든다.
 * - [ViewMode.PAGE] 원본 보기: 원본의 쪽 크기/여백/줄 위치(LINE_SEG)를 그대로 써서 한글과 같은 모양으로 쪽 단위 표시.
 * - [ViewMode.FLOW] 읽기 모드: 화면 폭에 맞춰 다시 흐르게 하되 글자/문단 모양, 줄바꿈, 들여쓰기, 표, 그림을 살린다.
 *
 * 스레드 안전해야 한다 (WebView 자바스크립트 브리지는 백그라운드 스레드에서 [pageHtml]/[flowChunkHtml] 을 부른다).
 * 큰 문서도 빨리 열리도록 쪽/덩어리 단위로 필요할 때 만든다.
 *
 * HTML ↔ Android 계약은 docs/rich-rendering.md 참고.
 */
class HtmlRenderer(val doc: RichDocument) {

    /** 원본 보기 쪽 수 (구역 전체) */
    val pageCount: Int
        get() = TODO("renderer")

    /** 읽기 모드 덩어리 수 */
    val flowChunkCount: Int
        get() = TODO("renderer")

    /** 처음 로드하는 전체 HTML 문서 (CSS, JS 포함) */
    fun shellHtml(mode: ViewMode, options: RenderOptions = RenderOptions()): String = TODO("renderer")

    /** 원본 보기 index 번째 쪽의 내부 HTML 조각 */
    fun pageHtml(index: Int): String = TODO("renderer")

    /** 읽기 모드 index 번째 덩어리의 HTML 조각 */
    fun flowChunkHtml(index: Int): String = TODO("renderer")

    /** 본문 검색 (대소문자 무시). 문서 순서대로 최대 limit 개 */
    fun search(query: String, limit: Int = 2000): List<SearchHit> = TODO("renderer")

    /** 전체 텍스트 (복사용). maxChars 를 넘으면 자른다 */
    fun plainText(maxChars: Int = 300_000): String = TODO("renderer")
}

enum class ViewMode { PAGE, FLOW }

data class RenderOptions(
    /** 앱이 어두운 테마인지 (읽기 모드 색/원본 보기 바탕색에 반영) */
    val darkMode: Boolean = false,
    /** 읽기 모드 글자 배율 (1.0 = 기본) */
    val textScale: Float = 1f,
)

/**
 * 검색 결과.
 * @param paraKey 문단 요소의 data-p 값
 * @param offset 그 문단 plainText 안에서의 시작 위치
 * @param page 원본 보기에서 그 위치가 있는 쪽 (0부터)
 * @param chunk 읽기 모드에서 그 문단이 있는 덩어리 (0부터)
 * @param preview 결과 주변 문장 (목록 표시용)
 */
data class SearchHit(
    val paraKey: String,
    val offset: Int,
    val length: Int,
    val page: Int,
    val chunk: Int,
    val preview: String,
)

/** WebView 기준 주소. 그림은 BASE_URL + ImageInfo.path 로 요청된다 */
const val BASE_URL = "https://fastreader.local/"
