package com.fastreader.app.core.hwp

import com.fastreader.app.core.CorruptDocumentException
import com.fastreader.app.core.doc.AutoNumberKind
import com.fastreader.app.core.doc.BreakType
import com.fastreader.app.core.doc.Caption
import com.fastreader.app.core.doc.ColumnDef
import com.fastreader.app.core.doc.DrawObject
import com.fastreader.app.core.doc.FieldKind
import com.fastreader.app.core.doc.HeaderFooter
import com.fastreader.app.core.doc.Inline
import com.fastreader.app.core.doc.LineSeg
import com.fastreader.app.core.doc.PageApply
import com.fastreader.app.core.doc.PageDef
import com.fastreader.app.core.doc.PageNumberPos
import com.fastreader.app.core.doc.PageNumbering
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.Section

/**
 * HWP 본문 구역 스트림(BodyText/SectionN 을 압축 해제·복호화한 바이트) → [Section].
 *
 * 레코드는 level 로 중첩된다. 한 레코드의 자식 = 바로 뒤에 오는, level 이 더 큰 레코드들.
 * - 문단: PARA_HEADER(L) → 자식(L+1) PARA_TEXT, PARA_CHAR_SHAPE, PARA_LINE_SEG, PARA_RANGE_TAG, CTRL_HEADER...
 * - 문단 목록(표 셀, 글상자, 머리말, 각주, 캡션): LIST_HEADER 뒤에 같은 level 의 PARA_HEADER 들이 이어지고
 *   마지막 문단의 글자 수 bit31 이 켜져 있다 (hwplib ForParagraphList 와 같은 규칙).
 * - 확장 컨트롤 문자(코드 1,2,3,11,12,14..18,21..23)는 문단의 CTRL_HEADER 자식들과 순서대로 짝지어진다.
 *
 * 손상된 데이터: 모든 읽기는 레코드 범위 안에서만 하고(넘으면 0), 컨트롤 하나/문단 하나가 잘못되어도
 * 그 부분만 건너뛰고 나머지는 살린다.
 */
object HwpBodyReader {
    fun readSection(data: ByteArray, info: HwpDocInfo, version: HwpVersion): Section {
        return try {
            HwpSectionReader(HwpRecordTree(HwpBinary.records(data)), info, version).read()
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: StackOverflowError) {
            throw CorruptDocumentException("본문 구조가 너무 깊습니다.", null)
        } catch (e: RuntimeException) {
            throw CorruptDocumentException("본문을 해석하지 못했습니다: ${e.message}", e)
        }
    }
}

/** 레코드 목록 + 각 레코드의 하위 트리 끝 위치 (O(n) 계산) */
internal class HwpRecordTree(val recs: List<HwpRecord>) {
    val size = recs.size
    val tag = IntArray(size) { recs[it].tag }
    val level = IntArray(size) { recs[it].level }

    /** end[i] = i 의 하위 트리 바로 다음 인덱스 */
    val end = IntArray(size)

    init {
        val stack = IntArray(size)
        var sp = 0
        for (i in 0 until size) {
            val lv = level[i]
            while (sp > 0 && level[stack[sp - 1]] >= lv) end[stack[--sp]] = i
            stack[sp++] = i
        }
        while (sp > 0) end[stack[--sp]] = size
    }

    fun reader(i: Int) = recs[i].reader()
    fun recSize(i: Int) = recs[i].size

    /** CTRL_HEADER / SHAPE_COMPONENT 첫 4바이트(컨트롤 ID) */
    fun ctrlIdOf(i: Int): Int {
        val r = recs[i]
        return if (r.size >= 4) HwpBinary.le32(r.data, r.offset) else 0
    }

    /** i 의 직계 자식 인덱스 */
    inline fun forChildren(i: Int, f: (Int) -> Unit) {
        var j = i + 1
        val e = end[i]
        while (j < e) {
            f(j)
            j = end[j]
        }
    }

    fun firstChild(i: Int, tagId: Int): Int {
        forChildren(i) { if (tag[it] == tagId) return it }
        return -1
    }
}

/** 한 구역을 읽는 동안의 상태 */
internal class HwpSectionReader(
    private val t: HwpRecordTree,
    private val info: HwpDocInfo,
    @Suppress("unused") private val version: HwpVersion,
) {
    // ---- 구역 상태 (secd/cold/head/foot/pgnp/pghd/nwno 가 채운다)
    private var pageDef: PageDef? = null
    private var columns: ColumnDef? = null
    private val headers = ArrayList<HeaderFooter>()
    private val footers = ArrayList<HeaderFooter>()
    private var pageNumbering: PageNumbering? = null
    private var startPageNumber = 0
    private var outlineNumberingId = 0
    private var hideFirstHeader = false
    private var hideFirstFooter = false
    private var hideFirstPageNumber = false

    /** 지금 읽고 있는 최상위 문단 번호 */
    private var topIndex = 0

    /** 지금 읽고 있는 최상위 문단에서 쪽이 바뀌는 텍스트 위치 (없으면 MAX) */
    private var topPageBreakPos = Int.MAX_VALUE
    private var firstPageEnded = false
    private var lastTopVertPos = Int.MIN_VALUE
    private var sawTopLine = false

    /** 필드 시작/끝 짝 맞추기: (컨트롤 ID 하위 24비트, 필드 id) */
    private val openFields = ArrayList<IntArray>()
    private var nextFieldId = 1

    internal val controls = HwpControlReader(t, this)

    /** DocInfo 에 있는 BinData 항목인가 (그림/채우기 이미지 ID 확인용) */
    fun hasBinItem(id: Int) = id > 0 && info.binItems.containsKey(id)

    fun read(): Section {
        val paragraphs = ArrayList<Paragraph>()
        var i = 0
        while (i < t.size) {
            if (t.tag[i] == HwpTags.PARA_HEADER) {
                topIndex = paragraphs.size
                val p = try {
                    paragraph(i, depth = 0, top = true)
                } catch (e: OutOfMemoryError) {
                    throw e
                } catch (e: RuntimeException) {
                    null // 이 문단만 버린다
                }
                if (p != null) paragraphs.add(p)
                if (topPageBreakPos != Int.MAX_VALUE) firstPageEnded = true
            }
            i = t.end[i]
        }
        return Section(
            page = pageDef ?: PageDef(),
            columns = columns ?: ColumnDef(),
            paragraphs = paragraphs,
            headers = headers,
            footers = footers,
            pageNumbering = pageNumbering,
            startPageNumber = startPageNumber,
            outlineNumberingId = outlineNumberingId,
            hideFirstHeader = hideFirstHeader,
            hideFirstFooter = hideFirstFooter,
            hideFirstPageNumber = hideFirstPageNumber,
        )
    }

    // ------------------------------------------------------------------ 문단 목록

    /**
     * LIST_HEADER(k) 뒤의 문단 목록을 읽는다. 반환: (문단들, 목록 다음 형제 인덱스).
     * [parentEnd] 는 부모 하위 트리 끝.
     */
    fun paragraphList(k: Int, parentEnd: Int, depth: Int): Pair<List<Paragraph>, Int> {
        val out = ArrayList<Paragraph>()
        var j = t.end[k]
        // 일부 생성기는 문단을 LIST_HEADER 의 자식으로 둔다
        val childFirst = k + 1
        val inChildren = childFirst < t.end[k] && t.tag[childFirst] == HwpTags.PARA_HEADER
        if (depth > MAX_DEPTH) {
            // 너무 깊은 중첩: 내용을 버리고 구조만 넘긴다
            while (j < parentEnd && t.tag[j] == HwpTags.PARA_HEADER) {
                val last = lastInList(j)
                j = t.end[j]
                if (last) break
            }
            return out to j
        }
        if (inChildren) {
            t.forChildren(k) { c ->
                if (t.tag[c] == HwpTags.PARA_HEADER) paragraphSafe(c, depth)?.let(out::add)
            }
            return out to j
        }
        while (j < parentEnd && t.tag[j] == HwpTags.PARA_HEADER) {
            paragraphSafe(j, depth)?.let(out::add)
            val last = lastInList(j)
            j = t.end[j]
            if (last) break
        }
        return out to j
    }

    private fun lastInList(i: Int): Boolean {
        val r = t.recs[i]
        return r.size >= 4 && (HwpBinary.le32(r.data, r.offset) and Int.MIN_VALUE) != 0
    }

    private fun paragraphSafe(i: Int, depth: Int): Paragraph? = try {
        paragraph(i, depth, top = false)
    } catch (e: OutOfMemoryError) {
        throw e
    } catch (e: RuntimeException) {
        null
    }

    // ------------------------------------------------------------------ 문단

    private fun paragraph(i: Int, depth: Int, top: Boolean): Paragraph {
        val r = t.reader(i)
        r.u32() // 글자 수 (bit31 = 목록의 마지막 문단)
        r.u32() // 컨트롤 마스크
        val paraShapeId = r.u16()
        val styleId = r.u8()
        val breakBits = r.u8()
        val breakType = when {
            breakBits and 0x04 != 0 -> BreakType.PAGE
            breakBits and 0x08 != 0 -> BreakType.COLUMN
            else -> BreakType.NONE
        }

        var textIdx = -1
        var charShapeIdx = -1
        var lineSegIdx = -1
        var ctrlCount = 0
        t.forChildren(i) { c ->
            when (t.tag[c]) {
                HwpTags.PARA_TEXT -> if (textIdx < 0) textIdx = c
                HwpTags.PARA_CHAR_SHAPE -> if (charShapeIdx < 0) charShapeIdx = c
                HwpTags.PARA_LINE_SEG -> if (lineSegIdx < 0) lineSegIdx = c
                HwpTags.CTRL_HEADER -> ctrlCount++
            }
        }
        val ctrls = IntArray(ctrlCount)
        if (ctrlCount > 0) {
            var n = 0
            t.forChildren(i) { c -> if (t.tag[c] == HwpTags.CTRL_HEADER) ctrls[n++] = c }
        }

        val lines = if (lineSegIdx >= 0) lineSegs(lineSegIdx) else emptyList()
        if (top) trackPages(breakType, lines)

        val content = inlines(textIdx, charShapeIdx, ctrls, depth, top)
        return Paragraph(paraShapeId = paraShapeId, styleId = styleId, breakType = breakType, content = content, lines = lines)
    }

    private fun lineSegs(k: Int): List<LineSeg> {
        val n = t.recSize(k) / 36
        if (n <= 0) return emptyList()
        val r = t.reader(k)
        return List(n) {
            LineSeg(
                textStart = r.s32(), vertPos = r.s32(), height = r.s32(), textHeight = r.s32(),
                baseline = r.s32(), spacing = r.s32(), horzStart = r.s32(), width = r.s32(), flags = r.s32(),
            )
        }
    }

    /** 최상위 문단의 줄 정보로 "첫 쪽" 여부를 추적한다 (pghd/nwno 를 구역 속성으로 옮길 때 쓴다) */
    private fun trackPages(breakType: BreakType, lines: List<LineSeg>) {
        topPageBreakPos = Int.MAX_VALUE
        if (breakType == BreakType.PAGE && topIndex > 0) topPageBreakPos = 0
        for (ls in lines) {
            val newPage = sawTopLine && (ls.isFirstInPage || ls.vertPos < lastTopVertPos)
            if (newPage && topPageBreakPos == Int.MAX_VALUE) topPageBreakPos = ls.textStart
            sawTopLine = true
            lastTopVertPos = ls.vertPos
        }
    }

    private fun onFirstPage(pos: Int) = !firstPageEnded && pos < topPageBreakPos

    // ------------------------------------------------------------------ 글자/컨트롤 → Inline

    private fun inlines(textIdx: Int, charShapeIdx: Int, ctrls: IntArray, depth: Int, top: Boolean): List<Inline> {
        // 글자 모양 구간 (시작 위치, ID). 위치 순 정렬이 깨진 파일 대비 정렬 (정상 파일은 이미 정렬됨)
        var runPos = IntArray(0)
        var runId = IntArray(0)
        if (charShapeIdx >= 0) {
            val n = t.recSize(charShapeIdx) / 8
            val r = t.reader(charShapeIdx)
            val pos = IntArray(n)
            val ids = IntArray(n)
            for (k in 0 until n) {
                pos[k] = r.s32()
                ids[k] = r.s32()
            }
            if ((1 until n).any { pos[it] < pos[it - 1] }) {
                val order = (0 until n).sortedBy { pos[it] }
                runPos = IntArray(n) { pos[order[it]] }
                runId = IntArray(n) { ids[order[it]] }
            } else {
                runPos = pos
                runId = ids
            }
        }
        val out = ArrayList<Inline>()
        var ctrlPtr = 0
        if (textIdx >= 0) {
            val rec = t.recs[textIdx]
            val data = rec.data
            val base = rec.offset
            val n = rec.size / 2
            var runK = -1
            // 첫 구간이 0 보다 뒤에서 시작하는 비정상 파일: 그 앞 글자도 첫 모양으로
            val cs = if (runId.isNotEmpty()) runId[0] else 0
            val sb = StringBuilder()
            var textStart = -1
            var textCs = 0

            fun flush() {
                if (sb.isNotEmpty()) {
                    out.add(Inline.Text(textStart, sb.toString(), textCs))
                    sb.setLength(0)
                }
                textStart = -1
            }

            fun shapeAt(p: Int): Int {
                while (runK + 1 < runPos.size && runPos[runK + 1] <= p) runK++
                return if (runK >= 0) runId[runK] else cs
            }

            fun addChar(p: Int, ch: Char, shape: Int) {
                if (textStart >= 0 && shape != textCs) flush()
                if (textStart < 0) {
                    textStart = p
                    textCs = shape
                }
                sb.append(ch)
            }

            var p = 0
            while (p < n) {
                val c = HwpBinary.le16(data, base + p * 2)
                val shape = shapeAt(p)
                if (c >= 32) {
                    addChar(p, c.toChar(), shape)
                    p++
                    continue
                }
                when (c) {
                    24 -> { addChar(p, '-', shape); p++ }
                    30 -> { addChar(p, '\u00A0', shape); p++ }
                    31 -> { addChar(p, '\u2007', shape); p++ }
                    10 -> { flush(); out.add(Inline.LineBreak(p, shape)); p++ }
                    9 -> {
                        flush()
                        // 탭: 추가 정보 12바이트 = 너비(u32), 채움 선 종류(u8), 탭 종류(u8), ...
                        val w = if (p + 2 < n) HwpBinary.le32(data, base + (p + 1) * 2) else 0
                        val leader = if (p + 3 < n) data[base + (p + 3) * 2].toInt() and 0xFF else 0
                        out.add(Inline.Tab(p, shape, width = w.coerceAtLeast(0), leader = HwpControlReader.lineType(leader)))
                        p += 8
                    }
                    4, 5, 6, 7, 8, 19, 20 -> {
                        flush()
                        if (c == 4) {
                            val id = if (p + 2 < n) HwpBinary.le32(data, base + (p + 1) * 2) else 0
                            fieldEnd(p, id)?.let(out::add)
                        }
                        p += 8
                    }
                    1, 2, 3, 11, 12, 14, 15, 16, 17, 18, 21, 22, 23 -> {
                        flush()
                        if (ctrlPtr < ctrls.size) {
                            val k = ctrls[ctrlPtr++]
                            control(k, p, shape, depth, top)?.let(out::add)
                        }
                        p += 8
                    }
                    else -> { flush(); p++ } // 0, 13(문단 끝), 25..29 (예약)
                }
            }
            flush()
        }
        // 글자와 짝이 없는 남은 컨트롤: 구역 정의 같은 부수 효과만 반영
        while (ctrlPtr < ctrls.size) {
            val k = ctrls[ctrlPtr++]
            val id = t.ctrlIdOf(k)
            if (id == ID_SECD || id == ID_COLD || id == ID_HEAD || id == ID_FOOT || id == ID_PGNP) {
                control(k, Int.MAX_VALUE, 0, depth, top)
            }
        }
        return out
    }

    private fun control(k: Int, pos: Int, charShape: Int, depth: Int, top: Boolean): Inline? = try {
        controlImpl(k, pos, charShape, depth, top)
    } catch (e: OutOfMemoryError) {
        throw e
    } catch (e: StackOverflowError) {
        null
    } catch (e: RuntimeException) {
        null // 이 컨트롤만 버린다 (위치는 유지)
    }

    private fun controlImpl(k: Int, pos: Int, cs: Int, depth: Int, top: Boolean): Inline? {
        val id = t.ctrlIdOf(k)
        if ((id ushr 24) == '%'.code) return fieldStart(k, pos, id)
        return when (id) {
            ID_TBL -> obj(pos, cs) { controls.table(k, depth + 1) }
            ID_GSO -> obj(pos, cs) { controls.gso(k, depth + 1) }
            ID_EQED -> obj(pos, cs) { controls.equation(k, depth + 1) }
            ID_FORM -> obj(pos, cs) { controls.form(k) }
            ID_SECD -> { sectionDefine(k); null }
            ID_COLD -> { if (columns == null) columns = HwpControlReader.columnDef(t.reader(k).also { it.skip(4) }); null }
            ID_HEAD, ID_FOOT -> { headerFooter(k, id == ID_HEAD, depth + 1); null }
            ID_FN, ID_EN -> note(k, pos, cs, id == ID_EN, depth + 1)
            ID_ATNO -> autoNumber(k, pos, cs)
            ID_NWNO -> { newNumber(k, pos, top); null }
            ID_PGNP -> { pageNumberPos(k); null }
            ID_PGHD -> { pageHide(k, pos, top); null }
            ID_TDUT -> ruby(k, pos, cs)
            ID_TCPS -> composeText(k, pos, cs)
            else -> null // bokm, idxm, tcmt(숨은 설명), pgct 등: 표시하지 않음
        }
    }

    private inline fun obj(pos: Int, cs: Int, make: () -> DrawObject?): Inline? =
        make()?.let { Inline.Object(pos, cs, it) }

    // ---- 구역 정의
    private fun sectionDefine(k: Int) {
        if (pageDef != null) return
        val r = t.reader(k)
        r.skip(4)
        val prop = r.s32()
        r.u16() // 단 사이 간격
        r.u16() // 세로 줄맞춤
        r.u16() // 가로 줄맞춤
        r.s32() // 기본 탭 간격
        val outline = r.u16()
        val pageStart = r.u16()
        outlineNumberingId = outline
        if (pageStart > 0) startPageNumber = pageStart
        hideFirstHeader = hideFirstHeader || prop and 0x01 != 0
        hideFirstFooter = hideFirstFooter || prop and 0x02 != 0
        hideFirstPageNumber = hideFirstPageNumber || prop and 0x20 != 0
        val pd = t.firstChild(k, HwpTags.PAGE_DEF)
        pageDef = if (pd >= 0) HwpControlReader.pageDef(t.reader(pd)) else PageDef()
    }

    // ---- 머리말/꼬리말
    private fun headerFooter(k: Int, header: Boolean, depth: Int) {
        val r = t.reader(k)
        r.skip(4)
        val apply = when (r.s32()) {
            1 -> PageApply.EVEN
            2 -> PageApply.ODD
            else -> PageApply.BOTH
        }
        val lh = t.firstChild(k, HwpTags.LIST_HEADER)
        var w = 0
        var h = 0
        var paras: List<Paragraph> = emptyList()
        if (lh >= 0) {
            val lr = t.reader(lh)
            lr.s32() // 문단 수
            lr.s32() // 속성
            w = lr.s32()
            h = lr.s32()
            paras = paragraphList(lh, t.end[k], depth).first
        }
        val hf = HeaderFooter(apply, paras, width = w, height = h, fromParagraph = topIndex)
        if (header) headers.add(hf) else footers.add(hf)
    }

    // ---- 각주/미주
    private fun note(k: Int, pos: Int, cs: Int, endnote: Boolean, depth: Int): Inline {
        val r = t.reader(k)
        r.skip(4)
        val number = r.s32()
        val lh = t.firstChild(k, HwpTags.LIST_HEADER)
        val paras = if (lh >= 0) paragraphList(lh, t.end[k], depth).first else emptyList()
        return Inline.Note(pos, cs, endnote, number, paras)
    }

    // ---- 자동 번호
    private fun autoNumber(k: Int, pos: Int, cs: Int): Inline {
        val r = t.reader(k)
        r.skip(4)
        val prop = r.s32()
        val number = r.u16()
        val kind = when (prop and 0x0F) {
            1 -> AutoNumberKind.FOOTNOTE
            2 -> AutoNumberKind.ENDNOTE
            3 -> AutoNumberKind.PICTURE
            4 -> AutoNumberKind.TABLE
            5 -> AutoNumberKind.EQUATION
            6 -> AutoNumberKind.TOTAL_PAGES // HWPX numType 순서 (PAGE..EQUATION, TOTAL_PAGE)
            else -> AutoNumberKind.PAGE
        }
        return Inline.AutoNumber(pos, cs, kind, HwpControlReader.numberType((prop ushr 4) and 0xFF), number)
    }

    // ---- 새 번호 지정: 쪽 번호이고 구역 첫 쪽에 있을 때만 구역 시작 번호로 반영
    private fun newNumber(k: Int, pos: Int, top: Boolean) {
        val r = t.reader(k)
        r.skip(4)
        val prop = r.s32()
        val number = r.u16()
        if (prop and 0x0F == 0 && top && onFirstPage(pos) && number > 0) startPageNumber = number
    }

    // ---- 쪽 번호 위치: 모델에는 구역당 하나뿐이므로 처음으로 "보이는" 설정을 쓴다 (표지에 '없음' 이 먼저 와도 본문 번호가 살도록)
    private fun pageNumberPos(k: Int) {
        val cur = pageNumbering
        if (cur != null && cur.position != PageNumberPos.NONE) return
        val r = t.reader(k)
        r.skip(4)
        val prop = r.s32()
        r.u16() // 번호 (사용 안 함)
        r.u16() // 사용자 기호
        r.u16() // 앞 장식 문자
        val side = r.u16() // 뒤 장식 문자 = 줄표 (HWPX pageNum sideChar, hwp2hwpx ForPageNum)
        val pos = HwpControlReader.pageNumberPos((prop ushr 8) and 0x0F)
        if (cur != null && pos == PageNumberPos.NONE) return
        pageNumbering = PageNumbering(
            position = pos,
            numberType = HwpControlReader.numberType(prop and 0xFF),
            dash = if (side >= 32) side.toChar().toString() else "",
            fromParagraph = topIndex,
        )
    }

    // ---- 감추기: 구역 첫 쪽에 있을 때만 구역의 "첫 쪽 감추기" 로 반영 (모델에 쪽별 감추기가 없음)
    private fun pageHide(k: Int, pos: Int, top: Boolean) {
        if (!top || !onFirstPage(pos)) return
        val r = t.reader(k)
        r.skip(4)
        val prop = r.s32()
        if (prop and 0x01 != 0) hideFirstHeader = true
        if (prop and 0x02 != 0) hideFirstFooter = true
        if (prop and 0x20 != 0) hideFirstPageNumber = true
    }

    // ---- 덧말
    private fun ruby(k: Int, pos: Int, cs: Int): Inline? {
        val r = t.reader(k)
        r.skip(4)
        val main = r.hwpString()
        val sub = r.hwpString()
        val position = r.s32()
        if (main.isEmpty() && sub.isEmpty()) return null
        return Inline.Ruby(pos, cs, cleanText(main), cleanText(sub), below = position == 1)
    }

    // ---- 글자 겹침: 겹친 글자를 글자로 (한 컨트롤 = 8칸 안에서).
    // 첫 글자가 테두리 모양(◯, □ 등)이면 빼고 안쪽 글자만 (hwp2hwpx ForCompose 와 같은 규칙 = HWPX composeText)
    private fun composeText(k: Int, pos: Int, cs: Int): Inline? {
        if (pos == Int.MAX_VALUE) return null
        val r = t.reader(k)
        r.skip(4)
        val count = r.u16()
        if (count <= 0 || count > 8 || r.remaining < count * 2) return null
        var s = r.utf16(count)
        if (s.length > 1 && s[0] in COMPOSE_SHAPES) s = s.substring(1)
        s = cleanText(s)
        return if (s.isEmpty()) null else Inline.Text(pos, s, cs)
    }

    // ---- 필드
    private fun fieldStart(k: Int, pos: Int, id: Int): Inline? {
        if (pos == Int.MAX_VALUE) return null
        val r = t.reader(k)
        r.skip(4)
        r.s32() // 속성
        r.u8() // 기타 속성
        val command = r.hwpString()
        val kind = when (id) {
            ID_HLK -> FieldKind.HYPERLINK
            ID_CLK -> FieldKind.CLICK_HERE
            else -> FieldKind.OTHER
        }
        val fid = nextFieldId++
        if (openFields.size < MAX_OPEN_FIELDS) openFields.add(intArrayOf(id and 0xFFFFFF, fid))
        val url = if (kind == FieldKind.HYPERLINK) HwpControlReader.hyperlinkUrl(command) else null
        return Inline.FieldStart(pos, fid, kind, url)
    }

    private fun fieldEnd(pos: Int, ctrlId: Int): Inline? {
        if (openFields.isEmpty()) return null
        val key = ctrlId and 0xFFFFFF
        var idx = openFields.indexOfLast { it[0] == key }
        if (idx < 0) idx = openFields.size - 1
        val f = openFields.removeAt(idx)
        return Inline.FieldEnd(pos, f[1])
    }

    /** 캡션 LIST_HEADER(k) → Caption */
    fun caption(k: Int, parentEnd: Int, depth: Int): Pair<Caption, Int> {
        val r = t.reader(k)
        r.s32() // 문단 수
        r.s32() // 속성
        val cprop = r.s32()
        val width = r.s32()
        val gap = r.u16()
        val (paras, next) = paragraphList(k, parentEnd, depth)
        return Caption(side = cprop and 0x03, width = width.coerceAtLeast(0), gap = gap, paragraphs = paras) to next
    }

    private fun cleanText(s: String): String {
        if (s.none { it < ' ' }) return s
        return buildString { for (c in s) if (c >= ' ') append(c) }
    }

    companion object {
        /** 표/글상자/각주 중첩 한도 (정상 문서는 10 단계도 드물다) */
        const val MAX_DEPTH = 40
        private const val MAX_OPEN_FIELDS = 256

        /** 글자 겹침의 테두리 모양 문자 (첫 글자일 때) */
        private const val COMPOSE_SHAPES = "◯●□■△▲☼◇◆▢♲♺♻\u3000"

        val ID_TBL = ctrlId("tbl ")
        val ID_GSO = ctrlId("gso ")
        val ID_EQED = ctrlId("eqed")
        val ID_FORM = ctrlId("form")
        val ID_SECD = ctrlId("secd")
        val ID_COLD = ctrlId("cold")
        val ID_HEAD = ctrlId("head")
        val ID_FOOT = ctrlId("foot")
        val ID_FN = ctrlId("fn  ")
        val ID_EN = ctrlId("en  ")
        val ID_ATNO = ctrlId("atno")
        val ID_NWNO = ctrlId("nwno")
        val ID_PGNP = ctrlId("pgnp")
        val ID_PGHD = ctrlId("pghd")
        val ID_TDUT = ctrlId("tdut")
        val ID_TCPS = ctrlId("tcps")
        val ID_HLK = ctrlId("%hlk")
        val ID_CLK = ctrlId("%clk")
    }
}
