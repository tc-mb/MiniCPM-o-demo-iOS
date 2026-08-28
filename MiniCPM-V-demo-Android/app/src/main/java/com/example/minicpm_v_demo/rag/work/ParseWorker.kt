package com.example.minicpm_v_demo.rag.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.crypto.EncryptedFileStore
import com.example.minicpm_v_demo.rag.crypto.RagTempFileCleaner
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.parser.ParsedBlockCodec
import com.example.minicpm_v_demo.rag.parser.OcrAwareDocumentParser
import com.example.minicpm_v_demo.rag.parser.ParserException
import com.example.minicpm_v_demo.rag.parser.ParserInput
import com.example.minicpm_v_demo.rag.parser.ParserRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ParseWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val documentId = inputData.getString(RagWorkContract.KEY_DOCUMENT_ID)
            ?: return@withContext Result.failure()
        runCatching { RagWorkContract.requireValidDocumentId(documentId) }
            .getOrElse { return@withContext Result.failure() }
        val app = applicationContext as? MiniCPMApplication ?: return@withContext Result.failure()
        val dao = app.ragDatabase.documentDao()
        val document = dao.findById(documentId) ?: return@withContext Result.failure()
        if (document.status in setOf(DocumentStatus.OCR, DocumentStatus.CHUNKING)) {
            return@withContext Result.success()
        }
        if (document.status != DocumentStatus.PARSING) return@withContext Result.failure()
        val staging = RagTempFileCleaner.stagingDirectory(applicationContext.noBackupFilesDir)
        val source = staging.resolve(document.privateFileName)
        if (!source.isFile) return@withContext fail(documentId, "SOURCE_UNAVAILABLE")
        val target = RagTempFileCleaner.parsedBlockFile(staging, documentId)
        try {
            setForeground(
                RagImportNotifications.foregroundInfo(
                    applicationContext,
                    documentId,
                    DocumentStatus.PARSING,
                ),
            )
            val store = EncryptedFileStore(app.ragKeyManager::getOrCreateMasterKey)
            val parser = ParserRegistry.forDocument(document.displayName, document.mimeType)
            store.withDecryptedInput(source) { plaintext ->
                val blocks = parser.parse(ParserInput(plaintext, shouldContinue = { !isStopped }))
                val pipeInput = java.io.PipedInputStream(64 * 1024)
                val pipeOutput = java.io.PipedOutputStream(pipeInput)
                var encodeFailure: Throwable? = null
                val encodeThread = Thread({
                    try { pipeOutput.use { ParsedBlockCodec.write(blocks, it) } }
                    catch (error: Throwable) { encodeFailure = error; runCatching { pipeOutput.close() } }
                }, "rag-parse-codec").apply { isDaemon = true; start() }
                try {
                    store.encrypt(pipeInput, target) { !isStopped }
                } finally {
                    pipeInput.close()
                    encodeThread.join()
                    encodeFailure?.let { throw it }
                }
            }
            val next = if ((parser as? OcrAwareDocumentParser)?.requiresOcr == true) {
                DocumentStatus.OCR
            } else {
                DocumentStatus.CHUNKING
            }
            dao.transition(documentId, next, 1, 1, System.currentTimeMillis())
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { terminal(documentId, DocumentStatus.CANCELLED, "CANCELLED") }
            throw cancelled
        } catch (error: ParserException) {
            target.delete()
            fail(documentId, "PARSE_${error.error.name}")
        } catch (_: Exception) {
            target.delete()
            fail(documentId, "PARSE_FAILED")
        }
    }

    private suspend fun fail(documentId: String, code: String): Result {
        return RagImportFailureHandler.fail(applicationContext, documentId, code)
    }

    private suspend fun terminal(documentId: String, status: DocumentStatus, code: String) {
        val app = applicationContext as? MiniCPMApplication ?: return
        val dao = app.ragDatabase.documentDao()
        val current = dao.findById(documentId) ?: return
        if (current.status in DocumentStatus.activeWorkStates) {
            dao.transition(documentId, status, 0, 1, System.currentTimeMillis(), code)
        }
    }
}
