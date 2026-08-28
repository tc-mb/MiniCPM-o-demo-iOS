package com.example.minicpm_v_demo.rag.work

import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Test

class RagImportFailureClassifierTest {
    @Test
    fun `maps exceptions to fixed non-sensitive error codes`() {
        assertEquals("SOURCE_PERMISSION_LOST", RagImportFailureClassifier.code(SecurityException("content://secret")))
        assertEquals("SOURCE_UNAVAILABLE", RagImportFailureClassifier.code(FileNotFoundException("secret.pdf")))
        assertEquals("ENCRYPTION_FAILED", RagImportFailureClassifier.code(GeneralSecurityException("key detail")))
        assertEquals("IO_FAILED", RagImportFailureClassifier.code(IOException("private path")))
        assertEquals("IMPORT_COPY_FAILED", RagImportFailureClassifier.code(IllegalStateException("private detail")))
    }
}
