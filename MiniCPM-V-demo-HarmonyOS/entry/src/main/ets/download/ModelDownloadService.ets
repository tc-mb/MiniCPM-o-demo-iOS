// Singleton service that owns the model download lifecycle.
//
// Responsibilities:
//   - Wraps DownloadManager.downloadModelsForSelected as the actual
//     byte-pulling mechanism.
//   - Translates (msg) callbacks into structured DownloadSnapshot published
//     to AppStorage, so any @StorageLink observer re-renders automatically.
//   - Cold-start / page-open reconciliation from filesystem (INVARIANT 1).
//
// The service holds an ability context attached from the page's
// aboutToAppear; the context survives across page navigations within the
// same ability, so downloads outlive any single page instance.

import common from '@ohos.app.ability.common';
import hilog from '@ohos.hilog';
import fs from '@ohos.file.fs';

import {
  downloadModelsForSelected,
  pauseDownloadForFile,
  resumeDownloadForFile,
  cancelDownloadForFile,
  reattachSystemTasks,
} from '../utils/DownloadManager';
import { LlamaEngine } from '../engine/LlamaEngine';
import { ModelInfo } from '../engine/ModelInfo';
import {
  DownloadSnapshot,
  DownloadStatus,
  IDLE_SNAPSHOT,
  DOWNLOAD_STATE_KEY,
} from './DownloadState';
import {
  scanModelDirForTmp,
  parseProgressMsg,
  expectedFilesForModel,
} from './Reconciler';

const DOMAIN = 0xC0DE;
const TAG = 'ModelDownloadService';

export class ModelDownloadService {
  private static _shared: ModelDownloadService | null = null;

  static get shared(): ModelDownloadService {
    if (ModelDownloadService._shared === null) {
      ModelDownloadService._shared = new ModelDownloadService();
    }
    return ModelDownloadService._shared as ModelDownloadService;
  }

  private constructor() {}

  // -------------------------------------------------------------------
  // State
  // -------------------------------------------------------------------

  private _context: common.Context | null = null;
  private _currentModel: ModelInfo | null = null;
  private _currentFile: string | null = null;
  private _bytesTotal: number = 0;
  private _lastBytesOnDisk: number = 0;
  private _lastTickAt: number = 0;
  private _running: boolean = false;
  /**
   * In-flight download promise. Used to dedupe concurrent start() callers
   * (e.g. Index page auto-download fires while the user is also looking
   * at ModelManager — both want the same completion signal).
   */
  private _currentRun: Promise<void> | null = null;
  /**
   * Filesystem-authoritative reconcile timer. While a download is running,
   * every 5 s we fs.statSync the current .tmp file and correct any drift
   * between the optimistic callback value and the actual flushed bytes
   * (INVARIANT 1 + INVARIANT 5).
   */
  private _reconcileTimerId: number = -1;
  /**
   * Most recently published snapshot. The 5 s reconcile timer reads this
   * to preserve UI fields (statusText, speed, eta) that it does NOT own —
   * only bytesOnDisk / pct / lastUpdated are reconciled from fs, the rest
   * are copied through so the user doesn't see the progress text flash
   * back to "准备下载…" every time the timer fires.
   */
  private _lastSnapshot: DownloadSnapshot = IDLE_SNAPSHOT;
  /**
   * One-shot guard: reattachSystemTasks should run at most once per app
   * session. If the first call found nothing (no surviving system task),
   * subsequent attach() calls (e.g. user navigates back to ModelManager)
   * would otherwise re-run search + getTask and may pick up tasks we
   * started ourselves in between, double-registering them.
   */
  private _reattachDone: boolean = false;

  // -------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------

  /**
   * Bind the service to an ability context. Idempotent — the first call
   * wins, subsequent calls with the same context are no-ops. Triggers
   * cold-start reconciliation UNLESS a download is already in flight
   * (in which case reconcile would clobber the running status).
   *
   * Called from ModelManager.aboutToAppear.
   */
  async attach(context: common.Context): Promise<void> {
    if (this._context === null) {
      this._context = context;
      hilog.info(DOMAIN, TAG, 'attached to ability context');
    }
    if (this._running) return;
    if (!this._reattachDone) {
      this._reattachDone = true;
      await this.reattachAndPublish();
      if (this._running) return;
    }
    await this.reconcileFromFs();
  }

  /**
   * Probe the system service for any still-running download tasks owned
   * by this app. If we find any whose saveas matches our selected model,
   * pick them up (re-attach callbacks) and publish a 'running' snapshot.
   *
   * This MUST run before reconcileFromFs on app launch, otherwise the
   * service would publish 'paused' (because .tmp exists) and let the user
   * click Download — which would create a duplicate task writing to the
   * same .tmp file the system task is still using → corruption.
   */
  private async reattachAndPublish(): Promise<void> {
    if (this._context === null) return;
    const model = LlamaEngine.getSelectedModel(this._context);
    this._currentModel = model;
    const dir = `${this._context.filesDir}/models/${model.id}`;
    const expected = expectedFilesForModel(model);
    const md5Map = new Map<string, string | null>();
    if (model.ggufMd5) md5Map.set(model.ggufFileName, model.ggufMd5);
    if (model.mmprojFileName && model.mmprojMd5) md5Map.set(model.mmprojFileName, model.mmprojMd5);
    if (model.acousticFileName && model.acousticMd5) md5Map.set(model.acousticFileName, model.acousticMd5);

    try {
      const result = await reattachSystemTasks(this._context, dir, expected, md5Map, (msg) => {
        this.onProgressMsg(msg);
      });
      if (result.matchedFileNames.length > 0) {
        // Pick the first matched file as the "current" — multiple files
        // (gguf + mmproj) being downloaded sequentially will fire progress
        // for whichever the system task is currently processing.
        const firstName = result.matchedFileNames[0];
        this._currentFile = firstName;
        this._lastBytesOnDisk = this.fsBytesForCurrentFile();
        if (this._lastBytesOnDisk < 0) this._lastBytesOnDisk = 0;
        this._lastTickAt = Date.now();

        if (result.anyRunning) {
          // Task survived the kill and is still live (RUNNING / RETRYING)
          // — pick up where the previous session left off.
          this._running = true;
          this.startReconcileTimer();
          this.publish({
            status: { kind: 'running', modelId: model.id, fileName: firstName },
            bytesOnDisk: this._lastBytesOnDisk,
            bytesTotal: 0,
            pct: 0,
            speed: 0,
            eta: 0,
            statusText: this.prefixModelName(this.str('download_status_preparing')),
            lastUpdated: Date.now(),
          });
          hilog.info(DOMAIN, TAG, 'reattached to %{public}d live task(s), currentFile=%{public}s',
            result.matchedFileNames.length, firstName);
        } else {
          // Matched task(s) exist but are held (PAUSED / STOPPED / …) —
          // the typical cold-start case after the user paused, then killed
          // and relaunched the app. Publish 'paused' so the UI offers
          // Resume + Cancel directly, instead of forcing a bogus
          // Pause -> Resume round-trip. Do NOT set _running (so
          // reconcileNow() can still re-evaluate when the user switches
          // models, and so attach() falls through to reconcileFromFs)
          // and do NOT start the reconcile timer (no bytes move while
          // paused).
          this._running = false;
          this.publish({
            status: { kind: 'paused', modelId: model.id, fileName: firstName },
            bytesOnDisk: this._lastBytesOnDisk,
            bytesTotal: 0,
            pct: 0,
            speed: 0,
            eta: 0,
            statusText: this.prefixModelName(this.str('download_status_paused_partial')),
            lastUpdated: Date.now(),
          });
          hilog.info(DOMAIN, TAG, 'reattached to %{public}d paused task(s), currentFile=%{public}s',
            result.matchedFileNames.length, firstName);
        }
      }
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'reattachAndPublish threw: %{public}s', `${e}`);
    }
  }

  /**
   * Start downloading the currently-selected model. Returns a Promise
   * that resolves when the download finishes (success or failure — failure
   * is captured internally and surfaced via AppStorage, the promise itself
   * does NOT reject).
   *
   * Behaviour depends on current state:
   *   - idle / completed / failed / cold-start paused → launch fresh download
   *   - running                                → no-op (dedupe, return same promise)
   *   - paused (user clicked Pause this session) → route to resume() so the
   *     existing system task picks up where it left off
   *
   * UI callers may ignore the returned promise — observing AppStorage
   * via @StorageLink is the canonical way to track progress.
   */
  start(context?: common.Context): Promise<void> {
    if (this._currentRun !== null) {
      if (this._running) {
        // Genuinely running — dedupe.
        return this._currentRun;
      }
      // _currentRun is alive but _running is false → paused this session.
      // Route to resume so we don't orphan the in-flight runDownload promise.
      return this.resume();
    }
    const ctx = context ?? this._context;
    if (ctx === null) {
      hilog.error(DOMAIN, TAG, 'start() called before attach() — returning resolved promise');
      return Promise.resolve();
    }
    this._context = ctx;

    const model = LlamaEngine.getSelectedModel(ctx);
    this._currentModel = model;
    this._currentFile = null;
    this._bytesTotal = 0;
    this._lastBytesOnDisk = 0;
    this._lastTickAt = Date.now();
    this._running = true;

    this.publish({
      status: { kind: 'running', modelId: model.id, fileName: model.ggufFileName },
      bytesOnDisk: 0,
      bytesTotal: 0,
      pct: 0,
      speed: 0,
      eta: 0,
      statusText: this.prefixModelName(this.str('download_status_preparing')),
      lastUpdated: Date.now(),
    });

    const p = this.runDownload(ctx);
    this._currentRun = p;
    // Cleanup AFTER awaiters see the terminal status. Run on a separate
    // chain so callers awaiting `p` are unaffected.
    p.finally(() => {
      this._currentRun = null;
      this._running = false;
      this.stopReconcileTimer();
    }).catch((): void => {
      /* swallowed — runDownload catches and publishes onFailed internally */
    });
    this.startReconcileTimer();
    return p;
  }

  /**
   * Begin periodic fs-authoritative reconciliation while a download is
   * active. INVARIANT 5: pct shown to
   * the user must lag actual fs bytes by ≤ 5 s. The progress callback
   * from request.agent reports optimistically (counts in-flight socket
   * bytes); fs.statSync reports what's actually flushed to disk. On
   * disagreement, fs wins.
   */
  private startReconcileTimer(): void {
    this.stopReconcileTimer();
    this._reconcileTimerId = setInterval((): void => {
      this.reconcileFromFsDuringRun();
    }, 5000);
  }

  private stopReconcileTimer(): void {
    if (this._reconcileTimerId !== -1) {
      clearInterval(this._reconcileTimerId);
      this._reconcileTimerId = -1;
    }
  }

  /**
   * Lightweight reconcile while running — only updates bytesOnDisk + pct
   * from fs.statSync. Other fields (statusText, speed, eta, status.kind)
   * are passed through from the previous snapshot so the UI doesn't
   * flash back to "准备下载…" every time the timer ticks.
   *
   * Also detects completion: when the system task finishes it renames
   * .tmp → final, fsBytesForCurrentFile returns -1 (no .tmp found), and
   * modelsExist returns true → we publish 'completed'.
   */
  private reconcileFromFsDuringRun(): void {
    if (!this._running) return;
    if (this._context === null || this._currentModel === null || this._currentFile === null) return;
    const fsBytes = this.fsBytesForCurrentFile();

    if (fsBytes < 0) {
      // .tmp vanished. Two possibilities:
      //   1) System task completed → renamed to final file → modelsExist true
      //   2) Something went wrong (cancellation, fs error)
      // Either way, stop the reconcile timer and re-evaluate from fs.
      if (this._context !== null && LlamaEngine.modelsExist(this._context)) {
        hilog.info(DOMAIN, TAG, 'reconcile: .tmp gone + final file exists → completed');
        this.stopReconcileTimer();
        this._running = false;
        this._currentRun = null;
        this.onCompleted(this._context);
      }
      return;
    }

    // If the optimistic value is ahead of fs (callback reported bytes the
    // OS hasn't flushed yet), pull it back to fs truth. If fs is ahead
    // (callback lagged), advance to fs truth. Either way, fs wins.
    if (fsBytes === this._lastBytesOnDisk) return;
    this._lastBytesOnDisk = fsBytes;
    const pct = this._bytesTotal > 0 ? Math.min(1, Math.max(0, fsBytes / this._bytesTotal)) : 0;
    const prev = this._lastSnapshot;
    this.publish({
      status: prev.status,
      bytesOnDisk: fsBytes,
      bytesTotal: this._bytesTotal,
      pct,
      speed: prev.speed,
      eta: prev.eta,
      statusText: prev.statusText,
      lastUpdated: Date.now(),
    });
  }

  /**
   * Force a re-reconcile from filesystem. Called when the user re-enters
   * the ModelManager page — picks up any drift if the page was destroyed
   * mid-download.
   */
  async reconcileNow(): Promise<void> {
    // During active download the optimistic callbacks + periodic reconcile
    // are the authority; no need to clobber with cold-start logic.
    if (this._running) return;
    await this.reconcileFromFs();
  }

  /**
   * Pause the currently-running download. Delegates to per-file task
   * registry in DownloadManager. The .tmp file is preserved so a later
   * resume can pick up where pause left off.
   */
  async pause(): Promise<void> {
    // Deliberately do NOT early-return on `!this._running`. pause()
    // flips _running=false unconditionally after pauseDownloadForFile,
    // even if a task's pause() rejected or a trailing progress callback
    // (which does NOT reset _running) re-published 'running' right after.
    // Once _running is false while the task is still transferring, the old
    // `if (!this._running) return` turned every later Pause click into a
    // no-op — rapid clicking could leave Pause permanently dead. The Pause
    // button is only rendered while the published status is 'running', so
    // reaching here means the user genuinely wants to pause; always attempt
    // it. pauseDownloadForFile is a no-op when there are no registered
    // tasks, so this is safe to re-issue.
    if (this._currentFile === null) return;
    hilog.info(DOMAIN, TAG, 'pause requested for %{public}s (wasRunning=%{public}s)',
      this._currentFile, `${this._running}`);
    try {
      await pauseDownloadForFile(this._currentFile);
      this._running = false;
      this.stopReconcileTimer();
      this.publish({
        status: {
          kind: 'paused',
          modelId: this._currentModel !== null ? this._currentModel.id : undefined,
          fileName: this._currentFile ?? undefined,
        },
        bytesOnDisk: this._lastBytesOnDisk,
        bytesTotal: this._bytesTotal,
        pct: this._bytesTotal > 0 ? this._lastBytesOnDisk / this._bytesTotal : 0,
        speed: 0,
        eta: 0,
        statusText: this.prefixModelName(this.str('download_status_paused_partial')),
        lastUpdated: Date.now(),
      });
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'pause failed: %{public}s', `${e}`);
    }
  }

  /**
   * Resume a paused download via task.resume(); if the system task is still
   * alive, the download picks up where it left off.
   */
  async resume(): Promise<void> {
    if (this._running) return;
    if (this._currentFile === null) return;
    if (this._context === null) return;
    hilog.info(DOMAIN, TAG, 'resume requested for %{public}s', this._currentFile);
    try {
      await resumeDownloadForFile(this._currentFile);
      this._running = true;
      this._lastTickAt = Date.now();
      this.startReconcileTimer();
      this.publish({
        status: {
          kind: 'running',
          modelId: this._currentModel !== null ? this._currentModel.id : undefined,
          fileName: this._currentFile ?? undefined,
        },
        bytesOnDisk: this._lastBytesOnDisk,
        bytesTotal: this._bytesTotal,
        pct: this._bytesTotal > 0 ? this._lastBytesOnDisk / this._bytesTotal : 0,
        speed: 0,
        eta: 0,
        statusText: this.prefixModelName(this.str('download_status_preparing')),
        lastUpdated: Date.now(),
      });
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'resume failed: %{public}s', `${e}`);
    }
  }

  /**
   * Cancel the current download. Stops system task, removes registration,
   * AND deletes any .tmp file for the current model — "Cancel" semantics
   * are "stop and discard progress", distinct from "Pause" which keeps
   * the .tmp for later resume. The service transitions to idle.
   */
  async cancel(): Promise<void> {
    if (this._currentFile === null) return;
    if (this._context === null || this._currentModel === null) return;
    hilog.info(DOMAIN, TAG, 'cancel requested for %{public}s', this._currentFile);
    try {
      await cancelDownloadForFile(this._currentFile);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'cancel task cleanup failed (continuing): %{public}s', `${e}`);
    }
    // Wipe .tmp files for this model so the next start begins cleanly.
    // Pause preserves .tmp; Cancel does not — that's the contract.
    this.cleanupTmpFiles(this._context, this._currentModel);

    this._running = false;
    this._currentRun = null;
    this.stopReconcileTimer();
    this._currentFile = null;
    this._bytesTotal = 0;
    this._lastBytesOnDisk = 0;

    this.publish({
      status: { kind: 'idle' },
      bytesOnDisk: 0,
      bytesTotal: 0,
      pct: 0,
      speed: 0,
      eta: 0,
      statusText: '',
      lastUpdated: Date.now(),
    });
  }

  /**
   * Remove every `.tmp` and `.r*.tmp` file in the model's directory whose
   * name matches one of the model's expected output files. Used by cancel
   * to implement "discard progress" semantics.
   */
  private cleanupTmpFiles(context: common.Context, model: ModelInfo): void {
    const dir = `${context.filesDir}/models/${model.id}`;
    const expected = expectedFilesForModel(model);
    let entries: string[];
    try {
      entries = fs.listFileSync(dir);
    } catch (_) {
      return;
    }
    for (const entry of entries) {
      if (!entry.endsWith('.tmp')) continue;
      let matched = false;
      for (const fname of expected) {
        if (entry === `${fname}.tmp`) { matched = true; break; }
        const prefix = `${fname}.r`;
        if (entry.startsWith(prefix) && entry.endsWith('.tmp')) {
          const mid = entry.substring(prefix.length, entry.length - '.tmp'.length);
          if (mid.length > 0 && /^\d+$/.test(mid)) { matched = true; break; }
        }
      }
      if (matched) {
        try { fs.unlinkSync(`${dir}/${entry}`); }
        catch (e) { hilog.warn(DOMAIN, TAG, 'unlink %{public}s failed: %{public}s', entry, `${e}`); }
      }
    }
  }

  // -------------------------------------------------------------------
  // Internal: download loop
  // -------------------------------------------------------------------

  private async runDownload(context: common.Context): Promise<void> {
    try {
      await downloadModelsForSelected(context, (msg: string) => {
        this.onProgressMsg(msg);
      });
      this.onCompleted(context);
    } catch (e) {
      this.onFailed(`${e}`);
    } finally {
      this.stopReconcileTimer();
    }
    // NOTE: _running / _currentRun cleanup is done by start()'s outer
    // finally chain so concurrent callers see consistent state.
  }

  private onProgressMsg(msg: string): void {
    const parsed = parseProgressMsg(msg);

    // Detect file transitions (gguf → mmproj → acoustic) and reset
    // per-file accumulators so the snapshot reflects the CURRENT file,
    // not a stale sum from the previous one.
    if (parsed.fileName !== undefined && parsed.fileName !== this._currentFile) {
      this._currentFile = parsed.fileName;
      this._bytesTotal = 0;
      this._lastBytesOnDisk = 0;
      this._lastTickAt = Date.now();
    }
    if (parsed.bytesTotal !== undefined && parsed.bytesTotal > 0) {
      this._bytesTotal = parsed.bytesTotal;
    }

    // INVARIANT 1: prefer fs bytes; fall back to parsed optimistic value.
    const fsBytes = this.fsBytesForCurrentFile();
    const bytesOnDisk = fsBytes >= 0
      ? fsBytes
      : (parsed.bytesOnDisk !== undefined ? parsed.bytesOnDisk : this._lastBytesOnDisk);

    const now = Date.now();
    const dtSec = (now - this._lastTickAt) / 1000;
    let speed = 0;
    if (dtSec > 0 && bytesOnDisk > this._lastBytesOnDisk) {
      speed = (bytesOnDisk - this._lastBytesOnDisk) / dtSec;
    }
    const remaining = this._bytesTotal - bytesOnDisk;
    const eta = speed > 0 ? remaining / speed : 0;
    this._lastBytesOnDisk = bytesOnDisk;
    this._lastTickAt = now;

    const pct = this._bytesTotal > 0
      ? Math.min(1, Math.max(0, bytesOnDisk / this._bytesTotal))
      : (parsed.pct !== undefined ? parsed.pct : 0);

    this.publish({
      status: {
        kind: 'running',
        modelId: this._currentModel !== null ? this._currentModel.id : undefined,
        fileName: this._currentFile ?? undefined,
        source: parsed.source,
      },
      bytesOnDisk,
      bytesTotal: this._bytesTotal,
      pct,
      speed,
      eta,
      statusText: this.prefixModelName(msg),
      lastUpdated: now,
    });
  }

  private onCompleted(context: common.Context): void {
    // fs is the authority — verify final state from disk
    const exists = LlamaEngine.modelsExist(context);
    const modelId = this._currentModel !== null ? this._currentModel.id : undefined;
    const status: DownloadStatus = exists
      ? { kind: 'completed', modelId }
      : { kind: 'failed', modelId, error: 'files missing after download' };

    this.publish({
      status,
      bytesOnDisk: exists ? this._bytesTotal : 0,
      bytesTotal: this._bytesTotal,
      pct: exists ? 1 : 0,
      speed: 0,
      eta: 0,
      statusText: this.prefixModelName(
        exists
          ? this.str('download_status_completed')
          : this.str('download_status_failed_missing')
      ),
      lastUpdated: Date.now(),
    });

    hilog.info(DOMAIN, TAG, 'download completed, exists=%{public}s', `${exists}`);
  }

  private onFailed(error: string): void {
    const modelId = this._currentModel !== null ? this._currentModel.id : undefined;
    this.publish({
      status: { kind: 'failed', modelId, error },
      bytesOnDisk: this._lastBytesOnDisk,
      bytesTotal: this._bytesTotal,
      pct: 0,
      speed: 0,
      eta: 0,
      statusText: this.prefixModelName(this.str('download_status_failed').replace('%s', error)),
      lastUpdated: Date.now(),
    });
    hilog.error(DOMAIN, TAG, 'download failed: %{public}s', error);
  }

  // -------------------------------------------------------------------
  // Internal: filesystem reconciliation
  // -------------------------------------------------------------------

  /**
   * Authoritative bytes-on-disk for the currently-downloading file,
   * derived from fs scan. Returns -1 if we don't yet know which file to
   * look at.
   */
  private fsBytesForCurrentFile(): number {
    if (this._context === null || this._currentModel === null || this._currentFile === null) {
      return -1;
    }
    const expected = expectedFilesForModel(this._currentModel);
    const sizes = scanModelDirForTmp(this._context, this._currentModel.id, expected);
    const v = sizes.get(this._currentFile);
    return v === undefined ? -1 : v;
  }

  /**
   * Cold-start / idle-state reconciliation. Looks at filesystem truth:
   *   - final file present + MD5 OK → completed
   *   - any tmp present → paused (incomplete)
   *   - otherwise → idle
   *
   * This is the ONLY path that should publish during app launch or page
   * re-entry (when no download is actively running).
   */
  private async reconcileFromFs(): Promise<void> {
    if (this._context === null) {
      this.publish(IDLE_SNAPSHOT);
      return;
    }
    const context = this._context;
    const model = LlamaEngine.getSelectedModel(context);
    this._currentModel = model;

    // Final files present? → completed (LlamaEngine.modelsExist checks
    // gguf + mmproj + acoustic as configured for the model)
    if (LlamaEngine.modelsExist(context)) {
      this.publish({
        status: { kind: 'completed' },
        bytesOnDisk: 0,
        bytesTotal: 0,
        pct: 0,
        speed: 0,
        eta: 0,
        statusText: '',
        lastUpdated: Date.now(),
      });
      return;
    }

    // Any in-flight tmp? → paused
    const expected = expectedFilesForModel(model);
    const sizes = scanModelDirForTmp(context, model.id, expected);
    let totalBytes = 0;
    let lastFileName: string | null = null;
    sizes.forEach((bytes: number, fname: string) => {
      totalBytes += bytes;
      if (bytes > 0) lastFileName = fname;
    });

    if (totalBytes > 0 && lastFileName !== null) {
      this._currentFile = lastFileName;
      this._lastBytesOnDisk = totalBytes;
      this.publish({
        status: { kind: 'paused', modelId: model.id, fileName: lastFileName },
        bytesOnDisk: totalBytes,
        bytesTotal: 0,
        pct: 0,
        speed: 0,
        eta: 0,
        statusText: this.prefixModelName(this.str('download_status_paused_partial')),
        lastUpdated: Date.now(),
      });
      hilog.info(DOMAIN, TAG, 'cold-start reconcile: paused at %{public}d bytes for %{public}s',
        totalBytes, lastFileName);
      return;
    }

    // Clean state
    this.publish({
      status: { kind: 'idle' },
      bytesOnDisk: 0,
      bytesTotal: 0,
      pct: 0,
      speed: 0,
      eta: 0,
      statusText: '',
      lastUpdated: Date.now(),
    });
  }

  // -------------------------------------------------------------------
  // Internal: AppStorage bridge
  // -------------------------------------------------------------------

  private publish(snapshot: DownloadSnapshot): void {
    this._lastSnapshot = snapshot;
    try {
      AppStorage.setOrCreate<DownloadSnapshot>(DOWNLOAD_STATE_KEY, snapshot);
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'publish failed: %{public}s', `${e}`);
    }
  }

  /**
   * Prefix a status line with the current model's display name so the
   * user sees "[MiniCPM-V 4.6] gguf-...: 42%" rather than just the
   * file-name-only message that DownloadManager emits.
   */
  private prefixModelName(text: string): string {
    if (this._currentModel !== null) {
      return `[${this._currentModel.displayName}] ${text}`;
    }
    return text;
  }

  /**
   * Look up a localised string from the ability's resourceManager. Falls
   * back to the raw key if the service has not yet been attached (very
   * unlikely — publish only happens after attach).
   */
  private str(name: string): string {
    if (this._context === null) return name;
    try {
      return this._context.resourceManager.getStringByNameSync(name);
    } catch (_) {
      return name;
    }
  }
}
