package com.example.minicpm_v_demo

/**
 * Metadata for a downloadable MiniCPM-V variant.
 *
 * Source-of-truth for download URLs: HuggingFace ([hfRepo]) and ModelScope
 * ([msRepo]) are both registered for every model, and `LlamaEngine`'s
 * downloader runs them in a **race**: both GETs are launched in parallel and
 * the first one to deliver HTTP headers wins; the loser's connection is
 * closed immediately. Mirrors iOS opt-r1's `MBModelDownloadHelperV2.downloadV2`
 * so all three demos consume the same upstream artifacts via the same
 * topology (no OBS direct link).
 *
 * The previously used [directGgufUrl] / [directMmprojUrl] knobs are retained
 * as an optional escape hatch — if a model ever needs a temporary mirror
 * during a TestFlight period they can be set and the racer treats them as a
 * third source — but for the current release lineup they stay null.
 *
 * [ggufRemoteName] and [mmprojRemoteName] decouple the on-disk filename from
 * the path segment on HF/MS. MiniCPM-V-4.6 stores mmproj as
 * `mmproj-model-merger-f16.gguf` locally (to avoid colliding with stale
 * pre-release files left in the per-model directory) but the upstream HF/MS
 * object is `mmproj-model-f16.gguf`.
 *
 * When [ggufMd5] / [mmprojMd5] are provided, the downloader verifies the
 * downloaded file's MD5 and deletes + raises on mismatch. Mirrors iOS demo
 * `MBV4ModelDownloadManager` / `MBV46ModelDownloadManager`.
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val ggufFileName: String,
    val mmprojFileName: String,
    val hfRepo: String? = null,
    val msRepo: String? = null,
    val hfBranch: String = "main",
    val msBranch: String = "master",
    val ggufRemoteName: String? = null,
    val mmprojRemoteName: String? = null,
    val directGgufUrl: String? = null,
    val directMmprojUrl: String? = null,
    val ggufMd5: String? = null,
    val mmprojMd5: String? = null
) {
    /** Path segment to request on HF/MS for the gguf, falling back to local name. */
    val ggufRemotePath: String
        get() = ggufRemoteName ?: ggufFileName

    /** Path segment to request on HF/MS for the mmproj, falling back to local name. */
    val mmprojRemotePath: String
        get() = mmprojRemoteName ?: mmprojFileName

    /** Whether the model registers a direct (non-HF/MS) mirror URL. */
    val hasDirectUrls: Boolean
        get() = !directGgufUrl.isNullOrBlank() && !directMmprojUrl.isNullOrBlank()

    /** Whether the model registers HF + MS repos for racing. */
    val hasHfMsSources: Boolean
        get() = !hfRepo.isNullOrBlank() && !msRepo.isNullOrBlank()

    companion object {
        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                id = "minicpm-v-4",
                displayName = "MiniCPM-V-4 (Q4_K_M)",
                description = "轻量级多模态模型，支持图文理解 (4.1B)",
                ggufFileName = "ggml-model-Q4_K_M.gguf",
                mmprojFileName = "mmproj-model-f16.gguf",
                hfRepo = "openbmb/MiniCPM-V-4-gguf",
                msRepo = "OpenBMB/MiniCPM-V-4-gguf"
                // No MD5 here on purpose: HF / ModelScope serve via git-LFS
                // which already provides hash-based integrity checks.
            ),
            // MiniCPM-V-4.6: HF (primary) + ModelScope (backup) racing, aligned
            // with iOS opt-r1's MBV46ModelDownloadManager. Both the local and
            // the remote file names are now the upstream-canonical
            // `mmproj-model-f16.gguf`: upstream master mtmd accepts the
            // OpenBMB-published `minicpmv4_6` projector type natively, so the
            // previous demo-private "merger" re-conversion is no longer
            // required. STALE_MMPROJ_NAMES handles cleanup of the legacy
            // `*-merger-*` file from earlier demo builds.
            ModelInfo(
                id = "minicpm-v-4_6-instruct",
                displayName = "MiniCPM-V-4.6 (Q4_K_M)",
                description = "新一代多模态模型，支持图文理解 (1.2B)",
                ggufFileName = "MiniCPM-V-4_6-Q4_K_M.gguf",
                mmprojFileName = "mmproj-model-f16.gguf",
                hfRepo = "openbmb/MiniCPM-V-4.6-gguf",
                msRepo = "OpenBMB/MiniCPM-V-4.6-gguf",
                ggufMd5 = "fd778481dd56b6036dd8f9cf7c1519cf",
                // OpenBMB original (projector_type=minicpmv4_6), as served by
                // both HuggingFace and ModelScope. Replaces the previous
                // OBS-only `aad0d36e..` merger-converted variant.
                mmprojMd5 = "54aea6e04d752f47309a48f12795a1a3"
            )
        )

        val DEFAULT_MODEL = AVAILABLE_MODELS.first()
    }
}
