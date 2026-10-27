# 앱 코드는 리플렉션을 쓰지 않으므로 기본 규칙으로 충분하다.
# 오류 보고 시 스택트레이스 줄 번호를 유지한다.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WebView 자바스크립트 브리지: JS 에서만 호출하므로 축소/이름 변경되지 않게 유지
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
