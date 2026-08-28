package com.example.minicpm_v_demo.rag.embed

import android.content.Context
import android.content.ComponentCallbacks2
import java.io.File

object EmbeddingSessionReleasePolicy {
    const val BACKGROUND_RETENTION_MS = 5L * 60 * 1_000

    fun shouldRelease(
        backgroundSinceMs: Long?,
        nowMs: Long,
        trimLevel: Int,
    ): Boolean = backgroundSinceMs != null &&
        nowMs - backgroundSinceMs >= BACKGROUND_RETENTION_MS &&
        trimLevel >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
}

data class InstalledEmbeddingModel(
    val modelId: String,
    val modelSha256: String,
)

object InstalledEmbeddingModelVerifier {
    fun verify(
        directory: File,
        manifest: EmbeddingModelManifest,
        modelFileName: String,
    ): InstalledEmbeddingModel? = runCatching {
        EmbeddingModelPackageVerifier.verify(directory, manifest)
        InstalledEmbeddingModel(
            modelId = manifest.modelId,
            modelSha256 = manifest.files.getValue(modelFileName),
        )
    }.getOrNull()
}

class EmbeddingModelManager(private val context: Context) : AutoCloseable {
    @Volatile private var opened: E5Embedder? = null

    fun modelDirectory(): File = File(context.filesDir, "rag/models/multilingual-e5-small")

    fun installedIdentity(): InstalledEmbeddingModel? = InstalledEmbeddingModelVerifier.verify(
        directory = modelDirectory(),
        manifest = E5ModelSpec.PINNED,
        modelFileName = "model.int8.onnx",
    )

    @Synchronized
    fun openInstalled(): E5Embedder? {
        opened?.let { return it }
        val directory = modelDirectory()
        if (!directory.isDirectory) return null
        return runCatching {
            E5Embedder.open(directory, E5ModelSpec.PINNED, E5ExecutionSelection.SELECTED)
        }.getOrNull()?.also {
            opened = it
            E5TokenizerRegistry.installVerified(it)
        }
    }

    @Synchronized
    override fun close() {
        opened?.close()
        opened = null
        E5TokenizerRegistry.clear()
    }
}
