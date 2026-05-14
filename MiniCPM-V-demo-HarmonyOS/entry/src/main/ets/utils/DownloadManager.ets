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

import {
  ModelInfo,
  findModelById,
  AVAILABLE_MODELS,
  DEFAULT_MODEL,
} from '../engine/ModelInfo';

const DOMAIN = 0xC0DE;
const TAG = 'DownloadManager';
const PREFS_NAME = 'model_prefs';

export type ProgressListener = (msg: string) => void;

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
  const model = await getSelectedModelAsync(context);
  const dir = `${context.filesDir}/models/${model.id}`;
  if (!fs.accessSync(dir)) {
    fs.mkdirSync(dir, true);
  }

  // Per file, build the *full* candidate-source list. HF + MS run in
  // parallel; directGgufUrl/directMmprojUrl, if present, are registered as
  // an extra race candidate. The first source to deliver bytes wins, the
  // others get cancelled. Mirrors iOS opt-r1's MBModelDownloadHelperV2.
  const ggufSources:   RaceSource[] = [];
  const mmprojSources: RaceSource[] = [];

  if (model.hasHfMsSources()) {
    const hfBase = `https://huggingface.co/${model.hfRepo}/resolve/${model.hfBranch}`;
    const msBase = `https://www.modelscope.cn/models/${model.msRepo}/resolve/${model.msBranch}`;
    ggufSources.push(  { label: 'HuggingFace', url: `${hfBase}/${model.ggufRemotePath()}`   });
    ggufSources.push(  { label: 'ModelScope',  url: `${msBase}/${model.ggufRemotePath()}`   });
    mmprojSources.push({ label: 'HuggingFace', url: `${hfBase}/${model.mmprojRemotePath()}` });
    mmprojSources.push({ label: 'ModelScope',  url: `${msBase}/${model.mmprojRemotePath()}` });
  }
  if (model.directGgufUrl) {
    ggufSources.push({ label: '直链', url: model.directGgufUrl });
  }
  if (model.directMmprojUrl) {
    mmprojSources.push({ label: '直链', url: model.directMmprojUrl });
  }
  if (ggufSources.length === 0 || mmprojSources.length === 0) {
    throw new Error(`Model ${model.id} has no download source configured`);
  }

  const labelSummary = ggufSources.map(s => s.label).join('+');
  onProgress(`${labelSummary} 多源 race 启动...`);

  const jobs: MultiSourceJob[] = [
    { name: model.ggufFileName,   sources: ggufSources,   md5: model.ggufMd5 },
    { name: model.mmprojFileName, sources: mmprojSources, md5: model.mmprojMd5 },
  ];
  for (const job of jobs) {
    await downloadFileWithSources(context, dir, job, onProgress);
  }
  onProgress('所有模型文件下载完成!');
}

/**
 * Multi-source race download.
 *
 * If the file already exists locally with a matching MD5, return early.
 * Otherwise launch one @ohos.request.agent download task per source in
 * parallel. The first task whose first `progress` callback reports
 * `processed > 0` wins; the other tasks are `stop()`'d, removed from the
 * agent queue, and their .tmp staging files are deleted.
 *
 * If every source fails (or the winner subsequently fails), the combined
 * error is surfaced. The bytes from a losing source never reach the target
 * path.
 */
async function downloadFileWithSources(
  context: common.Context,
  dir: string,
  job: MultiSourceJob,
  onProgress: ProgressListener,
): Promise<void> {
  const targetPath = `${dir}/${job.name}`;

  // Fast path: target already present and MD5 matches the manifest.
  if (fs.accessSync(targetPath) && job.md5) {
    onProgress(`校验已存在的 ${job.name} ...`);
    const actual = await computeMd5(targetPath);
    if (actual.toLowerCase() === job.md5.toLowerCase()) {
      onProgress(`${job.name} 已就绪 (MD5 校验通过)`);
      return;
    }
    hilog.warn(DOMAIN, TAG, '%{public}s MD5 mismatch (got %{public}s, want %{public}s); re-downloading',
               job.name, actual, job.md5);
    try { fs.unlinkSync(targetPath); } catch (_) { /* ignore */ }
  }

  // Single-source -> bypass race machinery for clarity / lower overhead.
  if (job.sources.length === 1) {
    const src = job.sources[0];
    await downloadSingleSource(context, dir, job.name, src, job.md5, onProgress);
    return;
  }

  hilog.info(DOMAIN, TAG, 'Race-downloading %{public}s from %{public}d sources', job.name, job.sources.length);

  return new Promise<void>((resolve, reject) => {
    // Per-source state.
    const tmpPaths: string[] = job.sources.map((_, i) => `${targetPath}.r${i}.tmp`);
    const tasks: (request.agent.Task | null)[] = job.sources.map(() => null);
    const createFailures: number[] = [];   // indices that failed at agent.create()
    const taskFailures:   number[] = [];   // indices that started but failed mid-download
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
      try {
        if (fs.accessSync(tmpPaths[idx])) fs.unlinkSync(tmpPaths[idx]);
      } catch (_) { /* ignore */ }

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
        retry: false,
      };

      request.agent.create(context, config).then((task: request.agent.Task) => {
        tasks[idx] = task;

        task.on('progress', (p: request.agent.Progress) => {
          // Race gate: first task to report non-empty bytes wins.
          if (winnerIdx === -1 && p.processed > 0) {
            winnerIdx = idx;
            hilog.info(DOMAIN, TAG, '%{public}s race winner: idx=%{public}d source=%{public}s',
                       job.name, idx, src.label);
            onProgress(`[${src.label}] 胜出，开始下载 ${job.name}...`);
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
            // A loser somehow finished after being told to stop; clean it up.
            stopLoser(idx);
            return;
          }
          settled = true;
          try {
            if (fs.accessSync(targetPath)) fs.unlinkSync(targetPath);
            fs.renameSync(tmpPaths[idx], targetPath);

            if (job.md5) {
              onProgress(`[${src.label}] 校验 ${job.name} MD5...`);
              const actual = await computeMd5(targetPath);
              if (actual.toLowerCase() !== job.md5.toLowerCase()) {
                hilog.error(DOMAIN, TAG, '%{public}s MD5 mismatch: expected %{public}s, got %{public}s',
                            job.name, job.md5, actual);
                try { fs.unlinkSync(targetPath); } catch (_) { /* ignore */ }
                reject(new Error(`${job.name} MD5 校验失败 (期望 ${job.md5}, 实际 ${actual})，文件已删除，请重试`));
                return;
              }
              hilog.info(DOMAIN, TAG, '%{public}s MD5 OK (%{public}s)', job.name, actual);
            }

            const size = fs.statSync(targetPath).size;
            onProgress(`${job.name} 下载完成 (${Math.floor(size / (1024 * 1024))} MB)`);
            resolve();
          } catch (e) {
            reject(e as object);
          } finally {
            request.agent.remove(task.tid).catch(() => undefined);
            cleanupLosers(idx);  // double-check losers were cleaned
          }
        });

        task.on('failed', () => {
          if (settled) return;
          // Winner failing -> bubble up immediately; no more candidates.
          if (winnerIdx === idx) {
            settled = true;
            request.agent.show(task.tid).then(info => {
              reject(new Error(`${src.label} 下载失败: ${JSON.stringify(info.faults)}`));
            }).catch(() => {
              reject(new Error(`${src.label} 下载失败`));
            }).finally(() => {
              request.agent.remove(task.tid).catch(() => undefined);
              try {
                if (fs.accessSync(tmpPaths[idx])) fs.unlinkSync(tmpPaths[idx]);
              } catch (_) { /* ignore */ }
            });
            return;
          }
          // Loser failed -> count, and if all candidates failed, settle.
          taskFailures.push(idx);
          hilog.warn(DOMAIN, TAG, '%{public}s race source %{public}s failed (%{public}d/%{public}d)',
                     job.name, src.label, taskFailures.length + createFailures.length, job.sources.length);
          request.agent.remove(task.tid).catch(() => undefined);
          try {
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
          taskFailures.push(idx);
          request.agent.remove(task.tid).catch(() => undefined);
          if (taskFailures.length + createFailures.length >= job.sources.length) {
            settleAllFailed();
          }
        });
      }).catch((e: BusinessError) => {
        // agent.create itself failed (e.g. unsupported URL scheme). Count
        // this as a source failure and check the total.
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

/** Simple wrapper for the rare 1-source case (e.g. legacy direct-mirror only). */
async function downloadSingleSource(
  context: common.Context,
  dir: string,
  fileName: string,
  src: RaceSource,
  md5: string | null,
  onProgress: ProgressListener,
): Promise<void> {
  const targetPath = `${dir}/${fileName}`;
  onProgress(`[${src.label}] 下载 ${fileName}...`);
  hilog.info(DOMAIN, TAG, 'Downloading %{public}s from %{public}s: %{public}s',
             fileName, src.label, src.url);

  const tmpPath = `${targetPath}.tmp`;
  if (fs.accessSync(tmpPath)) fs.unlinkSync(tmpPath);

  await new Promise<void>((resolve, reject) => {
    let lastProgressTime = 0;
    const config: request.agent.Config = {
      action: request.agent.Action.DOWNLOAD,
      url: src.url,
      headers: { 'User-Agent': 'MiniCPMV-demo/1.0' },
      saveas: tmpPath,
      overwrite: true,
      method: 'GET',
      mode: request.agent.Mode.FOREGROUND,
      network: request.agent.Network.ANY,
      retry: false,
    };
    request.agent.create(context, config).then((task: request.agent.Task) => {
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
            onProgress(`${fileName}: ${mb} MB downloaded`);
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
          request.agent.remove(task.tid).catch(() => undefined);
        }
      });
      task.on('failed', () => {
        request.agent.show(task.tid).then(info => {
          reject(new Error(`Download failed: ${JSON.stringify(info.faults)}`));
        }).catch(() => {
          reject(new Error('Download failed'));
        }).finally(() => {
          request.agent.remove(task.tid).catch(() => undefined);
        });
      });
      task.start();
    }).catch((e: object) => reject(e));
  });

  if (md5) {
    onProgress(`[${src.label}] 校验 ${fileName} MD5...`);
    const actual = await computeMd5(targetPath);
    if (actual.toLowerCase() !== md5.toLowerCase()) {
      hilog.error(DOMAIN, TAG, '%{public}s MD5 mismatch: expected %{public}s, got %{public}s',
                  fileName, md5, actual);
      try { fs.unlinkSync(targetPath); } catch (_) { /* ignore */ }
      throw new Error(`${fileName} MD5 校验失败 (期望 ${md5}, 实际 ${actual})，文件已删除，请重试`);
    }
    hilog.info(DOMAIN, TAG, '%{public}s MD5 OK (%{public}s)', fileName, actual);
  }

  const size = fs.statSync(targetPath).size;
  onProgress(`${fileName} 下载完成 (${Math.floor(size / (1024 * 1024))} MB)`);
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
  for (const name of [model.ggufFileName, model.mmprojFileName]) {
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
