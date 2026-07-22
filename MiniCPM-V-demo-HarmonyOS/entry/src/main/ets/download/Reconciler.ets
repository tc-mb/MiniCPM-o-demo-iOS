// Filesystem-authoritative reconciliation utilities.
//
// Two responsibilities:
//   1. scanModelDirForTmp — list every .tmp / .r*.tmp under models/<id>/,
//      sum their sizes per expected file. This is the AUTHORITATIVE input
//      for bytesOnDisk (INVARIANT 1).
//   2. parseProgressMsg — best-effort extraction of (fileName, pct,
//      bytesOnDisk, bytesTotal) from a download message string. Used as
//      the OPTIMISTIC overlay between fs reconcile ticks (every 500 ms).
//
// Rule of thumb: when fs and parseProgressMsg disagree, fs wins.

import fs from '@ohos.file.fs';
import common from '@ohos.app.ability.common';

import { ModelInfo } from '../engine/ModelInfo';

/**
 * For each expected file name in `expectedFiles`, sum the sizes of every
 * `*.tmp` and `*.r<N>.tmp` file present under `models/<modelId>/`. The
 * race downloader (DownloadManager.downloadFileWithSources) writes to
 * `<fname>.r0.tmp` / `<fname>.r1.tmp` while racing, then renames the
 * winner to `<fname>` on completion — only the loser cleanups are
 * deleted, so the surviving tmp bytes ARE the in-flight download size.
 *
 * Returns a Map keyed by file name. Files with no tmp present have value 0.
 */
export function scanModelDirForTmp(
  context: common.Context,
  modelId: string,
  expectedFiles: string[],
): Map<string, number> {
  const result = new Map<string, number>();
  for (const fname of expectedFiles) {
    result.set(fname, 0);
  }

  const dir = `${context.filesDir}/models/${modelId}`;
  let entries: string[];
  try {
    entries = fs.listFileSync(dir);
  } catch (_) {
    return result; // dir does not exist yet — nothing in flight
  }

  for (const entry of entries) {
    if (!entry.endsWith('.tmp')) continue;

    let matchedFname: string | null = null;
    for (const fname of expectedFiles) {
      if (entry === `${fname}.tmp`) {
        matchedFname = fname;
        break;
      }
      // Race tmp: <fname>.r<N>.tmp
      const prefix = `${fname}.r`;
      const suffix = '.tmp';
      if (entry.startsWith(prefix) && entry.endsWith(suffix)) {
        const mid = entry.substring(prefix.length, entry.length - suffix.length);
        if (mid.length > 0 && /^\d+$/.test(mid)) {
          matchedFname = fname;
          break;
        }
      }
    }
    if (matchedFname === null) continue;

    try {
      const stat = fs.statSync(`${dir}/${entry}`);
      const prev = result.get(matchedFname) ?? 0;
      result.set(matchedFname, prev + stat.size);
    } catch (_) {
      // file vanished between listSync and statSync — ignore
    }
  }

  return result;
}

/**
 * The list of file names that we expect to see on disk for a given model.
 * Used by Reconciler to know which tmp files to scan.
 */
export function expectedFilesForModel(model: ModelInfo): string[] {
  const files: string[] = [model.ggufFileName];
  if (model.mmprojFileName) files.push(model.mmprojFileName);
  if (model.acousticFileName) files.push(model.acousticFileName);
  return files;
}

/**
 * Structured progress parsed from a download message string. All fields
 * optional — non-progress messages (e.g. "verifying md5…") yield empty.
 *
 * Recognised formats (from DownloadManager.ets):
 *   "<fname>: <pct>% (<mb>/<totalMb> MB) [<source>]"   — race / single, with Content-Length
 *   "<fname>: <mb> MB [<source>]"                       — race / single, no Content-Length
 *   "<fname>: <mb> MB"                                  — single source, no CL, no source label
 */
export interface ParsedProgress {
  fileName?: string;
  /** 0..1 */
  pct?: number;
  /** bytes */
  bytesOnDisk?: number;
  /** bytes; only set when the message includes a total */
  bytesTotal?: number;
  /** race winner / single source label */
  source?: string;
}

const RE_PCT_WITH_TOTAL = /^(.+?):\s*(\d+)%\s*\((\d+)\/(\d+)\s*MB\)(?:\s*\[(.+)\])?$/;
const RE_MB_ONLY = /^(.+?):\s*(\d+)\s*MB(?:\s*\[(.+)\])?$/;
const MB = 1024 * 1024;

export function parseProgressMsg(msg: string): ParsedProgress {
  const m1 = msg.match(RE_PCT_WITH_TOTAL);
  if (m1 !== null && m1.length >= 5) {
    const fileName = m1[1] ?? '';
    const pctStr = m1[2] ?? '0';
    const onDiskStr = m1[3] ?? '0';
    const totalStr = m1[4] ?? '0';
    const source = m1[5];
    const pctNum = parseInt(pctStr, 10);
    const onDiskNum = parseInt(onDiskStr, 10);
    const totalNum = parseInt(totalStr, 10);
    return {
      fileName: fileName.length > 0 ? fileName : undefined,
      pct: isFinite(pctNum) ? pctNum / 100 : undefined,
      bytesOnDisk: isFinite(onDiskNum) ? onDiskNum * MB : undefined,
      bytesTotal: isFinite(totalNum) ? totalNum * MB : undefined,
      source: source && source.length > 0 ? source : undefined,
    };
  }

  const m2 = msg.match(RE_MB_ONLY);
  if (m2 !== null && m2.length >= 3) {
    const fileName = m2[1] ?? '';
    const mbStr = m2[2] ?? '0';
    const source = m2[3];
    const mbNum = parseInt(mbStr, 10);
    return {
      fileName: fileName.length > 0 ? fileName : undefined,
      bytesOnDisk: isFinite(mbNum) ? mbNum * MB : undefined,
      source: source && source.length > 0 ? source : undefined,
    };
  }

  return {};
}
