package com.example.minicpm_v_demo.rag.route

import java.text.Normalizer
import java.util.Locale

data class RagQueryFeatures(
    val normalizedQuery: String,
    val matchedDocumentCount: Int,
    val hasKnowledgeAnchor: Boolean,
    val hasComplexAnchor: Boolean,
    val isSelfContainedRequest: Boolean,
)

object RagQueryFeatureExtractor {
    private const val MAX_QUERY_CODE_POINTS = 4_096
    private val whitespace = Regex("\\s+")
    private val clauseReference = Regex(
        "(?:第\\s*[0-9一二三四五六七八九十百]+\\s*(?:条|款|节|章)|" +
            "\\b(?:section|clause)\\s*[0-9]+(?:\\.[0-9]+)*)",
        RegexOption.IGNORE_CASE,
    )
    private val fileExtension = Regex("\\.(?:pdf|docx?|xlsx?|pptx?|txt|md|csv|html?)\\b", RegexOption.IGNORE_CASE)
    private val knowledgeAnchor = Regex(
        "(?:根据(?:文档|合同|资料|文件)|依据(?:文档|合同|资料|文件)|知识库|文档|文件|资料|材料|" +
            "合同|附件|条款|原文|来源|员工手册|报价单|计划书|会议纪要|制度|规范|预算表|上传|" +
            "\\bdocument\\b|\\bknowledge base\\b|\\bsection\\b|\\bclause\\b|\\bpolicy\\b|" +
            "\\bfile\\b|\\buploaded material\\b|\\bsource\\b|\\bhandbook\\b|\\bquote\\b|\\bproject plan\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val complexAnchor = Regex(
        "(?:比较|对比|区别|差异|汇总|综合|跨文档|多份|多个文件|所有文件|所有文档|全部资料|" +
            "各文档|每份|分别|矛盾|不一致|统一清单|新旧|三份|" +
            "\\bcompare\\b|\\bcontrast\\b|\\bacross\\b|\\ball documents\\b|\\bevery source\\b|" +
            "\\bmultiple files\\b|\\btwo policies\\b|\\beach document\\b|\\bcombine evidence\\b|" +
            "\\bconflicts?\\b|\\bcross-document\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val selfContainedRequest = Regex(
        "(?:^(?:你好|您好|嗨|哈喽|早上好|下午好|晚上好|在吗|谢谢|多谢|辛苦了|好的|知道了|再见)[！!。,.，？? ]*$|" +
            "^(?:hello(?: there)?|hi|good morning|good afternoon|good evening|how are you|thank you|thanks|got it|bye)[!.?, ]*$|" +
            "谢谢|感谢|多谢|辛苦了|明白了|翻译|translate|改(?:写|成|得)|rewrite|润色|写一句|写一封|计算|解释什么是|介绍你自己)",
        RegexOption.IGNORE_CASE,
    )

    fun extract(query: String, knownDocumentNames: List<String>): RagQueryFeatures {
        val normalized = normalize(query)
        val matchedDocumentCount = knownDocumentNames.asSequence()
            .map(::normalize)
            .filter { it.length >= 2 }
            .distinct()
            .count(normalized::contains)
        val hasKnowledgeAnchor = matchedDocumentCount > 0 ||
            knowledgeAnchor.containsMatchIn(normalized) ||
            clauseReference.containsMatchIn(normalized) ||
            fileExtension.containsMatchIn(normalized)
        return RagQueryFeatures(
            normalizedQuery = normalized,
            matchedDocumentCount = matchedDocumentCount,
            hasKnowledgeAnchor = hasKnowledgeAnchor,
            hasComplexAnchor = matchedDocumentCount >= 2 ||
                complexAnchor.containsMatchIn(normalized) ||
                (normalized.contains("合同") && normalized.contains("报价单") && normalized.contains("计划书")),
            isSelfContainedRequest = selfContainedRequest.containsMatchIn(normalized),
        )
    }

    internal fun normalize(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val codePointCount = normalized.codePointCount(0, normalized.length)
        val bounded = if (codePointCount <= MAX_QUERY_CODE_POINTS) {
            normalized
        } else {
            normalized.substring(0, normalized.offsetByCodePoints(0, MAX_QUERY_CODE_POINTS))
        }
        return bounded.replace(whitespace, " ").trim()
    }
}
