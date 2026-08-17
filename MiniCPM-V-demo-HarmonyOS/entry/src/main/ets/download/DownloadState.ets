// Type definitions and constants for the download subsystem.
//
// INVARIANTS (must hold at every observable moment):
//
//   1. bytesOnDisk MUST come from fs.statSync(tmpPath).size, never from
//      dataPreferences / AppStorage / callback args. During active
//      download we ACCEPT callback-reported bytes as a 500ms optimistic
//      overlay, but every ≤5s an authoritative fs.statSync reconciles.
//
//   2. Derived fields (pct / speed / eta) are NEVER persisted. Each is
//      recomputed from bytesOnDisk + bytesTotal + clock at publish time.
//
//   3. dataPreferences stores BOOLEAN HINTS only (e.g. "has_pending:<fname>"),
//      never byte counts. Byte counts are always re-derived from fs on
//      observation.
//
//   4. After any state transition (running → paused / completed / failed),
//      the .tmp file MUST be fsync'd before AppStorage is updated. This
//      bounds post-kill drift to the OS page cache, not to unflushed
//      userspace buffers.
//
//   5. UI-displayed pct drift ≤ 5s vs actual fs bytes. Any larger drift
//      is a bug.

export type DownloadStatusKind =
  | 'idle'
  | 'running'
  | 'paused'
  | 'completed'
  | 'failed';

export interface DownloadStatus {
  kind: DownloadStatusKind;
  /**
   * Which model this download is for. Used by the page to distinguish
   * "the model I'm viewing is downloading" (show progress) from
   * "a different model is downloading" (show one-line notice).
   */
  modelId?: string;
  /** Currently-downloading file name, when known. */
  fileName?: string;
  /** Race winner / single source label, when known. */
  source?: string;
  /** Error message, only when kind === 'failed'. */
  error?: string;
}

export interface DownloadSnapshot {
  status: DownloadStatus;
  /** INVARIANT 1: always reconciled from fs.statSync (.tmp file). */
  bytesOnDisk: number;
  /** Total expected bytes; 0 means unknown. */
  bytesTotal: number;
  /** INVARIANT 2: derived, never persisted. 0..1. */
  pct: number;
  /** INVARIANT 2: derived, never persisted. bytes/sec. */
  speed: number;
  /** INVARIANT 2: derived, never persisted. seconds. */
  eta: number;
  /** Localized progress message for direct UI display. */
  statusText: string;
  /** epoch ms of last publish. */
  lastUpdated: number;
}

export const IDLE_SNAPSHOT: DownloadSnapshot = {
  status: { kind: 'idle' },
  bytesOnDisk: 0,
  bytesTotal: 0,
  pct: 0,
  speed: 0,
  eta: 0,
  statusText: '',
  lastUpdated: 0,
};

/** AppStorage key for the global download snapshot. */
export const DOWNLOAD_STATE_KEY = 'minicpmv_download_state';
