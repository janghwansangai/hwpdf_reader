package com.fastreader.app.core.render

import com.fastreader.app.core.doc.Caption
import com.fastreader.app.core.doc.DrawObject
import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.Picture
import com.fastreader.app.core.doc.RichDocument
import com.fastreader.app.core.doc.Shape
import com.fastreader.app.core.doc.Table
import java.util.IdentityHashMap

/**
 * 문서 전체를 한 번 문서 순서대로 훑어
 * - 모든 문단(표 셀/글상자/캡션/각주 안 포함)에 문서 전체에서 유일한 번호(paraKey = "p{번호}")를 매기고,
 * - 문단 번호/글머리표 글자를 계산하고,
 * - 검색/복사용 목록(머리말/꼬리말 제외)을 만든다.
 * 머리말/꼬리말 문단은 "h{번호}" 를 쓴다 (모든 쪽에 반복되므로 위치 기억에 쓰지 않는다).
 *
 * 만든 뒤에는 읽기만 하므로 여러 스레드에서 써도 된다.
 */
internal class DocIndex(val doc: RichDocument) {

    /** 검색/복사 대상 문단 */
    class Entry(
        val para: Paragraph,
        val key: Int,
        val section: Int,
        /** 최상위 조상 문단 번호 (구역 안 index) */
        val top: Int,
        /** 최상위 조상 문단 안에서 이 문단을 담은 개체/각주의 위치 (최상위 문단 자신이면 -1) */
        val anchorPos: Int,
        /** 0 본문, 1 개체 안, 2 각주, 3 미주 */
        val kind: Int,
        /** 가장 바깥 표와 그 행 (표 안 문단일 때, 쪽 나눔 계산용) */
        val table: Table?,
        val row: Int,
    )

    private val keys = IdentityHashMap<Paragraph, Int>()
    private val headings = IdentityHashMap<Paragraph, Heading>()
    private val hfKeys = IdentityHashMap<Paragraph, Int>()
    val entries = ArrayList<Entry>()

    /** [section][최상위 문단] → key */
    val topKeys: Array<IntArray>

    /** 전체 문단 수 (key 의 최댓값 + 1) */
    var keyCount = 0
        private set

    init {
        val counter = NumberingCounter(doc)
        topKeys = Array(doc.sections.size) { IntArray(doc.sections[it].paragraphs.size) { -1 } }
        var hf = 0
        for ((si, s) in doc.sections.withIndex()) {
            for (h in s.headers) for (p in h.paragraphs) hf = visitHf(p, hf, 0)
            for (h in s.footers) for (p in h.paragraphs) hf = visitHf(p, hf, 0)
            for ((pi, p) in s.paragraphs.withIndex()) {
                if (p.hidden) continue
                topKeys[si][pi] = keyCount
                visit(p, si, pi, -1, 0, null, -1, counter, s.outlineNumberingId, 0)
            }
        }
    }

    fun key(p: Paragraph): Int = keys[p] ?: -1

    /** data-p 값 */
    fun keyString(p: Paragraph): String? {
        keys[p]?.let { return "p$it" }
        hfKeys[p]?.let { return "h$it" }
        return null
    }

    fun heading(p: Paragraph): Heading? = headings[p]

    private fun visit(
        p: Paragraph, si: Int, top: Int, anchorPos: Int, kind: Int, table: Table?, row: Int,
        counter: NumberingCounter, outline: Int, depth: Int,
    ) {
        if (p.hidden || keys.containsKey(p)) return
        val key = keyCount++
        keys[p] = key
        counter.heading(p, outline)?.let { headings[p] = it }
        entries.add(Entry(p, key, si, top, anchorPos, kind, table, row))
        if (depth >= MAX_DEPTH) return
        for (inl in p.content) {
            val pos = if (anchorPos >= 0) anchorPos else inl.start
            when (inl) {
                is Inline.Object -> visitObject(inl.obj, si, top, pos, if (kind >= 2) kind else 1, table, row, counter, outline, depth + 1)
                is Inline.Note -> for (np in inl.paragraphs) {
                    visit(np, si, top, pos, if (inl.endnote) 3 else 2, table, row, counter, outline, depth + 1)
                }
                else -> {}
            }
        }
    }

    private fun visitObject(
        o: DrawObject, si: Int, top: Int, pos: Int, kind: Int, table: Table?, row: Int,
        counter: NumberingCounter, outline: Int, depth: Int,
    ) {
        if (depth >= MAX_DEPTH) return
        when (o) {
            is Table -> {
                for (cell in sortedCells(o)) {
                    val t = table ?: o
                    val r = if (table == null) cell.row else row
                    for (cp in cell.paragraphs) visit(cp, si, top, pos, kind, t, r, counter, outline, depth + 1)
                }
            }
            is Shape -> {
                for (sp in o.paragraphs) visit(sp, si, top, pos, kind, table, row, counter, outline, depth + 1)
                for (c in o.children) visitObject(c, si, top, pos, kind, table, row, counter, outline, depth + 1)
            }
            is Picture, is com.fastreader.app.core.doc.Equation -> {}
        }
        visitCaption(o.caption, si, top, pos, kind, table, row, counter, outline, depth)
    }

    private fun visitCaption(
        c: Caption?, si: Int, top: Int, pos: Int, kind: Int, table: Table?, row: Int,
        counter: NumberingCounter, outline: Int, depth: Int,
    ) {
        if (c == null) return
        for (cp in c.paragraphs) visit(cp, si, top, pos, kind, table, row, counter, outline, depth + 1)
    }

    private fun visitHf(p: Paragraph, n0: Int, depth: Int): Int {
        if (hfKeys.containsKey(p) || keys.containsKey(p)) return n0
        var n = n0
        hfKeys[p] = n++
        if (depth >= MAX_DEPTH) return n
        for (inl in p.content) {
            if (inl is Inline.Object) n = visitHfObject(inl.obj, n, depth + 1)
        }
        return n
    }

    private fun visitHfObject(o: DrawObject, n0: Int, depth: Int): Int {
        var n = n0
        if (depth >= MAX_DEPTH) return n
        when (o) {
            is Table -> for (cell in o.cells) for (cp in cell.paragraphs) n = visitHf(cp, n, depth + 1)
            is Shape -> {
                for (sp in o.paragraphs) n = visitHf(sp, n, depth + 1)
                for (c in o.children) n = visitHfObject(c, n, depth + 1)
            }
            else -> {}
        }
        o.caption?.let { c -> for (cp in c.paragraphs) n = visitHf(cp, n, depth + 1) }
        return n
    }

    companion object {
        /** 개체 중첩 한도 (손상 파일 대비) */
        const val MAX_DEPTH = 24

        fun sortedCells(t: Table) = if (t.cells.size < 2) t.cells else t.cells.sortedWith(compareBy({ it.row }, { it.col }))
    }
}
