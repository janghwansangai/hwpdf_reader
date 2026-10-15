package com.fastreader.app.core.hwpx

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/*
 * HWPX XML 을 SAX 로 읽기 위한 작은 틀.
 *
 * 요소마다 [XNode] 하나가 대응한다. 부모 노드의 [XNode.child] 가 자식 요소를 처리할 노드를 돌려주고,
 * null 이면 그 요소 아래 전체를 건너뛴다. 이름은 네임스페이스 접두사를 뗀 로컬 이름으로 비교한다.
 * `hp:switch` 는 드라이버가 직접 처리한다: 지원하는 네임스페이스의 `hp:case` 가 있으면 그것을, 없으면 `hp:default` 를
 * 골라 그 안의 요소를 switch 의 부모 노드에 그대로 넘긴다(둘 중 하나만).
 */

internal abstract class XNode {
    /** 자식 요소를 처리할 노드. null 이면 하위 전체를 건너뛴다. [a] 는 이 호출 안에서만 유효하다 */
    open fun child(name: String, a: XAttrs): XNode? = null

    open fun text(ch: CharArray, start: Int, length: Int) {}

    open fun end() {}
}

/** 아무것도 하지 않고 하위 요소만 통과시키는 노드 */
internal object IgnoreNode : XNode()

/** 요소 안의 글자를 모으는 노드 (길이 제한) */
internal open class TextCollector(private val limit: Int = 1 shl 20) : XNode() {
    val sb = StringBuilder()

    override fun text(ch: CharArray, start: Int, length: Int) {
        val n = minOf(length, limit - sb.length)
        if (n > 0) sb.append(ch, start, n)
    }
}

/** SAX Attributes 를 로컬 이름으로 읽는 도우미. 드라이버가 하나를 재사용한다 */
internal class XAttrs {
    private var raw: Attributes? = null
    private var count = 0
    private var names = arrayOfNulls<String>(16)
    private var localCache: HashMap<String, String>? = null

    /** 지금 요소가 선택된 hp:case 안에서 넘겨진 것인가 (hp:default 나 switch 밖이면 false) */
    var inCase = false

    /** 요소마다 한 번: 속성 이름의 로컬 부분을 미리 구해 둔다 */
    fun set(a: Attributes?, cache: HashMap<String, String>) {
        raw = a
        localCache = cache
        count = a?.length ?: 0
        if (count > names.size) names = arrayOfNulls(count)
        for (i in 0 until count) {
            var q = a!!.getQName(i)
            if (q.isNullOrEmpty()) q = a.getLocalName(i) ?: ""
            names[i] = cache[q] ?: run {
                val c = q.indexOf(':')
                val l = if (c >= 0) q.substring(c + 1) else q
                if (cache.size < 4096) cache[q] = l
                l
            }
        }
    }

    fun clear() {
        raw = null
        count = 0
    }

    operator fun get(name: String): String? {
        val a = raw ?: return null
        for (i in 0 until count) if (names[i] == name) return a.getValue(i)
        return null
    }

    fun str(name: String, def: String = ""): String = this[name] ?: def

    fun int(name: String, def: Int = 0): Int = parseInt(this[name], def)

    fun bool(name: String, def: Boolean = false): Boolean = parseBool(this[name], def)

    fun double(name: String, def: Double = 0.0): Double = parseDouble(this[name]) ?: def

    /** 길이(HWPUNIT). 터무니없는 값은 잘라낸다 (단, 부호 없는 32비트로 적힌 음수 4294967162 = -134 는 음수로) */
    fun len(name: String, def: Int = 0): Int {
        val s = this[name] ?: return def
        val v = parseLong(s) ?: return def
        val w = if (v in 0x80000000L..0xFFFFFFFFL) v - 0x100000000L else v
        return w.coerceIn(-MAX_LEN.toLong(), MAX_LEN.toLong()).toInt()
    }

    companion object {
        const val MAX_LEN = 20_000_000

        fun clampLen(v: Int) = v.coerceIn(-MAX_LEN, MAX_LEN)

        /** 정수(또는 실수를 반올림한 값). 해석할 수 없으면 null */
        fun parseLong(s: String): Long? {
            s.trim().let { t ->
                if (t.isEmpty()) return null
                t.toLongOrNull()?.let { return it }
                val d = parseDouble(t) ?: return null
                return Math.round(d.coerceIn(-9.0e18, 9.0e18))
            }
        }

        /** "123", "-5", "4294967295"(= -1), "12.7" 을 모두 받아들인다 */
        fun parseInt(s: String?, def: Int): Int {
            if (s == null) return def
            // 흔한 경우(부호 있는 10자리 이하 정수)는 직접 센다
            val n = s.length
            if (n in 1..10) {
                var i = 0
                var neg = false
                if (s[0] == '-') {
                    neg = true
                    i = 1
                }
                if (i < n) {
                    var v = 0L
                    while (i < n) {
                        val c = s[i]
                        if (c < '0' || c > '9') break
                        v = v * 10 + (c - '0')
                        i++
                    }
                    if (i == n) return (if (neg) -v else v).toInt()
                }
            }
            val t = s.trim()
            if (t.isEmpty()) return def
            t.toLongOrNull()?.let { return it.toInt() }
            val d = parseDouble(t) ?: return def
            return Math.round(d.coerceIn(Long.MIN_VALUE.toDouble(), Long.MAX_VALUE.toDouble())).toInt()
        }

        /** Kotlin toDoubleOrNull 은 정규식을 쓰므로 느리다 */
        fun parseDouble(s: String?): Double? {
            if (s == null) return null
            val t = s.trim()
            if (t.isEmpty() || t.length > 64) return null
            val c = t[0]
            if (!(c.isDigit() || c == '-' || c == '+' || c == '.')) return null
            return try {
                t.toDouble().takeIf { it.isFinite() }
            } catch (_: NumberFormatException) {
                null
            }
        }

        fun parseBool(s: String?, def: Boolean): Boolean = when (s?.trim()?.lowercase()) {
            null, "" -> def
            "1", "true" -> true
            "0", "false" -> false
            else -> def
        }
    }
}

/** 문서가 너무 크거나 이상할 때 파싱을 멈추기 위한 내부 신호 */
internal class LimitExceededException(message: String) : RuntimeException(message)

/** 루트 요소만 보고 처리를 그만두고 싶을 때 쓰는 내부 신호 */
internal class StopParsingException : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

internal class XmlDriver(private val root: XNode) : DefaultHandler() {
    private val stack = ArrayList<XNode>(64)
    private var skip = 0
    private val attrs = XAttrs()
    private val localNames = HashMap<String, String>()

    /** 중첩이 너무 깊어 일부를 건너뛰었는가 */
    var depthExceeded = false
        private set

    override fun startDocument() {
        stack.clear()
        stack.add(root)
        skip = 0
    }

    private fun local(localName: String?, qName: String?): String {
        val q = if (!qName.isNullOrEmpty()) qName else localName ?: ""
        localNames[q]?.let { return it }
        val c = q.indexOf(':')
        val l = if (c >= 0) q.substring(c + 1) else q
        if (localNames.size < 4096) localNames[q] = l
        return l
    }

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        if (skip > 0) {
            skip++
            return
        }
        if (stack.size >= MAX_DEPTH) {
            depthExceeded = true
            skip = 1
            return
        }
        val name = local(localName, qName)
        val top = stack[stack.size - 1]
        attrs.set(attributes, localNames)
        attrs.inCase = false
        val child = try {
            if (name == "switch") SwitchNode(top) else top.child(name, attrs)
        } finally {
            attrs.clear()
        }
        if (child == null) skip = 1 else stack.add(child)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        if (skip > 0) {
            skip--
            return
        }
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1).end()
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (skip == 0 && stack.isNotEmpty()) stack[stack.size - 1].text(ch, start, length)
    }

    /** 외부 엔티티는 절대 읽지 않는다 */
    override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

    /** 잘린/깨진 문서에서 열린 노드들을 안쪽부터 닫아 지금까지 읽은 내용을 살린다 */
    fun closeOpenNodes() {
        skip = 0
        while (stack.size > 1) {
            val n = stack.removeAt(stack.size - 1)
            try {
                n.end()
            } catch (_: LimitExceededException) {
                // 이미 한도를 넘었으면 나머지는 버린다
            } catch (_: RuntimeException) {
            }
        }
    }

    private class SwitchNode(private val parent: XNode) : XNode() {
        private var taken = false

        override fun child(name: String, a: XAttrs): XNode? = when (name) {
            "case" -> if (!taken && supportedCase(a["required-namespace"])) {
                taken = true
                CaseNode(parent, true)
            } else null
            "default" -> if (!taken) {
                taken = true
                CaseNode(parent, false)
            } else null
            else -> null
        }
    }

    private class CaseNode(private val parent: XNode, private val isCase: Boolean) : XNode() {
        override fun child(name: String, a: XAttrs): XNode? {
            a.inCase = isCase
            return parent.child(name, a)
        }

        override fun text(ch: CharArray, start: Int, length: Int) = parent.text(ch, start, length)
    }

    companion object {
        const val MAX_DEPTH = 400

        private val SUPPORTED_CASES = setOf(
            "http://www.hancom.co.kr/hwpml/2016/HwpUnitChar",
            "http://www.hancom.co.kr/hwpml/2016/paragraph",
        )

        fun supportedCase(ns: String?) = ns != null && ns.trim() in SUPPORTED_CASES
    }
}

internal object HwpxXml {
    private val factory: SAXParserFactory by lazy {
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            trySet { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            trySet { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            trySet { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            trySet { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            trySet { isXIncludeAware = false }
        }
    }

    private inline fun trySet(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        } catch (_: LinkageError) {
        }
    }

    /**
     * [input] 을 SAX 로 읽어 [root] 노드 나무에 넘긴다. 스트림은 닫지 않는다.
     * 오류가 나면 열린 노드를 닫아 읽은 데까지 살린 뒤 예외를 그대로 던진다.
     * @return 너무 깊은 중첩 때문에 일부를 건너뛰었으면 false
     */
    fun parse(input: InputStream, root: XNode): Boolean {
        val driver = XmlDriver(root)
        val guarded = guardProlog(NonClosingStream(input))
        val parser = synchronized(this) { factory.newSAXParser() }
        try {
            parser.parse(InputSource(guarded), driver)
        } catch (e: StopParsingException) {
            // 필요한 것만 읽고 멈춤
        } catch (e: Exception) {
            driver.closeOpenNodes()
            throw e
        } catch (e: StackOverflowError) {
            driver.closeOpenNodes()
            throw SAXException("XML 중첩이 너무 깊습니다.")
        }
        return !driver.depthExceeded
    }

    /**
     * 문서 앞부분(프롤로그)에 DOCTYPE 이 있으면 거부한다. HWPX 는 DTD 를 쓰지 않으며,
     * 오래된 안드로이드 Expat 은 엔티티 폭탄 방어가 없으므로 여기서 미리 막는다.
     */
    private fun guardProlog(input: InputStream): InputStream {
        val b = BufferedInputStream(input, 8192)
        b.mark(PROLOG_LIMIT + 16)
        PrologScanner(b).scan()
        b.reset()
        return b
    }

    /** 프롤로그(XML 선언, 주석, 처리 지시)만 훑는다. 표식 문자는 모두 ASCII 라 바이트 단위(UTF-16 이면 2바이트)로 본다 */
    private class PrologScanner(private val b: InputStream) {
        private var n = 0
        private var wide = 0 // 0: 1바이트, 1: UTF-16LE, 2: UTF-16BE

        private fun next(): Int {
            if (n >= PROLOG_LIMIT) throw SAXException("XML 머리부분이 너무 깁니다.")
            val c1 = b.read()
            if (c1 < 0) return -1
            n++
            if (wide == 0) return c1
            val c2 = b.read()
            if (c2 < 0) return -1
            n++
            return if (wide == 1) c1 or (c2 shl 8) else (c1 shl 8) or c2
        }

        fun scan() {
            var c = b.read()
            n++
            when (c) {
                0xEF -> { b.read(); b.read(); n += 2; c = next() } // UTF-8 BOM
                0xFF -> { b.read(); n++; wide = 1; c = next() }
                0xFE -> { b.read(); n++; wide = 2; c = next() }
            }
            while (true) {
                if (c < 0) return
                if (c == ' '.code || c == '\t'.code || c == '\r'.code || c == '\n'.code) {
                    c = next()
                    continue
                }
                if (c != '<'.code) throw SAXException("XML 문서가 아닙니다.")
                when (next()) {
                    '?'.code -> skipUntil("?>")
                    '!'.code -> {
                        if (next() == '-'.code && next() == '-'.code) skipUntil("-->")
                        else throw SAXException("DTD 가 들어 있는 XML 은 열 수 없습니다.")
                    }
                    else -> return // 루트 요소 시작
                }
                c = next()
            }
        }

        private fun skipUntil(end: String) {
            var matched = 0
            while (true) {
                val c = next()
                if (c < 0) return
                matched = if (c == end[matched].code) matched + 1 else if (c == end[0].code) 1 else 0
                if (matched == end.length) return
            }
        }
    }

    private const val PROLOG_LIMIT = 256 * 1024
}

/** 파서/그림 저장소가 zip 스트림을 닫지 못하게 막는다 */
internal class NonClosingStream(input: InputStream) : FilterInputStream(input) {
    override fun close() {}
}

internal class EntryTooLargeException : IOException("항목이 너무 큽니다.")

/** 최대 [limit] 바이트까지만 읽게 하고, 넘으면 [EntryTooLargeException] */
internal class LimitedStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int {
        val c = super.read()
        if (c >= 0) bump(1)
        return c
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) bump(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val s = super.skip(n)
        if (s > 0) bump(s)
        return s
    }

    private fun bump(n: Long) {
        count += n
        if (count > limit) throw EntryTooLargeException()
    }

    override fun markSupported() = false

    override fun close() {}
}
