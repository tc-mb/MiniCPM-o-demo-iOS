package com.example.minicpm_v_demo.rag.work

import android.content.Context
import android.graphics.Bitmap
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.config.RagLimits
import com.example.minicpm_v_demo.rag.crypto.EncryptedFileStore
import com.example.minicpm_v_demo.rag.crypto.RagTempFileCleaner
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.parser.BlockStructure
import com.example.minicpm_v_demo.rag.parser.ParsedBlock
import com.example.minicpm_v_demo.rag.parser.ParsedBlockCodec
import com.example.minicpm_v_demo.rag.parser.ParserError
import com.example.minicpm_v_demo.rag.parser.ParserException
import com.example.minicpm_v_demo.rag.parser.PdfPageSelection
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class OcrWorker(
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
        if (document.status == DocumentStatus.CHUNKING) return@withContext Result.success()
        if (document.status != DocumentStatus.OCR) return@withContext Result.failure()
        if (document.detectedType != "PDF") return@withContext fail(documentId, "OCR_UNSUPPORTED_FORMAT")

        val staging = RagTempFileCleaner.stagingDirectory(applicationContext.noBackupFilesDir)
        val source = staging.resolve(document.privateFileName)
        val target = RagTempFileCleaner.parsedBlockFile(staging, documentId)
        if (!source.isFile) return@withContext fail(documentId, "SOURCE_UNAVAILABLE")
        try {
            setForeground(
                RagImportNotifications.foregroundInfo(
                    applicationContext,
                    documentId,
                    DocumentStatus.OCR,
                ),
            )
            val store = EncryptedFileStore(app.ragKeyManager::getOrCreateMasterKey)
            val blocks = store.withDecryptedInput(source) { plaintext ->
                runBlocking { recognizePdf(plaintext, documentId) }
            }
            encryptBlocks(store, blocks.asSequence(), target)
            dao.transition(documentId, DocumentStatus.CHUNKING, blocks.size, blocks.size, System.currentTimeMillis())
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { terminal(documentId, DocumentStatus.CANCELLED, "CANCELLED") }
            throw cancelled
        } catch (error: ParserException) {
            target.delete()
            fail(documentId, "OCR_${error.error.name}")
        } catch (_: Exception) {
            target.delete()
            fail(documentId, "OCR_FAILED")
        }
    }

    private suspend fun recognizePdf(plaintext: java.io.InputStream, documentId: String): List<ParsedBlock> {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            PDDocument.load(plaintext, MemoryUsageSetting.setupMainMemoryOnly()).use { document ->
                if (document.numberOfPages > RagLimits.MAX_PDF_PAGES) throw ParserException(ParserError.PDF_PAGE_LIMIT)
                val renderer = PDFRenderer(document).apply { isSubsamplingAllowed = true }
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                val blocks = ArrayList<ParsedBlock>(document.numberOfPages)
                for (pageIndex in 0 until document.numberOfPages) {
                    if (isStopped) throw CancellationException("OCR cancelled")
                    val pageNumber = pageIndex + 1
                    stripper.startPage = pageNumber
                    stripper.endPage = pageNumber
                    val selectable = stripper.getText(document).trim()
                    val selected = if (PdfPageSelection.needsOcr(selectable)) {
                        val page = document.getPage(pageIndex)
                        val longestPoints = maxOf(page.cropBox.width, page.cropBox.height).coerceAtLeast(1f)
                        val scale = (MAX_BITMAP_EDGE / longestPoints).coerceAtMost(MAX_RENDER_SCALE)
                        var bitmap: Bitmap? = null
                        try {
                            bitmap = renderer.renderImage(pageIndex, scale, ImageType.RGB)
                            recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult().text.trim()
                        } finally {
                            bitmap?.recycle()
                        }
                    } else selectable
                    val text = PdfPageSelection.choose(selectable, selected)
                    if (text.isNotEmpty()) {
                        blocks += ParsedBlock(text, BlockStructure.PARAGRAPH, null, "page", pageNumber.toString())
                    }
                    setProgress(workDataOf(
                        WorkManagerRagWorkCoordinator.KEY_PROGRESS_DONE to pageNumber,
                        WorkManagerRagWorkCoordinator.KEY_PROGRESS_TOTAL to document.numberOfPages,
                    ))
                    (applicationContext as MiniCPMApplication).ragDatabase.documentDao().updateStatusAndProgress(
                        documentId, DocumentStatus.OCR, pageNumber, document.numberOfPages,
                        System.currentTimeMillis(), null, null,
                    )
                }
                return blocks
            }
        } finally {
            recognizer.close()
        }
    }

    private fun encryptBlocks(store: EncryptedFileStore, blocks: Sequence<ParsedBlock>, target: java.io.File) {
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        var writerFailure: Throwable? = null
        val writer = Thread({
            try { output.use { ParsedBlockCodec.write(blocks, it) } }
            catch (error: Throwable) { writerFailure = error; runCatching { output.close() } }
        }, "rag-ocr-codec").apply { isDaemon = true; start() }
        try {
            store.encrypt(input, target) { !isStopped }
        } finally {
            input.close()
            writer.join()
            writerFailure?.let { throw it }
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel(CancellationException("ML Kit OCR cancelled")) }
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

    companion object {
        private const val MAX_BITMAP_EDGE = 2048f
        private const val MAX_RENDER_SCALE = 4f
    }
}
