package com.example.minicpm_v_demo.rag.ui

import com.example.minicpm_v_demo.CitationRef
import com.example.minicpm_v_demo.rag.db.ChunkEntity
import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus

sealed interface CitationSourceResolution {
    data class Available(
        val documentName: String,
        val locator: String,
        val indexedText: String,
    ) : CitationSourceResolution

    data class Deleted(
        val documentNameSnapshot: String,
        val locator: String,
        val archivedExcerpt: String,
    ) : CitationSourceResolution

    data class Unavailable(
        val documentNameSnapshot: String,
        val locator: String,
        val archivedExcerpt: String,
    ) : CitationSourceResolution
}

object CitationSourceResolver {
    fun resolve(
        citation: CitationRef,
        document: DocumentEntity?,
        chunk: ChunkEntity?,
    ): CitationSourceResolution {
        if (document == null) return citation.deleted()
        if (
            document.id != citation.documentId ||
            document.status != DocumentStatus.READY ||
            chunk == null ||
            chunk.id != citation.chunkId ||
            chunk.documentId != document.id ||
            chunk.knowledgeBaseId != document.knowledgeBaseId
        ) {
            return citation.unavailable()
        }
        return CitationSourceResolution.Available(
            documentName = document.displayName,
            locator = citation.locator,
            indexedText = chunk.text,
        )
    }

    private fun CitationRef.deleted() = CitationSourceResolution.Deleted(
        documentNameSnapshot = documentNameSnapshot,
        locator = locator,
        archivedExcerpt = quotedText,
    )

    private fun CitationRef.unavailable() = CitationSourceResolution.Unavailable(
        documentNameSnapshot = documentNameSnapshot,
        locator = locator,
        archivedExcerpt = quotedText,
    )
}
