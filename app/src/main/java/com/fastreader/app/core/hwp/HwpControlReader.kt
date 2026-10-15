package com.fastreader.app.core.hwp

import com.fastreader.app.core.doc.Border
import com.fastreader.app.core.doc.BorderFill
import com.fastreader.app.core.doc.Caption
import com.fastreader.app.core.doc.Cell
import com.fastreader.app.core.doc.ColumnDef
import com.fastreader.app.core.doc.DrawObject
import com.fastreader.app.core.doc.Equation
import com.fastreader.app.core.doc.Gradient
import com.fastreader.app.core.doc.HorzRel
import com.fastreader.app.core.doc.Insets
import com.fastreader.app.core.doc.LineType
import com.fastreader.app.core.doc.NumberType
import com.fastreader.app.core.doc.ObjAlign
import com.fastreader.app.core.doc.ObjectLayout
import com.fastreader.app.core.doc.PageDef
import com.fastreader.app.core.doc.PageNumberPos
import com.fastreader.app.core.doc.Paragraph
import com.fastreader.app.core.doc.Picture
import com.fastreader.app.core.doc.Shape
import com.fastreader.app.core.doc.ShapeKind
import com.fastreader.app.core.doc.Table
import com.fastreader.app.core.doc.TextWrap
import com.fastreader.app.core.doc.VertRel
import kotlin.math.roundToInt

/**
 * 개체 컨트롤(표, 그리기 개체, 수식, 양식) 읽기와 레코드 내용 해석 도우미.
 * 레코드 배치는 hwplib reader/bodytext/paragraph/control 아래 코드와 같다.
 */
internal class HwpControlReader(private val t: HwpRecordTree, private val sec: HwpSectionReader) {

    // ------------------------------------------------------------------ 표

    private class RawCell(
        val col: Int, val row: Int, val colSpan: Int, val rowSpan: Int, val width: Int, val height: Int,
        val margin: Insets, val borderFillId: Int, val vertAlign: Int, val header: Boolean, val paragraphs: List<Paragraph>,
    )

    fun table(k: Int, depth: Int): Table? {
        val r = t.reader(k)
        r.skip(4)
        val (layout, prop) = objectLayout(r)
        val e = t.end[k]
        var caption: Caption? = null
        var tableRec = -1
        val raw = ArrayList<RawCell>()
        var j = k + 1
        while (j < e) {
            when (t.tag[j]) {
                HwpTags.LIST_HEADER -> {
                    if (tableRec < 0) {
                        val (c, next) = sec.caption(j, e, depth)
                        if (prop and HAS_CAPTION != 0 && caption == null) caption = c
                        j = next
                    } else {
                        val cr = t.reader(j)
                        cr.s32() // 문단 수
                        val cprop = cr.s32()
                        val col = cr.u16()
                        val row = cr.u16()
                        val colSpan = cr.u16()
                        val rowSpan = cr.u16()
                        val w = cr.s32()
                        val h = cr.s32()
                        val margin = Insets(left = cr.u16(), right = cr.u16(), top = cr.u16(), bottom = cr.u16())
                        val bf = cr.u16()
                        val (paras, next) = sec.paragraphList(j, e, depth)
                        raw.add(
                            RawCell(
                                col, row, colSpan, rowSpan, w.coerceAtLeast(0), h.coerceAtLeast(0), margin, bf,
                                ((cprop ushr 5) and 0x03).coerceAtMost(2), cprop and (1 shl 18) != 0, paras,
                            ),
                        )
                        j = next
                    }
                    continue
                }
                HwpTags.TABLE -> if (tableRec < 0) tableRec = j
            }
            j = t.end[j]
        }
        if (tableRec < 0) return null
        val tr = t.reader(tableRec)
        val tprop = tr.s32()
        var rowCount = tr.u16()
        var colCount = tr.u16()
        val spacing = tr.u16()
        val inMargin = Insets(left = tr.u16(), right = tr.u16(), top = tr.u16(), bottom = tr.u16())
        tr.skip(rowCount * 2) // 행별 셀 수
        val borderFillId = tr.u16()

        // 정상 문서: 선언된 행/열 수 = 셀들의 (주소 + 병합 수) 최댓값.
        // 손상 문서: 셀이 실제로 차지하는 범위로 줄이거나 늘리되 한도(MAX_GRID)를 넘지 않게 한다
        val valid = raw.filter { it.row < MAX_GRID && it.col < MAX_GRID }
        if (valid.isNotEmpty()) {
            val rowEnd = valid.maxOf { it.row + maxOf(1, it.rowSpan) }
            val colEnd = valid.maxOf { it.col + maxOf(1, it.colSpan) }
            rowCount = maxOf(valid.maxOf { it.row + 1 }, minOf(rowCount, rowEnd))
            colCount = maxOf(valid.maxOf { it.col + 1 }, minOf(colCount, colEnd))
        }
        rowCount = rowCount.coerceAtMost(MAX_GRID)
        colCount = colCount.coerceAtMost(MAX_GRID)
        val cells = valid.filter { it.row < rowCount && it.col < colCount }.map { c ->
            Cell(
                col = c.col, row = c.row,
                colSpan = c.colSpan.coerceIn(1, colCount - c.col),
                rowSpan = c.rowSpan.coerceIn(1, rowCount - c.row),
                width = c.width, height = c.height, margin = c.margin, borderFillId = c.borderFillId,
                vertAlign = c.vertAlign, paragraphs = c.paragraphs, header = c.header,
            )
        }
        return Table(
            layout = layout, caption = caption, rowCount = rowCount, colCount = colCount, cellSpacing = spacing,
            inMargin = inMargin, borderFillId = borderFillId, cells = cells, repeatHeader = tprop and 0x04 != 0,
        )
    }

    // ------------------------------------------------------------------ 수식

    fun equation(k: Int, depth: Int): Equation {
        val r = t.reader(k)
        r.skip(4)
        val (layout, prop) = objectLayout(r)
        val e = t.end[k]
        var caption: Caption? = null
        var script = ""
        var size = 1000
        var color = 0
        var j = k + 1
        var sawEq = false
        while (j < e) {
            when (t.tag[j]) {
                HwpTags.LIST_HEADER -> if (!sawEq && caption == null) {
                    val (c, next) = sec.caption(j, e, depth)
                    if (prop and HAS_CAPTION != 0) caption = c
                    j = next
                    continue
                }
                HwpTags.EQEDIT -> if (!sawEq) {
                    sawEq = true
                    val er = t.reader(j)
                    er.s32() // 속성
                    script = er.hwpString()
                    val s = er.s32()
                    if (s > 0) size = s
                    color = er.color()
                }
            }
            j = t.end[j]
        }
        return Equation(layout, caption, script = script, baseSize = size, color = color)
    }

    // ------------------------------------------------------------------ 양식 개체 (단추 등): 자리만 표시

    fun form(k: Int): Shape {
        val r = t.reader(k)
        r.skip(4)
        return Shape(objectLayout(r).first, kind = ShapeKind.OTHER, line = Border(LineType.SOLID, 0.12f, 0x808080))
    }

    // ------------------------------------------------------------------ 그리기 개체

    fun gso(k: Int, depth: Int): DrawObject? {
        val r = t.reader(k)
        r.skip(4)
        val (layout, prop) = objectLayout(r)
        val e = t.end[k]
        var caption: Caption? = null
        var sc = -1
        var j = k + 1
        while (j < e) {
            when (t.tag[j]) {
                HwpTags.LIST_HEADER -> if (sc < 0) {
                    val (c, next) = sec.caption(j, e, depth)
                    if (prop and HAS_CAPTION != 0 && caption == null) caption = c
                    j = next
                    continue
                }
                HwpTags.SHAPE_COMPONENT -> if (sc < 0) sc = j
            }
            j = t.end[j]
        }
        if (sc < 0) return null
        return shapeComponent(sc, layout, caption, top = true, depth = depth)
    }

    /**
     * SHAPE_COMPONENT(k) 와 그 자식들 → 개체.
     * [top] 이면 컨트롤 바로 아래(ID 가 두 번 저장됨), 아니면 묶음 개체 안의 자식(ID 한 번).
     */
    private fun shapeComponent(k: Int, layoutIn: ObjectLayout?, caption: Caption?, top: Boolean, depth: Int): DrawObject? {
        val r = t.reader(k)
        val id = r.s32()
        if (top) r.skip(4)
        val offX = r.s32()
        val offY = r.s32()
        r.u16() // 묶음 수준
        r.u16() // 개체 버전
        val orgW = r.s32()
        val orgH = r.s32()
        val curW = r.s32()
        val curH = r.s32()
        r.s32() // 속성 (뒤집기 등)
        r.u16() // 회전각
        r.s32() // 회전 중심 x
        r.s32() // 회전 중심 y
        val matrix = renderingMatrix(r)
        // 변환 행렬로 옮긴 (0,0)-(orgW,orgH) 의 경계 상자 [minX, minY, maxX, maxY] (계산 불가면 null)
        val box = transformedBox(matrix, orgW, orgH)
        val layout = layoutIn ?: childLayout(offX, offY, curW, curH, box)

        if (id == GSO_CONTAINER) {
            val children = ArrayList<DrawObject>()
            if (depth <= HwpSectionReader.MAX_DEPTH) {
                t.forChildren(k) { c ->
                    if (t.tag[c] == HwpTags.SHAPE_COMPONENT && children.size < MAX_CHILDREN) {
                        shapeComponent(c, null, null, top = false, depth = depth + 1)?.let(children::add)
                    }
                }
            }
            return Shape(layout, caption, ShapeKind.CONTAINER, line = Border(LineType.NONE), children = children)
        }

        // 선/채우기 (그림은 이 부분이 없을 수 있다 → 0 = 없음)
        val lineColor = r.color()
        val thick = r.s32()
        val lprop = r.s32()
        r.u8() // 외곽선 스타일
        val line = border(lprop and 0x3F, thick, lineColor)
        val fill = fill(r)?.let { f ->
            val img = f.imageFillId
            if (img != null && !sec.hasBinItem(img.removePrefix("bin").toIntOrNull() ?: 0)) f.copy(imageFillId = null) else f
        }

        // 글상자 / 개체별 레코드
        val e = t.end[k]
        var paras: List<Paragraph> = emptyList()
        var textMargin = Insets(283, 283, 283, 283)
        var textVA = 0
        var spec = -1
        var hasText = false
        var j = k + 1
        while (j < e) {
            val tg = t.tag[j]
            if (tg == HwpTags.LIST_HEADER && !hasText) {
                hasText = true
                val lr = t.reader(j)
                lr.s32() // 문단 수
                val lprop2 = lr.s32()
                textMargin = Insets(left = lr.u16(), right = lr.u16(), top = lr.u16(), bottom = lr.u16())
                textVA = ((lprop2 ushr 5) and 0x03).coerceAtMost(2)
                val (ps, next) = sec.paragraphList(j, e, depth)
                paras = ps
                j = next
                continue
            }
            if (spec < 0 && tg in SPECIFIC_TAGS) spec = j
            j = t.end[j]
        }

        // 점 좌표는 생성 시 크기(orgW/orgH) 기준 → 렌더링 행렬(크기 조절·뒤집기·회전)을 적용해 개체 상자 기준으로.
        // 행렬이 없으면 현재 크기 비율로만 조정
        val sx = if (orgW > 0 && curW > 0) curW.toDouble() / orgW else 1.0
        val sy = if (orgH > 0 && curH > 0) curH.toDouble() / orgH else 1.0
        fun pts(vararg v: Int): List<Int> {
            val out = ArrayList<Int>(v.size)
            var i = 0
            while (i + 1 < v.size) {
                val x = v[i].toDouble()
                val y = v[i + 1].toDouble()
                if (matrix != null && box != null) {
                    out.add((matrix[0] * x + matrix[1] * y + matrix[2] - box[0]).roundToInt())
                    out.add((matrix[3] * x + matrix[4] * y + matrix[5] - box[1]).roundToInt())
                } else {
                    out.add((x * sx).roundToInt())
                    out.add((y * sy).roundToInt())
                }
                i += 2
            }
            return out
        }

        val sr = if (spec >= 0) t.reader(spec) else null
        return when (id) {
            GSO_PICTURE -> if (sr != null && t.tag[spec] == HwpTags.SHAPE_COMPONENT_PICTURE) picture(sr, layout, caption) else
                Picture(layout, caption, imageId = null)
            GSO_RECT -> {
                var radius = 0
                var points = emptyList<Int>()
                if (sr != null && t.tag[spec] == HwpTags.SHAPE_COMPONENT_RECTANGLE) {
                    radius = sr.u8().coerceIn(0, 100)
                    points = pts(sr.s32(), sr.s32(), sr.s32(), sr.s32(), sr.s32(), sr.s32(), sr.s32(), sr.s32())
                }
                Shape(layout, caption, ShapeKind.RECT, line, fill, cornerRadius = radius, points = points, paragraphs = paras, textMargin = textMargin, textVertAlign = textVA)
            }
            GSO_ELLIPSE -> Shape(layout, caption, ShapeKind.ELLIPSE, line, fill, paragraphs = paras, textMargin = textMargin, textVertAlign = textVA)
            GSO_ARC -> {
                var points = emptyList<Int>()
                if (sr != null && t.tag[spec] == HwpTags.SHAPE_COMPONENT_ARC) {
                    sr.u8() // 호 종류
                    points = pts(sr.s32(), sr.s32(), sr.s32(), sr.s32(), sr.s32(), sr.s32())
                }
                Shape(layout, caption, ShapeKind.ARC, line, fill, points = points, paragraphs = paras, textMargin = textMargin, textVertAlign = textVA)
            }
            GSO_LINE, GSO_LINK_LINE -> {
                var points = emptyList<Int>()
                if (sr != null && t.tag[spec] == HwpTags.SHAPE_COMPONENT_LINE) points = pts(sr.s32(), sr.s32(), sr.s32(), sr.s32())
                Shape(layout, caption, ShapeKind.LINE, line, null, points = points)
            }
            GSO_POLYGON, GSO_CURVE -> {
                val want = if (id == GSO_POLYGON) HwpTags.SHAPE_COMPONENT_POLYGON else HwpTags.SHAPE_COMPONENT_CURVE
                var points = emptyList<Int>()
                if (sr != null && t.tag[spec] == want) {
                    val n = sr.s32().coerceIn(0, sr.remaining / 8)
                    val v = IntArray(n * 2) { sr.s32() }
                    points = pts(*v)
                }
                Shape(layout, caption, if (id == GSO_POLYGON) ShapeKind.POLYGON else ShapeKind.CURVE, line, fill, points = points, paragraphs = paras, textMargin = textMargin, textVertAlign = textVA)
            }
            GSO_OLE -> Shape(layout, caption, ShapeKind.OLE, line, fill)
            GSO_TEXTART -> {
                var text = ""
                if (sr != null && t.tag[spec] == HwpTags.SHAPE_COMPONENT_TEXTART) {
                    sr.skip(32) // 네 점
                    text = sr.hwpString().filter { it >= ' ' || it == '\n' }
                }
                Shape(layout, caption, ShapeKind.TEXT_ART, line, fill, text = text)
            }
            else -> Shape(layout, caption, ShapeKind.OTHER, line, fill, paragraphs = paras, textMargin = textMargin, textVertAlign = textVA)
        }
    }

    private fun picture(r: HwpReader, layout: ObjectLayout, caption: Caption?): Picture {
        val bColor = r.color()
        val bThick = r.s32()
        val bProp = r.s32()
        r.skip(32) // 이미지 사각형 네 점
        val cropL = r.s32()
        val cropT = r.s32()
        val cropR = r.s32()
        val cropB = r.s32()
        val inMargin = Insets(left = r.u16(), right = r.u16(), top = r.u16(), bottom = r.u16())
        // 그림 정보 (HWP 5.0 표 27): 밝기, 명암, 효과, BinItem ID
        val brightness = r.s8()
        val contrast = r.s8()
        val effect = r.u8()
        val binId = r.u16()
        var imgW = 0
        var imgH = 0
        if (!r.atEnd) {
            r.u8() // 테두리 투명도
            if (!r.atEnd) r.s32() // 인스턴스 ID
            if (!r.atEnd && skipPictureEffect(r) && r.remaining >= 8) {
                imgW = r.s32()
                imgH = r.s32()
            }
        }
        return Picture(
            layout = layout, caption = caption,
            imageId = if (sec.hasBinItem(binId)) "bin$binId" else null,
            cropLeft = cropL, cropTop = cropT, cropRight = cropR, cropBottom = cropB,
            imageWidth = imgW.coerceAtLeast(0), imageHeight = imgH.coerceAtLeast(0),
            border = border(bProp and 0x3F, bThick, bColor),
            effect = effect.coerceAtMost(3), brightness = brightness, contrast = contrast, inMargin = inMargin,
        )
    }

    /** 그림 효과(그림자/네온/부드러운 가장자리/반사)를 건너뛴다. 해석할 수 없으면 false */
    private fun skipPictureEffect(r: HwpReader): Boolean {
        val prop = r.s32()
        if (prop and 0x01 != 0) {
            r.skip(44)
            if (!skipEffectColor(r)) return false
        }
        if (prop and 0x02 != 0) {
            r.skip(8)
            if (!skipEffectColor(r)) return false
        }
        if (prop and 0x04 != 0) r.skip(4)
        if (prop and 0x08 != 0) r.skip(56)
        return !r.overrun
    }

    private fun skipEffectColor(r: HwpReader): Boolean {
        if (r.s32() != 0) return false // RGB 외 색 표현은 hwplib 도 지원하지 않음
        r.skip(4)
        val n = r.s32()
        if (n < 0 || n > r.remaining / 8) return false
        r.skip(n * 8)
        return !r.overrun
    }

    /** 변환 행렬로 (0,0)-(orgW,orgH) 를 옮긴 경계 상자 [minX, minY, maxX, maxY]. 계산할 수 없으면 null */
    private fun transformedBox(m: DoubleArray?, orgW: Int, orgH: Int): DoubleArray? {
        if (m == null || orgW <= 0 || orgH <= 0) return null
        val xs = doubleArrayOf(0.0, orgW.toDouble(), 0.0, orgW.toDouble())
        val ys = doubleArrayOf(0.0, 0.0, orgH.toDouble(), orgH.toDouble())
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (i in 0 until 4) {
            val px = m[0] * xs[i] + m[1] * ys[i] + m[2]
            val py = m[3] * xs[i] + m[4] * ys[i] + m[5]
            minX = minOf(minX, px); maxX = maxOf(maxX, px)
            minY = minOf(minY, py); maxY = maxOf(maxY, py)
        }
        val box = doubleArrayOf(minX, minY, maxX, maxY)
        return if (box.all { it.isFinite() && kotlin.math.abs(it) < 1e9 }) box else null
    }

    /** 묶음 안 자식 개체의 배치: 변환된 경계 상자 (묶음 영역 기준). 행렬이 없으면 오프셋 + 현재 크기 */
    private fun childLayout(offX: Int, offY: Int, curW: Int, curH: Int, box: DoubleArray?): ObjectLayout {
        var x = offX
        var y = offY
        var w = curW
        var h = curH
        if (box != null) {
            x = box[0].roundToInt()
            y = box[1].roundToInt()
            w = (box[2] - box[0]).roundToInt()
            h = (box[3] - box[1]).roundToInt()
        }
        return ObjectLayout(
            treatAsChar = false, vertRelTo = VertRel.PARA, horzRelTo = HorzRel.PARA,
            vertOffset = y, horzOffset = x, width = w.coerceAtLeast(0), height = h.coerceAtLeast(0),
        )
    }

    /** 렌더링 정보: 이동 행렬 × (크기·회전 행렬 쌍)… 을 곱한 2x3 행렬. 없거나 깨졌으면 null */
    private fun renderingMatrix(r: HwpReader): DoubleArray? {
        val n = r.u16()
        if (r.remaining < 48) return null
        var m = matrix(r)
        val pairs = minOf(n, r.remaining / 96)
        for (i in 0 until pairs) {
            m = mul(m, matrix(r))
            m = mul(m, matrix(r))
        }
        if (pairs < n) r.skip(r.remaining) // 잘린 레코드
        return m
    }

    private fun matrix(r: HwpReader): DoubleArray = DoubleArray(6) {
        val lo = r.s32().toLong() and 0xFFFFFFFFL
        val hi = r.s32().toLong()
        java.lang.Double.longBitsToDouble((hi shl 32) or lo)
    }

    /** 2x3 아핀 행렬 곱 a·b ([e1 e2 e3; e4 e5 e6]) */
    private fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[0] * b[0] + a[1] * b[3], a[0] * b[1] + a[1] * b[4], a[0] * b[2] + a[1] * b[5] + a[2],
        a[3] * b[0] + a[4] * b[3], a[3] * b[1] + a[4] * b[4], a[3] * b[2] + a[4] * b[5] + a[5],
    )

    companion object {
        private const val HAS_CAPTION = 1 shl 29
        private const val MAX_GRID = 4096
        private const val MAX_CHILDREN = 10_000

        private val GSO_LINE = ctrlId("\$lin")
        private val GSO_RECT = ctrlId("\$rec")
        private val GSO_ELLIPSE = ctrlId("\$ell")
        private val GSO_ARC = ctrlId("\$arc")
        private val GSO_POLYGON = ctrlId("\$pol")
        private val GSO_CURVE = ctrlId("\$cur")
        private val GSO_PICTURE = ctrlId("\$pic")
        private val GSO_OLE = ctrlId("\$ole")
        private val GSO_CONTAINER = ctrlId("\$con")
        private val GSO_LINK_LINE = ctrlId("\$col")
        private val GSO_TEXTART = ctrlId("\$tat")

        private val SPECIFIC_TAGS = intArrayOf(
            HwpTags.SHAPE_COMPONENT_LINE, HwpTags.SHAPE_COMPONENT_RECTANGLE, HwpTags.SHAPE_COMPONENT_ELLIPSE,
            HwpTags.SHAPE_COMPONENT_ARC, HwpTags.SHAPE_COMPONENT_POLYGON, HwpTags.SHAPE_COMPONENT_CURVE,
            HwpTags.SHAPE_COMPONENT_OLE, HwpTags.SHAPE_COMPONENT_PICTURE, HwpTags.SHAPE_COMPONENT_TEXTART,
        )

        /** 선 굵기 단계(테두리/단 구분선) → mm */
        private val THICKNESS_MM = floatArrayOf(0.1f, 0.12f, 0.15f, 0.2f, 0.25f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 5.0f)

        /**
         * HWP 선 종류 값 → [LineType] (화면에 그려지는 모양 기준).
         * 2 = 파선, 3 = 점선. 한글이 저장한 미리보기 그림(ref-hwp2hwpx test/table_line, test/rectangle)에서 확인.
         * HWPX 는 같은 값을 "DOT"/"DASH" 로 이름을 바꿔 적으므로(hwp2hwpx ValueConvertor 주석의 "hwpx 포맷 오류") HWPX 쪽에서 맞춘다.
         */
        fun lineType(v: Int): LineType = when (v) {
            0 -> LineType.NONE
            1 -> LineType.SOLID
            2 -> LineType.DASH
            3 -> LineType.DOT
            4 -> LineType.DASH_DOT
            5 -> LineType.DASH_DOT_DOT
            6 -> LineType.LONG_DASH
            7 -> LineType.CIRCLE
            8 -> LineType.DOUBLE
            9 -> LineType.THIN_THICK
            10 -> LineType.THICK_THIN
            11 -> LineType.THIN_THICK_THIN
            12 -> LineType.WAVE
            13 -> LineType.DOUBLE_WAVE
            14 -> LineType.THICK_3D
            15 -> LineType.THICK_3D_REV
            16 -> LineType.SOLID_3D
            17 -> LineType.SOLID_3D_REV
            else -> LineType.SOLID
        }

        /** 개체 선 (굵기 HWPUNIT). 굵기 0 이하는 표시하지 않는다 */
        fun border(type: Int, thickness: Int, color: Int): Border {
            val lt = lineType(type)
            if (lt == LineType.NONE || thickness <= 0) return Border(LineType.NONE, 0f, color)
            return Border(lt, thickness * 25.4f / 7200f, color)
        }

        /** 번호 모양 값 (hwplib NumberShape) → [NumberType]. 0..16 은 순서가 같다 */
        fun numberType(v: Int): NumberType = when (v) {
            in 0..16 -> NumberType.entries[v]
            else -> NumberType.SYMBOL // 0x80 기호, 0x81 사용자 문자
        }

        fun pageNumberPos(v: Int): PageNumberPos = when (v) {
            1 -> PageNumberPos.TOP_LEFT
            2 -> PageNumberPos.TOP_CENTER
            3 -> PageNumberPos.TOP_RIGHT
            4 -> PageNumberPos.BOTTOM_LEFT
            5 -> PageNumberPos.BOTTOM_CENTER
            6 -> PageNumberPos.BOTTOM_RIGHT
            7 -> PageNumberPos.OUTSIDE_TOP
            8 -> PageNumberPos.OUTSIDE_BOTTOM
            9 -> PageNumberPos.INSIDE_TOP
            10 -> PageNumberPos.INSIDE_BOTTOM
            else -> PageNumberPos.NONE
        }

        private fun align(v: Int): ObjAlign = when (v) {
            1 -> ObjAlign.CENTER
            2 -> ObjAlign.END
            3 -> ObjAlign.INSIDE
            4 -> ObjAlign.OUTSIDE
            else -> ObjAlign.START
        }

        /**
         * 개체 공통 속성 (CTRL_HEADER 의 컨트롤 ID 뒤). hwplib ForCtrlHeaderGso / GsoHeaderProperty.
         * 반환: (배치, 원래 속성 값)
         */
        fun objectLayout(r: HwpReader): Pair<ObjectLayout, Int> {
            val prop = r.s32()
            val y = r.s32()
            val x = r.s32()
            val w = r.s32()
            val h = r.s32()
            val z = r.s32()
            val out = Insets(left = r.u16(), right = r.u16(), top = r.u16(), bottom = r.u16())
            r.s32() // 인스턴스 ID
            if (r.remaining > 4) r.s32() // 쪽 나눔 방지
            val desc = if (!r.atEnd) r.hwpString() else ""
            val layout = ObjectLayout(
                treatAsChar = prop and 0x01 != 0,
                vertRelTo = when ((prop ushr 3) and 0x03) {
                    0 -> VertRel.PAPER
                    1 -> VertRel.PAGE
                    else -> VertRel.PARA
                },
                vertAlign = align((prop ushr 5) and 0x07),
                horzRelTo = when ((prop ushr 8) and 0x03) {
                    0 -> HorzRel.PAPER
                    1 -> HorzRel.PAGE
                    2 -> HorzRel.COLUMN
                    else -> HorzRel.PARA
                },
                horzAlign = align((prop ushr 10) and 0x07),
                vertOffset = y,
                horzOffset = x,
                width = w.coerceAtLeast(0),
                height = h.coerceAtLeast(0),
                zOrder = z,
                outMargin = out,
                textWrap = when ((prop ushr 21) and 0x07) {
                    0 -> TextWrap.SQUARE
                    2 -> TextWrap.BEHIND_TEXT
                    3 -> TextWrap.IN_FRONT_OF_TEXT
                    else -> TextWrap.TOP_AND_BOTTOM
                },
                description = desc.filter { it >= ' ' || it == '\n' },
            )
            return layout to prop
        }

        /** 채우기 정보 (hwplib ForFillInfo). 채우기가 없으면 null */
        fun fill(r: HwpReader): BorderFill? {
            val type = r.s32()
            if (type == 0) {
                r.skip(4)
                return null
            }
            var fillColor: Int? = null
            var patternColor: Int? = null
            var patternType = -1
            var gradient: Gradient? = null
            var image: String? = null
            if (type and 0x01 != 0) {
                val back = r.rawColor()
                val pat = r.rawColor()
                patternType = r.s32()
                fillColor = colorOrNull(back)
                if (patternType in 0..5) patternColor = colorOrNull(pat) else patternType = -1
            }
            if (type and 0x04 != 0) {
                val gType = r.s8()
                val angle = r.s32()
                val cx = r.s32()
                val cy = r.s32()
                r.s32() // 번짐 정도
                val count = r.s32()
                val n = count.coerceIn(0, r.remaining / 4)
                if (n > 2) r.skip(n * 4) // 색 위치
                val colors = List(minOf(n, r.remaining / 4)) { rgb(r.rawColor()) }
                gradient = Gradient(type = (gType - 1).coerceIn(0, 3), angle = angle, centerX = cx, centerY = cy, colors = colors)
            }
            if (type and 0x02 != 0) {
                r.u8() // 채우기 방식
                r.s8() // 밝기
                r.s8() // 명암
                r.u8() // 효과
                val bin = r.u16()
                if (bin > 0) image = "bin$bin"
            }
            return BorderFill(fillColor = fillColor, patternColor = patternColor, patternType = patternType, gradient = gradient, imageFillId = image)
        }

        /** COLORREF(0x00BBGGRR) → 0xRRGGBB */
        private fun rgb(v: Int) = ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v ushr 16) and 0xFF)

        /** 상위 바이트가 있는 값(0xFFFFFFFF 등)은 "없음" */
        private fun colorOrNull(v: Int): Int? = if (v ushr 24 != 0) null else rgb(v)

        fun pageDef(r: HwpReader): PageDef {
            val w = r.s32()
            val h = r.s32()
            val left = r.s32()
            val right = r.s32()
            val top = r.s32()
            val bottom = r.s32()
            val header = r.s32()
            val footer = r.s32()
            val gutter = r.s32()
            val prop = r.s32()
            val landscape = prop and 0x01 != 0
            if (w <= 0 || h <= 0) return PageDef()
            return PageDef(
                width = if (landscape) h else w, height = if (landscape) w else h,
                marginLeft = left.coerceAtLeast(0), marginRight = right.coerceAtLeast(0),
                marginTop = top.coerceAtLeast(0), marginBottom = bottom.coerceAtLeast(0),
                marginHeader = header.coerceAtLeast(0), marginFooter = footer.coerceAtLeast(0),
                marginGutter = gutter.coerceAtLeast(0), landscape = landscape,
            )
        }

        /** 단 정의 (컨트롤 ID 뒤). hwplib ForControlColumnDefine */
        fun columnDef(r: HwpReader): ColumnDef {
            val prop = r.u16()
            val count = ((prop ushr 2) and 0xFF).coerceAtLeast(1)
            val same = prop and (1 shl 12) != 0
            var gap = 0
            val widths = ArrayList<Int>()
            if (count < 2 || same) {
                gap = r.u16()
                r.u16() // 속성 2
            } else {
                r.u16() // 속성 2
                for (i in 0 until count) {
                    widths.add(r.u16())
                    val g = r.u16()
                    if (i == 0) gap = g
                }
            }
            val type = r.u8()
            val thick = r.u8()
            val color = r.color()
            val sep = if (type == 0 || r.overrun) null else Border(lineType(type), THICKNESS_MM.getOrElse(thick) { 0.12f }, color)
            return ColumnDef(count = count, gap = gap, widths = widths, separator = sep)
        }

        /**
         * 하이퍼링크 필드 명령 문자열 → URL.
         * 형식: "주소;종류;...;" 이며 주소 안의 ':', ';', '?' 등은 '\' 로 이스케이프된다 (예: "http\://a.com\?x=1;1;0;0;").
         * "주소|대상" 형태면 '|' 앞까지.
         */
        fun hyperlinkUrl(command: String): String? {
            val sb = StringBuilder()
            var i = 0
            while (i < command.length) {
                val c = command[i]
                if (c == '\\' && i + 1 < command.length) {
                    sb.append(command[i + 1])
                    i += 2
                    continue
                }
                if (c == ';' || c == '|') break
                sb.append(c)
                i++
            }
            val url = sb.toString().trim()
            if (url.isEmpty()) return null
            return if (url.startsWith("www.", ignoreCase = true)) "http://$url" else url
        }
    }
}
