package com.example.minicpm_v_demo.rag.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class RagKeyManager(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    fun getOrCreateMasterKey(): SecretKey = synchronized(KEYSTORE_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(KEY_BITS)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }
            .generateKey()
    }

    fun getOrCreateDatabasePassphrase(): ByteArray = synchronized(KEYSTORE_LOCK) {
        preferences.getString(WRAPPED_PASSPHRASE_KEY, null)?.let(::unwrapPassphrase)
            ?: ByteArray(DATABASE_PASSPHRASE_BYTES).also { passphrase ->
                secureRandom.nextBytes(passphrase)
                val encoded = wrapPassphrase(passphrase)
                check(preferences.edit().putString(WRAPPED_PASSPHRASE_KEY, encoded).commit()) {
                    "Unable to persist wrapped RAG database passphrase"
                }
            }
    }

    private fun wrapPassphrase(passphrase: ByteArray): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
            updateAAD(DATABASE_AAD)
        }
        val nonce = cipher.iv.also {
            check(it.size == GCM_NONCE_BYTES) { "Unexpected Android Keystore GCM nonce length" }
        }
        val ciphertext = cipher.doFinal(passphrase)
        return Base64.encodeToString(byteArrayOf(WRAP_FORMAT_VERSION) + nonce + ciphertext, Base64.NO_WRAP)
    }

    private fun unwrapPassphrase(encoded: String): ByteArray {
        val container = Base64.decode(encoded, Base64.NO_WRAP)
        require(container.size > 1 + GCM_NONCE_BYTES + GCM_TAG_BYTES) { "Invalid wrapped passphrase" }
        require(container[0] == WRAP_FORMAT_VERSION) { "Unsupported wrapped passphrase version" }
        val nonce = container.copyOfRange(1, 1 + GCM_NONCE_BYTES)
        val ciphertext = container.copyOfRange(1 + GCM_NONCE_BYTES, container.size)
        return Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(DATABASE_AAD)
            doFinal(ciphertext)
        }.also {
            require(it.size == DATABASE_PASSPHRASE_BYTES) { "Invalid database passphrase length" }
        }
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEFAULT_KEY_ALIAS = "minicpm-local-rag-master-v1"
        private const val DEFAULT_PREFERENCES_NAME = "minicpm_local_rag_crypto"
        private const val WRAPPED_PASSPHRASE_KEY = "wrapped_database_passphrase_v1"
        private const val KEY_BITS = 256
        private const val DATABASE_PASSPHRASE_BYTES = 32
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val WRAP_FORMAT_VERSION: Byte = 1
        private val DATABASE_AAD = "MiniCPM-RAG-DB-PASSPHRASE-v1".toByteArray(Charsets.UTF_8)
        private val KEYSTORE_LOCK = Any()
    }
}
