package com.example.minicpm_v_demo.rag.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.minicpm_v_demo.rag.db.KnowledgeBaseEntity
import com.example.minicpm_v_demo.rag.db.RagDatabaseFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RagEncryptionTest {
    private lateinit var context: Context
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDirectory = File(context.cacheDir, "rag-encryption-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @Test
    fun databasePassphraseIsRandomLengthAndStableAcrossManagerRecreation() {
        val identity = UUID.randomUUID().toString()
        val first = keyManager(identity).getOrCreateDatabasePassphrase()
        val second = keyManager(identity).getOrCreateDatabasePassphrase()

        assertEquals(32, first.size)
        assertArrayEquals(first, second)
        assertFalse(first.all { it == 0.toByte() })
    }

    @Test
    fun encryptedDatabaseReopensWithSameKeyAndRejectsDifferentKey() = runBlocking {
        val identity = UUID.randomUUID().toString()
        val databaseName = "rag-$identity.db"
        val factory = RagDatabaseFactory(context, keyManager(identity), databaseName)
        val now = System.currentTimeMillis()

        factory.open().let { database ->
            try {
            database.knowledgeBaseDao().insert(
                KnowledgeBaseEntity("kb", "Encrypted", "encrypted", now, now),
            )
            } finally {
                database.close()
            }
        }
        factory.open().let { database ->
            try {
                assertEquals(listOf("kb"), database.knowledgeBaseDao().findAll().map { it.id })
            } finally {
                database.close()
            }
        }

        val wrongFactory = RagDatabaseFactory(context, keyManager("wrong-$identity"), databaseName)
        assertThrows(Exception::class.java) {
            wrongFactory.open().let { database ->
                try {
                    database.openHelper.writableDatabase
                } finally {
                    database.close()
                }
            }
        }
        context.deleteDatabase(databaseName)
        Unit
    }

    @Test
    fun fileEncryptionUsesUniqueNoncesAndRejectsTampering() {
        val key = generatedAesKey()
        val store = EncryptedFileStore(keyProvider = { key })
        val first = File(testDirectory, "first.rag")
        val second = File(testDirectory, "second.rag")
        val plaintext = "classified office document".toByteArray()

        store.encrypt(ByteArrayInputStream(plaintext), first)
        store.encrypt(ByteArrayInputStream(plaintext), second)

        assertFalse(readNonce(first).contentEquals(readNonce(second)))
        val decrypted = ByteArrayOutputStream()
        store.decrypt(second, decrypted)
        assertArrayEquals(plaintext, decrypted.toByteArray())
        RandomAccessFile(first, "rw").use { file ->
            file.seek(file.length() - 1)
            val lastByte = file.read()
            file.seek(file.length() - 1)
            file.write(lastByte xor 0x01)
        }
        assertThrows(IOException::class.java) {
            store.decrypt(first, ByteArrayOutputStream())
        }
    }

    @Test
    fun productionKeystoreKeyEncryptsFileInNoBackupRagDirectory() {
        val manager = RagKeyManager(context)
        val targetDirectory = File(context.noBackupFilesDir, "rag/source").apply { mkdirs() }
        val target = File(targetDirectory, "keystore-${UUID.randomUUID()}.src.enc")
        val plaintext = "production keystore probe".toByteArray()
        try {
            val store = EncryptedFileStore(manager::getOrCreateMasterKey)
            store.encrypt(ByteArrayInputStream(plaintext), target)
            val restored = ByteArrayOutputStream()
            store.decrypt(target, restored)
            assertArrayEquals(plaintext, restored.toByteArray())
        } finally {
            target.delete()
        }
    }

    @Test
    fun encryptedFileCanBeConsumedAsAStreamWithoutPlaintextFile() {
        val key = generatedAesKey()
        val store = EncryptedFileStore(keyProvider = { key })
        val target = File(testDirectory, "stream.rag")
        val plaintext = "streamed parsed blocks 世界".toByteArray()
        store.encrypt(ByteArrayInputStream(plaintext), target)

        val restored = store.withDecryptedInput(target) { it.readBytes() }

        assertArrayEquals(plaintext, restored)
        assertEquals(listOf("stream.rag"), testDirectory.listFiles().orEmpty().map(File::getName))
    }

    @Test
    fun decryptedStreamPreservesConsumerFailureInsteadOfBrokenPipeFailure() {
        val key = generatedAesKey()
        val store = EncryptedFileStore(keyProvider = { key })
        val target = File(testDirectory, "consumer-error.rag")
        store.encrypt(ByteArrayInputStream(ByteArray(256 * 1024) { 7 }), target)

        val failure = assertThrows(ConsumerProbeException::class.java) {
            store.withDecryptedInput(target) { input ->
                input.read()
                throw ConsumerProbeException()
            }
        }

        assertEquals("consumer failed", failure.message)
    }

    @Test
    fun failedReplacementPreservesPreviousAuthenticatedFile() {
        val key = generatedAesKey()
        val store = EncryptedFileStore(keyProvider = { key })
        val target = File(testDirectory, "atomic.rag")
        val original = "original".toByteArray()
        store.encrypt(ByteArrayInputStream(original), target)

        assertThrows(IOException::class.java) {
            store.encrypt(FailingInputStream("replacement".toByteArray()), target)
        }

        val restored = ByteArrayOutputStream()
        store.decrypt(target, restored)
        assertArrayEquals(original, restored.toByteArray())
    }

    private fun keyManager(identity: String) = RagKeyManager(
        context = context,
        keyAlias = "minicpm-rag-test-$identity",
        preferencesName = "rag-crypto-test-$identity",
    )

    private fun generatedAesKey() = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }

    private fun readNonce(file: File): ByteArray = RandomAccessFile(file, "r").use { input ->
        val magic = ByteArray(4)
        input.readFully(magic)
        assertArrayEquals(byteArrayOf('R'.code.toByte(), 'A'.code.toByte(), 'G'.code.toByte(), 'F'.code.toByte()), magic)
        assertEquals(1, input.readUnsignedByte())
        val nonceLength = input.readUnsignedByte()
        ByteArray(nonceLength).also(input::readFully)
    }

    private class FailingInputStream(private val prefix: ByteArray) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index >= prefix.size) throw IOException("simulated interrupted source")
            return prefix[index++].toInt() and 0xff
        }
    }

    private class ConsumerProbeException : RuntimeException("consumer failed")
}
