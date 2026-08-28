package com.example.minicpm_v_demo.rag.parser

enum class BlockStructure {
    PARAGRAPH,
    HEADING,
    CODE,
    TABLE_ROW,
}

data class ParsedBlock(
    val text: String,
    val structure: BlockStructure,
    val titlePath: String? = null,
    val locatorType: String,
    val locatorValue: String,
)
