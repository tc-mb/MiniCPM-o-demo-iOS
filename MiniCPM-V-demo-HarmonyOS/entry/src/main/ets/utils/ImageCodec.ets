// Image picker + decoder helpers. Mirrors the Android demo's
// MainActivity.handleSelectedImage flow (read URI -> decode bitmap ->
// re-encode as PNG bytes -> hand to native prefillImage).
//
// On HarmonyOS we use:
//   * @ohos.file.picker.PhotoViewPicker     -> system gallery
//   * @ohos.multimedia.image.ImageSource     -> decode any of jpg/png/heic
//   * imagePackerApi.packing                 -> re-encode as PNG so the
//                                               C++ side (stb_image) only
//                                               has to handle one format
//
// Down-sampling policy (TODO: revisit when we re-enable multi-slice prefill):
// the native side currently sets `image_max_slice_nums = 1`, so mtmd / clip
// will always squeeze the whole image into a single ~448 px overview tile
// regardless of input resolution. Sending the original 4000x3000 picture is
// therefore pure waste -- it bloats the NAPI ArrayBuffer copy, makes PNG
// re-encoding multi-second on a low-end CPU, and the model sees the exact
// same downscaled tile as if we'd sent a 1024-edge image. We cap the long
// edge at 1024 px before PNG encoding to keep memory/IPC cheap; once we
// (eventually) re-enable multi-slice prefill on Android / HarmonyOS this
// cap should be raised or removed.

import { picker } from '@kit.CoreFileKit';
import { image } from '@kit.ImageKit';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';

const DOMAIN = 0xC0DE;
const TAG = 'ImageCodec';

// Long-edge cap fed into the native prefill path. See file header.
const TARGET_LONG_EDGE = 1024;

export interface PickedImage {
  fileName: string;
  width:    number;     // dimensions actually fed to native (post-downsample)
  height:   number;
  pngBytes: ArrayBuffer;
  pixelMap: image.PixelMap;
}

/** Result of [pickImageOrVideoUri]: a URI plus whether it looks like a video. */
export interface PickedMediaUri {
  uri: string;
  isVideo: boolean;
}

const VIDEO_EXT_RE = /\.(mp4|mov|mkv|m4v|webm|3gp|3gpp|avi|wmv|flv|ts|m3u8)(\?|$)/i;

/**
 * Returns true if [uri] looks like a video by extension.  Used by the
 * chat-page entry point to dispatch the picker result to the
 * image-decode path or the video-extract path.  HarmonyOS picker URIs
 * include the original filename so the extension is reliable for the
 * built-in gallery.
 */
export function isVideoMediaUri(uri: string): boolean {
  return VIDEO_EXT_RE.test(uri);
}

/**
 * Opens the system photo picker filtered to BOTH images and videos.
 * The chat-page caller then dispatches to either [decodeImageFromUri]
 * or [VideoFrameExtractor.extractFromUri] based on the URI extension.
 * Returns null if the user cancels.
 */
export async function pickImageOrVideoUri(): Promise<PickedMediaUri | null> {
  const photoPicker = new picker.PhotoViewPicker();
  const opts = new picker.PhotoSelectOptions();
  // IMAGE_VIDEO_TYPE shows both photos and videos in a single picker -
  // matches the iOS demo's HXPhotoPicker which lets the user pick
  // either type with one tap.
  opts.MIMEType = picker.PhotoViewMIMETypes.IMAGE_VIDEO_TYPE;
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
  return { uri: uris[0], isVideo: isVideoMediaUri(uris[0]) };
}

/**
 * Decode + downscale + PNG-encode a single image from [uri].  Behaves
 * exactly like the original [pickAndDecodeImage] body, but accepts a
 * caller-provided URI so the picker step can be shared with video.
 */
export async function decodeImageFromUri(uri: string): Promise<PickedImage> {
  const file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
  let pixelMap: image.PixelMap | null = null;
  try {
    const src = image.createImageSource(file.fd);
    try {
      const info = await src.getImageInfo();
      const origW = info.size.width;
      const origH = info.size.height;
      const longEdge = Math.max(origW, origH);

      let currentScale = 1.0;
      const scaleFactor = 0.5; // 每次缩放比例
      // Step 1: pick a sampleSize (1/2/4/8) to keep peak decode memory low.
      // sampleSize is the cheapest way to downsample because it happens
      // during decode, before we ever materialise the full RGBA buffer.
      let sampleSize = 1;
      while (sampleSize < 8 && longEdge / (sampleSize * 2) >= TARGET_LONG_EDGE) {
        sampleSize *= 2;
        currentScale *= scaleFactor;
      }
      // const decodeOpts: image.DecodingOptions = sampleSize > 1
      //   ? { sampleSize }
      //   : {};
      // const pixelMap = await src.createPixelMap(decodeOpts);
      const newWidth = Math.floor(info.size.width * currentScale);
      const newHeight = Math.floor(info.size.height * currentScale);
      pixelMap = await src.createPixelMap({
        desiredSize: { width: newWidth, height: newHeight }
      });

      // Step 2: sampleSize is geometric, so the result might still be a bit
      // larger than TARGET_LONG_EDGE (e.g. a 2400 px image with sampleSize=2
      // lands at 1200 px). Snap to the cap with PixelMap.scale().
      const sampledLongEdge = longEdge / sampleSize;
      if (sampledLongEdge > TARGET_LONG_EDGE) {
        const ratio = TARGET_LONG_EDGE / sampledLongEdge;
        await pixelMap.scale(ratio, ratio);
      }

      // After the optional scale, `pixelMap.getImageInfo()` reflects the new
      // dimensions, which is what the model actually sees.
      const finalInfo = await pixelMap.getImageInfo();
      hilog.info(DOMAIN, TAG,
        'image downsampled: %{public}dx%{public}d -> %{public}dx%{public}d (sampleSize=%{public}d)',
        origW, origH, finalInfo.size.width, finalInfo.size.height, sampleSize);

      // Re-encode as PNG (lossless), quality 100. The native side accepts
      // any stb_image-supported format but always re-encoding to PNG keeps
      // the tested-path narrow.
      const packer = image.createImagePacker();
      let pngBytes: ArrayBuffer;
      try {
        pngBytes = await packer.packing(pixelMap, { format: 'image/png', quality: 100 });
      } finally {
        packer.release();
      }

      const fileName = uri.split('/').pop() ?? `image-${Date.now()}.png`;
      // Ownership of pixelMap is transferred to the caller (ChatMessage).
      const result: PickedImage = {
        fileName,
        width: finalInfo.size.width,
        height: finalInfo.size.height,
        pngBytes,
        pixelMap,
      };
      pixelMap = null; // prevent cleanup in finally
      return result;
    } finally {
      await src.release();
    }
  } finally {
    fs.closeSync(file);
    if (pixelMap !== null) {
      try { await pixelMap.release(); } catch (_) { /* ignore */ }
    }
  }
}

export function imageInfoString(img: PickedImage): string {
  const sizeKb = Math.floor(img.pngBytes.byteLength / 1024);
  return `${img.width} x ${img.height} (${sizeKb} KB)`;
}

/**
 * Backwards-compatible image-only picker, kept for the debug-image
 * code path.  New media flow uses [pickImageOrVideoUri] +
 * [decodeImageFromUri] / [VideoFrameExtractor.extractFromUri].
 */
export async function pickAndDecodeImage(): Promise<PickedImage | null> {
  const photoPicker = new picker.PhotoViewPicker();
  const opts = new picker.PhotoSelectOptions();
  opts.MIMEType = picker.PhotoViewMIMETypes.IMAGE_TYPE;
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
  return await decodeImageFromUri(uris[0]);
}
