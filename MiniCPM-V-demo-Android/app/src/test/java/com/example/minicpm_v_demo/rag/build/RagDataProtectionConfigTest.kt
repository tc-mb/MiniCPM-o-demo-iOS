package com.example.minicpm_v_demo.rag.build

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagDataProtectionConfigTest {
    private val workingDirectory = System.getProperty("user.dir") ?: error("JVM user.dir is unavailable")
    private val projectRoot = generateSequence(File(workingDirectory)) { it.parentFile }
        .firstOrNull { File(it, "app/src/main/AndroidManifest.xml").isFile }
        ?: error("Cannot locate Android project root")

    @Test
    fun `backup rules exclude encrypted RAG database files and wrapped key material`() {
        val legacyRules = File(projectRoot, "app/src/main/res/xml/backup_rules.xml").readText()
        val extractionRules = File(projectRoot, "app/src/main/res/xml/data_extraction_rules.xml").readText()
        val requiredExclusions = listOf(
            "domain=\"database\" path=\"local-rag.db\"",
            "domain=\"sharedpref\" path=\"minicpm_local_rag_crypto.xml\"",
            "domain=\"file\" path=\"rag/\"",
        )

        requiredExclusions.forEach { exclusion ->
            assertTrue("Missing legacy backup exclusion: $exclusion", legacyRules.contains(exclusion))
            assertTrue("Missing Android 12+ backup exclusion: $exclusion", extractionRules.contains(exclusion))
        }
    }

    @Test
    fun `application exposes one lazy encrypted RAG database`() {
        val application = File(
            projectRoot,
            "app/src/main/java/com/example/minicpm_v_demo/MiniCPMApplication.kt",
        ).readText()

        assertTrue(application.contains("val ragKeyManager by lazy"))
        assertTrue(application.contains("val ragDatabase by lazy"))
        assertTrue(application.contains("RagDatabaseFactory(this, ragKeyManager).open()"))
        assertFalse(application.contains("fallbackToDestructiveMigration"))
    }
}
