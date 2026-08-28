package com.example.minicpm_v_demo.rag.storage

import com.example.minicpm_v_demo.rag.db.DocumentEntity
import java.io.File

class RagDocumentRemovalService(
    private val stagingDirectory: File,
    private val deleteRecord: suspend (String) -> Int,
) {
    suspend fun remove(document: DocumentEntity) {
        RagDocumentArtifactCleaner.delete(stagingDirectory, document)
        check(deleteRecord(document.id) == 1) { "Document record was not deleted" }
    }
}
