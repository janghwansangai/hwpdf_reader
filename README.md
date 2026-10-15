# 초고속 문서리더 (PDF / HWP / HWPX)

휴대폰에서 PDF와 한글 문서를 빠르게 열어 보는 안드로이드 앱입니다. (Android 8.0 이상)

## 기능

- **PDF**: 안드로이드 내장 PdfRenderer로 페이지를 필요한 만큼만 그려서 큰 파일도 바로 열립니다.
  핀치·더블탭 확대, 확대 시 고해상도 재렌더링, 야간(반전) 보기, 페이지 이동.
- **HWP (한글 5.0 이상)**: 외부 라이브러리 없이 직접 구현한 파서로 본문을 추출합니다.
  표는 행 단위로 묶어 보여주고, **배포용 문서(읽기 전용)** 도 열립니다.
- **HWPX**: XML을 스트리밍으로 읽어 메모리를 적게 씁니다.
- 본문 검색(결과 이동·강조), 글자 크기 조절(두 손가락 또는 슬라이더), 읽은 위치 기억.
- 최근 문서, 공유 / 다른 앱으로 열기, 전체 텍스트 복사, 다크 모드.
- 파일 관리자·카카오톡·메일 등에서 "열기"와 "공유"로 바로 열 수 있습니다.
  파일 이름이나 MIME 형식이 틀려도 파일 내용을 보고 형식을 판별합니다.

지원하지 않는 것: 암호가 걸린 문서, HWP 3.0(한글 97 이전) 형식, 한글 문서 안의 그림·도형 표시.
이런 경우 앱이 종료되지 않고 이유를 안내합니다.

## 빌드

GitHub에 push하면 Actions가 테스트 후 APK를 만듭니다.
Actions 실행 결과의 **Artifacts → FastDocReader-apk** 에서 내려받아 휴대폰에 설치하세요.
`app-release.apk`(용량이 작고 빠름)를 권장합니다.

로컬 빌드:

```bash
./gradlew testDebugUnitTest assembleRelease
```

## 구조

```
app/src/main/java/com/fastreader/app/
├── MainActivity.kt          홈: 최근 문서 카드 + 하단 '파일 열기'
├── ViewerActivity.kt        문서 화면: 하단 도구 막대(검색·글자·페이지·공유)
├── core/                    안드로이드 의존성이 없는 파서 (단위 테스트 대상)
│   ├── CompoundFile.kt      OLE2 컨테이너 리더
│   ├── HwpParser.kt         HWP 5.x 본문/표/배포용 문서
│   ├── HwpxParser.kt        HWPX(OWPML) 본문/표
│   └── DocumentType.kt      내용 기반 형식 판별
├── viewer/                  PDF 렌더링, 확대 뷰, 텍스트 목록, ViewModel
└── data/                    최근 문서 · 설정 저장
```
