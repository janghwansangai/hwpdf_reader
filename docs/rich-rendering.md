# 한글 문서 원본 모양 보기: 설계

HWP/HWPX 문서를 **원본 모양 그대로(쪽 보기)** 와 **화면에 맞춘 읽기 모드** 두 가지로 보여준다.
기존 텍스트 추출(`core/HwpParser.kt`, `core/HwpxParser.kt`, `TextDocument`)은 새 파서가 실패할 때의 예비 경로로 남긴다.

```
파일 ──► HwpRichParser / HwpxRichParser ──► RichDocument (core/doc/RichDocument.kt)
                    │ (그림 바이트)                     │
                    ▼                                   ▼
               ImageSink(캐시 파일)              HtmlRenderer (core/render)
                                                       │ shellHtml / pageHtml / flowChunkHtml / search
                                                       ▼
                                   Android WebView (viewer/RichDocView.kt) + ViewerActivity
```

## 1. 모델 (`core/doc/RichDocument.kt`)

이미 정의되어 있다. 이 문서에서 바꾸지 않는다. 바꿔야 하면 이유와 함께 보고한다.

핵심 규칙:
- 길이는 HWPUNIT(1/7200 inch). **HWP 의 문단 여백/들여쓰기/문단 간격은 파일에 2배로 저장**되어 있으니 파서가 2로 나눠 넣는다
  (참고: hwp2hwpx `header/ForParaProperties.java` 의 `/ 2`). 줄 간격도 PERCENT 가 아니면 `/2`.
  HWPX 는 `hp:switch` 의 `hp:case`(HwpUnitChar 네임스페이스) 값이 실제 값, `hp:default` 값은 2배 값이다.
- 색은 0xRRGGBB. HWP COLORREF 는 0x00BBGGRR 이므로 변환. 0xFFFFFFFF 등 "없음"은 null.
- `Paragraph.content` 의 `Inline.start` 와 `LineSeg.textStart` 는 같은 좌표계. HWP 는 원본 WCHAR 위치 그대로
  (일반 문자 1, 확장/인라인 컨트롤 8). HWPX 는 linesegarray 의 textpos 와 같은 좌표계가 되도록 파서가 위치를 센다
  (hwp2hwpx `section/ForPara.java` 가 HWP 값을 그대로 옮기므로 HWPX textpos 도 HWP WCHAR 위치와 같다:
  컨트롤 = 8, 일반 글자 = 1, 탭 = 8, 줄바꿈 = 1. 샘플로 확인할 것).
- 표 셀/글상자/머리말 안의 문단도 같은 `Paragraph` 이며, 그 `LineSeg.vertPos/horzStart` 는 **그 영역(셀 안쪽 여백 안) 기준**이다.
- 최상위 문단의 `LineSeg.vertPos` 는 **본문 영역 위쪽 기준**이며 쪽이 바뀌면 다시 작아진다(`flags bit0` = 쪽의 첫 줄).

## 2. 파서

### 2.1 HWP (`core/hwp/`)
- `HwpBinary.kt` (공용, 작성됨): 레코드 분해, 압축 해제, 배포용 복호화, `HwpReader`.
- `HwpDocInfo.kt` (공용 데이터, 작성됨) + **`HwpDocInfoReader.kt`**: `object HwpDocInfoReader { fun read(data: ByteArray, version: HwpVersion): HwpDocInfo }`
  - FACE_NAME(언어별 7묶음, ID_MAPPINGS 의 개수 순서), CHAR_SHAPE, PARA_SHAPE, BORDER_FILL(ID 1부터), NUMBERING(1부터), BULLET(1부터), STYLE(0부터), BIN_DATA.
- **`HwpBodyReader.kt`**: `object HwpBodyReader { fun readSection(data: ByteArray, info: HwpDocInfo, version: HwpVersion): Section }`
  - PARA_HEADER/PARA_TEXT/PARA_CHAR_SHAPE/PARA_LINE_SEG, CTRL_HEADER(secd, cold, tbl, gso, eqed, head, foot, fn, en, atno, nwno, pgnp, pghd, tdut, %hlk 등 필드), LIST_HEADER, TABLE, SHAPE_COMPONENT*, PAGE_DEF, FOOTNOTE_SHAPE.
  - 탭(코드 9)의 인라인 데이터에 탭 너비가 들어있다. 확장 컨트롤 순서대로 CTRL_HEADER 와 짝지어진다.
- **`HwpRichParser.kt`**: `object HwpRichParser { fun parse(src: RandomAccessSource, images: ImageSink?): RichDocument }`
  - FileHeader(압축/암호/배포용), DocInfo, BodyText 또는 ViewText(배포용) 섹션, BinData 스트림(압축 여부: 항목 설정 → 문서 설정) → ImageSink.
  - 암호 문서/HWP 3.0 은 기존과 같은 `EncryptedDocumentException`/`UnsupportedDocumentException`.
  - 손상 파일: 모든 RuntimeException 은 `CorruptDocumentException` 으로. 읽은 데까지는 살린다.
- 참고 구현: `/tmp/claude-0/hwplib-src` (hwplib, Java, 읽기 코드는 `reader/`), `/tmp/claude-0/ref-hwp.js` (TS 파서), `/tmp/claude-0/ref-pyhwp`.

### 2.2 HWPX (`core/hwpx/`)
- **`HwpxRichParser.kt`**: `object HwpxRichParser { fun parse(input: InputStream, images: ImageSink?): RichDocument }`
  - zip 을 한 번 스트리밍으로 읽는다(ZipInputStream). `Contents/content.hpf`(그림 ID ↔ 경로, 구역 순서), `Contents/header.xml`(글꼴, charPr, paraPr, borderFill, numbering, bullet, style), `Contents/sectionN.xml`, `BinData/*`.
  - 엔트리 순서는 보장되지 않으므로 header 가 section 뒤에 와도 동작해야 한다(섹션 XML 은 바이트로 보관 후 header 를 읽고 나서 해석하거나, ID 참조만 저장하고 마지막에 연결).
  - SAX 또는 XmlPullParser. XXE 차단. 네임스페이스 접두사(hp:, hh:, hc:, hs:)에 의존하지 말고 로컬 이름으로 처리.
- 참고: `/tmp/claude-0/hwpxlib-src` (읽기 코드), `/tmp/claude-0/ref-hwp2hwpx` (HWP→HWPX 대응표: 속성 의미가 같다).

## 3. 렌더러 (`core/render/`)

`HtmlRenderer.kt` 의 공개 API(작성됨, 본문은 TODO)를 구현한다. 공개 시그니처는 바꾸지 않는다.

### 3.1 원본 보기 (ViewMode.PAGE)
- 1 HWPUNIT = 1/75 CSS px. 글자 크기 pt → px = pt × 96 / 72.
- 쪽 나누기: 구역마다 새 쪽. 최상위 문단의 줄(LineSeg)이 `isFirstInPage` 이거나 vertPos 가 앞 줄보다 작아지면 새 쪽. `BreakType.PAGE` 도 새 쪽.
  LINE_SEG 가 없는 문단은 앞 줄 아래에 이어서 흐름 배치(높이 추정)하고 본문 높이를 넘으면 다음 쪽.
- 줄 배치: 각 줄을 `position:absolute; top: vertPos; left: horzStart; width: width; height: height` 상자로 그린다.
  줄 안 글자는 한 줄(nowrap). 정렬은 문단 모양(양쪽 정렬은 문단 마지막 줄이 아니면 `text-align-last: justify`).
  베이스라인은 LineSeg.baseline 에 맞춘다. 글꼴 차이로 넘치는 줄은 JS 가 가로 축소(scaleX)한다.
- 개체: 글자처럼 취급(treatAsChar)이면 줄 안의 inline-block. 아니면 기준(종이/쪽/단/문단)과 정렬/오프셋으로 절대 배치.
- 표: 셀 크기(width/height)와 행/열 주소로 열 경계/행 경계를 계산해 셀을 절대 배치(병합 셀 포함). 셀 테두리/배경은 BorderFill. 셀 안 문단도 같은 줄 배치.
- 머리말/꼬리말/쪽 번호, 각주(쪽 아래), 다단(줄 horzStart 로 자연스럽게 반영).
- 그림: `<img src="img/{id}.{ext}">` (상대 경로; ImageInfo.path). WMF/EMF 등 표시 불가 형식은 빈 상자 + "그림".
- 수식: 스크립트를 간단히 보기 좋게(기울임) 표시. 도형: 사각형/타원/선은 CSS/SVG 로 간단히.

### 3.2 읽기 모드 (ViewMode.FLOW)
- 원본의 **줄바꿈(Shift+Enter)·문단·빈 문단·정렬·들여쓰기/내어쓰기·문단 간격·줄 간격·글자 모양(굵게/기울임/밑줄/취소선/색/크기 비율/위첨자)** 을 살린다.
  글자 크기는 원본 pt 비율을 유지하되 기준(10pt)을 화면용 기본 크기로 맞춘다. 여백/들여쓰기는 화면 폭에 맞게 축소(예: em 단위).
- 표는 진짜 `<table>`(colspan/rowspan, 테두리, 배경, 셀 세로 정렬). 넓은 표는 가로 스크롤 상자 안에.
- 그림은 `max-width:100%`. 문단 번호/글머리표는 계산해서 표시.
- 문서를 덩어리(chunk)로 나눠 처음 덩어리만 shell 에 넣고, 나머지는 스크롤할 때 JS 가 차례로 붙인다.

### 3.3 공통
- 모든 문단 요소에 `data-p="{paraKey}"` (문서 전체에서 유일, 표 셀 안 문단 포함, 두 모드에서 같은 키). 원본 보기에서 한 문단이 여러 줄/쪽에 걸치면 각 줄 요소에 같은 data-p.
  자동 생성 텍스트(문단 번호, 쪽 번호 등)는 `class="gen"` 으로 감싸 검색 위치 계산에서 제외.
- 문서 텍스트는 반드시 HTML 이스케이프. 하이퍼링크는 http/https/mailto 만 `<a>` 로.
- 글꼴: 원본 글꼴 이름 + 대체(바탕/명조/궁서 계열 → serif, 그 외 → sans-serif). 한글 글꼴 이름(예: 함초롬바탕, 맑은 고딕)을 그대로 font-family 첫 번째로 둔다.
- 어두운 테마: 읽기 모드는 바탕/기본 글자색을 테마에 맞추고(검정에 가까운 글자색 → 밝은색), 원본 보기는 종이는 흰색 그대로, 바깥 배경만 어둡게.

### 3.4 HTML ↔ Android 계약

Android 는 `webView.loadDataWithBaseURL(BASE_URL, renderer.shellHtml(mode, options), "text/html", "utf-8", null)` 로 연다.
그림 요청 `BASE_URL + "img/..."` 은 `shouldInterceptRequest` 가 캐시 파일로 응답한다. 그 밖의 외부 요청은 모두 막는다.

**JS → Android**: `window.FRBridge` (Android `addJavascriptInterface(obj, "FRBridge")`). 없으면(테스트 브라우저) 무시.
| 메서드 | 설명 |
|---|---|
| `pageHtml(index: int): String` | 원본 보기 쪽 HTML 조각 (= renderer.pageHtml) |
| `flowHtml(index: int): String` | 읽기 모드 덩어리 HTML 조각 (= renderer.flowChunkHtml) |
| `onReady(mode: String)` | 첫 화면 배치 완료 ("page" / "flow") |
| `onPosition(json: String)` | 스크롤 위치 변경(최대 초당 5회). `{"mode":"page","page":3,"total":40,"progress":0.07}` / `{"mode":"flow","progress":0.3,"anchor":"p152"}` |
| `onLink(url: String)` | 문서 안 링크를 눌렀을 때 |

브리지가 없을 때(데스크톱 테스트) JS 는 `window.FR_FRAGMENTS = {page: [...], flow: [...]}` 가 있으면 그것을 쓴다.

**Android → JS**: `webView.evaluateJavascript("FR.xxx(...)")`
| 함수 | 설명 |
|---|---|
| `FR.goToPage(i)` | 원본 보기 i 쪽(0부터)으로 이동 |
| `FR.goToProgress(f)` | 0..1 위치로 이동 (두 모드) |
| `FR.state()` | 현재 위치 JSON 문자열 반환 (저장용; 두 모드 공통 형식 `{"mode":..,"page":..,"progress":..,"anchor":..}`) |
| `FR.restore(json)` | `FR.state()` 결과로 위치 복원 (모드가 달라도 anchor/progress 로 최대한) |
| `FR.showHit(json)` | `{"paraKey":"p12","offset":3,"length":2,"page":1,"chunk":0}` 위치로 이동하고 강조 (필요하면 쪽/덩어리 로드) |
| `FR.clearHits()` | 강조 제거 |
| `FR.setTextScale(s)` | 읽기 모드 글자 배율 (1.0 기본) |

## 4. Android (`viewer/`, `ViewerActivity.kt`)
- `viewer/RichDocView.kt`: WebView 를 감싸는 클래스. **androidx 를 쓰지 않고 android.* 만 사용**(로컬에서 android-all jar 로 컴파일 확인 가능하게).
  설정: JS on, 파일/콘텐츠 접근 off, 확대(내장 줌, 컨트롤 숨김), 외부 요청 차단, 링크는 콜백으로.
- `ViewerViewModel`: HWP/HWPX 는 Rich 파서 → `HtmlRenderer`. 실패하면 기존 텍스트 파서로 대체(안내 문구 표시).
  그림은 `cacheDir/docimg/<문서별 폴더>/` 에 저장, 문서를 닫으면 삭제.
- 하단 도구 막대(한글 문서): 검색 · 보기 전환(원본/읽기) · 글자(읽기 모드) · 쪽/위치 · 공유.
  보기 모드와 읽기 모드 글자 배율은 설정에 저장. 위치는 문서별로 `FR.state()` 를 저장/복원.
- 검색: `renderer.search` 결과를 이전/다음으로 `FR.showHit`.

## 5. 테스트 / 확인
- 파서/렌더러는 JVM 단위 테스트(`app/src/test`)로 확인. 샘플: `app/src/test/resources/samples/`, 추가 샘플 `/tmp/claude-0/hwplib-src/sample_hwp`, `/tmp/claude-0/hwpxlib-src/testFile`.
- 화면 확인: 렌더러 HTML 을 Playwright Chromium(안드로이드 WebView 와 같은 엔진)으로 휴대폰 폭(412px) 스크린샷.
