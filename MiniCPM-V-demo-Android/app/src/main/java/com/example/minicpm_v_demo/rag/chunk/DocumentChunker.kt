package com.example.minicpm_v_demo.rag.chunk

import com.example.minicpm_v_demo.rag.embed.E5Tokenizer
import com.example.minicpm_v_demo.rag.embed.validatedTokenSpans
import com.example.minicpm_v_demo.rag.parser.BlockStructure
import com.example.minicpm_v_demo.rag.parser.ParsedBlock
import java.security.MessageDigest

data class ChunkConfig(
    val targetTokens: Int = 350,
    val minTokens: Int = 80,
    val maxTokens: Int = 480,
    val overlapTokens: Int = 60,
    val titleMaxTokens: Int = 120,
    val version: Int = 1,
) {
    init {
        require(minTokens > 0 && targetTokens in minTokens..maxTokens)
        require(overlapTokens >= 0 && overlapTokens < targetTokens)
        require(titleMaxTokens >= 0 && version > 0)
    }
}

data class ChunkDraft(
    val ordinal: Int,
    val text: String,
    val searchText: String,
    val titlePath: String?,
    val locatorType: String,
    val locatorValue: String,
    val tokenCount: Int,
    val contentSha256: String,
)

class DocumentChunker(private val tokenizer: E5Tokenizer) {
    fun chunk(blocks: Sequence<ParsedBlock>, config: ChunkConfig = ChunkConfig()): Sequence<ChunkDraft> = sequence {
        val source = blocks.iterator()
        var pending: ParsedBlock? = null
        fun nextBlock(): ParsedBlock? = pending?.also { pending = null } ?: if (source.hasNext()) source.next() else null
        var ordinal = 0
        var activeTitle: String? = null
        var overlapTail = ""
        var block = nextBlock()
        while (block != null) {
            if (block.structure == BlockStructure.HEADING) {
                activeTitle = block.titlePath ?: block.text
                overlapTail = ""
                block = nextBlock()
                continue
            }
            if (block.structure == BlockStructure.TABLE_ROW) {
                overlapTail = ""
                val header = block
                val headerText = header.text.trim()
                var current = mutableListOf(headerText)
                var candidate = nextBlock()
                while (candidate?.structure == BlockStructure.TABLE_ROW) {
                    val rowText = candidate.text.trim()
                    val proposed = (current + rowText).joinToString("\n")
                    if (tokenCount(proposed, activeTitle, config) > config.targetTokens && current.size > 1) {
                        for (draft in tableGroup(current, activeTitle, header, config)) {
                            yield(draft.withOrdinal(ordinal++))
                        }
                        current = mutableListOf(headerText)
                    }
                    current += rowText
                    candidate = nextBlock()
                }
                pending = candidate
                for (draft in tableGroup(current, activeTitle, header, config)) {
                    yield(draft.withOrdinal(ordinal++))
                }
                block = nextBlock()
                continue
            }
            val group = mutableListOf<ParsedBlock>()
            val pageBoundary = block.locatorType == "page"
            group += block
            if (!pageBoundary) {
                var candidate = nextBlock()
                while (candidate != null && candidate.structure !in setOf(BlockStructure.HEADING, BlockStructure.TABLE_ROW) &&
                    candidate.locatorType != "page" && tokenCount(group.joinToString("\n") { it.text }, activeTitle, config) < config.targetTokens
                ) {
                    group += candidate
                    candidate = nextBlock()
                }
                pending = candidate
            }
            val sourceText = group.joinToString("\n") { it.text.trim() }.trim()
            if (sourceText.isEmpty()) {
                block = nextBlock()
                continue
            }
            val locator = group.first()
            val text = if (!pageBoundary && overlapTail.isNotEmpty()) overlapTail + sourceText else sourceText
            val drafts = splitText(text, activeTitle ?: locator.titlePath, locator, config)
            for (draft in drafts) {
                yield(draft.withOrdinal(ordinal++))
            }
            overlapTail = if (pageBoundary || drafts.isEmpty()) "" else tokenTail(drafts.last().text, config.overlapTokens)
            block = nextBlock()
        }
    }

    private fun tableGroup(
        rows: List<String>,
        title: String?,
        locator: ParsedBlock,
        config: ChunkConfig,
    ): List<ChunkDraft> {
        val text = rows.joinToString("\n")
        return if (tokenCount(text, title, config) <= config.maxTokens) {
            listOf(draft(text, title, locator, config))
        } else {
            splitText(text, title, locator, config)
        }
    }

    private fun splitText(text: String, title: String?, locator: ParsedBlock, config: ChunkConfig): List<ChunkDraft> {
        val spans = tokenizer.validatedTokenSpans(text)
        if (spans.isEmpty()) return emptyList()
        val titleTokens = title?.let { tokenizer.validatedTokenSpans(it).size.coerceAtMost(config.titleMaxTokens) } ?: 0
        val window = (config.targetTokens - titleTokens).coerceIn(1, config.maxTokens - titleTokens.coerceAtMost(config.maxTokens - 1))
        val maxBody = (config.maxTokens - titleTokens).coerceAtLeast(1)
        val minBody = (config.minTokens - titleTokens).coerceAtLeast(1).coerceAtMost(maxBody)
        val effectiveWindow = minOf(window, maxBody)
        val result = mutableListOf<ChunkDraft>()
        var startToken = 0
        while (startToken < spans.size) {
            var endToken = minOf(startToken + effectiveWindow, spans.size)
            if (endToken < spans.size) {
                val nextStart = (endToken - config.overlapTokens).coerceAtLeast(startToken + 1)
                if (spans.size - nextStart < minBody) {
                    endToken = (spans.size - minBody + config.overlapTokens)
                        .coerceIn(startToken + 1, minOf(startToken + maxBody, spans.size - 1))
                }
            }
            val startChar = spans[startToken].start
            val endChar = spans[endToken - 1].endExclusive
            result += draft(text.substring(startChar, endChar), title, locator, config)
            if (endToken == spans.size) break
            startToken = (endToken - config.overlapTokens).coerceAtLeast(startToken + 1)
        }
        return result
    }

    private fun draft(text: String, title: String?, locator: ParsedBlock, config: ChunkConfig): ChunkDraft {
        val count = tokenCount(text, title, config)
        val canonical = buildString {
            append(config.version).append('\u0000')
            append(tokenizer.modelId).append('\u0000').append(tokenizer.tokenizerSha256).append('\u0000')
            append(title.orEmpty()).append('\u0000').append(locator.locatorType).append('\u0000')
            append(locator.locatorValue).append('\u0000').append(text)
        }
        return ChunkDraft(
            ordinal = -1,
            text = text,
            searchText = CjkBigramEncoder.encode(text),
            titlePath = title,
            locatorType = locator.locatorType,
            locatorValue = locator.locatorValue,
            tokenCount = count,
            contentSha256 = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) },
        )
    }

    private fun tokenCount(text: String, title: String?, config: ChunkConfig): Int =
        tokenizer.validatedTokenSpans(text).size +
            (title?.let { tokenizer.validatedTokenSpans(it).size.coerceAtMost(config.titleMaxTokens) } ?: 0)

    private fun tokenTail(text: String, count: Int): String {
        if (count <= 0) return ""
        val spans = tokenizer.validatedTokenSpans(text)
        if (spans.isEmpty()) return ""
        return text.substring(spans[(spans.size - count).coerceAtLeast(0)].start)
    }

    private fun ChunkDraft.withOrdinal(value: Int) = copy(ordinal = value)
}
