package com.example.minicpm_v_demo.rag.crypto

import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedFileStore(
    private val keyProvider: () -> SecretKey,
) {
    fun encrypt(
        source: InputStream,
        target: File,
        shouldContinue: () -> Boolean = { true },
    ) {
        val parent = target.parentFile
        require(parent == null || parent.isDirectory || parent.mkdirs()) {
            "Unable to create encrypted file directory"
        }
        val atomicFile = AtomicFile(target)
        val fileOutput = atomicFile.startWrite()
        try {
            // Android Keystore keys with randomized encryption enabled reject caller-provided
            // IVs. Let the provider generate the nonce, then persist it in the authenticated
            // file header for decryption.
            val cipher = newEncryptCipher()
            val nonce = cipher.iv
            check(nonce.size == GCM_NONCE_BYTES) { "Unexpected AES-GCM nonce length" }
            DataOutputStream(BufferedOutputStream(fileOutput)).useWithoutClosingUnderlying { output ->
                output.write(MAGIC)
                output.writeByte(FORMAT_VERSION)
                output.writeByte(nonce.size)
                output.write(nonce)
                transform(source, output, cipher, shouldContinue)
                output.flush()
            }
            atomicFile.finishWrite(fileOutput)
        } catch (error: Exception) {
            atomicFile.failWrite(fileOutput)
            throw error
        }
    }

    @Throws(IOException::class)
    fun decrypt(source: File, destination: OutputStream) {
        try {
            DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                require(magic.contentEquals(MAGIC)) { "Invalid encrypted RAG file header" }
                require(input.readUnsignedByte() == FORMAT_VERSION) { "Unsupported encrypted RAG file version" }
                val nonceLength = input.readUnsignedByte()
                require(nonceLength == GCM_NONCE_BYTES) { "Invalid encrypted RAG file nonce" }
                val nonce = ByteArray(nonceLength).also(input::readFully)
                transform(input, destination, newDecryptCipher(nonce))
            }
        } catch (error: GeneralSecurityException) {
            throw IOException("Encrypted RAG file authentication failed", error)
        }
    }

    fun <T> withDecryptedInput(source: File, block: (InputStream) -> T): T {
        val plaintextInput = PipedInputStream(PIPE_BUFFER_BYTES)
        val plaintextOutput = PipedOutputStream(plaintextInput)
        var decryptFailure: Throwable? = null
        val decryptThread = Thread({
            try {
                plaintextOutput.use { decrypt(source, it) }
            } catch (error: Throwable) {
                decryptFailure = error
                runCatching { plaintextOutput.close() }
            }
        }, "rag-decrypt-stream").apply { isDaemon = true; start() }
        var consumerCompleted = false
        try {
            return plaintextInput.use(block).also { consumerCompleted = true }
        } finally {
            plaintextInput.close()
            decryptThread.join()
            if (consumerCompleted) decryptFailure?.let { throw it }
        }
    }

    private fun newEncryptCipher(): Cipher = Cipher
        .getInstance(AES_GCM_TRANSFORMATION)
        .apply {
            init(Cipher.ENCRYPT_MODE, keyProvider())
            updateAAD(FILE_AAD)
        }

    private fun newDecryptCipher(nonce: ByteArray): Cipher = Cipher
        .getInstance(AES_GCM_TRANSFORMATION)
        .apply {
            init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(FILE_AAD)
        }

    private fun transform(
        source: InputStream,
        destination: OutputStream,
        cipher: Cipher,
        shouldContinue: () -> Boolean = { true },
    ) {
        val inputBuffer = ByteArray(BUFFER_BYTES)
        while (true) {
            if (!shouldContinue()) throw IOException("Encrypted file operation cancelled")
            val count = source.read(inputBuffer)
            if (count < 0) break
            if (count == 0) continue
            cipher.update(inputBuffer, 0, count)?.takeIf { it.isNotEmpty() }?.let(destination::write)
        }
        if (!shouldContinue()) throw IOException("Encrypted file operation cancelled")
        cipher.doFinal()?.takeIf { it.isNotEmpty() }?.let(destination::write)
    }

    private inline fun DataOutputStream.useWithoutClosingUnderlying(block: (DataOutputStream) -> Unit) {
        block(this)
    }

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'A'.code.toByte(), 'G'.code.toByte(), 'F'.code.toByte())
        private const val FORMAT_VERSION = 1
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val BUFFER_BYTES = 64 * 1024
        private const val PIPE_BUFFER_BYTES = 64 * 1024
        private val FILE_AAD = "MiniCPM-RAG-FILE-v1".toByteArray(Charsets.UTF_8)
    }
}
