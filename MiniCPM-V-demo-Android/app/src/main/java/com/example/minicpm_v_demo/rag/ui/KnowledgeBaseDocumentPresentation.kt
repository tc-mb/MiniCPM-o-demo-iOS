package com.example.minicpm_v_demo.rag.ui

import com.example.minicpm_v_demo.rag.db.DocumentStatus

sealed interface KnowledgeBaseDocumentPresentation {
    data class Processing(val status: DocumentStatus) : KnowledgeBaseDocumentPresentation
    data object Uploaded : KnowledgeBaseDocumentPresentation
    data class Failure(val reason: String) : KnowledgeBaseDocumentPresentation

    companion object {
        fun from(status: DocumentStatus, errorCode: String?): KnowledgeBaseDocumentPresentation? = when (status) {
            DocumentStatus.QUEUED,
            DocumentStatus.COPYING,
            DocumentStatus.PARSING,
            DocumentStatus.OCR,
            DocumentStatus.CHUNKING,
            DocumentStatus.EMBEDDING,
            DocumentStatus.INDEXING,
            -> Processing(status)
            DocumentStatus.READY -> Uploaded
            DocumentStatus.FAILED -> Failure(failureReason(errorCode))
            else -> null
        }

        fun failureReason(errorCode: String?): String = ERROR_REASONS[errorCode] ?: "导入失败"

        private val ERROR_REASONS = mapOf(
            "SOURCE_PERMISSION_LOST" to "文件访问权限已失效",
            "SOURCE_UNAVAILABLE" to "无法读取文件",
            "SOURCE_TOO_LARGE" to "文件超过大小限制",
            "EMPTY_SOURCE" to "文件内容为空",
            "UNSUPPORTED_TYPE" to "暂不支持此文件格式",
            "DECLARATION_MISMATCH" to "文件格式与扩展名不一致",
            "DUPLICATE_CONTENT" to "知识库中已有相同内容",
            "ENCRYPTION_FAILED" to "加密失败",
            "IO_FAILED" to "文件读写失败",
            "IMPORT_COPY_FAILED" to "导入失败",
            "TOKENIZER_MISMATCH" to "知识库模型版本未同步，请重试导入",
            "EMPTY_DOCUMENT" to "文件中没有可索引的文字",
            "CHUNK_FAILED" to "文档切块失败",
            "PARSE_INVALID_ENCODING" to "文本编码无效，请另存为 UTF-8",
            "PARSE_TEXT_LIMIT_EXCEEDED" to "文档文字超过处理上限",
            "PARSE_RECORD_TOO_LARGE" to "文档中存在过大的记录",
            "PARSE_MALFORMED_DOCUMENT" to "文档结构损坏或不完整",
            "PARSE_UNSUPPORTED_FORMAT" to "此格式的解析功能尚未完成",
            "PARSE_FAILED" to "文档解析失败",
            "OCR_FAILED" to "图片文字识别失败",
            "EMBED_FAILED" to "生成文档向量失败",
            "INDEX_FINALIZATION_FAILED" to "写入知识库索引失败",
            "IMPORT_FAILED" to "导入失败",
        )
    }
}
