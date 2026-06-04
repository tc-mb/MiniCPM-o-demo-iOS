// WAV audio player using @ohos.multimedia.audio AudioRenderer.
// Reads the sample rate from the WAV header at runtime (not hardcoded),
// because VoxCPM2 output is 48 kHz while recordings are 16 kHz.
// Uses stream mode so the buffer stays small even for long audio.
//
// Mirrors Android's AudioTrack.MODE_STREAM approach in TtsActivity.kt.

import audio from '@ohos.multimedia.audio';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';

const DOMAIN = 0xC0DE;
const TAG = 'AudioPlayer';

const STREAM_CHUNK_BYTES = 4096;

export type PlayProgressListener = (currentMs: number, totalMs: number) => void;

export interface WavHeaderInfo {
  sampleRate: number;
  dataOffset: number;
  dataSize: number;
}

export class AudioPlayer {
  private renderer: audio.AudioRenderer | null = null;
  private playing: boolean = false;

  /**
   * Read the WAV header at [path] and return { sampleRate, dataOffset, dataSize }.
   */
  static readWavHeader(path: string): WavHeaderInfo | null {
    try {
      const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
      const fd = file.fd;
      const header = new ArrayBuffer(44);
      fs.readSync(fd, header, { offset: 0, length: 44 });
      fs.closeSync(file);

      const view = new DataView(header);
      // "RIFF"
      if (view.getUint8(0) !== 0x52 || view.getUint8(1) !== 0x49 ||
          view.getUint8(2) !== 0x46 || view.getUint8(3) !== 0x46) {
        hilog.error(DOMAIN, TAG, 'Not a valid RIFF file');
        return null;
      }
      // "WAVE"
      if (view.getUint8(8) !== 0x57 || view.getUint8(9) !== 0x41 ||
          view.getUint8(10) !== 0x56 || view.getUint8(11) !== 0x45) {
        hilog.error(DOMAIN, TAG, 'Not a valid WAVE file');
        return null;
      }

      const sampleRate = view.getUint32(24, true);

      // Seek to "data" chunk
      let dataOffset = 40;
      // "data" at offset 36
      if (view.getUint8(36) === 0x64 && view.getUint8(37) === 0x61 &&
          view.getUint8(38) === 0x74 && view.getUint8(39) === 0x61) {
        dataOffset = 44; // after "data" + size (4 bytes)
      } else {
        // Search for "data" chunk
        const rawFile = fs.openSync(path, fs.OpenMode.READ_ONLY);
        const rawFd = rawFile.fd;
        const seekBuf = new ArrayBuffer(4096);
        fs.readSync(rawFd, seekBuf, { offset: 0, length: 4096 });
        fs.closeSync(rawFile);
        const seekView = new DataView(seekBuf);
        for (let i = 12; i < 4088; i++) {
          if (seekView.getUint8(i) === 0x64 && seekView.getUint8(i + 1) === 0x61 &&
              seekView.getUint8(i + 2) === 0x74 && seekView.getUint8(i + 3) === 0x61) {
            dataOffset = i + 8; // after "data" + size (4 bytes)
            break;
          }
        }
      }

      const dataSize = view.getUint32(dataOffset - 4, true);

      hilog.info(DOMAIN, TAG, 'WAV: %{public}d Hz, data at %{public}d (%{public}d bytes)',
                 sampleRate, dataOffset, dataSize);

      const result: WavHeaderInfo = {
        sampleRate: sampleRate,
        dataOffset: dataOffset,
        dataSize: dataSize,
      };
      return result;
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'readWavHeader failed: %{public}s', `${e}`);
      return null;
    }
  }

  /**
   * Play a WAV file. The sample rate is read from the header at runtime.
   * @param path Absolute path to the WAV file.
   * @param onProgress Optional callback with (currentMs, totalMs).
   */
  async play(path: string, onProgress: PlayProgressListener): Promise<void> {
    if (this.playing) {
      await this.stop();
    }

    const header = AudioPlayer.readWavHeader(path);
    if (!header) {
      throw new Error('Failed to parse WAV header');
    }

    const streamInfo: audio.AudioStreamInfo = {
      samplingRate: header.sampleRate,
      channels: audio.AudioChannel.CHANNEL_1,
      sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,
      encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW,
    };

    const rendererInfo: audio.AudioRendererInfo = {
      usage: audio.StreamUsage.STREAM_USAGE_MEDIA,
      rendererFlags: 0,
    };

    const rendererOptions: audio.AudioRendererOptions = {
      streamInfo: streamInfo,
      rendererInfo: rendererInfo,
    };

    try {
      this.renderer = await audio.createAudioRenderer(rendererOptions);
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'createAudioRenderer failed: %{public}s', `${e}`);
      throw new Error(`createAudioRenderer failed: ${e}`);
    }

    const totalSamples = header.dataSize / 2; // 16-bit = 2 bytes per sample
    const totalMs = Math.floor(totalSamples / header.sampleRate * 1000);
    let bytesWritten = 0;

    const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
    const fd = file.fd;
    let offset = header.dataOffset;
    const endOffset = header.dataOffset + header.dataSize;

    try {
      await this.renderer.start();
      this.playing = true;

      while (offset < endOffset && this.playing) {
        const remaining = endOffset - offset;
        const chunkSize = Math.min(STREAM_CHUNK_BYTES, remaining);
        const buf = new ArrayBuffer(chunkSize);
        const readLen = fs.readSync(fd, buf, { offset: offset, length: chunkSize });
        if (readLen <= 0) break;

        await this.renderer.write(buf.slice(0, readLen));
        bytesWritten += readLen;
        offset += readLen;

        const currentMs = Math.floor((bytesWritten / header.dataSize) * totalMs);
        onProgress(currentMs, totalMs);
      }
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'play streaming failed: %{public}s', `${e}`);
    } finally {
      fs.closeSync(file);
      await this.stop();
    }
  }

  /**
   * Stop playback and release the AudioRenderer.
   */
  async stop(): Promise<void> {
    this.playing = false;
    if (this.renderer) {
      try {
        await this.renderer.stop();
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'renderer.stop failed: %{public}s', `${e}`);
      }
      try {
        this.renderer.release();
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'renderer.release failed: %{public}s', `${e}`);
      }
      this.renderer = null;
    }
  }

  /** True while audio is actively playing. */
  isPlaying(): boolean {
    return this.playing;
  }
}
