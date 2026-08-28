import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val localSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("signing.local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use(::load)
}

fun signingProperty(name: String): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: localSigningProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val installationKeystorePath = signingProperty("MINICPMV_KEYSTORE")
val installationKeystorePassword = signingProperty("MINICPMV_KEYSTORE_PASSWORD")
val installationKeyAlias = signingProperty("MINICPMV_KEY_ALIAS")
val installationKeyPassword = signingProperty("MINICPMV_KEY_PASSWORD")
val installationSigningIsConfigured = listOf(
    installationKeystorePath,
    installationKeystorePassword,
    installationKeyAlias,
    installationKeyPassword,
).all { !it.isNullOrBlank() } && installationKeystorePath?.let(::file)?.isFile == true

// Certificate of the one canonical key accepted by existing development installs.
val expectedInstallationCertificateSha256 =
    "12BEFEDA42FECFE1F9A268466B85906E0B18E13C960B7217487FC6145166EB85"

val ragGuardArtifactDir = providers.gradleProperty("RAG_GUARD_ARTIFACT_DIR").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::file)
    ?: providers.environmentVariable("RAG_GUARD_ARTIFACT_DIR").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let(::file)
    ?: rootProject.file("models/rag-guard-v4-2-e5")
val generatedRagGuardAssets = layout.buildDirectory.dir("generated/ragGuardAssets")

android {
    namespace = "com.example.minicpm_v_demo"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.example.minicpm_v_demo"
        // minSdk = 24 (Android 7.0) covers ~99% of in-use devices.
        // The native code only requires arm64-v8a (Android 5.0+), and the
        // app itself uses no Android 13+ APIs. The adaptive icon XML is
        // placed under mipmap-anydpi-v26/ so pre-Oreo devices fall back
        // to the WebP icons in mipmap-{m,h,xh,xxh,xxxh}dpi/.
        minSdk = 24
        targetSdk = 37
        versionCode = 15
        versionName = "2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_BUILD_TOOLS=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"

                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_LLAMAFILE=ON"
                arguments += "-DLLAMA_CURL=OFF"
                providers.environmentVariable("KLEIDIAI_SOURCE_DIR").orNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { sourceDirectory ->
                        val cmakePath = file(sourceDirectory).absolutePath.replace('\\', '/')
                        arguments += "-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI_DOWNLOAD=$cmakePath"
                    }
            }
        }
    }

    // Debug and release installs must share one explicit, stable signing source.
    // Credentials live in ignored signing.local.properties or Gradle properties.
    signingConfigs {
        create("installation") {
            if (installationSigningIsConfigured) {
                storeFile = file(requireNotNull(installationKeystorePath))
                storePassword = installationKeystorePassword
                keyAlias = installationKeyAlias
                keyPassword = installationKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (installationSigningIsConfigured) {
                signingConfig = signingConfigs.getByName("installation")
            }
        }
        release {
            // Keep ProGuard/R8 disabled: the app calls native JNI symbols and
            // shrinking the Kotlin side has no measurable benefit here, while
            // an over-aggressive shrinker is the most common cause of crashes
            // in apps with lots of JNI bindings.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the signing config when the keystore actually exists,
            // so contributors without the secret can still run :assembleRelease
            // (it'll produce an unsigned apk in that case).
            val signingCfg = signingConfigs.getByName("installation")
            if (installationSigningIsConfigured) {
                signingConfig = signingCfg
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    buildFeatures {
        viewBinding = true
    }

    androidResources {
        noCompress.add("gguf")
        noCompress.add("bin")
        noCompress.add("onnx")
    }

    sourceSets.getByName("main").assets.directories.add(
        generatedRagGuardAssets.get().asFile.absolutePath,
    )
}

val prepareRagGuardAssets = tasks.register("prepareRagGuardAssets") {
    group = "build"
    description = "Verify and stage the pinned RAG Guard v4.2 INT8 model for APK assets."
    val manifestFile = ragGuardArtifactDir.resolve("manifest.json")
    val modelFile = ragGuardArtifactDir.resolve("model.int8.onnx")
    inputs.files(manifestFile, modelFile)
    outputs.dir(generatedRagGuardAssets)
    doLast {
        check(manifestFile.isFile && modelFile.isFile) {
            "Verified RAG Guard v4.2 artifacts are missing from ${ragGuardArtifactDir.absolutePath}"
        }
        val artifactRoot = ragGuardArtifactDir.canonicalFile
        check(manifestFile.canonicalFile.parentFile == artifactRoot)
        check(modelFile.canonicalFile.parentFile == artifactRoot)
        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parse(manifestFile) as Map<String, Any?>
        check(manifest["architecture"] == "shared_encoder_three_plus_four_heads")
        check(manifest["test_evaluated"] == false && manifest["test"] == null)
        check(manifest["evaluated_splits"] == listOf("calibration"))
        @Suppress("UNCHECKED_CAST")
        val deployment = manifest["deployment"] as? Map<String, Any?>
            ?: error("RAG Guard deployment metadata is missing")
        check(deployment["channel"] == "production")
        check(deployment["selection_basis"] == "recorded_metrics")
        @Suppress("UNCHECKED_CAST")
        val files = manifest["files"] as? Map<String, Map<String, Any?>>
            ?: error("RAG Guard manifest files section is invalid")
        val model = files["model.int8.onnx"] ?: error("RAG Guard INT8 model is not declared")
        val declaredBytes = (model["bytes"] as Number).toLong()
        val declaredSha256 = model["sha256"] as String
        check(modelFile.length() == declaredBytes) { "RAG Guard model size mismatch" }
        val modelDigest = MessageDigest.getInstance("SHA-256")
        modelFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) modelDigest.update(buffer, 0, count)
            }
        }
        val actualSha256 = modelDigest.digest().joinToString("") { byte -> "%02x".format(byte) }
        check(actualSha256 == declaredSha256) { "RAG Guard model SHA-256 mismatch" }
        val outputRoot = generatedRagGuardAssets.get().asFile
        project.delete(outputRoot)
        val assetDirectory = outputRoot.resolve("rag_guard_v4_2")
        check(assetDirectory.mkdirs() || assetDirectory.isDirectory)
        modelFile.copyTo(assetDirectory.resolve("model.int8.onnx"), overwrite = false)
    }
}

tasks.configureEach {
    if (name.matches(Regex("merge(?:Debug|Release)Assets", RegexOption.IGNORE_CASE))) {
        dependsOn(prepareRagGuardAssets)
    }
}

val verifyInstallationSigning = tasks.register("verifyInstallationSigning") {
    group = "verification"
    description = "Fail before device installation when the canonical signing key is absent or wrong."
    doLast {
        check(installationSigningIsConfigured) {
            "Stable installation signing is required. Configure MINICPMV_KEYSTORE, " +
                "MINICPMV_KEYSTORE_PASSWORD, MINICPMV_KEY_ALIAS and MINICPMV_KEY_PASSWORD " +
                "in ignored signing.local.properties or Gradle properties."
        }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            file(requireNotNull(installationKeystorePath)).inputStream().use { input ->
                load(input, requireNotNull(installationKeystorePassword).toCharArray())
            }
        }
        val certificate = requireNotNull(keyStore.getCertificate(requireNotNull(installationKeyAlias))) {
            "Configured installation key alias does not exist."
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { byte -> "%02X".format(byte) }
        check(fingerprint == expectedInstallationCertificateSha256) {
            "Installation signing certificate mismatch. Refusing to build/install an incompatible APK. " +
                "Expected $expectedInstallationCertificateSha256, got $fingerprint."
        }
    }
}

tasks.configureEach {
    val installsOnDevice = name.startsWith("install", ignoreCase = true) ||
        (name.startsWith("connected", ignoreCase = true) && name.endsWith("AndroidTest"))
    if (installsOnDevice && name != verifyInstallationSigning.name) {
        dependsOn(verifyInstallationSigning)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Room 2.8.4's migration schema serializers are generated against 1.8.1.
    // Align transitive SavedState serialization to avoid a test/runtime ABI split.
    implementation(platform("org.jetbrains.kotlinx:kotlinx-serialization-bom:1.8.1"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Local, offline RAG storage, durable indexing, parsing, OCR and embedding runtime.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.sqlcipher.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.onnxruntime.extensions.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.pdfbox.android)
    ksp(libs.androidx.room.compiler)

    // Markdown rendering for AI streaming responses (headings, bold, lists, code, etc.)
    implementation("io.noties.markwon:core:4.6.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
}

// ---------------------------------------------------------------------------
// Dynamic CPU dispatch: build an additional libggml-cpu.so optimised for
// ARMv8.6-a (i8mm + bf16) and package it alongside the baseline build.
// At runtime the Kotlin CpuFeatures helper detects hardware capabilities
// and pre-loads the best variant before the rest of the native chain.
// ---------------------------------------------------------------------------

fun runCmd(vararg args: String) {
    val logFile = File.createTempFile("minicpmv-native-", ".log")
    try {
        val proc = ProcessBuilder(*args)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .start()
        val rc = proc.waitFor()
        if (rc != 0) {
            val outputTail = logFile.readLines().takeLast(200).joinToString("\n")
            error(
                "Command failed (rc=$rc): ${args.joinToString(" ")}\n" +
                    outputTail
            )
        }
    } finally {
        logFile.delete()
    }
}

val sdkRoot: String = System.getenv("ANDROID_HOME")
    ?: file("../local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
    ?: error("Cannot locate Android SDK — set ANDROID_HOME or local.properties")

tasks.register("buildGgmlCpu_v86") {
    group = "native"
    description = "Build libggml-cpu optimised for armv8.6-a+i8mm+bf16"

    val destSo = file("src/main/jniLibs/arm64-v8a/libggml-cpu-v86.so")
    outputs.file(destSo)

    doLast {
        val cmake = "$sdkRoot/cmake/4.1.2/bin/cmake"
        val ninja = File(cmake).parentFile.resolve("ninja.exe").absolutePath
        val toolchain = "$sdkRoot/ndk/29.0.14206865/build/cmake/android.toolchain.cmake"
        val configuredKleidiAiSource = System.getenv("KLEIDIAI_SOURCE_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let(::file)
            ?.takeIf { it.resolve("CMakeLists.txt").isFile }
        val kleidiAiSource = configuredKleidiAiSource
            ?: fileTree(file(".cxx/Release")) {
                include("*/arm64-v8a/_deps/kleidiai_download-src/CMakeLists.txt")
            }.files
                .map { it.parentFile }
                .maxByOrNull { it.lastModified() }
            ?: error(
                "KleidiAI source cache is missing. Run the standard Android native " +
                    "build once or set KLEIDIAI_SOURCE_DIR before buildGgmlCpu_v86."
            )
        val bd = File(project.layout.buildDirectory.asFile.get(), "v86-cmake/arm64-v8a")
        bd.mkdirs()

        runCmd(
            cmake,
            "--fresh",
            "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=$ninja",
            "-DFETCHCONTENT_FULLY_DISCONNECTED=ON",
            "-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI_DOWNLOAD=${kleidiAiSource.absolutePath}",
            "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
            "-DANDROID_ABI=arm64-v8a",
            "-DANDROID_PLATFORM=android-24",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DBUILD_SHARED_LIBS=ON",
            "-DLLAMA_BUILD_COMMON=ON",
            "-DLLAMA_OPENSSL=OFF",
            "-DLLAMA_CURL=OFF",
            "-DLLAMA_BUILD_TOOLS=ON",
            "-DGGML_NATIVE=OFF",
            "-DGGML_LLAMAFILE=ON",
            "-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm+fp16+bf16",
            "-S", file("src/main/cpp").absolutePath,
            "-B", bd.absolutePath,
        )

        runCmd(
            cmake,
            "--build", bd.absolutePath,
            "--target", "ggml-cpu",
            "-j", Runtime.getRuntime().availableProcessors().toString(),
        )

        val builtSo = fileTree(bd).matching { include("**/libggml-cpu.so") }.singleFile
        destSo.parentFile.mkdirs()
        builtSo.copyTo(destSo, overwrite = true)
        logger.lifecycle("Copied v86 ggml-cpu -> ${destSo.absolutePath} (${destSo.length() / 1024}K)")
    }
}

afterEvaluate {
    listOf("Debug", "Release").forEach { buildType ->
        tasks.findByName("merge${buildType}JniLibFolders")?.dependsOn("buildGgmlCpu_v86")
    }
}
