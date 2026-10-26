package com.fastreader.app.core.doc

/*
 * 한글(HWP/HWPX) 문서의 "모양 정보까지 포함한" 공통 모델.
 *
 * HWP 파서와 HWPX 파서는 모두 이 모델을 만들고, 렌더러(원본 쪽 보기 / 읽기 모드)는 이 모델만 본다.
 *
 * 규칙
 * - 길이 단위는 모두 HWPUNIT(1/7200 inch). HWP 파일에 2배로 저장된 값(문단 여백 등)은 파서가 실제 값으로 바꿔 넣는다.
 * - 색은 0xRRGGBB 정수. "없음/투명"은 null.
 * - 모양 ID 는 원본 문서의 번호 그대로 Map 키로 쓴다. 없는 ID 는 렌더러가 기본값을 쓴다.
 * - 모델은 만든 뒤 바뀌지 않는다(여러 스레드에서 동시에 읽어도 안전해야 함).
 */

class RichDocument(
    val charShapes: Map<Int, CharShape>,
    val paraShapes: Map<Int, ParaShape>,
    val borderFills: Map<Int, BorderFill>,
    val numberings: Map<Int, Numbering>,
    val bullets: Map<Int, Bullet>,
    val styles: Map<Int, Style>,
    val sections: List<Section>,
    /** 이미지 ID → 정보. 실제 바이트는 파싱 중 [ImageSink] 로 전달된다. */
    val images: Map<String, ImageInfo>,
    /** 사용자에게 보여줄 안내 (배포용 문서, 일부 손상 등). 없으면 null */
    val notice: String? = null,
)

// ---------------------------------------------------------------- 글자 모양

/** 언어 구분. HWP 의 7개 언어별 글꼴/장평/자간/크기/위치 배열 순서와 같다. */
enum class Lang { HANGUL, LATIN, HANJA, JAPANESE, OTHER, SYMBOL, USER }

data class CharShape(
    /** 언어별 글꼴 이름 (Lang 순서, 7개). 모르면 "" */
    val fontNames: List<String>,
    /** 언어별 장평 % (기본 100) */
    val ratios: List<Int>,
    /** 언어별 자간 % (기본 0, 글자 크기 대비) */
    val spacings: List<Int>,
    /** 언어별 상대 크기 % (기본 100) */
    val relSizes: List<Int>,
    /** 언어별 글자 위치 % (기본 0, +는 위로) */
    val offsets: List<Int>,
    /** 기준 크기, 1/100 pt (예: 1000 = 10pt) */
    val baseSize: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: UnderlinePos = UnderlinePos.NONE,
    val underlineStyle: LineType = LineType.SOLID,
    val underlineColor: Int? = null,
    val strike: Boolean = false,
    val strikeStyle: LineType = LineType.SOLID,
    val strikeColor: Int? = null,
    val outline: Boolean = false,
    val shadow: Boolean = false,
    val shadowColor: Int? = null,
    val emboss: Boolean = false,
    val engrave: Boolean = false,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    /** 강조점 (0 = 없음) */
    val emphasis: Int = 0,
    val textColor: Int = 0x000000,
    /** 글자 배경(음영)색. 없으면 null (HWP 의 0xFFFFFFFF/흰색 "없음" 포함) */
    val shadeColor: Int? = null,
    /** 글자 테두리/배경 BorderFill ID. 없으면 0 */
    val borderFillId: Int = 0,
) {
    fun fontName(lang: Lang) = fontNames.getOrElse(lang.ordinal) { "" }
    fun ratio(lang: Lang) = ratios.getOrElse(lang.ordinal) { 100 }
    fun spacing(lang: Lang) = spacings.getOrElse(lang.ordinal) { 0 }
    fun relSize(lang: Lang) = relSizes.getOrElse(lang.ordinal) { 100 }
    fun offset(lang: Lang) = offsets.getOrElse(lang.ordinal) { 0 }

    companion object {
        val DEFAULT = CharShape(
            fontNames = List(7) { "" }, ratios = List(7) { 100 }, spacings = List(7) { 0 },
            relSizes = List(7) { 100 }, offsets = List(7) { 0 }, baseSize = 1000,
        )
    }
}

enum class UnderlinePos { NONE, BOTTOM, TOP }

/** 선 종류. HWP/HWPX 의 선 종류를 CSS 로 표현 가능한 수준으로 묶었다. */
enum class LineType { NONE, SOLID, DASH, DOT, DASH_DOT, DASH_DOT_DOT, LONG_DASH, CIRCLE, DOUBLE, THIN_THICK, THICK_THIN, THIN_THICK_THIN, WAVE, DOUBLE_WAVE, THICK_3D, THICK_3D_REV, SOLID_3D, SOLID_3D_REV }

// ---------------------------------------------------------------- 문단 모양

enum class Align { JUSTIFY, LEFT, RIGHT, CENTER, DISTRIBUTE, DISTRIBUTE_SPACE }

enum class LineSpacingType {
    /** 글자에 따라 % */
    PERCENT,

    /** 고정 값 (HWPUNIT) */
    FIXED,

    /** 여백만 지정 (줄 사이 간격, HWPUNIT) */
    BETWEEN_LINES,

    /** 최소 (HWPUNIT) */
    AT_LEAST,
}

enum class HeadingType { NONE, OUTLINE, NUMBER, BULLET }

data class ParaShape(
    val align: Align = Align.JUSTIFY,
    /** 실제 HWPUNIT (HWP 원본의 2배 저장 값을 이미 나눈 값) */
    val marginLeft: Int = 0,
    val marginRight: Int = 0,
    /** 첫 줄 들여쓰기(+) / 내어쓰기(-) */
    val indent: Int = 0,
    val spacingBefore: Int = 0,
    val spacingAfter: Int = 0,
    val lineSpacingType: LineSpacingType = LineSpacingType.PERCENT,
    /** PERCENT 이면 %, 나머지는 HWPUNIT */
    val lineSpacing: Int = 160,
    val headingType: HeadingType = HeadingType.NONE,
    /** 문단 머리 번호/글머리표 ID (headingType 이 NUMBER/BULLET 일 때), OUTLINE 이면 구역의 개요 번호 ID 를 쓴다 */
    val headingId: Int = 0,
    /** 문단 수준 0..9 */
    val headingLevel: Int = 0,
    val borderFillId: Int = 0,
    val borderOffsetLeft: Int = 0,
    val borderOffsetRight: Int = 0,
    val borderOffsetTop: Int = 0,
    val borderOffsetBottom: Int = 0,
    val keepWithNext: Boolean = false,
    val pageBreakBefore: Boolean = false,
) {
    companion object {
        val DEFAULT = ParaShape()
    }
}

data class Style(val name: String, val englishName: String = "", val paraShapeId: Int = 0, val charShapeId: Int = 0)

// ---------------------------------------------------------------- 테두리/배경

data class Border(
    val type: LineType = LineType.NONE,
    /** mm */
    val widthMm: Float = 0.12f,
    val color: Int = 0x000000,
) {
    val visible get() = type != LineType.NONE
}

data class Gradient(
    /** 0 줄무늬(선형), 1 원형, 2 원뿔, 3 사각 */
    val type: Int,
    val angle: Int,
    val centerX: Int,
    val centerY: Int,
    val colors: List<Int>,
)

data class BorderFill(
    val left: Border = Border(),
    val right: Border = Border(),
    val top: Border = Border(),
    val bottom: Border = Border(),
    /** 대각선 (있으면) */
    val diagonal: Border? = null,
    val backDiagonal: Border? = null,
    /** 단색 채우기(면색). 없으면 null */
    val fillColor: Int? = null,
    /** 무늬색/무늬 종류 (-1 = 무늬 없음) */
    val patternColor: Int? = null,
    val patternType: Int = -1,
    val gradient: Gradient? = null,
    /** 그림 채우기 이미지 ID */
    val imageFillId: String? = null,
) {
    companion object {
        val NONE = BorderFill()
    }
}

// ---------------------------------------------------------------- 문단 번호 / 글머리표

enum class NumberType {
    DIGIT, CIRCLED_DIGIT, ROMAN_UPPER, ROMAN_LOWER, LATIN_UPPER, LATIN_LOWER,
    CIRCLED_LATIN_UPPER, CIRCLED_LATIN_LOWER, HANGUL_SYLLABLE, CIRCLED_HANGUL_SYLLABLE,
    HANGUL_JAMO, CIRCLED_HANGUL_JAMO, HANGUL_DIGIT, HANJA_DIGIT, CIRCLED_HANJA_DIGIT,
    HANJA_GAP, HANJA_GAP_LOWER, SYMBOL,
}

data class NumberingLevel(
    /** 번호 서식 문자열. "^1." 처럼 ^n 은 n 수준 번호로 바뀐다. 빈 문자열이면 번호 없음 */
    val format: String,
    val numberType: NumberType = NumberType.DIGIT,
    val startNumber: Int = 1,
    /** 번호의 글자 모양 ID (-1 = 문단 첫 글자 모양 사용) */
    val charShapeId: Int = -1,
    val align: Align = Align.LEFT,
    /** 번호 너비 보정 / 본문과의 거리 (HWPUNIT) */
    val widthAdjust: Int = 0,
    val textOffset: Int = 0,
)

data class Numbering(val levels: List<NumberingLevel>, val startNumber: Int = 1)

data class Bullet(val char: String, val charShapeId: Int = -1, val imageId: String? = null)

// ---------------------------------------------------------------- 구역 / 쪽

data class PageDef(
    /** 실제 용지 방향을 반영한 폭/높이 (가로 방향이면 이미 바꿔서 넣음) */
    val width: Int = 59528,
    val height: Int = 84188,
    val marginLeft: Int = 8504,
    val marginRight: Int = 8504,
    val marginTop: Int = 5668,
    val marginBottom: Int = 4252,
    val marginHeader: Int = 4252,
    val marginFooter: Int = 4252,
    val marginGutter: Int = 0,
    val landscape: Boolean = false,
) {
    /** 본문 영역 */
    val bodyLeft get() = marginLeft + marginGutter
    val bodyTop get() = marginTop + marginHeader
    val bodyWidth get() = width - marginLeft - marginRight - marginGutter
    val bodyHeight get() = height - marginTop - marginBottom - marginHeader - marginFooter
}

data class ColumnDef(val count: Int = 1, val gap: Int = 0, val widths: List<Int> = emptyList(), val separator: Border? = null)

enum class PageApply { BOTH, EVEN, ODD }

class HeaderFooter(
    val apply: PageApply,
    val paragraphs: List<Paragraph>,
    /** 머리말/꼬리말 영역 크기 (있으면) */
    val width: Int = 0,
    val height: Int = 0,
    /** 이 머리말이 처음 나타나는 문단 번호(구역 최상위 문단 index). 그 문단이 있는 쪽부터 적용 */
    val fromParagraph: Int = 0,
)

enum class PageNumberPos { NONE, TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT, OUTSIDE_TOP, OUTSIDE_BOTTOM, INSIDE_TOP, INSIDE_BOTTOM }

data class PageNumbering(
    val position: PageNumberPos,
    val numberType: NumberType = NumberType.DIGIT,
    val prefix: String = "",
    val suffix: String = "",
    /** 줄표 등 장식 문자 (예: "-") */
    val dash: String = "",
    val charShapeId: Int = -1,
    val fromParagraph: Int = 0,
)

class Section(
    val page: PageDef,
    val columns: ColumnDef = ColumnDef(),
    val paragraphs: List<Paragraph>,
    val headers: List<HeaderFooter> = emptyList(),
    val footers: List<HeaderFooter> = emptyList(),
    val pageNumbering: PageNumbering? = null,
    /** 이 구역의 시작 쪽 번호 (0 = 앞 구역에 이어서) */
    val startPageNumber: Int = 0,
    /** 개요 번호에 쓰는 Numbering ID */
    val outlineNumberingId: Int = 0,
    /** 첫 쪽에 머리말/꼬리말/쪽번호 감추기 */
    val hideFirstHeader: Boolean = false,
    val hideFirstFooter: Boolean = false,
    val hideFirstPageNumber: Boolean = false,
)

// ---------------------------------------------------------------- 문단

/**
 * 문단.
 *
 * 위치(position) 규칙: [content] 의 각 [Inline] 은 [Inline.start] 위치에서 시작한다.
 * [Inline.Text] 는 [start, start + text.length) 를 차지하고, 그 밖의 Inline 은 start 한 칸으로 본다.
 * [LineSeg.textStart] 와 [Inline.start] 는 같은 좌표계(파서가 정함, HWP 는 원본 WCHAR 위치)이며 단조 증가한다.
 * 렌더러는 이 두 값을 비교만 한다 (줄 시작 위치가 Text 중간이면 그 지점에서 자른다).
 */
class Paragraph(
    val paraShapeId: Int,
    val styleId: Int = 0,
    /** 문단 앞에서 강제로 나누기 */
    val breakType: BreakType = BreakType.NONE,
    val content: List<Inline>,
    /** 원본에서 계산된 줄 배치. 없을 수 있다(그러면 렌더러가 흐름 배치) */
    val lines: List<LineSeg> = emptyList(),
    /** 변경 추적/숨은 설명 등으로 숨겨진 문단이면 true (렌더링 생략) */
    val hidden: Boolean = false,
) {
    /** 검색/복사용 순수 텍스트 (개체는 제외, 탭은 \t, 줄바꿈은 \n) */
    val plainText: String by lazy {
        buildString {
            for (i in content) when (i) {
                is Inline.Text -> append(i.text)
                is Inline.Tab -> append('\t')
                is Inline.LineBreak -> append('\n')
                else -> {}
            }
        }
    }
}

enum class BreakType { NONE, PAGE, COLUMN, SECTION }

data class LineSeg(
    /** 이 줄이 시작하는 텍스트 위치 */
    val textStart: Int,
    /** 줄의 세로 위치 (본문/셀/글상자 등 "그 문단을 담는 영역"의 위쪽 기준) */
    val vertPos: Int,
    /** 줄 높이 (개체 포함) */
    val height: Int,
    /** 글자 부분 높이 */
    val textHeight: Int,
    /** 줄 위쪽에서 베이스라인까지 거리 */
    val baseline: Int,
    /** 줄 간격 (이 줄 아래에 추가로 띄우는 거리) */
    val spacing: Int,
    /** 단(column) 왼쪽 기준 줄 시작 가로 위치 */
    val horzStart: Int,
    /** 줄(세그먼트) 폭 */
    val width: Int,
    /** 원본 태그 비트. bit0: 쪽의 첫 줄, bit1: 단의 첫 줄, bit16: 빈 세그먼트, bit17: 줄의 첫 세그먼트, bit18: 줄의 마지막 세그먼트 */
    val flags: Int = 0,
) {
    val isFirstInPage get() = flags and 1 != 0
    val isFirstInColumn get() = flags and 2 != 0
}

sealed class Inline {
    abstract val start: Int

    /** 같은 글자 모양의 연속 문자열. 일반 공백/특수 공백(  묶음빈칸,   고정폭빈칸)/하이픈 포함 */
    data class Text(override val start: Int, val text: String, val charShapeId: Int) : Inline()

    /** 탭. width 는 원본에 기록된 탭 너비(HWPUNIT, 모르면 0) */
    data class Tab(override val start: Int, val charShapeId: Int, val width: Int = 0, val leader: LineType = LineType.NONE) : Inline()

    /** 강제 줄 바꿈 (Shift+Enter) */
    data class LineBreak(override val start: Int, val charShapeId: Int) : Inline()

    /** 표/그림/도형/글상자/수식 등 개체 */
    data class Object(override val start: Int, val charShapeId: Int, val obj: DrawObject) : Inline()

    /** 각주/미주 참조. 본문에는 번호(위첨자)로, 내용은 쪽 아래/구역 끝에 */
    data class Note(override val start: Int, val charShapeId: Int, val endnote: Boolean, val number: Int, val paragraphs: List<Paragraph>) : Inline()

    /** 자동 번호 (쪽 번호, 전체 쪽 수, 각주 번호 등) */
    data class AutoNumber(override val start: Int, val charShapeId: Int, val kind: AutoNumberKind, val numberType: NumberType = NumberType.DIGIT, val number: Int = 0) : Inline()

    /** 필드 시작/끝 (하이퍼링크 등). url 은 하이퍼링크일 때만 */
    data class FieldStart(override val start: Int, val id: Int, val kind: FieldKind, val url: String? = null) : Inline()
    data class FieldEnd(override val start: Int, val id: Int) : Inline()

    /** 덧말(본말 위/아래 작은 글씨) */
    data class Ruby(override val start: Int, val charShapeId: Int, val base: String, val annotation: String, val below: Boolean = false) : Inline()
}

enum class AutoNumberKind { PAGE, TOTAL_PAGES, FOOTNOTE, ENDNOTE, PICTURE, TABLE, EQUATION }

enum class FieldKind { HYPERLINK, CLICK_HERE, OTHER }

// ---------------------------------------------------------------- 개체

enum class VertRel { PAPER, PAGE, PARA }
enum class HorzRel { PAPER, PAGE, COLUMN, PARA }
enum class ObjAlign { START, CENTER, END, INSIDE, OUTSIDE }
enum class TextWrap { SQUARE, TIGHT, THROUGH, TOP_AND_BOTTOM, BEHIND_TEXT, IN_FRONT_OF_TEXT }

data class Insets(val left: Int = 0, val right: Int = 0, val top: Int = 0, val bottom: Int = 0) {
    companion object {
        val ZERO = Insets()
    }
}

/** 개체 공통 배치 속성 */
data class ObjectLayout(
    /** 글자처럼 취급 (줄 안에 놓임) */
    val treatAsChar: Boolean = true,
    val vertRelTo: VertRel = VertRel.PARA,
    val horzRelTo: HorzRel = HorzRel.COLUMN,
    /** START = 위/왼쪽, END = 아래/오른쪽 */
    val vertAlign: ObjAlign = ObjAlign.START,
    val horzAlign: ObjAlign = ObjAlign.START,
    val vertOffset: Int = 0,
    val horzOffset: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val zOrder: Int = 0,
    val outMargin: Insets = Insets.ZERO,
    val textWrap: TextWrap = TextWrap.TOP_AND_BOTTOM,
    /** 개체 설명(대체 텍스트) */
    val description: String = "",
)

data class Caption(
    /** 0 왼쪽, 1 오른쪽, 2 위, 3 아래 */
    val side: Int,
    val width: Int,
    val gap: Int,
    val paragraphs: List<Paragraph>,
)

sealed class DrawObject {
    abstract val layout: ObjectLayout
    abstract val caption: Caption?
}

class Table(
    override val layout: ObjectLayout,
    override val caption: Caption? = null,
    val rowCount: Int,
    val colCount: Int,
    val cellSpacing: Int = 0,
    val inMargin: Insets = Insets.ZERO,
    val borderFillId: Int = 0,
    val cells: List<Cell>,
    /** 제목 줄 반복 */
    val repeatHeader: Boolean = false,
) : DrawObject()

class Cell(
    val col: Int,
    val row: Int,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    /** 셀 크기 (HWPUNIT) */
    val width: Int,
    val height: Int,
    val margin: Insets = Insets(141, 141, 141, 141),
    val borderFillId: Int = 0,
    /** 0 위, 1 가운데, 2 아래 */
    val vertAlign: Int = 0,
    val paragraphs: List<Paragraph>,
    val header: Boolean = false,
)

class Picture(
    override val layout: ObjectLayout,
    override val caption: Caption? = null,
    /** [RichDocument.images] 의 키. 없으면 null (빈 상자로 표시) */
    val imageId: String?,
    /**
     * 원본 이미지에서 남겨 쓰는 영역의 좌표 (왼쪽, 위, 오른쪽, 아래; 원본 이미지 크기 [imageWidth]x[imageHeight] 기준 HWPUNIT).
     * 넷 다 0 이면 자르지 않는다.
     */
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropRight: Int = 0,
    val cropBottom: Int = 0,
    /** 원본 이미지 크기 (HWPUNIT, 모르면 0) */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** 그림 테두리 */
    val border: Border = Border(),
    /** 0 원본, 1 회색조, 2 흑백, 3 패턴 */
    val effect: Int = 0,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val alpha: Int = 0,
    /** 그림 안쪽 여백 */
    val inMargin: Insets = Insets.ZERO,
) : DrawObject()

enum class ShapeKind { RECT, ELLIPSE, LINE, ARC, POLYGON, CURVE, CONTAINER, OLE, TEXT_ART, OTHER }

class Shape(
    override val layout: ObjectLayout,
    override val caption: Caption? = null,
    val kind: ShapeKind,
    val line: Border = Border(LineType.SOLID, 0.12f, 0),
    val fill: BorderFill? = null,
    /** 사각형 모서리 곡률 % */
    val cornerRadius: Int = 0,
    /** 선/다각형/곡선의 점 (개체 영역 기준 HWPUNIT, x,y 쌍) */
    val points: List<Int> = emptyList(),
    /** 글상자 내용 (없으면 빈 목록) */
    val paragraphs: List<Paragraph> = emptyList(),
    val textMargin: Insets = Insets(283, 283, 283, 283),
    /** 글상자 세로 정렬 0 위 1 가운데 2 아래 */
    val textVertAlign: Int = 0,
    /** 묶음 개체의 자식들. 자식 layout 의 horzOffset/vertOffset 은 묶음 영역 기준 */
    val children: List<DrawObject> = emptyList(),
    /** 글맵시 등 글자 (TEXT_ART) */
    val text: String = "",
) : DrawObject()

class Equation(
    override val layout: ObjectLayout,
    override val caption: Caption? = null,
    /** 한글 수식 스크립트 (예: "{a+b} over 2") */
    val script: String,
    /** 1/100 pt */
    val baseSize: Int = 1000,
    val color: Int = 0,
) : DrawObject()

// ---------------------------------------------------------------- 이미지

data class ImageInfo(val id: String, val extension: String) {
    val mimeType: String
        get() = when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "tif", "tiff" -> "image/tiff"
            "wmf" -> "image/wmf"
            "emf" -> "image/emf"
            else -> "application/octet-stream"
        }

    /** WebView 가 직접 그릴 수 있는 형식인가 */
    val displayable: Boolean
        get() = extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg")

    /** 렌더러가 HTML 에 쓰는 상대 경로 */
    val path: String get() = "img/$id.${extension.lowercase()}"
}

/**
 * 파서가 문서 안의 그림 데이터를 넘겨주는 곳. Android 에서는 캐시 폴더에 파일로 저장한다.
 * [put] 은 파싱 스레드에서 호출되며 data 스트림은 호출이 끝나면 닫힌다.
 */
fun interface ImageSink {
    fun put(info: ImageInfo, data: java.io.InputStream)
}
