package com.example.minicpm_v_demo.rag.build

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagDependencyPolicyTest {
    private val workingDirectory = System.getProperty("user.dir")
        ?: error("JVM user.dir is unavailable")
    private val projectRoot: File = generateSequence(File(workingDirectory)) { it.parentFile }
        .firstOrNull { File(it, "gradle/libs.versions.toml").isFile }
        ?: error("Cannot locate Android project root from $workingDirectory")

    @Test
    fun `production dependencies use pinned versions`() {
        val gradleFiles = listOf(
            File(projectRoot, "build.gradle.kts"),
            File(projectRoot, "settings.gradle.kts"),
            File(projectRoot, "app/build.gradle.kts"),
        )

        val dynamicVersion = Regex("""(?:latest\.(?:release|integration)|:\s*[^\s\"']*\+)""")
        val violations = gradleFiles.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (dynamicVersion.containsMatchIn(line)) {
                    "${file.relativeTo(projectRoot).invariantSeparatorsPath}:${index + 1}: $line"
                } else {
                    null
                }
            }
        }

        assertTrue("Dynamic dependency versions are forbidden:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `local RAG dependencies are declared at reviewed versions`() {
        val catalog = File(projectRoot, "gradle/libs.versions.toml").readText()
        val appBuild = File(projectRoot, "app/build.gradle.kts").readText()

        val requiredVersions = mapOf(
            "room" to "2.8.4",
            "workManager" to "2.11.2",
            "sqlCipher" to "4.17.0",
            "sqlite" to "2.6.2",
            "onnxRuntime" to "1.25.0",
            "onnxRuntimeExtensions" to "0.13.0",
            "mlKitTextRecognition" to "16.0.1",
            "pdfBoxAndroid" to "2.0.27.0",
            "ksp" to "2.3.10",
        )
        requiredVersions.forEach { (name, version) ->
            assertTrue("Missing pinned version $name=$version", catalog.contains("$name = \"$version\""))
        }

        val requiredAliases = listOf(
            "libs.androidx.room.runtime",
            "libs.androidx.room.ktx",
            "libs.androidx.work.runtime.ktx",
            "libs.androidx.sqlite.ktx",
            "libs.sqlcipher.android",
            "libs.onnxruntime.android",
            "libs.onnxruntime.extensions.android",
            "libs.mlkit.text.recognition",
            "libs.mlkit.text.recognition.chinese",
            "libs.pdfbox.android",
        )
        requiredAliases.forEach { alias ->
            assertTrue("Missing RAG dependency $alias", appBuild.contains("implementation($alias)"))
        }

        assertFalse("RAG runtime must not use compileOnly dependencies", appBuild.contains("compileOnly(libs.onnxruntime"))
    }

    @Test
    fun `Room compiler uses KSP2 and exports versioned schemas`() {
        val catalog = File(projectRoot, "gradle/libs.versions.toml").readText()
        val appBuild = File(projectRoot, "app/build.gradle.kts").readText()

        assertTrue(catalog.contains("ksp = { id = \"com.google.devtools.ksp\", version.ref = \"ksp\" }"))
        assertTrue(catalog.contains("room = { id = \"androidx.room\", version.ref = \"room\" }"))
        assertTrue(catalog.contains("androidx-room-compiler = { group = \"androidx.room\", name = \"room-compiler\", version.ref = \"room\" }"))
        assertTrue(appBuild.contains("alias(libs.plugins.ksp)"))
        assertTrue(appBuild.contains("alias(libs.plugins.room)"))
        assertTrue(appBuild.contains("ksp(libs.androidx.room.compiler)"))
        assertTrue(appBuild.contains("schemaDirectory(\"\$projectDir/schemas\")"))
        assertFalse("AGP 9 uses built-in Kotlin", appBuild.contains("org.jetbrains.kotlin.android"))
    }

    @Test
    fun `R8 keeps ONNX Runtime JNI entry points`() {
        val proguardRules = File(projectRoot, "app/proguard-rules.pro").readText()

        assertTrue(proguardRules.contains("-keep class ai.onnxruntime.** { *; }"))
    }
}
