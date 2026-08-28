package com.example.minicpm_v_demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.embed.EmbeddingModelPackageVerifier
import com.example.minicpm_v_demo.rag.guard.CurrentRagGuardModel
import java.io.File
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallationPersistenceInstrumentedTest {
    @Test
    fun captureAggregateBaselineBeforeOverwrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseline = baselineFile(context)
        currentSnapshot(context).store(baseline.outputStream(), "aggregate install persistence baseline")
        assertTrue(baseline.isFile && baseline.length() > 0)
    }

    @Test
    fun verifyAggregateBaselineAfterOverwriteAndDeleteProbe() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseline = baselineFile(context)
        assertTrue("Install persistence baseline is missing", baseline.isFile)
        val expected = Properties().apply { baseline.inputStream().use(::load) }
        val actual = currentSnapshot(context)
        expected.remove(GUARD_MODEL_SHA256)
        val actualGuardSha256 = actual.remove(GUARD_MODEL_SHA256)
        assertEquals(expected, actual)
        assertEquals(CurrentRagGuardModel.PINNED.model.sha256, actualGuardSha256)
        assertTrue("Cannot delete install persistence baseline", baseline.delete())
    }

    private suspend fun currentSnapshot(context: Context): Properties {
        val app = context.applicationContext as MiniCPMApplication
        val knowledgeBases = app.ragDatabase.knowledgeBaseDao().findAll()
        val documents = knowledgeBases.flatMap { knowledgeBase ->
            app.ragDatabase.documentDao().findByKnowledgeBase(knowledgeBase.id)
        }
        val archive = ConversationArchiveDiskStore(File(context.filesDir, "conversation-store")).load()
        val hnswFiles = app.hnswIndexDirectory.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".enc") || it.name.endsWith(".previous")) }
        val e5Identity = app.embeddingModelManager.installedIdentity()
        app.ragGuardModelManager.openInstalled()?.close()
        val guardFile = app.ragGuardModelManager.modelDirectory().resolve(CurrentRagGuardModel.PINNED.model.name)
        val guardHash = guardFile.takeIf(File::isFile)?.let(EmbeddingModelPackageVerifier::sha256).orEmpty()
        return Properties().apply {
            setProperty("conversationCount", archive?.conversations?.size.orZero().toString())
            setProperty(
                "messageCount",
                archive?.conversations?.sumOf { it.messages.size }.orZero().toString(),
            )
            setProperty("knowledgeBaseCount", knowledgeBases.size.toString())
            setProperty("documentCount", documents.size.toString())
            DocumentStatus.entries.forEach { status ->
                setProperty(
                    "documents_${status.name}",
                    documents.count { it.status == status }.toString(),
                )
            }
            setProperty("e5ModelSha256", e5Identity?.modelSha256.orEmpty())
            setProperty(GUARD_MODEL_SHA256, guardHash)
            setProperty("hnswEncryptedFileCount", hnswFiles.size.toString())
            setProperty("hnswEncryptedTotalBytes", hnswFiles.sumOf(File::length).toString())
        }
    }

    private fun baselineFile(context: Context): File =
        File(context.noBackupFilesDir, "rag/install-persistence-baseline.properties").apply {
            parentFile?.mkdirs()
        }

    private fun Int?.orZero(): Int = this ?: 0

    private companion object {
        const val GUARD_MODEL_SHA256 = "guardModelSha256"
    }
}
