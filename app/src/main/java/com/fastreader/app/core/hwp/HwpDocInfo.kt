package com.fastreader.app.core.hwp

import com.fastreader.app.core.doc.BorderFill
import com.fastreader.app.core.doc.Bullet
import com.fastreader.app.core.doc.CharShape
import com.fastreader.app.core.doc.Numbering
import com.fastreader.app.core.doc.ParaShape
import com.fastreader.app.core.doc.Style

/** HWP BIN_DATA 레코드 정보 */
data class HwpBinItem(
    /** 1부터 시작하는 BinData ID (그림 개체가 참조하는 번호) */
    val binId: Int,
    /** 0 LINK, 1 EMBEDDING, 2 STORAGE */
    val type: Int,
    /** 확장자 (소문자, 점 없이) */
    val extension: String,
    /** 0 문서 설정 따름, 1 압축, 2 압축 안 함 */
    val compression: Int,
) {
    /** 문서 안 스트림 경로 (EMBEDDING 일 때). 예: BinData/BIN0001.jpg */
    val streamPath: String get() = "BinData/BIN%04X.%s".format(binId, extension)

    /** [com.fastreader.app.core.doc.RichDocument.images] 의 키 */
    val imageId: String get() = "bin$binId"
}

/** DocInfo 스트림에서 읽은 문서 전체 공통 정보 */
class HwpDocInfo(
    /** 구역 수 (DOCUMENT_PROPERTIES) */
    val sectionCount: Int,
    /** 시작 번호: 쪽, 각주, 미주, 그림, 표, 수식 (DOCUMENT_PROPERTIES) */
    val startPage: Int,
    val startFootnote: Int,
    val startEndnote: Int,
    val faceNames: List<List<String>>,
    val charShapes: Map<Int, CharShape>,
    val paraShapes: Map<Int, ParaShape>,
    val borderFills: Map<Int, BorderFill>,
    val numberings: Map<Int, Numbering>,
    val bullets: Map<Int, Bullet>,
    val styles: Map<Int, Style>,
    val binItems: Map<Int, HwpBinItem>,
) {
    companion object {
        val EMPTY = HwpDocInfo(1, 1, 1, 1, List(7) { emptyList() }, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
}
