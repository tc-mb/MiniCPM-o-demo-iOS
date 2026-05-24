// Uniform-sampling video frame extractor that mirrors the iOS demo's
// MBVideoFrameExtractor and the Android demo's VideoFrameExtractor.kt so
// the three platforms feed MiniCPM-V-4.6 with the exact same frame set
// for a given clip.
//
// Pipeline:
//   1. picker URI -> fs.openSync -> fd
//   2. media.createAVMetadataExtractor() to read duration + width + height
//   3. compute up to MAX_FRAMES timestamps (1 fps when ceil(durationSec)
//      <= MAX_FRAMES, otherwise uniform sampling - same rule iOS uses)
//   4. media.createAVImageGenerator() with the same fd, loop
//      fetchFrameByTime to grab each frame as a PixelMap
//   5. scale each PixelMap to a 1024px long-edge cap (matches the
//      single-image path in ImageCodec.ets) and PNG-encode
//
// Caller MUST run this off the UI thread; even a 30 s clip can take 3-5s
// to process on a mid-range HarmonyOS phone.

import { picker } from '@kit.CoreFileKit';
import { image } from '@kit.ImageKit';
import { media } from '@kit.MediaKit';
import { BusinessError } from '@ohos.base';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';
import resourceManager from '@ohos.resourceManager';

const DOMAIN = 0xC0DE;
const TAG = 'VideoFrameExtractor';

/** Hard cap on frames per turn; matches iOS demo on V4.6. */
export const MAX_VIDEO_FRAMES = 64;

/** Long-edge cap of every extracted frame, matches ImageCodec.ets. */
const TARGET_LONG_EDGE = 1024;

export interface PickedVideo {
  fileName: string;
  fileSizeBytes: number;
  durationMs: number;
  width: number;             // dimensions of each frame fed to native (post-downsample)
  height: number;
  thumbnail: image.PixelMap; // first frame, used by the chat-cell thumbnail
  frames: ArrayBuffer[];     // PNG-encoded frames in display order
}

/**
 * Opens the system picker filtered to videos only, then extracts up to
 * [MAX_VIDEO_FRAMES] uniformly-sampled frames from the chosen clip.
 * Returns null if the user cancels.
 */
export async function pickAndExtractVideo(): Promise<PickedVideo | null> {
  const photoPicker = new picker.PhotoViewPicker();
  const opts = new picker.PhotoSelectOptions();
  // VIDEO_TYPE here means: only let the user pick a video.  The
  // single-image picker (ImageCodec.pickAndDecodeImage) uses
  // IMAGE_TYPE.  We keep the two pickers separate rather than using
  // IMAGE_VIDEO_TYPE so the entry/dispatch code stays straightforward;
  // the chat-page caller decides which one to launch.
  opts.MIMEType = picker.PhotoViewMIMETypes.VIDEO_TYPE;
  opts.maxSelectNumber = 1;

  let uris: string[];
  try {
    const res = await photoPicker.select(opts);
    uris = res.photoUris;
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'photoPicker cancelled or failed: %{public}s', `${e}`);
    return null;
  }
  if (!uris || uris.length === 0) return null;
  return await extractFromUri(uris[0]);
}

/** Visible-for-tests: extract frames from an already-known URI. */
export async function extractFromUri(uri: string): Promise<PickedVideo> {
  const file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
  try {
    const stat = fs.statSync(file.fd);
    const fileSize = stat.size;

    // Step 1: metadata (duration, native width/height).
    const extractor = await media.createAVMetadataExtractor();
    let durationMs = 0;
    let nativeW = 0;
    let nativeH = 0;
    try {
      extractor.fdSrc = { fd: file.fd, offset: 0, length: fileSize };
      const meta = await extractor.fetchMetadata();
      durationMs = parseInt(meta.duration ?? '0', 10) || 0;
      nativeW = parseInt(meta.videoWidth ?? '0', 10) || 0;
      nativeH = parseInt(meta.videoHeight ?? '0', 10) || 0;
      hilog.info(DOMAIN, TAG,
        'Metadata: duration=%{public}sms, size=%{public}dx%{public}d, fileSize=%{public}d',
        `${durationMs}`, nativeW, nativeH, fileSize);
    } finally {
      try { await extractor.release(); } catch (_) { /* ignore */ }
    }
    if (durationMs <= 0) {
      throw new Error(`Unable to read video duration (uri=${uri})`);
    }

    // Step 2: compute sample timestamps - mirror iOS demo.
    const durationSec = durationMs / 1000.0;
    const timestampsSec = computeSampleTimestamps(durationSec);
    hilog.info(DOMAIN, TAG, 'Sampling %{public}d frames from %{public}sms video',
               timestampsSec.length, `${durationMs}`);

    // Step 3: compute per-frame target size, capped at TARGET_LONG_EDGE.
    let targetW = nativeW;
    let targetH = nativeH;
    if (nativeW > 0 && nativeH > 0) {
      const longEdge = Math.max(nativeW, nativeH);
      if (longEdge > TARGET_LONG_EDGE) {
        const ratio = TARGET_LONG_EDGE / longEdge;
        targetW = Math.floor(nativeW * ratio);
        targetH = Math.floor(nativeH * ratio);
      }
    }
    const pixelMapParams: media.PixelMapParams = (targetW > 0 && targetH > 0)
      ? { width: targetW, height: targetH }
      : {} as media.PixelMapParams;

    // Step 4: per-frame extraction.
    const generator = await media.createAVImageGenerator();
    const frames: ArrayBuffer[] = [];
    let thumbnail: image.PixelMap | null = null;
    const packer = image.createImagePacker();
    try {
      generator.fdSrc = { fd: file.fd, offset: 0, length: fileSize };

      for (let i = 0; i < timestampsSec.length; i++) {
        const tSec = timestampsSec[i];
        const timeUs = Math.round(tSec * 1_000_000);
        try {
          const pm = await generator.fetchFrameByTime(
            timeUs,
            media.AVImageQueryOptions.AV_IMAGE_QUERY_CLOSEST,
            pixelMapParams,
          );
          // Keep the first frame as the chat-cell thumbnail; the rest
          // are PNG-encoded and then released.
          if (thumbnail === null) {
            thumbnail = pm;
            // Also encode the first frame so prefill sees a leading frame.
            const pngBuf = await packer.packing(pm, { format: 'image/png', quality: 100 });
            frames.push(pngBuf);
          } else {
            const pngBuf = await packer.packing(pm, { format: 'image/png', quality: 100 });
            frames.push(pngBuf);
            try { await pm.release(); } catch (_) { /* ignore */ }
          }
        } catch (e) {
          hilog.warn(DOMAIN, TAG,
            'fetchFrameByTime #%{public}d at %{public}sms failed: %{public}s',
            i, `${timeUs / 1000}`, `${(e as BusinessError).message ?? e}`);
        }
      }
    } finally {
      try { packer.release(); } catch (_) { /* ignore */ }
      try { await generator.release(); } catch (_) { /* ignore */ }
    }

    if (frames.length === 0 || thumbnail === null) {
      throw new Error('No frames could be extracted from the video');
    }

    const fileName = uri.split('/').pop() ?? `video-${Date.now()}`;
    return {
      fileName,
      fileSizeBytes: fileSize,
      durationMs,
      width: targetW > 0 ? targetW : nativeW,
      height: targetH > 0 ? targetH : nativeH,
      thumbnail,
      frames,
    };
  } finally {
    fs.closeSync(file);
  }
}

/**
 * Returns the wall-clock timestamps (seconds) at which frames should be
 * extracted, mirroring iOS [MBVideoFrameExtractor].
 */
function computeSampleTimestamps(durationSec: number): number[] {
  if (durationSec <= 0) return [];
  const totalSecsCeil = Math.ceil(durationSec);
  const out: number[] = [];
  if (totalSecsCeil <= MAX_VIDEO_FRAMES) {
    // 1 fps - anchored to i + 0.5s to avoid the first SOF black frame.
    const n = Math.max(1, totalSecsCeil);
    for (let i = 0; i < n; i++) {
      out.push(Math.min(i + 0.5, durationSec - 0.001));
    }
  } else {
    // Uniform sampling: midpoint of MAX_VIDEO_FRAMES equal slices.
    const slice = durationSec / MAX_VIDEO_FRAMES;
    for (let i = 0; i < MAX_VIDEO_FRAMES; i++) {
      out.push(Math.min((i + 0.5) * slice, durationSec - 0.001));
    }
  }
  return out;
}

/** Human-readable summary used in chat-cell perf log. */
export function videoInfoString(v: PickedVideo, rm?: resourceManager.ResourceManager): string {
  const sec = v.durationMs / 1000.0;
  const sizeStr = v.fileSizeBytes > 0 ? ` (${Math.floor(v.fileSizeBytes / 1024)} KB)` : '';
  const videoLabel = rm ? rm.getStringByNameSync('video_label') : 'Video';
  const resStr = (v.width > 0 && v.height > 0) ? `${v.width}x${v.height}` : videoLabel;
  const durStr = `${sec.toFixed(1)}s`;
  const baseStr = `${resStr}${sizeStr} · ${durStr}`;
  if (rm) {
    return rm.getStringByNameSync('video_frame_info')
      .replace('%s', baseStr)
      .replace('%d', `${v.frames.length}`);
  }
  return `${baseStr} · ${v.frames.length} frames`;
}
