package com.fastreader.app.core.render

/**
 * HTML/CSS 출력 도우미 (이스케이프, 단위 변환, 숫자 서식).
 * 모든 함수는 상태가 없어 여러 스레드에서 동시에 써도 된다.
 */
internal object Html {

    /** 1 HWPUNIT = 1/7200 inch = 1/75 CSS px */
    const val HWP_PER_PX = 75.0

    fun px(hwp: Int): Double = hwp / HWP_PER_PX

    /** 소수 둘째 자리까지 (불필요한 0 생략). String.format 보다 훨씬 빠르다 */
    fun StringBuilder.num(v: Double): StringBuilder {
        if (v.isNaN() || v.isInfinite()) return append('0')
        var r = Math.round(v * 100.0)
        if (r < 0) {
            append('-')
            r = -r
        }
        append(r / 100)
        val f = (r % 100).toInt()
        if (f != 0) {
            append('.')
            if (f % 10 == 0) append(f / 10) else {
                if (f < 10) append('0')
                append(f)
            }
        }
        return this
    }

    fun StringBuilder.px(hwp: Int): StringBuilder = num(hwp / HWP_PER_PX).append("px")
    fun StringBuilder.pxd(v: Double): StringBuilder = num(v).append("px")

    fun fmt(v: Double): String = StringBuilder(12).num(v).toString()

    /** #rrggbb */
    fun color(rgb: Int): String {
        val v = rgb and 0xFFFFFF
        val s = Integer.toHexString(v)
        return if (s.length == 6) "#$s" else "#" + "0".repeat(6 - s.length) + s
    }

    /**
     * 본문 글자 이스케이프. 길이를 바꾸지 않는 치환만 한다(검색 위치 계산이 DOM 글자 수와 같아야 하므로):
     * 제어 문자 → 공백, keepNewline 이 아니면 \n → 공백. & < > 는 엔티티(DOM 에서는 한 글자).
     */
    fun StringBuilder.text(s: String, from: Int = 0, to: Int = s.length, keepNewline: Boolean = false): StringBuilder {
        var i = from
        while (i < to) {
            val c = s[i]
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '\n' -> append(if (keepNewline) '\n' else ' ')
                c == '\t' -> append('\t')
                c < ' ' || c == '\u007f' -> append(' ')
                // 짝 없는 서로게이트는 UTF-8 로 쓸 수 없다 → 같은 길이의 대체 문자
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < to && Character.isLowSurrogate(s[i + 1])) {
                        append(c).append(s[i + 1])
                        i++
                    } else append('�')
                }
                Character.isLowSurrogate(c) -> append('�')
                else -> append(c)
            }
            i++
        }
        return this
    }

    fun escape(s: String): String = StringBuilder(s.length + 16).text(s).toString()

    /** 속성 값 이스케이프 (큰따옴표 안) */
    fun StringBuilder.attr(s: String): StringBuilder {
        for (c in s) {
            when {
                c == '&' -> append("&amp;")
                c == '"' -> append("&quot;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c < ' ' || c == '\u007f' -> append(' ')
                Character.isSurrogate(c) -> append(c)
                else -> append(c)
            }
        }
        return this
    }

    /** JSON 문자열 리터럴 (따옴표 포함). </script> 안에 넣어도 안전하도록 < > & 와 U+2028/2029 를 \\u 로 */
    fun StringBuilder.json(s: String): StringBuilder {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<', '>', '&', ' ', ' ' -> unicodeEscape(c)
                else -> if (c < ' ' || Character.isSurrogate(c)) unicodeEscape(c) else append(c)
            }
        }
        return append('"')
    }

    private fun StringBuilder.unicodeEscape(c: Char) {
        val h = Integer.toHexString(c.code)
        append("\\u")
        for (k in h.length until 4) append('0')
        append(h)
    }

    fun json(s: String): String = StringBuilder(s.length + 8).json(s).toString()

    /** 하이퍼링크로 허용하는 주소 (http/https/mailto). 아니면 null */
    fun safeUrl(url: String?): String? {
        if (url == null) return null
        val u = url.trim()
        if (u.length > 4096) return null
        val lower = u.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:"))) return null
        if (u.any { it < ' ' || it == '\u007f' }) return null
        return u
    }

    /** CSS 문자열 리터럴 안에 넣을 글꼴 이름 등 */
    fun cssString(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' || c == '\\' -> sb.append('\\').append(c)
                c < ' ' || c == '\u007f' || c == '<' || c == '>' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** 0..255 범위의 알파를 가진 rgba */
    fun rgba(rgb: Int, alpha: Double): String {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return "rgba($r,$g,$b,${fmt(alpha.coerceIn(0.0, 1.0))})"
    }
}
