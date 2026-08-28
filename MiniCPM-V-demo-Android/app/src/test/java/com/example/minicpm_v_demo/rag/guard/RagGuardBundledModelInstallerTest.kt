package com.example.minicpm_v_demo.rag.guard

import java.io.ByteArrayInputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RagGuardBundledModelInstallerTest {
    @Test
    fun `first install is verified and a valid install is reused`() {
        val root = createTempDirectory("rag-guard-bundle-").toFile()
        val bytes = "verified-v4-model".toByteArray()
        var opens = 0
        try {
            val installer = installer(root, bytes) {
                opens++
                ByteArrayInputStream(bytes)
            }

            assertEquals(root.canonicalFile, installer.ensureInstalled())
            assertEquals(root.canonicalFile, installer.ensureInstalled())

            assertArrayEquals(bytes, root.resolve("model.int8.onnx").readBytes())
            assertEquals(1, opens)
            assertFalse(root.resolve(".model.int8.onnx.installing").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `corrupted installed file is replaced by the verified bundle`() {
        val root = createTempDirectory("rag-guard-replace-").toFile()
        val bytes = "replacement-v4-model".toByteArray()
        try {
            root.resolve("model.int8.onnx").apply {
                parentFile.mkdirs()
                writeText("corrupt")
            }

            installer(root, bytes) { ByteArrayInputStream(bytes) }.ensureInstalled()

            assertArrayEquals(bytes, root.resolve("model.int8.onnx").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `wrong sized bundle fails closed and removes temporary output`() {
        val root = createTempDirectory("rag-guard-fail-").toFile()
        val expected = "expected".toByteArray()
        try {
            val installer = installer(root, expected) {
                ByteArrayInputStream("expected-extra".toByteArray())
            }

            assertThrows(IllegalArgumentException::class.java) { installer.ensureInstalled() }
            assertFalse(root.resolve("model.int8.onnx").exists())
            assertFalse(root.resolve(".model.int8.onnx.installing").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `installer never writes outside the canonical model directory`() {
        val root = createTempDirectory("rag-guard-path-").toFile()
        val bytes = "verified".toByteArray()
        try {
            installer(root, bytes) { ByteArrayInputStream(bytes) }.ensureInstalled()

            val model = root.resolve("model.int8.onnx").canonicalFile
            assertEquals(root.canonicalFile, model.parentFile)
            assertTrue(model.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun installer(
        root: java.io.File,
        bytes: ByteArray,
        open: () -> java.io.InputStream,
    ): RagGuardBundledModelInstaller {
        val modelFile = root.resolve("expected.bin").apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
        val manifest = CurrentRagGuardModel.PINNED.copy(
            model = RagGuardModelFile(
                name = "model.int8.onnx",
                bytes = bytes.size.toLong(),
                sha256 = RagGuardModelPackageVerifier.sha256(modelFile),
            ),
        )
        modelFile.delete()
        return RagGuardBundledModelInstaller(
            modelDirectory = root,
            manifest = manifest,
            openAsset = open,
        )
    }
}
