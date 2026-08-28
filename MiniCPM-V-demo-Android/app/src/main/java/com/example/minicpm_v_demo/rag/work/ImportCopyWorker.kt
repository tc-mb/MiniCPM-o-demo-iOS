package com.example.minicpm_v_demo.rag.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.crypto.EncryptedFileStore
import com.example.minicpm_v_demo.rag.crypto.RagTempFileCleaner
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.importer.DocumentImportException
import com.example.minicpm_v_demo.rag.importer.DocumentImportRequest
import com.example.minicpm_v_demo.rag.importer.DocumentImportSource
import com.example.minicpm_v_demo.rag.importer.DocumentImporter
import com.example.minicpm_v_demo.rag.importer.EncryptedDocumentWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ImportCopyWorker(
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
        if (document.status in setOf(DocumentStatus.PARSING, DocumentStatus.OCR, DocumentStatus.CHUNKING)) {
            return@withContext Result.success()
        }
        val sourceUri = document.sourceUri?.let(Uri::parse) ?: return@withContext Result.failure()
        if (sourceUri.scheme != "content") return@withContext Result.failure()

        try {
            setForeground(
                RagImportNotifications.foregroundInfo(
                    applicationContext,
                    documentId,
                    DocumentStatus.COPYING,
                ),
            )
            if (document.status == DocumentStatus.QUEUED) {
                dao.transition(documentId, DocumentStatus.COPYING, 0, 1, System.currentTimeMillis())
            } else if (document.status != DocumentStatus.COPYING) {
                return@withContext Result.failure()
            }
            setProgress(workDataOf(
                WorkManagerRagWorkCoordinator.KEY_PROGRESS_DONE to 0,
                WorkManagerRagWorkCoordinator.KEY_PROGRESS_TOTAL to 1,
            ))
            val importer = DocumentImporter(
                stagingDirectory = RagTempFileCleaner.stagingDirectory(applicationContext.noBackupFilesDir),
                encryptedDocumentWriter = EncryptedDocumentWriter { plaintext, target, shouldContinue ->
                    EncryptedFileStore(app.ragKeyManager::getOrCreateMasterKey)
                        .encrypt(plaintext, target, shouldContinue)
                },
                duplicateShaExists = { knowledgeBaseId, sha256 ->
                    runBlocking { dao.contentHashExists(knowledgeBaseId, sha256, documentId) }
                },
            )
            val imported = importer.copy(
                DocumentImportRequest(
                    documentId = documentId,
                    knowledgeBaseId = document.knowledgeBaseId,
                    source = DocumentImportSource(
                        displayName = document.displayName,
                        declaredMimeType = document.mimeType.takeIf(String::isNotBlank),
                        declaredSizeBytes = document.sizeBytes.takeIf { it > 0 },
                        persistPermission = { true },
                        open = {
                            requireNotNull(applicationContext.contentResolver.openInputStream(sourceUri)) {
                                "Document source is unavailable"
                            }
                        },
                    ),
                ),
                shouldContinue = { !isStopped },
            )
            check(
                dao.updateImportedMetadata(
                    id = documentId,
                    privateFileName = imported.privateFileName,
                    detectedType = imported.detectedType.name,
                    sha256 = imported.sha256,
                    sizeBytes = imported.sizeBytes,
                    updatedAt = System.currentTimeMillis(),
                ) == 1,
            )
            dao.transition(documentId, DocumentStatus.PARSING, 1, 1, System.currentTimeMillis())
            setProgress(workDataOf(
                WorkManagerRagWorkCoordinator.KEY_PROGRESS_DONE to 1,
                WorkManagerRagWorkCoordinator.KEY_PROGRESS_TOTAL to 1,
            ))
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { markCancelled(documentId) }
            throw cancelled
        } catch (error: DocumentImportException) {
            val cancelled = error.error.name == "CANCELLED"
            if (cancelled) {
                markCancelled(documentId)
                Result.failure()
            } else {
                RagImportFailureHandler.fail(applicationContext, documentId, error.error.name)
            }
        } catch (error: Exception) {
            RagImportFailureHandler.fail(applicationContext, documentId, RagImportFailureClassifier.code(error))
        }
    }

    private suspend fun markCancelled(documentId: String) {
        transitionTerminal(documentId, DocumentStatus.CANCELLED, "CANCELLED")
    }

    private suspend fun transitionTerminal(documentId: String, status: DocumentStatus, code: String) {
        val app = applicationContext as? MiniCPMApplication ?: return
        val dao = app.ragDatabase.documentDao()
        val current = dao.findById(documentId) ?: return
        if (current.status == status) return
        if (current.status in DocumentStatus.activeWorkStates) {
            dao.transition(documentId, status, 0, 1, System.currentTimeMillis(), code)
        }
    }
}
