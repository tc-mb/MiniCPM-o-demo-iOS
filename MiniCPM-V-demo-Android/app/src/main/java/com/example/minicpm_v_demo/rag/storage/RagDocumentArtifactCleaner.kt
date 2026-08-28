package com.example.minicpm_v_demo.rag.storage

import com.example.minicpm_v_demo.rag.db.DocumentEntity
import java.io.File
import java.nio.file.Files

object RagDocumentArtifactCleaner {
    private val SAFE_DOCUMENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun delete(stagingDirectory: File, document: DocumentEntity) {
        require(SAFE_DOCUMENT_ID.matches(document.id)) { "Unsafe document ID" }
        require(document.privateFileName == "${document.id}.src.enc") { "Unsafe private file name" }
        if (!stagingDirectory.exists()) return
        require(stagingDirectory.isDirectory) { "RAG staging path is not a directory" }
        require(!Files.isSymbolicLink(stagingDirectory.toPath())) { "RAG staging directory cannot be a symbolic link" }

        val stagingPath = stagingDirectory.toPath().toAbsolutePath().normalize()
        listOf(document.privateFileName, "${document.id}.blocks.enc").forEach { name ->
            val target = stagingPath.resolve(name).normalize()
            require(target.parent == stagingPath) { "RAG artifact escaped staging directory" }
            require(!Files.isSymbolicLink(target)) { "RAG artifact cannot be a symbolic link" }
            Files.deleteIfExists(target)
        }
    }
}
