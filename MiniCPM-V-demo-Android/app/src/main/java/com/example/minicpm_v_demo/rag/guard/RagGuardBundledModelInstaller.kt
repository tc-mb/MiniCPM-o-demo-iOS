package com.example.minicpm_v_demo.rag.guard

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class RagGuardBundledModelInstaller(
    private val modelDirectory: File,
    private val manifest: RagGuardModelManifest,
    private val openAsset: () -> InputStream,
) {
    @Synchronized
    fun ensureInstalled(): File {
        val root = modelDirectory.canonicalFile
        if (runCatching { RagGuardModelPackageVerifier.verify(root, manifest) }.isSuccess) {
            return root
        }
        require(root.exists() || root.mkdirs()) { "Cannot create RAG guard model directory" }
        require(root.isDirectory) { "RAG guard model path is not a directory" }
        val destination = root.resolve(manifest.model.name).canonicalFile
        val temporary = root.resolve(".${manifest.model.name}.installing").canonicalFile
        require(destination.parentFile == root && temporary.parentFile == root) {
            "RAG guard model path escapes private storage"
        }
        temporary.delete()
        try {
            copyExactModel(temporary)
            require(temporary.length() == manifest.model.bytes) { "Bundled model size mismatch" }
            require(RagGuardModelPackageVerifier.sha256(temporary) == manifest.model.sha256) {
                "Bundled model hash mismatch"
            }
            if (destination.exists()) require(destination.delete()) {
                "Cannot replace invalid RAG guard model"
            }
            require(temporary.renameTo(destination)) { "Cannot publish RAG guard model atomically" }
            return RagGuardModelPackageVerifier.verify(root, manifest)
        } finally {
            temporary.delete()
        }
    }

    private fun copyExactModel(temporary: File) {
        openAsset().use { input ->
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= manifest.model.bytes) { "Bundled model exceeds declared size" }
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
                require(total == manifest.model.bytes) { "Bundled model is truncated" }
            }
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
