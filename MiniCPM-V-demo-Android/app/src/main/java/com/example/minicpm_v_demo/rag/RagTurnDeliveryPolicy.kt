package com.example.minicpm_v_demo.rag

internal fun RagTurnPlan.plainModelPromptOrNull(originalUserText: String): String? = when (this) {
    is RagTurnPlan.Ready -> null
    else -> originalUserText
}
