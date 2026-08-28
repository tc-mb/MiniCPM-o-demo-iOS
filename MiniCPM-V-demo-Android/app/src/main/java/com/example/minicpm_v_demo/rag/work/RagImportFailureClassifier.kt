package com.example.minicpm_v_demo.rag.work

import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException

object RagImportFailureClassifier {
    fun code(error: Throwable): String = when (error) {
        is SecurityException -> "SOURCE_PERMISSION_LOST"
        is FileNotFoundException -> "SOURCE_UNAVAILABLE"
        is GeneralSecurityException -> "ENCRYPTION_FAILED"
        is IOException -> "IO_FAILED"
        else -> "IMPORT_COPY_FAILED"
    }
}
