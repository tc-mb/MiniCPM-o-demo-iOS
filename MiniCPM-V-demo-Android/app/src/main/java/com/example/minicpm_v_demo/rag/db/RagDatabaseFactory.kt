package com.example.minicpm_v_demo.rag.db

import android.content.Context
import androidx.room.Room
import com.example.minicpm_v_demo.rag.crypto.RagKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class RagDatabaseFactory(
    context: Context,
    private val keyManager: RagKeyManager,
    private val databaseName: String = RagDatabase.DATABASE_NAME,
) {
    private val applicationContext = context.applicationContext

    fun open(): RagDatabase {
        ensureSqlCipherLoaded()
        val passphrase = keyManager.getOrCreateDatabasePassphrase()
        return Room.databaseBuilder(applicationContext, RagDatabase::class.java, databaseName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(RagMigrations.MIGRATION_1_2, RagMigrations.MIGRATION_2_3)
            .build()
    }

    companion object {
        @Volatile
        private var sqlCipherLoaded = false

        private fun ensureSqlCipherLoaded() {
            if (sqlCipherLoaded) return
            synchronized(this) {
                if (!sqlCipherLoaded) {
                    System.loadLibrary("sqlcipher")
                    sqlCipherLoaded = true
                }
            }
        }
    }
}
