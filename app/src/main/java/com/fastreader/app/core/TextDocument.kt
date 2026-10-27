package com.fastreader.app.core

enum class BlockKind { PARAGRAPH, TABLE_ROW }

data class TextBlock(val text: String, val kind: BlockKind = BlockKind.PARAGRAPH)

/** HWP/HWPX 에서 추출한 본문. 화면에는 블록(문단/표의 행) 단위로 표시한다. */
class TextDocument(val blocks: List<TextBlock>, val notice: String? = null) {
    val isEmpty: Boolean get() = blocks.all { it.text.isBlank() }
}

/** 문단 목록을 만들면서 연속된 빈 줄을 하나로 줄여 화면 낭비를 막는다. */
class BlockCollector {
    private val blocks = ArrayList<TextBlock>()
    private var lastBlank = true

    fun add(text: String, kind: BlockKind = BlockKind.PARAGRAPH) {
        val cleaned = text.trimEnd()
        if (cleaned.isBlank()) {
            if (lastBlank) return
            lastBlank = true
            blocks.add(TextBlock("", BlockKind.PARAGRAPH))
        } else {
            lastBlank = false
            blocks.add(TextBlock(cleaned, kind))
        }
    }

    fun build(notice: String? = null): TextDocument {
        while (blocks.isNotEmpty() && blocks.last().text.isEmpty()) blocks.removeAt(blocks.size - 1)
        return TextDocument(blocks, notice)
    }
}

const val TABLE_CELL_SEPARATOR = "  │  "
