package com.fastreader.app.core.render

import com.fastreader.app.core.render.Html.text

/**
 * 한글 수식 스크립트(예: "{a+b} over 2", "sqrt {x^2 + 1}", "sum _{i=1} ^n i") → 간단한 HTML.
 * 완전한 수식 조판이 아니라 분수/근호/위·아래 첨자/그리스 문자/기호를 알아보기 쉽게 보여주는 수준이다.
 * 모르는 명령은 글자 그대로 둔다. 모든 글자는 이스케이프된다.
 */
internal object EquationHtml {

    fun write(sb: StringBuilder, script: String) {
        val tokens = tokenize(script.take(20_000))
        val p = Parser(tokens)
        val node = try {
            p.sequence(0)
        } catch (e: RuntimeException) {
            null
        }
        if (node == null) {
            sb.text(script.take(2000))
            return
        }
        node.html(sb)
    }

    // ------------------------------------------------------------------ 토큰

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '"' -> {
                    val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                    out.add("\"" + s.substring(i + 1, end))
                    i = end + 1
                }
                c.isLetter() && c.code < 0x80 -> {
                    var j = i + 1
                    while (j < s.length && s[j].isLetter() && s[j].code < 0x80) j++
                    out.add(s.substring(i, j))
                    i = j
                }
                c.isDigit() -> {
                    var j = i + 1
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                    out.add(s.substring(i, j))
                    i = j
                }
                else -> {
                    out.add(c.toString())
                    i++
                }
            }
            if (out.size > 10_000) break
        }
        return out
    }

    // ------------------------------------------------------------------ 구조

    private sealed class Node {
        abstract fun html(sb: StringBuilder)
    }

    private class Lit(val s: String, val italic: Boolean = false) : Node() {
        override fun html(sb: StringBuilder) {
            if (italic) sb.append("<i>").text(s).append("</i>") else sb.text(s)
        }
    }

    private class Seq(val items: List<Node>) : Node() {
        override fun html(sb: StringBuilder) = items.forEach { it.html(sb) }
    }

    private class Frac(val num: Node, val den: Node) : Node() {
        override fun html(sb: StringBuilder) {
            sb.append("<span class=\"ef\"><span class=\"en\">")
            num.html(sb)
            sb.append("</span><span class=\"ed\">")
            den.html(sb)
            sb.append("</span></span>")
        }
    }

    private class Script(val base: Node, val sup: Node?, val sub: Node?) : Node() {
        override fun html(sb: StringBuilder) {
            base.html(sb)
            if (sub != null) {
                sb.append("<sub>"); sub.html(sb); sb.append("</sub>")
            }
            if (sup != null) {
                sb.append("<sup>"); sup.html(sb); sb.append("</sup>")
            }
        }
    }

    private class Root(val body: Node, val index: Node?) : Node() {
        override fun html(sb: StringBuilder) {
            if (index != null) {
                sb.append("<sup class=\"ei\">"); index.html(sb); sb.append("</sup>")
            }
            sb.append("√<span class=\"eo\">")
            body.html(sb)
            sb.append("</span>")
        }
    }

    private class Group(val open: String, val body: Node, val close: String) : Node() {
        override fun html(sb: StringBuilder) {
            sb.text(open); body.html(sb); sb.text(close)
        }
    }

    private class Parser(private val t: List<String>) {
        var i = 0

        fun peek() = t.getOrNull(i)

        /** '}' 또는 끝까지. over 는 가장 낮은 우선순위의 이항 연산자 */
        fun sequence(depth: Int): Node {
            if (depth > 60) throw IllegalStateException("too deep")
            val items = ArrayList<Node>()
            while (true) {
                val tok = peek() ?: break
                if (tok == "}" || tok.equals("RIGHT", true)) break
                if (tok.equals("over", true) || tok.equals("atop", true)) {
                    i++
                    val num = Seq(ArrayList(items))
                    val den = sequence(depth + 1)
                    return Frac(num, den)
                }
                val atom = atom(depth) ?: continue
                items.add(scripts(atom, depth))
            }
            return Seq(items)
        }

        private fun scripts(base: Node, depth: Int): Node {
            var sup: Node? = null
            var sub: Node? = null
            while (true) {
                when (peek()) {
                    "^" -> { i++; sup = atom(depth) ?: Lit("") }
                    "_" -> { i++; sub = atom(depth) ?: Lit("") }
                    else -> break
                }
            }
            return if (sup == null && sub == null) base else Script(base, sup, sub)
        }

        fun atom(depth: Int): Node? {
            val tok = peek() ?: return null
            i++
            if (tok == "{") {
                val n = sequence(depth + 1)
                if (peek() == "}") i++
                return n
            }
            if (tok == "}") return null
            if (tok.startsWith("\"")) return Lit(tok.substring(1))
            if (tok == "~") return Lit(" ")
            if (tok == "`") return Lit(" ")
            if (tok == "#") return Lit(" ")
            if (tok == "&") return Lit("")
            val lower = tok.lowercase()
            when (lower) {
                "sqrt" -> return Root(atom(depth + 1) ?: Lit(""), null)
                "root" -> {
                    val idx = atom(depth + 1)
                    if (peek()?.equals("of", true) == true) i++
                    return Root(atom(depth + 1) ?: Lit(""), idx)
                }
                "left" -> {
                    val open = peek()?.let { i++; bracket(it) } ?: ""
                    val body = sequence(depth + 1)
                    var close = ""
                    if (peek()?.equals("RIGHT", true) == true) {
                        i++
                        close = peek()?.let { i++; bracket(it) } ?: ""
                    }
                    return Group(open, body, close)
                }
                "rm", "it", "bold", "b", "rmbold" -> return atom(depth)
                "matrix", "pmatrix", "bmatrix", "dmatrix", "cases", "pile", "lpile", "rpile", "eqalign" -> {
                    val body = atom(depth + 1) ?: Lit("")
                    return when (lower) {
                        "pmatrix" -> Group("(", body, ")")
                        "bmatrix" -> Group("[", body, "]")
                        "dmatrix" -> Group("|", body, "|")
                        "cases" -> Group("{", body, "")
                        else -> body
                    }
                }
            }
            SYMBOLS[tok]?.let { return Lit(it) }
            SYMBOLS[lower]?.let { if (lower !in CASE_SENSITIVE) return Lit(it) }
            // 영문 한 글자 변수는 기울임
            if (tok.length == 1 && tok[0].isLetter() && tok[0].code < 0x80) return Lit(tok, italic = true)
            if (tok.all { it.isLetter() && it.code < 0x80 } && tok.length <= 4 && lower in FUNCS) return Lit(lower)
            return Lit(tok, italic = tok.all { it.isLetter() && it.code < 0x80 })
        }

        private fun bracket(s: String) = when (s.lowercase()) {
            "lbrace" -> "{"
            "rbrace" -> "}"
            "langle" -> "⟨"
            "rangle" -> "⟩"
            "lfloor" -> "⌊"
            "rfloor" -> "⌋"
            "lceil" -> "⌈"
            "rceil" -> "⌉"
            "." -> ""
            else -> s
        }
    }

    private val FUNCS = setOf("sin", "cos", "tan", "log", "ln", "exp", "lim", "max", "min", "det", "mod", "sec", "csc", "cot", "sinh", "cosh", "tanh", "arg", "gcd")

    /** 대문자로 쓰면 다른 뜻인 이름 (그리스 대문자) */
    private val CASE_SENSITIVE = setOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa", "lambda", "mu", "nu", "xi", "omicron", "pi", "rho", "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega")

    private val SYMBOLS: Map<String, String> = mapOf(
        "times" to "×", "TIMES" to "×", "div" to "÷", "DIV" to "÷", "pm" to "±", "PM" to "±", "mp" to "∓", "cdot" to "·", "CDOT" to "·",
        "le" to "≤", "LE" to "≤", "leq" to "≤", "ge" to "≥", "GE" to "≥", "geq" to "≥", "ne" to "≠", "NE" to "≠", "neq" to "≠",
        "approx" to "≈", "sim" to "∼", "equiv" to "≡", "inf" to "∞", "infty" to "∞", "INF" to "∞", "partial" to "∂", "nabla" to "∇",
        "sum" to "∑", "SUM" to "∑", "prod" to "∏", "PROD" to "∏", "int" to "∫", "INT" to "∫", "oint" to "∮", "dint" to "∬", "tint" to "∭",
        "rarrow" to "→", "larrow" to "←", "uarrow" to "↑", "darrow" to "↓", "lrarrow" to "↔", "RARROW" to "⇒", "LARROW" to "⇐", "LRARROW" to "⇔",
        "->" to "→", "in" to "∈", "notin" to "∉", "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇",
        "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "forall" to "∀", "exist" to "∃", "exists" to "∃", "therefore" to "∴", "because" to "∵",
        "angle" to "∠", "perp" to "⊥", "deg" to "°", "prime" to "′", "cdots" to "⋯", "ldots" to "…", "vdots" to "⋮", "ddots" to "⋱",
        "and" to "∧", "or" to "∨", "not" to "¬", "triangle" to "△", "circ" to "∘", "bullet" to "•", "star" to "⋆", "hbar" to "ℏ",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ",
        "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "omicron" to "ο", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "ALPHA" to "Α", "BETA" to "Β", "GAMMA" to "Γ", "DELTA" to "Δ", "EPSILON" to "Ε", "ZETA" to "Ζ", "ETA" to "Η", "THETA" to "Θ",
        "IOTA" to "Ι", "KAPPA" to "Κ", "LAMBDA" to "Λ", "MU" to "Μ", "NU" to "Ν", "XI" to "Ξ", "OMICRON" to "Ο", "PI" to "Π", "RHO" to "Ρ",
        "SIGMA" to "Σ", "TAU" to "Τ", "UPSILON" to "Υ", "PHI" to "Φ", "CHI" to "Χ", "PSI" to "Ψ", "OMEGA" to "Ω",
    )
}
