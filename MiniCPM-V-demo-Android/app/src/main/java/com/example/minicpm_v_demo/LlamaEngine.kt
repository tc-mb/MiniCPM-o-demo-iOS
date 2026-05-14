package com.example.minicpm_v_demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed class LlamaState {
    object Uninitialized : LlamaState()
    object Initializing : LlamaState()
    object Initialized : LlamaState()
    object LoadingModel : LlamaState()
    object ModelReady : LlamaState()
    object ProcessingSystemPrompt : LlamaState()
    object PrefillingImage : LlamaState()
    object ProcessingUserPrompt : LlamaState()
    object Generating : LlamaState()
    object UnloadingModel : LlamaState()
    data class Error(val exception: Exception) : LlamaState()
}

class LlamaEngine private constructor(
    private val context: Context,
    private val nativeLibDir: String
) {

    companion object {
        private val TAG = LlamaEngine::class.java.simpleName

        @Volatile
        private var instance: LlamaEngine? = null

        // Per-turn token budget. iOS demo (MTMDParams.swift) uses 100 because
        // ANE makes generation fast enough that the cap is rarely hit; on
        // Android with CPU-only inference users complained about replies
        // getting truncated mid-sentence, so we raise it to a safer ceiling.
        // 512 tokens is roughly 250-350 Chinese characters per turn, which
        // covers the vast majority of single-turn answers; the n_ctx=4096
        // buffer plus shift_context() in llama_jni.cpp still keeps multi-turn
        // chats stable.
        const val DEFAULT_PREDICT_LENGTH = 512

        const val MODEL_SUBDIR = "models"

        private const val PREFS_NAME = "model_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model_id"
        private const val KEY_IMAGE_MAX_SLICE = "image_max_slice_nums"

        // MiniCPM-V's hard upper bound on slice count.  Values higher than 9
        // get clamped by clip.cpp::get_best_grid anyway; we cap on the UI
        // side to keep the slider semantics honest.
        const val MIN_IMAGE_SLICE = 1
        const val MAX_IMAGE_SLICE = 9
        // Out-of-the-box we run with MiniCPM-V's full slice budget (9 =
        // the model's built-in default) so first-launch image quality
        // matches what the model card promises.  Users who care about
        // prefill latency drop the chat-page slider down to 1 (no
        // slicing, ~9x fewer image tokens).
        const val DEFAULT_IMAGE_SLICE = MAX_IMAGE_SLICE

        fun getInstance(context: Context): LlamaEngine =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }
                LlamaEngine(context, nativeLibDir).also { instance = it }
            }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getSelectedModel(context: Context): ModelInfo {
            val modelId = prefs(context).getString(KEY_SELECTED_MODEL, ModelInfo.DEFAULT_MODEL.id) ?: ModelInfo.DEFAULT_MODEL.id
            return ModelInfo.AVAILABLE_MODELS.find { it.id == modelId } ?: ModelInfo.DEFAULT_MODEL
        }

        fun setSelectedModel(context: Context, modelId: String) {
            prefs(context).edit().putString(KEY_SELECTED_MODEL, modelId).apply()
        }

        // Slice cap is persisted globally (not per-model) - users keep
        // their preferred speed / quality trade-off across model swaps,
        // and v4 / v4.6 / 2.6 all use the same slicing semantics.
        fun getImageMaxSliceNums(context: Context): Int =
            prefs(context).getInt(KEY_IMAGE_MAX_SLICE, DEFAULT_IMAGE_SLICE)
                .coerceIn(MIN_IMAGE_SLICE, MAX_IMAGE_SLICE)

        fun setImageMaxSliceNumsPref(context: Context, n: Int) {
            val clamped = n.coerceIn(MIN_IMAGE_SLICE, MAX_IMAGE_SLICE)
            prefs(context).edit().putInt(KEY_IMAGE_MAX_SLICE, clamped).apply()
        }

        fun modelDir(context: Context): String =
            File(context.filesDir, MODEL_SUBDIR).absolutePath

        fun modelDirFor(context: Context, model: ModelInfo): String =
            File(modelDir(context), model.id).absolutePath

        fun modelPath(context: Context): String {
            val model = getSelectedModel(context)
            return File(modelDirFor(context, model), model.ggufFileName).absolutePath
        }

        fun mmprojPath(context: Context): String {
            val model = getSelectedModel(context)
            return File(modelDirFor(context, model), model.mmprojFileName).absolutePath
        }

        fun modelsExist(context: Context): Boolean =
            File(modelPath(context)).exists() && File(mmprojPath(context)).exists()

        // Rename map for files that were previously sideloaded with a
        // different name and now need to match the iOS demo's filenames so a
        // re-push isn't required. Keyed by ModelInfo.id; values are
        // (oldName -> newName) pairs scoped to that model's sub-directory.
        // Maps any historical filename (left) to the current canonical name
        // (right) for files that may already exist in a user's per-model
        // sub-directory. Each entry is consulted on app start; if a left-hand
        // file is found it is renamed to the right-hand one. MD5 checks then
        // either accept it or trigger a re-download. Keep the rename targets
        // in sync with [ModelInfo.AVAILABLE_MODELS].
        private val LEGACY_FILE_RENAMES: Map<String, List<Pair<String, String>>> = mapOf(
            "minicpm-v-4_6-instruct" to listOf(
                // Earliest sideloaded LLM name, then our previous demo name,
                // both rolled forward to the released HF filename. The GGUF
                // content is byte-stable across these names (LFS-hashed on
                // HF, MD5-pinned on OBS) so renaming is safe.
                "MiniCPM-V4.6-instruct-Q4_K_M.gguf" to "MiniCPM-V-4_6-Q4_K_M.gguf",
                "minicpmv46-llm-Q4_K_M.gguf" to "MiniCPM-V-4_6-Q4_K_M.gguf"
                // NOTE: deliberately *not* renaming the legacy mmproj
                // filenames here; their bytes are not necessarily compatible
                // with the demo's current clip.cpp. They are purged outright
                // by [STALE_MMPROJ_NAMES] below.
            )
        )

        // Pre-v2.0 the v4.6 mmproj came from a temporary OBS bucket and went
        // through several incompatible revisions (pre-release / sealed
        // minicpmv4_6 / demo-merger). To keep the rollout clean we purge any
        // file that matches a known stale name; modelsExist() then reports
        // "missing" and the new HF/MS racing download fetches the canonical
        // OpenBMB-published `mmproj-model-f16.gguf` (whose MD5 is pinned in
        // ModelInfo and verified after download).
        private val STALE_MMPROJ_NAMES: Map<String, List<String>> = mapOf(
            "minicpm-v-4_6-instruct" to listOf(
                "mmproj-v46-model-f16.gguf",
                // v1.7-v1.9 demo: bytes converted on the Support-iOS-Demo
                // branch (clip.projector_type=merger). Upstream master mtmd
                // accepts the original minicpmv4_6 projector natively so we
                // no longer need this re-conversion; the file would also
                // fail the new MD5 anyway.
                "mmproj-model-merger-f16.gguf"
                // Intentionally NOT listing "mmproj-model-f16.gguf" here:
                // that IS the canonical name from v2.0 onwards. If a user
                // has a stale copy from an even earlier OBS revision sitting
                // under that name, the MD5 check inside downloadFile will
                // delete it and re-download cleanly.
            )
        )

        // One-shot migration: previously all model files lived flat under models/.
        // With multiple models (v4, v4.6, ...) some filenames collide (e.g. mmproj-model-f16.gguf),
        // so each model now gets its own subdirectory keyed by ModelInfo.id.
        // Also handles renaming files that have been sideloaded under a
        // legacy name (see [LEGACY_FILE_RENAMES]).
        fun migrateLegacyLayoutIfNeeded(context: Context) {
            val rootDir = File(modelDir(context))
            if (!rootDir.exists()) return

            for (model in ModelInfo.AVAILABLE_MODELS) {
                val targetDir = File(rootDir, model.id)
                val flatGguf = File(rootDir, model.ggufFileName)
                val flatMmproj = File(rootDir, model.mmprojFileName)
                if (flatGguf.exists() || flatMmproj.exists()) {
                    if (!targetDir.exists()) targetDir.mkdirs()
                    if (flatGguf.exists()) {
                        val dst = File(targetDir, model.ggufFileName)
                        if (!dst.exists() && flatGguf.renameTo(dst)) {
                            Log.i(TAG, "Migrated legacy ${model.ggufFileName} into ${model.id}/")
                        }
                    }
                    if (flatMmproj.exists()) {
                        val dst = File(targetDir, model.mmprojFileName)
                        if (!dst.exists() && flatMmproj.renameTo(dst)) {
                            Log.i(TAG, "Migrated legacy ${model.mmprojFileName} into ${model.id}/")
                        }
                    }
                }

                LEGACY_FILE_RENAMES[model.id]?.let { renames ->
                    val perModelDir = File(rootDir, model.id)
                    if (!perModelDir.exists()) return@let
                    for ((oldName, newName) in renames) {
                        val src = File(perModelDir, oldName)
                        val dst = File(perModelDir, newName)
                        if (src.exists() && !dst.exists() && src.renameTo(dst)) {
                            Log.i(TAG, "Renamed legacy ${model.id}/$oldName -> $newName")
                        }
                    }
                }

                // Purge any stale mmproj cached under a previous filename:
                // the OBS object behind that name has been re-converted at
                // least once and the cached bytes may be incompatible with
                // the demo's current clip.cpp (would crash inside
                // mtmd_init_from_file). Companion .tmp from a partial old
                // download is dropped too. This is intentionally one-way -
                // we never try to "rescue" the bytes by renaming.
                STALE_MMPROJ_NAMES[model.id]?.let { stale ->
                    val perModelDir = File(rootDir, model.id)
                    if (!perModelDir.exists()) return@let
                    for (name in stale) {
                        val f = File(perModelDir, name)
                        if (f.exists() && f.delete()) {
                            Log.i(TAG, "Purged stale mmproj ${model.id}/$name (incompatible OBS revision)")
                        }
                        val tmp = File(perModelDir, "$name.tmp")
                        if (tmp.exists() && tmp.delete()) {
                            Log.i(TAG, "Purged stale mmproj tmp ${model.id}/$name.tmp")
                        }
                    }
                }
            }
        }

        // Maps a selected [ModelInfo] to the matching MiniCPM-V family version
        // tag that the (legacy) fork mtmd-ios.h would have returned via
        // mtmd_get_minicpmv_version.  Used in [loadModel] to push the value
        // down to native after loadMmproj.
        //   - 46  : MiniCPM-V-4.6 instruct (the demo's only V-4.6 build)
        //   - 5   : MiniCPM-V-4 (corresponds to upstream `clip.minicpmv_version=5`)
        //   - 0   : no match / unknown -> native treats as "no vision-version
        //           gating; use default n_ctx + plain assistant prefix"
        // Kept in the companion object so [downloadModels] / [loadModel] /
        // any future callers can reuse the same mapping without re-grepping
        // model ids in three places.
        fun inferMinicpmvVersion(model: ModelInfo): Int = when (model.id) {
            "minicpm-v-4_6-instruct" -> 46
            "minicpm-v-4"            -> 5
            else                     -> 0
        }

        // Per-file source descriptor used by [downloadFileMultiSource] / racing.
        private data class FileSource(val label: String, val url: URL)

        // Racing winner state passed from header-probe goroutines to the
        // body-streaming logic. [conn] is the still-open HttpURLConnection
        // whose response headers we accepted; the loser connections are
        // disconnected before [body] is reached.
        private data class RaceWinner(
            val index: Int,
            val source: String,
            val conn: HttpURLConnection,
            val responseCode: Int,
            val contentLength: Long,
        )

        suspend fun downloadModels(
            context: Context,
            onProgress: (String) -> Unit
        ) = withContext(Dispatchers.IO) {
            val model = getSelectedModel(context)
            val dir = File(modelDirFor(context, model))
            if (!dir.exists()) dir.mkdirs()

            // Per file, build the *full* candidate-source list. HF + MS run
            // in parallel; directGgufUrl/directMmprojUrl, if present, are
            // registered as a third candidate. The first source to deliver
            // valid response headers wins, the others get disconnected.
            // Mirrors iOS opt-r1's MBModelDownloadHelperV2.downloadV2.
            data class FileSpec(
                val name: String,
                val sources: List<FileSource>,
                val md5: String?,
            )

            val ggufSources = mutableListOf<FileSource>()
            val mmprojSources = mutableListOf<FileSource>()

            if (model.hasHfMsSources) {
                val hfBase = "https://huggingface.co/${model.hfRepo}/resolve/${model.hfBranch}"
                val msBase = "https://www.modelscope.cn/models/${model.msRepo}/resolve/${model.msBranch}"
                ggufSources.add(FileSource("HuggingFace", URL("$hfBase/${model.ggufRemotePath}")))
                ggufSources.add(FileSource("ModelScope", URL("$msBase/${model.ggufRemotePath}")))
                mmprojSources.add(FileSource("HuggingFace", URL("$hfBase/${model.mmprojRemotePath}")))
                mmprojSources.add(FileSource("ModelScope", URL("$msBase/${model.mmprojRemotePath}")))
            }
            // Optional direct-mirror escape hatch (e.g. OBS during TestFlight).
            // Registered as an extra race candidate; not exclusive of HF/MS.
            if (!model.directGgufUrl.isNullOrBlank()) {
                ggufSources.add(FileSource("直链", URL(model.directGgufUrl)))
            }
            if (!model.directMmprojUrl.isNullOrBlank()) {
                mmprojSources.add(FileSource("直链", URL(model.directMmprojUrl)))
            }

            if (ggufSources.isEmpty() || mmprojSources.isEmpty()) {
                throw RuntimeException("Model ${model.id} has no download source configured")
            }

            val files = listOf(
                FileSpec(model.ggufFileName, ggufSources, model.ggufMd5),
                FileSpec(model.mmprojFileName, mmprojSources, model.mmprojMd5),
            )

            val racedLabel = files.first().sources.joinToString("+") { it.label }
            onProgress("$racedLabel 多源 race 启动...")

            for (file in files) {
                downloadFileMultiSource(dir, file.name, file.sources, file.md5, onProgress)
            }

            onProgress("所有模型文件下载完成!")
        }

        /**
         * Multi-source racing download.
         *
         * Launches one HttpURLConnection GET per [sources] entry in parallel.
         * The first connection whose response headers indicate success
         * (HTTP 200/206 with positive Content-Length, or 416 with a valid
         * Content-Range matching the local `.tmp`) wins; the others are
         * disconnected immediately. The winner's body is streamed to disk
         * exactly the same way [downloadFile] does it for the single-source
         * path, including resume semantics, MD5 verification, and progress
         * reporting tagged with the winning source label.
         *
         * If every source fails before any of them produces headers, the
         * race surfaces a single combined RuntimeException to the caller.
         *
         * Mirrors iOS opt-r1's MBModelDownloadHelperV2.downloadV2 (race the
         * URLSessionDownloadTask `didReceiveResponse` callbacks). Implementing
         * this on Android uses `HttpURLConnection.responseCode` as the
         * synchronous header gate — it blocks the worker dispatcher thread
         * until the server sends the first byte of the status line, which is
         * the exact moment we want to compare for a winner.
         */
        private suspend fun downloadFileMultiSource(
            dir: File,
            fileName: String,
            sources: List<FileSource>,
            expectedMd5: String?,
            onProgress: (String) -> Unit,
        ) {
            require(sources.isNotEmpty()) { "downloadFileMultiSource: empty sources" }

            // Single source -> bypass race plumbing entirely.
            if (sources.size == 1) {
                val (label, url) = sources.first()
                downloadFile(dir, fileName, url, label, expectedMd5, onProgress)
                return
            }

            val targetFile = File(dir, fileName)
            val tmpFile = File(dir, "$fileName.tmp")

            // Fast path: same as downloadFile - if target already exists and
            // MD5 matches the manifest, treat as done.
            if (targetFile.exists() && expectedMd5 != null) {
                onProgress("校验已存在的 $fileName ...")
                val actual = computeMd5(targetFile)
                if (actual.equals(expectedMd5, ignoreCase = true)) {
                    onProgress("$fileName 已就绪 (MD5 校验通过)")
                    Log.i(TAG, "$fileName already present and MD5 matches, skipping download")
                    if (tmpFile.exists()) tmpFile.delete()
                    return
                }
                Log.w(TAG, "$fileName already present but MD5 mismatch (got $actual, want $expectedMd5); re-downloading")
                targetFile.delete()
                if (tmpFile.exists()) tmpFile.delete()
            }

            val resumeFrom = if (tmpFile.exists()) tmpFile.length() else 0L
            if (resumeFrom > 0) {
                onProgress("续传 $fileName，已下 ${resumeFrom / (1024 * 1024)} MB...")
                Log.i(TAG, "Resuming $fileName from $resumeFrom bytes via ${sources.size}-way race")
            } else {
                onProgress("${sources.joinToString("+") { it.label }} race 拉取 $fileName...")
                Log.i(TAG, "Race-downloading $fileName from ${sources.size} sources")
            }

            coroutineScope {
                val winnerSlot = CompletableDeferred<RaceWinner>()
                val failedCount = AtomicInteger(0)
                val totalSources = sources.size
                val openConns = ConcurrentHashMap<Int, HttpURLConnection>()

                val jobs = sources.mapIndexed { idx, src ->
                    launch(Dispatchers.IO) {
                        var conn: HttpURLConnection? = null
                        try {
                            conn = (src.url.openConnection() as HttpURLConnection).apply {
                                connectTimeout = 10_000
                                readTimeout = 120_000
                                requestMethod = "GET"
                                instanceFollowRedirects = true
                                setRequestProperty("User-Agent", "MiniCPMV-demo/1.0")
                                if (resumeFrom > 0) {
                                    setRequestProperty("Range", "bytes=$resumeFrom-")
                                }
                            }
                            openConns[idx] = conn
                            Log.i(TAG, "race[$idx] ${src.label} -> ${src.url} start")

                            val code = conn.responseCode

                            // 416 == server thinks we already have everything.
                            // Treat as a valid winner with len=-1 so the body
                            // loop short-circuits and just finalises .tmp.
                            if (code == 416) {
                                val totalLen = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                                if (totalLen != null && tmpFile.length() == totalLen) {
                                    if (winnerSlot.complete(RaceWinner(idx, src.label, conn!!, 416, -1L))) return@launch
                                    conn.disconnect()
                                    return@launch
                                }
                                throw RuntimeException("$src.label: HTTP 416 but local .tmp size ${tmpFile.length()} != server $totalLen")
                            }

                            if (code !in 200..206) {
                                throw RuntimeException("${src.label}: HTTP $code")
                            }
                            val contentLen = conn.contentLength.toLong()
                            if (contentLen <= 0L) {
                                // Some mirrors return chunked-encoded responses
                                // without Content-Length. Still acceptable, the
                                // streaming loop just won't show a percentage.
                                Log.w(TAG, "race[$idx] ${src.label}: headers OK but no Content-Length")
                            }
                            Log.i(TAG, "race[$idx] ${src.label} headers OK: code=$code len=$contentLen")

                            if (!winnerSlot.complete(RaceWinner(idx, src.label, conn!!, code, contentLen))) {
                                // Lost the race - drop the connection so the
                                // server-side socket is closed promptly.
                                conn.disconnect()
                            }
                        } catch (e: CancellationException) {
                            conn?.disconnect()
                            throw e
                        } catch (e: Throwable) {
                            try { conn?.disconnect() } catch (_: Throwable) {}
                            val fc = failedCount.incrementAndGet()
                            Log.w(TAG, "race[$idx] ${src.label} failed (${fc}/${totalSources}): ${e.message}")
                            if (fc >= totalSources && !winnerSlot.isCompleted) {
                                winnerSlot.completeExceptionally(
                                    RuntimeException("$fileName: all $totalSources sources failed")
                                )
                            }
                        }
                    }
                }

                val winner = winnerSlot.await()
                onProgress("[${winner.source}] 胜出，开始下载 $fileName...")
                Log.i(TAG, "race winner for $fileName: idx=${winner.index} source=${winner.source} code=${winner.responseCode}")

                // Cancel everyone else first so their sockets stop pulling
                // bytes before we start streaming the winner.
                jobs.forEachIndexed { idx, job ->
                    if (idx != winner.index) {
                        openConns[idx]?.disconnect()
                        job.cancel()
                    }
                }

                streamWinnerToDisk(winner, tmpFile, targetFile, fileName, expectedMd5, resumeFrom, onProgress)
            }
        }

        /**
         * Streams a successful [winner] connection body into [tmpFile], then
         * promotes to [targetFile] and verifies MD5. Carries the resume
         * semantics from [downloadFile] (206 -> append, 200 -> truncate, 416
         * -> finalise as-is) so race winners are observationally equivalent
         * to single-source downloads.
         */
        private fun streamWinnerToDisk(
            winner: RaceWinner,
            tmpFile: File,
            targetFile: File,
            fileName: String,
            expectedMd5: String?,
            resumeFrom: Long,
            onProgress: (String) -> Unit,
        ) {
            val source = winner.source
            val conn = winner.conn
            try {
                if (winner.responseCode == 416) {
                    Log.i(TAG, "$fileName: server reports complete (HTTP 416 via race)")
                } else {
                    val acceptedResume = winner.responseCode == HttpURLConnection.HTTP_PARTIAL // 206
                    val effectiveStart = if (acceptedResume) resumeFrom else 0L
                    val totalSize = if (winner.contentLength > 0) winner.contentLength + effectiveStart else -1L

                    if (winner.responseCode == HttpURLConnection.HTTP_OK && resumeFrom > 0) {
                        Log.w(TAG, "$fileName: server returned 200 instead of 206; restarting from 0")
                        if (tmpFile.exists()) tmpFile.delete()
                    }

                    conn.inputStream.use { input ->
                        FileOutputStream(tmpFile, acceptedResume && resumeFrom > 0).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var totalRead = effectiveStart
                            var lastProgressTime = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                totalRead += read
                                val now = System.currentTimeMillis()
                                if (now - lastProgressTime > 500) {
                                    lastProgressTime = now
                                    val msg = if (totalSize > 0) {
                                        val pct = totalRead * 100 / totalSize
                                        val mb = totalRead / (1024 * 1024)
                                        val totalMb = totalSize / (1024 * 1024)
                                        "$fileName: $pct% ($mb/$totalMb MB) [$source]"
                                    } else {
                                        val mb = totalRead / (1024 * 1024)
                                        "$fileName: $mb MB [$source]"
                                    }
                                    onProgress(msg)
                                }
                            }
                            output.flush()
                            try {
                                output.fd.sync()
                            } catch (e: Throwable) {
                                Log.w(TAG, "fsync failed for $fileName (continuing): ${e.message}")
                            }
                        }
                    }
                }

                if (targetFile.exists()) targetFile.delete()
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }

                if (expectedMd5 != null) {
                    onProgress("[$source] 校验 $fileName MD5...")
                    val actual = computeMd5(targetFile)
                    if (!actual.equals(expectedMd5, ignoreCase = true)) {
                        Log.e(TAG, "$fileName MD5 mismatch: expected $expectedMd5, got $actual")
                        targetFile.delete()
                        if (tmpFile.exists()) tmpFile.delete()
                        throw RuntimeException(
                            "$fileName MD5 校验失败 (期望 $expectedMd5, 实际 $actual)，文件已删除，请重试"
                        )
                    }
                    Log.i(TAG, "$fileName MD5 OK ($actual)")
                }

                onProgress("$fileName 下载完成 (${targetFile.length() / (1024 * 1024)} MB)")
                Log.i(TAG, "$fileName saved to ${targetFile.absolutePath} (winner=$source)")
            } finally {
                try { conn.disconnect() } catch (_: Throwable) {}
            }
        }

        // Downloads a single file with HTTP Range-based resume. The on-disk
        // staging file is `<fileName>.tmp`; if it already exists from a prior
        // (interrupted) attempt we re-issue the request with `Range: bytes=N-`
        // so the server only sends the missing tail. The .tmp file is
        // deliberately preserved across cancellations / process death so the
        // user can resume seamlessly the next time `downloadModels` runs.
        //
        // Server response handling:
        //   - 200 OK  : server ignored Range (or .tmp was empty) -> overwrite from 0
        //   - 206 Partial Content : append missing tail to existing .tmp
        //   - 416 Range Not Satisfiable : .tmp is already the full file -> finalize
        private fun downloadFile(
            dir: File,
            fileName: String,
            url: URL,
            source: String,
            expectedMd5: String?,
            onProgress: (String) -> Unit
        ) {
            val targetFile = File(dir, fileName)
            val tmpFile = File(dir, "$fileName.tmp")

            // Fast path: if the file is already on disk and its hash matches,
            // skip re-downloading. Saves 500MB-1GB on app reinstall / dev
            // iteration when the previous download is still valid.
            if (targetFile.exists() && expectedMd5 != null) {
                onProgress("[$source] 校验已存在的 $fileName ...")
                val actual = computeMd5(targetFile)
                if (actual.equals(expectedMd5, ignoreCase = true)) {
                    onProgress("$fileName 已就绪 (MD5 校验通过)")
                    Log.i(TAG, "$fileName already present and MD5 matches, skipping download")
                    // Stale .tmp from an even older interrupted attempt - drop it.
                    if (tmpFile.exists()) tmpFile.delete()
                    return
                }
                Log.w(TAG, "$fileName already present but MD5 mismatch (got $actual, want $expectedMd5); re-downloading")
                targetFile.delete()
                // The cached .tmp (if any) belonged to a previous, mismatching
                // version; better to start clean.
                if (tmpFile.exists()) tmpFile.delete()
            }

            val resumeFrom = if (tmpFile.exists()) tmpFile.length() else 0L
            if (resumeFrom > 0) {
                val mb = resumeFrom / (1024 * 1024)
                onProgress("[$source] 续传 $fileName，已下 ${mb} MB...")
                Log.i(TAG, "Resuming $fileName from $resumeFrom bytes ($source: $url)")
            } else {
                onProgress("[$source] 下载 $fileName...")
                Log.i(TAG, "Downloading $fileName from $source: $url")
            }

            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 120000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MiniCPMV-demo/1.0")
                if (resumeFrom > 0) {
                    setRequestProperty("Range", "bytes=$resumeFrom-")
                }
            }

            try {
                val responseCode = conn.responseCode

                // 416: server thinks we already have everything. Verify size
                // matches Content-Range total before treating .tmp as complete.
                if (responseCode == 416 /* RANGE_NOT_SATISFIABLE */) {
                    val totalLen = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                    if (totalLen != null && tmpFile.length() == totalLen) {
                        Log.i(TAG, "$fileName: server reports complete (HTTP 416, size=$totalLen)")
                    } else {
                        // .tmp is somehow larger than server's file (different
                        // mirror? truncated download earlier?). Reset.
                        Log.w(TAG, "$fileName: HTTP 416 but local size ${tmpFile.length()} != server $totalLen; restarting")
                        tmpFile.delete()
                        throw RuntimeException("$source 返回 416 但本地缓存大小异常，请重试")
                    }
                } else {
                    val acceptedResume = responseCode == HttpURLConnection.HTTP_PARTIAL // 206
                    val isOk = responseCode == HttpURLConnection.HTTP_OK // 200
                    if (!acceptedResume && !isOk) {
                        throw RuntimeException("$source returned $responseCode for $fileName")
                    }

                    // 200 means the server didn't honor Range - either we asked
                    // for bytes=0- (then 200/206 are equivalent), or the server
                    // doesn't support ranges. Either way, restart from 0.
                    if (isOk && resumeFrom > 0) {
                        Log.w(TAG, "$fileName: server returned 200 instead of 206; restarting from 0")
                        if (tmpFile.exists()) tmpFile.delete()
                    }

                    val effectiveStart = if (acceptedResume) resumeFrom else 0L
                    val remaining = conn.contentLength.toLong() // -1 if unknown
                    val totalSize = if (remaining > 0) remaining + effectiveStart else -1L

                    conn.inputStream.use { input ->
                        // append=true only when we actually got 206 and are
                        // continuing the existing .tmp; otherwise truncate.
                        FileOutputStream(tmpFile, acceptedResume && resumeFrom > 0).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var totalRead = effectiveStart
                            var lastProgressTime = 0L

                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                totalRead += read

                                val now = System.currentTimeMillis()
                                if (now - lastProgressTime > 500) {
                                    lastProgressTime = now
                                    val progress = if (totalSize > 0) {
                                        val pct = totalRead * 100 / totalSize
                                        val mb = totalRead / (1024 * 1024)
                                        val totalMb = totalSize / (1024 * 1024)
                                        "$fileName: $pct% ($mb/$totalMb MB)"
                                    } else {
                                        val mb = totalRead / (1024 * 1024)
                                        "$fileName: $mb MB downloaded"
                                    }
                                    onProgress(progress)
                                }
                            }
                            output.flush()
                            // fd.sync() ensures bytes hit the storage device
                            // before we trust the .tmp size for the next
                            // resume attempt. It's cheap relative to a 1GB
                            // download but, on some Android devices/FS
                            // combinations, can throw SyncFailedException -
                            // wrap it so a fsync hiccup doesn't fail an
                            // otherwise-fine download.
                            try {
                                output.fd.sync()
                            } catch (e: Throwable) {
                                Log.w(TAG, "fsync failed for $fileName (continuing): ${e.message}")
                            }
                        }
                    }
                }

                // Promote .tmp -> final.
                if (targetFile.exists()) targetFile.delete()
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }

                if (expectedMd5 != null) {
                    onProgress("[$source] 校验 $fileName MD5...")
                    val actual = computeMd5(targetFile)
                    if (!actual.equals(expectedMd5, ignoreCase = true)) {
                        Log.e(TAG, "$fileName MD5 mismatch: expected $expectedMd5, got $actual")
                        // Bad payload -> drop both the final and any (stale)
                        // .tmp so the next retry restarts cleanly.
                        targetFile.delete()
                        if (tmpFile.exists()) tmpFile.delete()
                        throw RuntimeException(
                            "$fileName MD5 校验失败 (期望 $expectedMd5, 实际 $actual)，文件已删除，请重试"
                        )
                    }
                    Log.i(TAG, "$fileName MD5 OK ($actual)")
                }

                onProgress("$fileName 下载完成 (${targetFile.length() / (1024 * 1024)} MB)")
                Log.i(TAG, "$fileName saved to ${targetFile.absolutePath}")
            } finally {
                conn.disconnect()
                // Note: we deliberately do NOT delete tmpFile in catch-all -
                // a transient network error / process kill should leave the
                // partial bytes on disk so the next call can resume.
            }
        }

        // Parse the "*/<total>" suffix out of a Content-Range header.
        // Returns null if header is missing or malformed.
        private fun parseContentRangeTotal(header: String?): Long? {
            if (header.isNullOrEmpty()) return null
            val slash = header.lastIndexOf('/')
            if (slash < 0 || slash == header.length - 1) return null
            return header.substring(slash + 1).trim().toLongOrNull()
        }

        // Streaming MD5 over a file. Buffer size kept small enough to avoid
        // memory pressure on cheaper devices while still being decently fast
        // (~150MB/s on the test phone for the 1GB mmproj).
        private fun computeMd5(file: File): String {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    @Volatile
    private var _mmprojLoaded = false

    @ExperimentalCoroutinesApi
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    private val _state = MutableStateFlow<LlamaState>(LlamaState.Uninitialized)
    val state: StateFlow<LlamaState> = _state.asStateFlow()

    @Volatile
    private var _cancelGeneration = false

    @Volatile
    private var _readyForSystemPrompt = false

    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    // image_max_slice_nums: 1..9 (or -1 for model default).  See JNI for
    // the semantics; the upper layer should pass MIN_IMAGE_SLICE..MAX_IMAGE_SLICE.
    private external fun loadMmproj(mmprojPath: String, imageMaxSliceNums: Int): Int
    // Native counterpart of [setImageMaxSliceNums].  Renamed in JNI as
    // setImageMaxSliceNumsNative to avoid the name collision with the
    // public suspend wrapper above.
    private external fun setImageMaxSliceNumsNative(n: Int)
    // Pushes the MiniCPM-V family version (5 / 46 / 100045 / ...) down to
    // native so prepare()'s n_ctx selection and the assistant-turn prefix
    // logic see the right value.  Required since upstream master mtmd
    // dropped mtmd_get_minicpmv_version().  See LlamaEngine.loadModel.
    private external fun setMinicpmvVersionNative(version: Int)
    // 0 if no mmproj is loaded.  46 / 460 / 461 = MiniCPM-V-4.6 family.
    // Used by [isVideoUnderstandingSupported] to gate the video path.
    private external fun getMinicpmvVersionNative(): Int
    private external fun prepare(): Int
    private external fun systemInfo(): String
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun prefillImage(imageData: ByteArray, imageSize: Int): Int
    private external fun fullReset()
    private external fun nativeCancelGeneration()
    private external fun unload()
    private external fun shutdown()

    init {
        llamaScope.launch {
            try {
                check(_state.value is LlamaState.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = LlamaState.Initializing
                Log.i(TAG, "Loading native library...")
                Log.i(TAG, CpuFeatures.summary())

                val variant = CpuFeatures.bestGgmlCpuVariant()
                if (variant != null) {
                    try {
                        Log.i(TAG, "Pre-loading optimised ggml-cpu ($variant)")
                        System.loadLibrary("ggml-cpu-$variant")
                        Log.i(TAG, "Optimised ggml-cpu ($variant) loaded successfully")
                    } catch (e: UnsatisfiedLinkError) {
                        Log.w(TAG, "Optimised ggml-cpu-$variant not available, using baseline", e)
                    }
                }

                System.loadLibrary("minicpm_v_demo")
                init(nativeLibDir)
                _state.value = LlamaState.Initialized
                Log.i(TAG, "Native library loaded! System info: \n${systemInfo()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                _state.value = LlamaState.Error(e)
                throw e
            }
        }
    }

    suspend fun loadModel(pathToModel: String, pathToMmproj: String? = null) =
        withContext(llamaDispatcher) {
            check(_state.value is LlamaState.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }
            try {
                Log.i(TAG, "Checking access to model file... \n$pathToModel")
                File(pathToModel).let {
                    require(it.exists()) { "File not found: $pathToModel" }
                    require(it.isFile) { "Not a valid file: $pathToModel" }
                    require(it.canRead()) { "Cannot read file: $pathToModel" }
                }

                Log.i(TAG, "Loading model... \n$pathToModel")
                _readyForSystemPrompt = false
                _state.value = LlamaState.LoadingModel
                load(pathToModel).let {
                    if (it != 0) throw RuntimeException("Failed to load model (code: $it)")
                }

                if (pathToMmproj != null) {
                    Log.i(TAG, "Checking access to mmproj file... \n$pathToMmproj")
                    File(pathToMmproj).let {
                        require(it.exists()) { "mmproj file not found: $pathToMmproj" }
                        require(it.isFile) { "Not a valid mmproj file: $pathToMmproj" }
                        require(it.canRead()) { "Cannot read mmproj file: $pathToMmproj" }
                    }

                    val sliceCap = getImageMaxSliceNums(context)
                    Log.i(TAG, "Loading mmproj (image_max_slice_nums=$sliceCap)... \n$pathToMmproj")
                    loadMmproj(pathToMmproj, sliceCap).let {
                        if (it != 0) {
                            Log.w(TAG, "Failed to load mmproj (code: $it), continuing without vision support")
                        } else {
                            _mmprojLoaded = true
                            // Upstream master mtmd dropped mtmd_get_minicpmv_version, so the
                            // native side can no longer auto-detect which MiniCPM-V family
                            // an mmproj belongs to.  Push the version down from Kotlin based
                            // on the selected ModelInfo id; native uses this to (a) flip
                            // n_ctx to V46_CONTEXT_SIZE on prepare() and (b) pick the right
                            // assistant-turn prefix.  Mirrors iOS opt-r1, which routes the
                            // same hint through the MBMtmd bridge / MTMDParams.
                            val mv = inferMinicpmvVersion(getSelectedModel(context))
                            setMinicpmvVersionNative(mv)
                            Log.i(TAG, "mmproj loaded successfully! minicpmv_version=$mv")
                        }
                    }
                }

                prepare().let {
                    if (it != 0) throw RuntimeException("Failed to prepare resources (code: $it)")
                }
                Log.i(TAG, "Model loaded!")
                _readyForSystemPrompt = true
                _cancelGeneration = false
                _state.value = LlamaState.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, (e.message ?: "Error loading model") + "\n" + pathToModel, e)
                _state.value = LlamaState.Error(e)
                throw e
            }
        }

    val isVisionSupported: Boolean get() = _mmprojLoaded

    // Live update of the per-image slice cap.  Persists the new value to
    // SharedPreferences so the next mmproj reload picks it up too, and
    // (if mmproj is currently loaded) immediately patches the in-memory
    // clip context so the *next* image picked uses the new cap without
    // needing a model reload.  Cheap (no allocation, no warmup) - safe to
    // wire to a continuous slider.
    suspend fun setImageMaxSliceNums(n: Int) = withContext(llamaDispatcher) {
        val clamped = n.coerceIn(MIN_IMAGE_SLICE, MAX_IMAGE_SLICE)
        setImageMaxSliceNumsPref(context, clamped)
        if (_mmprojLoaded) {
            Log.i(TAG, "Live-updating image_max_slice_nums = $clamped")
            setImageMaxSliceNumsNative(clamped)
        } else {
            Log.i(TAG, "image_max_slice_nums = $clamped persisted; will apply on next mmproj load")
        }
    }

    suspend fun setSystemPrompt(prompt: String) =
        withContext(llamaDispatcher) {
            require(prompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_readyForSystemPrompt) { "System prompt must be set RIGHT AFTER model loaded!" }
            check(_state.value is LlamaState.ModelReady) {
                "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
            }

            Log.i(TAG, "Sending system prompt...")
            _readyForSystemPrompt = false
            _state.value = LlamaState.ProcessingSystemPrompt
            processSystemPrompt(prompt).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to process system prompt: $result").also {
                        _state.value = LlamaState.Error(it)
                        throw it
                    }
                }
            }
            Log.i(TAG, "System prompt processed! Awaiting user prompt...")
            _state.value = LlamaState.ModelReady
        }

    suspend fun prefillImage(imageData: ByteArray) =
        withContext(llamaDispatcher) {
            check(_mmprojLoaded) { "Vision model not loaded!" }
            check(_state.value is LlamaState.ModelReady) {
                "Cannot prefill image in ${_state.value.javaClass.simpleName}!"
            }

            Log.i(TAG, "Prefilling image...")
            _state.value = LlamaState.PrefillingImage
            val result = prefillImage(imageData, imageData.size)
            if (result != 0) {
                _state.value = LlamaState.ModelReady
                throw RuntimeException("Failed to prefill image (code: $result)")
            }
            Log.i(TAG, "Image prefilled!")
            _state.value = LlamaState.ModelReady
        }

    /**
     * True iff the currently loaded mmproj advertises a MiniCPM-V-4.6
     * family clip (46 / 460 / 461).  Earlier models share the same image
     * code path but the iOS demo also restricts video understanding to
     * V-4.6 because the perceiver token count + extended `n_ctx=8192`
     * combine to make multi-frame prefill cheap enough to be usable.
     * The Android backend mirrors that gate by reading
     * mtmd_get_minicpmv_version() back through getMinicpmvVersionNative.
     */
    val isVideoUnderstandingSupported: Boolean
        get() {
            if (!_mmprojLoaded) return false
            val v = try { getMinicpmvVersionNative() } catch (_: Throwable) { 0 }
            return v == 46 || v == 460 || v == 461
        }

    /**
     * Prefills a sequence of video frames into the model.  Iterates over
     * [frames] and feeds each one through the same native path as
     * [prefillImage] (which is exactly what iOS does via repeated
     * `mtmd_ios_prefill_frame`: the C++ implementation of `prefill_frame`
     * and `prefill_image` in `mtmd-ios.cpp` are byte-for-byte identical
     * apart from the error-log prefix).
     *
     * Side effects to match iOS [MBHomeViewController+CaptureVideo]:
     *  - Slice cap is temporarily forced to 1 for the duration of the
     *    video so the per-frame ViT cost stays manageable; the user's
     *    chat-page slider value is restored on exit (also on failure).
     *    The persisted SharedPreference is NOT touched - the slider
     *    keeps showing the user's chosen value.
     *  - State is parked in [LlamaState.PrefillingImage] across all
     *    frames so the UI shows a single "prefilling" badge rather
     *    than flickering between frames.
     *
     * @param onProgress invoked on this engine's serial dispatcher
     * after each successfully-prefilled frame.  Caller should
     * `withContext(Dispatchers.Main)` before touching UI.
     */
    suspend fun prefillVideoFrames(
        frames: List<ByteArray>,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> }
    ) = withContext(llamaDispatcher) {
        check(_mmprojLoaded) { "Vision model not loaded!" }
        check(isVideoUnderstandingSupported) {
            "Video understanding only supported on MiniCPM-V-4.6 (current version=" +
                "${getMinicpmvVersionNative()})"
        }
        check(_state.value is LlamaState.ModelReady) {
            "Cannot prefill video in ${_state.value.javaClass.simpleName}!"
        }
        require(frames.isNotEmpty()) { "No frames to prefill" }

        val savedSliceCap = getImageMaxSliceNums(context)
        val needSliceOverride = savedSliceCap > 1

        Log.i(TAG, "Prefilling ${frames.size} video frames (savedSlice=$savedSliceCap, override=$needSliceOverride)")
        _state.value = LlamaState.PrefillingImage
        try {
            if (needSliceOverride) {
                Log.i(TAG, "Temporarily forcing image_max_slice_nums=1 for video frames")
                setImageMaxSliceNumsNative(1)
            }
            for ((idx, frame) in frames.withIndex()) {
                val rc = prefillImage(frame, frame.size)
                if (rc != 0) {
                    throw RuntimeException(
                        "Failed to prefill video frame ${idx + 1}/${frames.size} (code: $rc)"
                    )
                }
                onProgress(idx + 1, frames.size)
            }
            Log.i(TAG, "Video frames prefilled successfully")
        } finally {
            if (needSliceOverride) {
                Log.i(TAG, "Restoring image_max_slice_nums=$savedSliceCap after video")
                setImageMaxSliceNumsNative(savedSliceCap)
            }
            _state.value = LlamaState.ModelReady
        }
    }

    suspend fun clearContext() =
        withContext(llamaDispatcher) {
            check(_state.value is LlamaState.ModelReady) {
                "Cannot clear context in ${_state.value.javaClass.simpleName}"
            }
            fullReset()
            _readyForSystemPrompt = true
            Log.i(TAG, "Context fully reset - context recreated, ready for new conversation")
        }

    fun sendUserPrompt(
        message: String,
        predictLength: Int = DEFAULT_PREDICT_LENGTH
    ): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt must not be empty!" }
        check(_state.value is LlamaState.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        try {
            _cancelGeneration = false
            Log.i(TAG, "Sending user prompt...")
            _readyForSystemPrompt = false
            _state.value = LlamaState.ProcessingUserPrompt

            processUserPrompt(message, predictLength).let { result ->
                if (result != 0) {
                    Log.e(TAG, "Failed to process user prompt: $result")
                    return@flow
                }
            }

            Log.i(TAG, "User prompt processed. Generating assistant prompt...")
            _state.value = LlamaState.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { utf8token ->
                    if (utf8token.isNotEmpty()) emit(utf8token)
                } ?: break
            }
            if (_cancelGeneration) {
                Log.i(TAG, "Assistant generation aborted per requested.")
            } else {
                Log.i(TAG, "Assistant generation complete. Awaiting user prompt...")
            }
            _state.value = LlamaState.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Assistant generation's flow collection cancelled.")
            _state.value = LlamaState.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = LlamaState.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    fun cancelGeneration() {
        _cancelGeneration = true
        llamaScope.launch {
            nativeCancelGeneration()
        }
    }

    suspend fun unloadModel() = withContext(llamaDispatcher) {
        if (_state.value is LlamaState.ModelReady) {
            Log.i(TAG, "Unloading model...")
            _readyForSystemPrompt = false
            _mmprojLoaded = false
            _state.value = LlamaState.UnloadingModel
            unload()
            _state.value = LlamaState.Initialized
            Log.i(TAG, "Model unloaded")
        }
    }

    fun resetToInitialized() {
        _mmprojLoaded = false
        _readyForSystemPrompt = false
        _cancelGeneration = false
        _state.value = LlamaState.Initialized
    }

    fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is LlamaState.ModelReady -> {
                    Log.i(TAG, "Unloading model and free resources...")
                    _readyForSystemPrompt = false
                    _mmprojLoaded = false
                    _state.value = LlamaState.UnloadingModel
                    unload()
                    _state.value = LlamaState.Initialized
                    Log.i(TAG, "Model unloaded!")
                }
                is LlamaState.Error -> {
                    Log.i(TAG, "Resetting error states...")
                    _mmprojLoaded = false
                    _state.value = LlamaState.Initialized
                }
                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            _mmprojLoaded = false
            when (_state.value) {
                is LlamaState.Uninitialized -> {}
                is LlamaState.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }

}
