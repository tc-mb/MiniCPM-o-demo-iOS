package com.example.minicpm_v_demo.rag.guard

import android.content.Context
import com.example.minicpm_v_demo.rag.embed.EmbeddingModelManager
import java.io.File

class RagGuardModelManager private constructor(
    private val directoryProvider: () -> File,
    private val installer: () -> File?,
    private val opener: (File) -> OnnxRagGuardClassifier,
) : AutoCloseable {
    @Volatile private var opened: OnnxRagGuardClassifier? = null

    constructor(
        context: Context,
        embeddingModelManager: EmbeddingModelManager,
    ) : this(
        directoryProvider = { File(context.filesDir, MODEL_DIRECTORY) },
        installer = {
            val directory = File(context.filesDir, MODEL_DIRECTORY)
            RagGuardBundledModelInstaller(
                modelDirectory = directory,
                manifest = CurrentRagGuardModel.PINNED,
                openAsset = { context.assets.open(BUNDLED_MODEL_ASSET) },
            ).ensureInstalled()
        },
        opener = { directory ->
            val tokenizer = requireNotNull(embeddingModelManager.openInstalled()) {
                "Verified E5 tokenizer is unavailable"
            }
            OnnxRagGuardClassifier.open(directory, tokenizer)
        },
    )

    fun modelDirectory(): File = directoryProvider()

    @Synchronized
    fun openInstalled(): OnnxRagGuardClassifier? {
        opened?.let { return it }
        val directory = runCatching { installer() }.getOrNull() ?: return null
        if (!directory.isDirectory) return null
        return runCatching { opener(directory) }.getOrNull()?.also { opened = it }
    }

    @Synchronized
    override fun close() {
        opened?.close()
        opened = null
    }

    companion object {
        private const val MODEL_DIRECTORY = "rag/models/rag-guard-v4-2-e5"
        private const val BUNDLED_MODEL_ASSET = "rag_guard_v4_2/model.int8.onnx"

        internal fun forTest(
            directory: File,
            opener: (File) -> OnnxRagGuardClassifier,
        ) = RagGuardModelManager({ directory }, { directory.takeIf(File::isDirectory) }, opener)
    }
}
