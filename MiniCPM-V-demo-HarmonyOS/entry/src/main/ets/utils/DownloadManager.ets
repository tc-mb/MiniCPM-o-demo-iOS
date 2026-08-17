// Multi-source racing downloader. Mirrors the Android LlamaEngine.kt
// downloadFileMultiSource path and iOS opt-r1's MBModelDownloadHelperV2.
//
// For every model file we register every available source (HuggingFace +
// ModelScope, plus an optional direct mirror) and run the GETs in parallel.
// The first task whose @ohos.request.agent emits a non-empty `progress` is
// taken as the winner; the other tasks are immediately `stop()`'d and
// removed, and their temporary files (`*.r0.tmp`, `*.r1.tmp`, ...) are
// deleted. Only the winner's bytes ever land in the canonical target path.
//
// Why progress-based and not response-headers-based: @ohos.request.agent is
// the OS-managed background download service; it doesn't expose raw HTTP
// response headers to ArkTS. The first `progress` callback with
// `processed > 0` is the closest analog to "got the first body byte" and
// behaves equivalently for our race semantics (the slower mirror will
// stay at `processed == 0` long enough for the winner to capture the slot).
//
// Streaming-md5 and the rest of the resume/cleanup logic match the Android
// implementation byte-for-byte.

import request from '@ohos.request';
import fs from '@ohos.file.fs';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import common from '@ohos.app.ability.common';
import dataPreferences from '@ohos.data.preferences';
import hilog from '@ohos.hilog';
import { BusinessError } from '@ohos.base';
import resourceManager from '@ohos.resourceManager';

import {
  ModelInfo,
  findModelById,
  AVAILABLE_MODELS,
  DEFAULT_MODEL,
} from '../engine/ModelInfo';
import { matchExpectedFile } from '../download/Reconciler';

const DOMAIN = 0xC0DE;
const TAG = 'DownloadManager';
const PREFS_NAME = 'model_prefs';

export type ProgressListener = (msg: string) => void;

/**
 * Active task registry, keyed by file name (e.g. "model.gguf").
 * Populated by downloadFileWithSources / downloadSingleSource; consumed
 * by pauseDownloadForFile / resumeDownloadForFile / cancelDownloadForFile.
 *
 * The race downloader stores up to N tasks per file (one per source); the
 * single-source path stores 1. After race settlement only the winner
 * remains in the array (losers are removed via stopLoser).
 */
const activeTasks: Map<string, request.agent.Task[]> = new Map();

/** Pause every active task for the given file. No-op if none registered. */
export async function pauseDownloadForFile(fileName: string): Promise<void> {
  const arr = activeTasks.get(fileName);
  if (!arr || arr.length === 0) return;
  hilog.info(DOMAIN, TAG, 'pause %{public}s (%{public}d task(s))', fileName, arr.length);
  await Promise.all(arr.map((t) => t.pause().catch((e: object) => {
    hilog.warn(DOMAIN, TAG, 'pause task %{public}s failed: %{public}s', `${t.tid}`, `${e}`);
  })));
}

/** Resume every active (paused) task for the given file. */
export async function resumeDownloadForFile(fileName: string): Promise<void> {
  const arr = activeTasks.get(fileName);
  if (!arr || arr.length === 0) return;
  hilog.info(DOMAIN, TAG, 'resume %{public}s (%{public}d task(s))', fileName, arr.length);
  await Promise.all(arr.map((t) => t.resume().catch((e: object) => {
    hilog.warn(DOMAIN, TAG, 'resume task %{public}s failed: %{public}s', `${t.tid}`, `${e}`);
  })));
}

/** True iff at least one active task is registered for the given file. */
export function hasActiveTask(fileName: string): boolean {
  const arr = activeTasks.get(fileName);
  return !!arr && arr.length > 0;
}

/**
 * Diagnostic (read-only): enumerate every request.agent task this app owns
 * and return a compact, human-readable summary (count + per-task tid · state ·
 * saveas). Used to observe duplicate / orphan tasks — e.g. call before and
 * after a download cycle and confirm the count is stable. No stop / remove.
 *
 * Callable from a debug page / the test module; also logs the summary via
 * hilog so it shows up in the device log during real-device testing.
 */
export async function debugListSystemTasks(): Promise<string> {
  const lines: string[] = [];
  let tids: string[] = [];
  try {
    tids = await request.agent.search({ action: request.agent.Action.DOWNLOAD });
  } catch (e) {
    const msg = `debugListSystemTasks: search failed: ${e}`;
    hilog.warn(DOMAIN, TAG, '%{public}s', msg);
    return msg;
  }
  lines.push(`request.agent task count = ${tids.length}`);
  for (const tid of tids) {
    try {
      const info = await request.agent.show(tid);
      const saveas = info.saveas ?? '(empty)';
      const state = info.progress.state;
      lines.push(`  tid=${tid} state=0x${state.toString(16)} saveas=${saveas}`);
    } catch (e) {
      lines.push(`  tid=${tid} show failed: ${e}`);
    }
  }
  const summary = lines.join('\n');
  hilog.info(DOMAIN, TAG, 'debugListSystemTasks:\n%{public}s', summary);
  return summary;
}

/**
 * Stop + remove every active task for the given file. Does NOT delete
 * the .tmp file — that's preserved for future resume.
 */
export async function cancelDownloadForFile(fileName: string): Promise<void> {
  const arr = activeTasks.get(fileName);
  if (!arr || arr.length === 0) return;
  hilog.info(DOMAIN, TAG, 'cancel %{public}s (%{public}d task(s))', fileName, arr.length);
  await Promise.all(arr.map(async (t) => {
    try {
      await t.stop();
    } catch (_) { /* ignore */ }
    try {
      await request.agent.remove(t.tid);
    } catch (_) { /* ignore */ }
  }));
  activeTasks.delete(fileName);
}

/**
 * Result of reattach: which expected file names had surviving system tasks
 * that we re-attached to (kept alive for task.resume), whether any is
 * actively transferring, and how many stray non-matching tasks we cleaned up.
 */
export interface ReattachResult {
  matchedFileNames: string[];
  anyRunning: boolean;
  cleanedUpStrayTids: number;
}

/**
 * On app launch, enumerate every request.agent task owned by this app and
 * RE-ATTACH to any whose saveas matches one of `expectedFiles` (keeping the
 * task alive so the user can resume it via task.resume — the platform's
 * HTTP-Range resume mechanism). Stray / terminal tasks are removed.
 *
 * Why keep+resume instead of stop+remove+recreate: request.agent resume is
 * designed around a LIVE task + task.resume() (the system issues the Range
 * request internally). Re-creating a task with Config.begins on an existing
 * partial FAILS on device ("GetFd File exists" with overwrite:false; silent
 * truncation with overwrite:true) — verified on MLN-AL00. So the only way to
 * truly resume a partial is to keep the surviving task and resume it.
 *
 * Only the race WINNER survives a kill (losers are stopped during race
 * settlement, before any kill), so there is at most one task per file — no
 * winner-gate is needed, and the completed handler renames the ACTUAL saveas
 * basename (.tmp or .rN.tmp), fixing the old wrong-rename bug (#2). If two
 * tasks for the same file somehow survive (kill during race, before
 * settlement), the duplicate is stopped+removed (keep first).
 *
 * Strategy:
 *   - Matched, non-terminal task → getTask + register + re-hook callbacks
 *   - Duplicate survivor for same file → stop + remove (keep first)
 *   - Terminal task (COMPLETED/FAILED/REMOVED) → remove to free the record
 *   - Non-matching task → remove (leftover from a deleted model)
 */
export async function reattachSystemTasks(
  context: common.Context,
  dir: string,
  expectedFiles: string[],
  md5Map: Map<string, string | null>,
  onProgress: ProgressListener,
  onCompletedFile: (fileName: string) => Promise<void>,
): Promise<ReattachResult> {
  const matchedFileNames: string[] = [];
  const seen: Set<string> = new Set();
  let anyRunning = false;
  let cleanedUpStrayTids = 0;

  let tids: string[];
  try {
    const filter: request.agent.Filter = {
      action: request.agent.Action.DOWNLOAD,
    };
    tids = await request.agent.search(filter);
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'reattach: search failed: %{public}s', `${e}`);
    return { matchedFileNames, anyRunning, cleanedUpStrayTids };
  }

  hilog.info(DOMAIN, TAG, 'reattach: search returned %{public}d task(s): %{public}s',
    tids.length, tids.join(','));

  for (const tid of tids) {
    let info: request.agent.TaskInfo;
    try {
      info = await request.agent.show(tid);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'reattach: show(%{public}s) failed: %{public}s', tid, `${e}`);
      continue;
    }

    const saveas = info.saveas ?? '';
    if (saveas.length === 0) {
      hilog.warn(DOMAIN, TAG, 'reattach: tid %{public}s has empty saveas, skipping', tid);
      continue;
    }

    // Match saveas to one of our expected file names. The basename is
    // either `<fname>.tmp` (single-source) or `<fname>.r<N>.tmp` (race).
    const basename = saveas.indexOf('/') >= 0
      ? saveas.substring(saveas.lastIndexOf('/') + 1)
      : saveas;
    const matchedFname = matchExpectedFile(basename, expectedFiles);

    if (matchedFname === null) {
      // Not our task (different URL / leftover from deleted model).
      hilog.info(DOMAIN, TAG, 'reattach: tid %{public}s (saveas=%{public}s) does not match any expected file — removing',
        tid, basename);
      try {
        await request.agent.remove(tid);
        cleanedUpStrayTids++;
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'reattach: remove(%{public}s) failed: %{public}s', tid, `${e}`);
      }
      continue;
    }

    const fileName = matchedFname;
    const state = info.progress.state;

    // Terminal task (COMPLETED / FAILED / REMOVED): free the record.
    if (state === request.agent.State.COMPLETED
      || state === request.agent.State.FAILED
      || state === request.agent.State.REMOVED) {
      hilog.info(DOMAIN, TAG, 'reattach: %{public}s already terminal (state=%{public}d) — removing record',
        fileName, state);
      try { await request.agent.remove(tid); cleanedUpStrayTids++; } catch (_) { /* ignore */ }
      continue;
    }

    // Duplicate survivor for the same file (kill happened during the race,
    // before settlement). We cannot know which source won, so keep the first
    // and stop+remove the extra to avoid two completions racing on rename.
    if (seen.has(fileName)) {
      hilog.info(DOMAIN, TAG, 'reattach: duplicate survivor for %{public}s (tid=%{public}s) — stopping+removing',
        fileName, tid);
      try {
        const dup = await request.agent.getTask(context, tid);
        try { await dup.stop(); } catch (_) { /* ignore */ }
      } catch (_) { /* ignore */ }
      try { await request.agent.remove(tid); cleanedUpStrayTids++; } catch (_) { /* ignore */ }
      continue;
    }
    seen.add(fileName);

    // Surviving in-flight task from a previous session — KEEP it and re-hook
    // callbacks so the user can resume via task.resume() (the platform's
    // Range-resume). Only the winner survives, so one task per file.
    let task: request.agent.Task;
    try {
      task = await request.agent.getTask(context, tid);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'reattach: getTask(%{public}s) failed: %{public}s', tid, `${e}`);
      continue;
    }
    const actualTmpPath = `${dir}/${basename}`;
    const targetPath = `${dir}/${fileName}`;
    const md5 = md5Map.get(fileName) ?? null;
    registerTask(fileName, task);
    if (state === request.agent.State.RUNNING || state === request.agent.State.RETRYING) {
      anyRunning = true;
    }
    hilog.info(DOMAIN, TAG, 'reattach: re-attached surviving %{public}s (state=%{public}d, tid=%{public}s, tmp=%{public}s)',
      fileName, state, tid, basename);

    let lastProgressTime = 0;
    task.on('progress', (p: request.agent.Progress) => {
      const now = Date.now();
      if (now - lastProgressTime > 500) {
        lastProgressTime = now;
        const got = p.processed;
        const totalArr = p.sizes;
        const total = totalArr && totalArr.length > 0 ? totalArr[0] : -1;
        if (total > 0) {
          const pct = Math.floor(got * 100 / total);
          const mb = Math.floor(got / (1024 * 1024));
          const totalMb = Math.floor(total / (1024 * 1024));
          onProgress(`${fileName}: ${pct}% (${mb}/${totalMb} MB)`);
        } else {
          const mb = Math.floor(got / (1024 * 1024));
          onProgress(`${fileName}: ${mb} MB`);
        }
      }
    });

    task.on('completed', async () => {
      try {
        if (fs.accessSync(targetPath)) fs.unlinkSync(targetPath);
        // Rename the ACTUAL saveas file (.tmp or .rN.tmp), not a hardcoded
        // .tmp — this was bug #2 (race tasks write .rN.tmp).
        fs.renameSync(actualTmpPath, targetPath);
        if (md5) {
          onProgress(`verifying md5 ${fileName}`);
          const actual = await computeMd5(targetPath);
          if (actual.toLowerCase() !== md5.toLowerCase()) {
            hilog.error(DOMAIN, TAG, 'reattach %{public}s MD5 mismatch: expected %{public}s, got %{public}s',
              fileName, md5, actual);
            try { fs.unlinkSync(targetPath); } catch (_) { /* ignore */ }
            onProgress(`md5 mismatch ${fileName}`);
            return;
          }
          hilog.info(DOMAIN, TAG, 'reattach %{public}s MD5 OK', fileName);
        }
        onProgress(`${fileName} complete`);
        // Continue to any remaining files (#3): the service re-enters the
        // job loop, which fast-paths this now-complete file and downloads rest.
        await onCompletedFile(fileName);
      } catch (e) {
        hilog.error(DOMAIN, TAG, 'reattach %{public}s completed handler threw: %{public}s',
          fileName, `${e}`);
      } finally {
        unregisterTask(fileName, task);
        request.agent.remove(task.tid).catch(() => undefined);
      }
    });

    task.on('failed', () => {
      unregisterTask(fileName, task);
      request.agent.show(task.tid).then((failedInfo: request.agent.TaskInfo) => {
        onProgress(`${fileName} failed: ${JSON.stringify(failedInfo.faults)}`);
      }).catch(() => {
        onProgress(`${fileName} failed`);
      }).finally(() => {
        request.agent.remove(task.tid).catch(() => undefined);
      });
    });

    matchedFileNames.push(fileName);
  }

  hilog.info(DOMAIN, TAG, 'reattach: re-attached %{public}d matched task(s) (anyRunning=%{public}s), cleaned up %{public}d stray',
    matchedFileNames.length, `${anyRunning}`, cleanedUpStrayTids);
  return { matchedFileNames, anyRunning, cleanedUpStrayTids };
}

/** Stop everything across all files. Used on app destruction. */
export async function cancelAllDownloads(): Promise<void> {
  const fileNames = Array.from(activeTasks.keys());
  for (const f of fileNames) {
    await cancelDownloadForFile(f);
  }
}

/** Internal: register a task under its file name. */
function registerTask(fileName: string, task: request.agent.Task): void {
  const arr = activeTasks.get(fileName);
  if (arr) {
    arr.push(task);
  } else {
    activeTasks.set(fileName, [task]);
  }
}

/** Internal: unregister a task. Safe to call multiple times. */
function unregisterTask(fileName: string, task: request.agent.Task): void {
  const arr = activeTasks.get(fileName);
  if (!arr) return;
  const i = arr.indexOf(task);
  if (i >= 0) arr.splice(i, 1);
  if (arr.length === 0) activeTasks.delete(fileName);
}

/** Internal: a race-leftover .tmp file candidate for consolidation. */
interface RaceTmpCandidate {
  path: string;
  size: number;
}

/**
 * Returns the byte size of `${dir}/${fileName}.tmp` if it exists, else 0.
 * Used by the single-source path to decide whether to issue an HTTP Range
 * request (Config.begins) for resumption.
 */
export function detectExistingTmpBytes(dir: string, fileName: string): number {
  const tmpPath = `${dir}/${fileName}.tmp`;
  try {
    if (!fs.accessSync(tmpPath)) return 0;
    return fs.statSync(tmpPath).size;
  } catch (_) {
    return 0;
  }
}

/**
 * Remove `<fileName>.tmp` and every `<fileName>.r<N>.tmp` under `dir`. Used
 * when a leftover .tmp has no surviving live task and so cannot be resumed
 * (begins-resume is unavailable on device) — discard it so the next download
 * starts clean.
 */
export function cleanTmpsForFile(dir: string, fileName: string): void {
  const prefix = `${fileName}.r`;
  let entries: string[];
  try { entries = fs.listFileSync(dir); } catch (_) { return; }
  for (const e of entries) {
    if (e === `${fileName}.tmp` || (e.startsWith(prefix) && e.endsWith('.tmp'))) {
      try { fs.unlinkSync(`${dir}/${e}`); } catch (_) { /* ignore */ }
    }
  }
}

/**
 * Inspect `${dir}` for race-leftover tmp files matching `${fileName}.r<N>.tmp`.
 * If any are found:
 *   - Pick the one with the largest size
 *   - Rename it to `${fileName}.tmp` (replacing any existing single .tmp)
 *   - Delete all other race tmps
 *   - Return the (now single-.tmp) byte count
 * If none found, returns 0 — caller proceeds with a fresh race.
 *
 * Why: race + HTTP Range begin are incompatible (multiple sources writing
 * to different files at the same offset → incoherent). When resuming, we
 * must collapse to a single source. Picking the largest race tmp
 * preserves the most progress; discarding the others avoids confusion.
 */
export function consolidateRaceTmps(dir: string, fileName: string, targetPath: string): number {
  const singleTmpPath = `${targetPath}.tmp`;
  let entries: string[];
  try {
    entries = fs.listFileSync(dir);
  } catch (_) {
    return 0;
  }

  const prefix = `${fileName}.r`;
  const suffix = '.tmp';
  const raceTmps: RaceTmpCandidate[] = [];

  for (const entry of entries) {
    if (!entry.startsWith(prefix) || !entry.endsWith(suffix)) continue;
    const mid = entry.substring(prefix.length, entry.length - suffix.length);
    if (mid.length === 0 || !/^\d+$/.test(mid)) continue;
    const p = `${dir}/${entry}`;
    try {
      const s = fs.statSync(p);
      const candidate: RaceTmpCandidate = { path: p, size: s.size };
      raceTmps.push(candidate);
    } catch (_) { /* skip */ }
  }

  if (raceTmps.length === 0) return 0;

  // Pick largest
  raceTmps.sort((a, b) => b.size - a.size);
  const winner = raceTmps[0];

  // Replace single .tmp with the winner
  try {
    if (fs.accessSync(singleTmpPath)) fs.unlinkSync(singleTmpPath);
    fs.renameSync(winner.path, singleTmpPath);
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'consolidate rename failed: %{public}s', `${e}`);
    return 0;
  }

  // Delete losers
  for (const t of raceTmps) {
    if (t.path === winner.path) continue;
    try { fs.unlinkSync(t.path); } catch (_) { /* ignore */ }
  }

  hilog.info(DOMAIN, TAG, 'consolidated %{public}d race tmp(s) → single .tmp (%{public}d bytes)',
             raceTmps.length, winner.size);
  return winner.size;
}

/** One physical source for a given file. */
interface RaceSource {
  label: string;
  url:   string;
}

/** A file plus all of its candidate sources for racing. */
interface MultiSourceJob {
  name:    string;          // local on-disk filename
  sources: RaceSource[];    // 1..N candidates; first non-empty progress wins
  md5:     string | null;
}

function str(rm: resourceManager.ResourceManager, name: string): string {
  return rm.getStringByNameSync(name);
}

// Async because the underlying preferences API is async; called from
// LlamaEngine.downloadModels which itself is awaited.
async function getSelectedModelAsync(context: common.Context): Promise<ModelInfo> {
  try {
    const p = await dataPreferences.getPreferences(context, PREFS_NAME);
    const id = await p.get('selected_model_id', DEFAULT_MODEL.id) as string;
    return findModelById(id);
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'getSelectedModelAsync fallback to default: %{public}s', `${e}`);
    return DEFAULT_MODEL;
  }
}

export async function downloadModelsForSelected(
  context: common.Context,
  onProgress: ProgressListener,
): Promise<void> {
  const rm = context.resourceManager;
  const model = await getSelectedModelAsync(context);
  const dir = `${context.filesDir}/models/${model.id}`;
  if (!fs.accessSync(dir)) {
    fs.mkdirSync(dir, true);
  }

  const ggufSources:   RaceSource[] = [];
  const mmprojSources: RaceSource[] = [];
  const acousticSources: RaceSource[] = [];

  if (model.hasHfMsSources()) {
    const hfBase = `https://huggingface.co/${model.hfRepo}/resolve/${model.hfBranch}`;
    const msBase = `https://www.modelscope.cn/models/${model.msRepo}/resolve/${model.msBranch}`;
    ggufSources.push(  { label: 'HuggingFace', url: `${hfBase}/${model.ggufRemotePath()}`   });
    ggufSources.push(  { label: 'ModelScope',  url: `${msBase}/${model.ggufRemotePath()}`   });
    if (!model.isTextOnly) {
      mmprojSources.push({ label: 'HuggingFace', url: `${hfBase}/${model.mmprojRemotePath()}` });
      mmprojSources.push({ label: 'ModelScope',  url: `${msBase}/${model.mmprojRemotePath()}` });
    }
    if (model.isTts) {
      acousticSources.push({ label: 'HuggingFace', url: `${hfBase}/${model.acousticRemotePath()}` });
      acousticSources.push({ label: 'ModelScope',  url: `${msBase}/${model.acousticRemotePath()}` });
    }
  }
  if (model.directGgufUrl) {
    ggufSources.push({ label: str(rm, 'source_direct'), url: model.directGgufUrl });
  }
  if (!model.isTextOnly && model.directMmprojUrl) {
    mmprojSources.push({ label: str(rm, 'source_direct'), url: model.directMmprojUrl });
  }
  if (model.isTts && model.directAcousticUrl) {
    acousticSources.push({ label: str(rm, 'source_direct'), url: model.directAcousticUrl });
  }
  if (ggufSources.length === 0) {
    throw new Error(`Model ${model.id} has no GGUF download source configured`);
  }
  if (!model.isTextOnly && mmprojSources.length === 0 && !model.isTts) {
    throw new Error(`Model ${model.id} has no mmproj download source configured`);
  }
  if (model.isTts && acousticSources.length === 0 && !model.hasHfMsSources()) {
    throw new Error(`Model ${model.id} has no acoustic download source configured`);
  }

  const labelSummary = ggufSources.map(s => s.label).join('+');
  onProgress(str(rm, 'download_race_start').replace('%s', labelSummary));

  const jobs: MultiSourceJob[] = [
    { name: model.ggufFileName,   sources: ggufSources,   md5: model.ggufMd5 },
  ];
  if (!model.isTextOnly && !model.isTts) {
    jobs.push({ name: model.mmprojFileName, sources: mmprojSources, md5: model.mmprojMd5 });
  }
  if (model.isTts && acousticSources.length > 0) {
    jobs.push({ name: model.acousticFileName, sources: acousticSources, md5: model.acousticMd5 });
  }
  for (const job of jobs) {
    await downloadFileWithSources(context, dir, job, onProgress);
  }
  onProgress(str(rm, 'download_all_complete'));
}

/**
 * Multi-source race download.
 */
async function downloadFileWithSources(
  context: common.Context,
  dir: string,
  job: MultiSourceJob,
  onProgress: ProgressListener,
): Promise<void> {
  const rm = context.resourceManager;
  const targetPath = `${dir}/${job.name}`;

  // Fast path: target already present and MD5 matches the manifest.
  if (fs.accessSync(targetPath) && job.md5) {
    onProgress(str(rm, 'download_verifying_existing').replace('%s', job.name));
    const actual = await computeMd5(targetPath);
    if (actual.toLowerCase() === job.md5.toLowerCase()) {
      onProgress(str(rm, 'download_file_ready').replace('%s', job.name));
      return;
    }
    hilog.warn(DOMAIN, TAG, '%{public}s MD5 mismatch (got %{public}s, want %{public}s); re-downloading',
               job.name, actual, job.md5);
    try { fs.unlinkSync(targetPath); } catch (_) { /* ignore */ }
  }

  // Single-source -> bypass race machinery for clarity / lower overhead.
  if (job.sources.length === 1) {
    const src = job.sources[0];
    // Detect existing .tmp for resume via HTTP Range (Config.begins).
    const begins = detectExistingTmpBytes(dir, job.name);
    await downloadSingleSource(context, dir, job.name, src, job.md5, onProgress, begins);
    return;
  }

  // Multi-source: check for leftover .tmp from a previous run. If found,
  // the race-tmp files are consolidated into a single .tmp (largest wins,
  // others discarded) and we resume via single-source + begins instead of
  // starting a fresh race. Race + begins would have multiple sources
  // writing to the same offset of different files — incoherent.
  // begins-resume of an existing partial is BROKEN on device (GetFd error /
  // truncation — request.agent resume requires a LIVE task via task.resume,
  // not Config.begins on a stale file). We reach this multi-source branch
  // only when there is a leftover .tmp but NO surviving live task (system
  // cleaned it, or the race never settled). Such a .tmp is unrecoverable —
  // discard it and start a fresh race. (The common cold-start case keeps the
  // live task and resumes it directly via reattach, never reaching here.)
  const hasLeftoverTmp = consolidateRaceTmps(dir, job.name, targetPath) > 0
    || detectExistingTmpBytes(dir, job.name) > 0;
  if (hasLeftoverTmp) {
    hilog.info(DOMAIN, TAG, '%{public}s: discarding unrecoverable leftover .tmp (begins-resume unavailable) — fresh download',
               job.name);
    cleanTmpsForFile(dir, job.name);
  }

  hilog.info(DOMAIN, TAG, 'Race-downloading %{public}s from %{public}d sources', job.name, job.sources.length);

  return new Promise<void>((resolve, reject) => {
    // Per-source state.
    const tmpPaths: string[] = job.sources.map((_, i) => `${targetPath}.r${i}.tmp`);
    const tasks: (request.agent.Task | null)[] = job.sources.map(() => null);
    const createFailures: number[] = [];
    const taskFailures:   number[] = [];
    let winnerIdx = -1;
    let settled = false;
    let lastProgressTime = 0;

    const stopLoser = (idx: number): void => {
      const task = tasks[idx];
      if (task) {
        try {
          task.stop().then(() => {
            request.agent.remove(task.tid).catch(() => undefined);
          }).catch(() => {
            request.agent.remove(task.tid).catch(() => undefined);
          });
        } catch (_) { /* ignore */ }
      }
      try {
        if (fs.accessSync(tmpPaths[idx])) fs.unlinkSync(tmpPaths[idx]);
      } catch (_) { /* ignore */ }
    };

    const cleanupLosers = (winIdx: number): void => {
      for (let j = 0; j < job.sources.length; j++) {
        if (j !== winIdx) stopLoser(j);
      }
    };

    const settleAllFailed = (): void => {
      if (settled) return;
      settled = true;
      reject(new Error(`${job.name}: all ${job.sources.length} sources failed`));
    };

    for (let i = 0; i < job.sources.length; i++) {
      const idx = i;
      const src = job.sources[idx];

      hilog.info(DOMAIN, TAG, 'race[%{public}d] %{public}s start: %{public}s', idx, src.label, src.url);

      const config: request.agent.Config = {
        action: request.agent.Action.DOWNLOAD,
        url: src.url,
        headers: { 'User-Agent': 'MiniCPMV-demo/1.0' },
        saveas: tmpPaths[idx],
        overwrite: true,
        method: 'GET',
        mode: request.agent.Mode.FOREGROUND,
        network: request.agent.Network.ANY,
      };

      request.agent.create(context, config).then((task: request.agent.Task) => {
        tasks[idx] = task;
        registerTask(job.name, task);

        task.on('progress', (p: request.agent.Progress) => {
          // Race gate: first task to report non-empty bytes wins.
          if (winnerIdx === -1 && p.processed > 0) {
            winnerIdx = idx;
            hilog.info(DOMAIN, TAG, '%{public}s race winner: idx=%{public}d source=%{public}s',
                       job.name, idx, src.label);
            onProgress(str(rm, 'download_race_winner').replace('%s', src.label).replace('%s', job.name));
            cleanupLosers(idx);
          }
          if (winnerIdx !== idx) return;

          const now = Date.now();
          if (now - lastProgressTime > 500) {
            lastProgressTime = now;
            const got = p.processed;
            const totalArr = p.sizes;
            const total = totalArr && totalArr.length > 0 ? totalArr[0] : -1;
            if (total > 0) {
              const pct = Math.floor(got * 100 / total);
              const mb = Math.floor(got / (1024 * 1024));
              const totalMb = Math.floor(total / (1024 * 1024));
              onProgress(`${job.name}: ${pct}% (${mb}/${totalMb} MB) [${src.label}]`);
            } else {
              const mb = Math.floor(got / (1024 * 1024));
              onProgress(`${job.name}: ${mb} MB [${src.label}]`);
            }
          }
        });

        task.on('completed', async () => {
          if (settled) return;
          if (winnerIdx !== idx) {
            stopLoser(idx);
            unregisterTask(job.name, task);
            return;
          }
          settled = true;
          try {
            if (fs.accessSync(targetPath)) fs.unlinkSync(targetPath);
            fs.renameSync(tmpPaths[idx], targetPath);

            if (job.md5) {
              onProgress(str(rm, 'download_verifying_md5').replace('%s', src.label).replace('%s', job.name));
              const actual = await computeMd5(targetPath);
              if (actual.toLowerCase() !== job.md5.toLowerCase()) {
                hilog.error(DOMAIN, TAG, '%{public}s MD5 mismatch: expected %{public}s, got %{public}s',
                            job.name, job.md5, actual);
                try { fs.unlinkSync(targetPath); } catch (_) { /* ignore */ }
                reject(new Error(
                  str(rm, 'download_md5_failed').replace('%s', job.name).replace('%s', job.md5).replace('%s', actual)
                ));
                return;
              }
              hilog.info(DOMAIN, TAG, '%{public}s MD5 OK (%{public}s)', job.name, actual);
            }

            const size = fs.statSync(targetPath).size;
            const mbSize = Math.floor(size / (1024 * 1024));
            onProgress(str(rm, 'download_file_complete').replace('%s', job.name).replace('%d', `${mbSize}`));
            resolve();
          } catch (e) {
            reject(e as object);
          } finally {
            unregisterTask(job.name, task);
            request.agent.remove(task.tid).catch(() => undefined);
            cleanupLosers(idx);
          }
        });

        task.on('failed', () => {
          if (settled) return;
          if (winnerIdx === idx) {
            settled = true;
            unregisterTask(job.name, task);
            request.agent.show(task.tid).then(info => {
              reject(new Error(
                str(rm, 'download_source_failed_detail').replace('%s', src.label).replace('%s', JSON.stringify(info.faults))
              ));
            }).catch(() => {
              reject(new Error(str(rm, 'download_source_failed').replace('%s', src.label)));
            }).finally(() => {
              request.agent.remove(task.tid).catch(() => undefined);
            });
            return;
          }
          taskFailures.push(idx);
          hilog.warn(DOMAIN, TAG, '%{public}s race source %{public}s failed (%{public}d/%{public}d)',
                     job.name, src.label, taskFailures.length + createFailures.length, job.sources.length);
          unregisterTask(job.name, task);
          request.agent.remove(task.tid).catch(() => undefined);
          try {
            // Loser tmp is safe to clean — only winner bytes are useful.
            if (fs.accessSync(tmpPaths[idx])) fs.unlinkSync(tmpPaths[idx]);
          } catch (_) { /* ignore */ }
          if (taskFailures.length + createFailures.length >= job.sources.length) {
            settleAllFailed();
          }
        });

        task.start().catch((e: BusinessError) => {
          if (settled) return;
          hilog.warn(DOMAIN, TAG, '%{public}s race source %{public}s task.start() failed: %{public}s',
                     job.name, src.label, `${e}`);
          unregisterTask(job.name, task);
          taskFailures.push(idx);
          request.agent.remove(task.tid).catch(() => undefined);
          if (taskFailures.length + createFailures.length >= job.sources.length) {
            settleAllFailed();
          }
        });
      }).catch((e: BusinessError) => {
        hilog.warn(DOMAIN, TAG, '%{public}s race source %{public}s agent.create failed: %{public}s',
                   job.name, src.label, `${e}`);
        createFailures.push(idx);
        if (taskFailures.length + createFailures.length >= job.sources.length) {
          settleAllFailed();
        }
      });
    }
  });
}

/** Simple wrapper for the rare 1-source case (e.g. legacy direct-mirror only).
 *  Also used as the resume path: when `begins` > 0, the system issues an
 *  HTTP Range request and appends to the existing .tmp file.
 */
async function downloadSingleSource(
  context: common.Context,
  dir: string,
  fileName: string,
  src: RaceSource,
  md5: string | null,
  onProgress: ProgressListener,
  begins: number = 0,
): Promise<void> {
  const rm = context.resourceManager;
  const targetPath = `${dir}/${fileName}`;
  if (begins > 0) {
    onProgress(str(rm, 'download_resuming').replace('%s', fileName).replace('%d', `${Math.floor(begins / (1024 * 1024))}`));
    hilog.info(DOMAIN, TAG, 'Resuming %{public}s from %{public}d bytes via %{public}s',
               fileName, begins, src.label);
  } else {
    onProgress(str(rm, 'download_fetching_file').replace('%s', src.label).replace('%s', fileName));
    hilog.info(DOMAIN, TAG, 'Downloading %{public}s from %{public}s: %{public}s',
               fileName, src.label, src.url);
  }

  const tmpPath = `${targetPath}.tmp`;

  await new Promise<void>((resolve, reject) => {
    let lastProgressTime = 0;
    const config: request.agent.Config = {
      action: request.agent.Action.DOWNLOAD,
      url: src.url,
      headers: { 'User-Agent': 'MiniCPMV-demo/1.0' },
      saveas: tmpPath,
      // NOTE: begins-resume of an EXISTING partial is broken on device:
      //   overwrite:true  -> truncates .tmp, writes Range tail at offset 0 (corrupt)
      //   overwrite:false -> "GetFd File exists" error
      // request.agent resume is designed around task.pause()/resume() on a LIVE
      // task (HTTP Range handled internally), not Config.begins on a stale file.
      // Kept overwrite:true so a stale .tmp is replaced; true resume needs the
      // live-task reattach path or Route B (@ohos.net.http Range). See tmp/.
      overwrite: true,
      method: 'GET',
      mode: request.agent.Mode.FOREGROUND,
      network: request.agent.Network.ANY,
      begins: begins,
    };
    request.agent.create(context, config).then((task: request.agent.Task) => {
      registerTask(fileName, task);
      task.on('progress', (p: request.agent.Progress) => {
        const now = Date.now();
        if (now - lastProgressTime > 500) {
          lastProgressTime = now;
          const got = p.processed;
          const totalArr = p.sizes;
          const total = totalArr && totalArr.length > 0 ? totalArr[0] : -1;
          if (total > 0) {
            const pct = Math.floor(got * 100 / total);
            const mb = Math.floor(got / (1024 * 1024));
            const totalMb = Math.floor(total / (1024 * 1024));
            onProgress(`${fileName}: ${pct}% (${mb}/${totalMb} MB)`);
          } else {
            const mb = Math.floor(got / (1024 * 1024));
            onProgress(`${fileName}: ${mb} MB`);
          }
        }
      });
      task.on('completed', async () => {
        try {
          if (fs.accessSync(targetPath)) fs.unlinkSync(targetPath);
          fs.renameSync(tmpPath, targetPath);
          resolve();
        } catch (e) {
          reject(e as object);
        } finally {
          unregisterTask(fileName, task);
          request.agent.remove(task.tid).catch(() => undefined);
        }
      });
      task.on('failed', () => {
        unregisterTask(fileName, task);
        request.agent.show(task.tid).then(info => {
          reject(new Error(`Download failed: ${JSON.stringify(info.faults)}`));
        }).catch(() => {
          reject(new Error('Download failed'));
        }).finally(() => {
          request.agent.remove(task.tid).catch(() => undefined);
          // NOTE: do NOT unlink tmp — preserved for resume.
        });
      });
      task.start();
    }).catch((e: object) => reject(e));
  });

  if (md5) {
    onProgress(str(rm, 'download_verifying_md5').replace('%s', src.label).replace('%s', fileName));
    const actual = await computeMd5(targetPath);
    if (actual.toLowerCase() !== md5.toLowerCase()) {
      hilog.error(DOMAIN, TAG, '%{public}s MD5 mismatch: expected %{public}s, got %{public}s',
                  fileName, md5, actual);
      try { fs.unlinkSync(targetPath); } catch (_) { /* ignore */ }
      throw new Error(
        str(rm, 'download_md5_failed').replace('%s', fileName).replace('%s', md5).replace('%s', actual)
      );
    }
    hilog.info(DOMAIN, TAG, '%{public}s MD5 OK (%{public}s)', fileName, actual);
  }

  const size = fs.statSync(targetPath).size;
  const mbSize = Math.floor(size / (1024 * 1024));
  onProgress(str(rm, 'download_file_complete').replace('%s', fileName).replace('%d', `${mbSize}`));
}

// Streaming MD5 — keeps memory bounded on the 1GB+ mmproj. 64KB chunks
// give ~150 MB/s on the test phone, identical to the Android implementation.
export async function computeMd5(path: string): Promise<string> {
  const md = cryptoFramework.createMd('MD5');
  const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
  try {
    const CHUNK = 64 * 1024;
    const buf = new ArrayBuffer(CHUNK);
    let offset = 0;
    while (true) {
      const read = fs.readSync(file.fd, buf, { offset, length: CHUNK });
      if (read <= 0) break;
      // The crypto framework expects a DataBlob; copy only the read portion.
      const slice = read === CHUNK ? buf : buf.slice(0, read);
      await md.update({ data: new Uint8Array(slice) });
      offset += read;
    }
    const digest = await md.digest();
    return bytesToHex(digest.data);
  } finally {
    fs.closeSync(file);
  }
}

function bytesToHex(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i++) {
    const h = bytes[i].toString(16);
    s += h.length === 1 ? `0${h}` : h;
  }
  return s;
}

// Convenience wrapper used by ModelManager to delete the currently
// selected model's two files. Mirrors deleteModelFiles() on Android.
export async function deleteSelectedModelFiles(context: common.Context): Promise<boolean> {
  const model = await getSelectedModelAsync(context);
  const dir = `${context.filesDir}/models/${model.id}`;
  let deleted = false;
  const filesToDelete: string[] = [model.ggufFileName];
  if (model.mmprojFileName) {
    filesToDelete.push(model.mmprojFileName);
  }
  if (model.acousticFileName) {
    filesToDelete.push(model.acousticFileName);
  }
  for (const name of filesToDelete) {
    const p = `${dir}/${name}`;
    if (fs.accessSync(p)) {
      try { fs.unlinkSync(p); deleted = true; }
      catch (e) {
        hilog.warn(DOMAIN, TAG, 'unlink %{public}s failed: %{public}s', p, `${e}`);
      }
    }
  }
  return deleted;
}
