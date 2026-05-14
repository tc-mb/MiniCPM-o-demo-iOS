// Mirror of Android ModelInfo.kt (opt-r1): HF + ModelScope are both
// registered for every model and the downloader runs them in a race.
//
// `directGgufUrl` / `directMmprojUrl` remain as an optional escape hatch for
// temporary mirrors during a TestFlight period; for the current release
// lineup they stay null and the V-4.6 release is fetched from openbmb/* on
// HF and OpenBMB/* on ModelScope, just like the iOS opt-r1 demo.
//
// `ggufRemoteName` / `mmprojRemoteName` decouple the on-disk filename from
// the path segment served by HF/MS. For instance MiniCPM-V-4.6 keeps mmproj
// locally as `mmproj-model-merger-f16.gguf` (to avoid colliding with stale
// pre-release files in the per-model directory) but the upstream object is
// `mmproj-model-f16.gguf`.

export class ModelInfo {
  readonly id: string;
  readonly displayName: string;
  readonly description: string;
  readonly ggufFileName: string;
  readonly mmprojFileName: string;
  readonly hfRepo: string | null;
  readonly msRepo: string | null;
  readonly hfBranch: string;
  readonly msBranch: string;
  readonly ggufRemoteName: string | null;
  readonly mmprojRemoteName: string | null;
  readonly directGgufUrl: string | null;
  readonly directMmprojUrl: string | null;
  readonly ggufMd5: string | null;
  readonly mmprojMd5: string | null;

  constructor(
    id: string,
    displayName: string,
    description: string,
    ggufFileName: string,
    mmprojFileName: string,
    hfRepo: string | null = null,
    msRepo: string | null = null,
    hfBranch: string = 'main',
    msBranch: string = 'master',
    ggufRemoteName: string | null = null,
    mmprojRemoteName: string | null = null,
    directGgufUrl: string | null = null,
    directMmprojUrl: string | null = null,
    ggufMd5: string | null = null,
    mmprojMd5: string | null = null
  ) {
    this.id = id;
    this.displayName = displayName;
    this.description = description;
    this.ggufFileName = ggufFileName;
    this.mmprojFileName = mmprojFileName;
    this.hfRepo = hfRepo;
    this.msRepo = msRepo;
    this.hfBranch = hfBranch;
    this.msBranch = msBranch;
    this.ggufRemoteName = ggufRemoteName;
    this.mmprojRemoteName = mmprojRemoteName;
    this.directGgufUrl = directGgufUrl;
    this.directMmprojUrl = directMmprojUrl;
    this.ggufMd5 = ggufMd5;
    this.mmprojMd5 = mmprojMd5;
  }

  /** Path segment to request on HF/MS for the gguf, falling back to local name. */
  ggufRemotePath(): string {
    return this.ggufRemoteName ?? this.ggufFileName;
  }

  /** Path segment to request on HF/MS for the mmproj, falling back to local name. */
  mmprojRemotePath(): string {
    return this.mmprojRemoteName ?? this.mmprojFileName;
  }

  hasDirectUrls(): boolean {
    return !!this.directGgufUrl && !!this.directMmprojUrl;
  }

  hasHfMsSources(): boolean {
    return !!this.hfRepo && !!this.msRepo;
  }
}

export const AVAILABLE_MODELS: ModelInfo[] = [
  new ModelInfo(
    'minicpm-v-4',
    'MiniCPM-V-4 (Q4_K_M)',
    '轻量级多模态模型，支持图文理解 (4.1B)',
    'ggml-model-Q4_K_M.gguf',
    'mmproj-model-f16.gguf',
    'openbmb/MiniCPM-V-4-gguf',
    'OpenBMB/MiniCPM-V-4-gguf'
    // No MD5 here on purpose: HF / ModelScope serve via git-LFS which already
    // provides hash-based integrity checks.
  ),
  // MiniCPM-V-4.6 (release / instruct): HF (primary) + ModelScope (backup)
  // racing, aligned with iOS opt-r1's MBV46ModelDownloadManager. Upstream
  // master mtmd accepts the OpenBMB-published `minicpmv4_6` projector type
  // natively, so we no longer need the demo-private "merger" re-conversion;
  // both the local and the remote file names are the upstream-canonical
  // `mmproj-model-f16.gguf`. The LEGACY_FILE_RENAMES below maps the
  // historical demo names (including v1.3's `*-merger-*`) onto this canonical
  // name so an upgrading user's bytes are either accepted (MD5 OK) or cleanly
  // re-downloaded (MD5 mismatch).
  new ModelInfo(
    'minicpm-v-4_6-instruct',
    'MiniCPM-V-4.6 (Q4_K_M)',
    '新一代多模态模型，支持图文理解 (1.2B)',
    'MiniCPM-V-4_6-Q4_K_M.gguf',
    'mmproj-model-f16.gguf',
    'openbmb/MiniCPM-V-4.6-gguf',
    'OpenBMB/MiniCPM-V-4.6-gguf',
    'main',
    'master',
    null,
    null,
    null,
    null,
    'fd778481dd56b6036dd8f9cf7c1519cf',
    // OpenBMB original (projector_type=minicpmv4_6), as served by both
    // HuggingFace and ModelScope. Replaces the previous OBS-only
    // `aad0d36e..` merger-converted variant.
    '54aea6e04d752f47309a48f12795a1a3'
  ),
];

export const DEFAULT_MODEL: ModelInfo = AVAILABLE_MODELS[0];

// Maps any historical filename (left) to the current canonical name (right)
// for files that may already exist in a user's per-model subdirectory.
// Each entry is consulted on app start; if a left-hand file is found it is
// renamed to the right-hand one. MD5 checks then either accept it or trigger
// a re-download. Keep in sync with ModelInfo.kt LEGACY_FILE_RENAMES.
export interface LegacyRename {
  oldName: string;
  newName: string;
}

export const LEGACY_FILE_RENAMES: Record<string, LegacyRename[]> = {
  'minicpm-v-4_6-instruct': [
    { oldName: 'MiniCPM-V4.6-instruct-Q4_K_M.gguf', newName: 'MiniCPM-V-4_6-Q4_K_M.gguf' },
    { oldName: 'minicpmv46-llm-Q4_K_M.gguf',         newName: 'MiniCPM-V-4_6-Q4_K_M.gguf' },
  ],
};

// Files that may exist on disk from earlier demo builds but whose bytes are
// NOT compatible with the current master-mtmd loader (different projector
// type / a sealed pre-release revision / OBS-converted "merger" variant).
// Renaming them would silently feed bad bytes into native and break with a
// load-time error 2; we therefore delete them outright on app start so
// modelsExist() reports "missing" and the user is funnelled into the new
// HF + ModelScope race download. Mirrors Android's STALE_MMPROJ_NAMES.
export const STALE_MMPROJ_NAMES: Record<string, string[]> = {
  'minicpm-v-4_6-instruct': [
    // v1.0-v1.1 pre-release: clip.projector_type bound to the demo's
    // private clip.cpp, not loadable by master mtmd.
    'mmproj-v46-model-f16.gguf',
    // v1.3 demo: bytes converted on the Support-iOS-Demo branch (projector
    // type `merger`). Master mtmd accepts upstream OpenBMB's
    // `minicpmv4_6` projector natively, but does NOT recognise the
    // demo-private `merger` variant; the file would also fail the new
    // MD5 verification anyway.
    'mmproj-model-merger-f16.gguf',
    // Intentionally NOT listing 'mmproj-model-f16.gguf' here: that IS the
    // canonical name from v1.4 / v2.0 onwards. If a user has a stale copy
    // of an earlier OBS revision sitting under that name, the MD5 check
    // inside downloadFile will delete it and re-download cleanly.
  ],
};

export function findModelById(id: string): ModelInfo {
  for (const m of AVAILABLE_MODELS) {
    if (m.id === id) {
      return m;
    }
  }
  return DEFAULT_MODEL;
}
