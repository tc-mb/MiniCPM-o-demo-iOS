// 16 kHz mono 16-bit PCM WAV recorder. Mirrors Android's AudioRecorder.kt:
// uses @ohos.multimedia.audio AudioCapturer to capture microphone input,
// writes a standard WAV file with proper header. The output file is suitable
// as a reference audio for VoxCPM2 Voice Cloning.
//
// Recording format: 16 kHz, mono, 16-bit PCM (s16le).

import audio from '@ohos.multimedia.audio';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';

const DOMAIN = 0xC0DE;
const TAG = 'AudioRecorder';

const SAMPLE_RATE = 16000;
const NUM_CHANNELS = 1; // mono
const BITS_PER_SAMPLE = 16;
const BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8;

export enum RecorderError {
  NONE = 0,
  ALREADY_RECORDING = 1,
  CREATE_FAILED = 2,
  START_FAILED = 3,
  STOP_FAILED = 4,
  WRITE_FAILED = 5,
}

export class AudioRecorder {
  private capturer: audio.AudioCapturer | null = null;
  private recording: boolean = false;
  private rawData: ArrayBuffer[] = [];
  private filePath: string = '';

  /**
   * Start recording. The microphone data is collected in memory; call
   * stop() to write the WAV file and retrieve the path.
   *
   * @param outputPath Absolute file path for the output WAV.
   * @returns RecorderError.NONE on success.
   */
  async start(outputPath: string): Promise<RecorderError> {
    if (this.recording) {
      hilog.warn(DOMAIN, TAG, 'start: already recording');
      return RecorderError.ALREADY_RECORDING;
    }

    this.rawData = [];
    this.filePath = outputPath;

    const streamInfo: audio.AudioStreamInfo = {
      samplingRate: SAMPLE_RATE,
      channels: audio.AudioChannel.CHANNEL_1,
      sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,
      encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW,
    };

    const capturerInfo: audio.AudioCapturerInfo = {
      source: audio.SourceType.SOURCE_TYPE_MIC,
      capturerFlags: 0,
    };

    const capturerOptions: audio.AudioCapturerOptions = {
      streamInfo: streamInfo,
      capturerInfo: capturerInfo,
    };

    try {
      this.capturer = await audio.createAudioCapturer(capturerOptions);
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'createAudioCapturer failed: %{public}s', `${e}`);
      return RecorderError.CREATE_FAILED;
    }

    this.capturer.on('readData', (buf: ArrayBuffer) => {
      if (buf.byteLength > 0) {
        const copy = buf.slice(0);
        this.rawData.push(copy);
      }
    });

    this.capturer.on('stateChange', (state: audio.AudioState) => {
      hilog.info(DOMAIN, TAG, 'capturer state: %{public}d', state);
    });

    try {
      await this.capturer.start();
      this.recording = true;
      hilog.info(DOMAIN, TAG, 'Recording started -> %{public}s', outputPath);
      return RecorderError.NONE;
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'capturer.start failed: %{public}s', `${e}`);
      this.capturer.release();
      this.capturer = null;
      return RecorderError.START_FAILED;
    }
  }

  /**
   * Stop recording, write the WAV file, and release resources.
   * @returns RecorderError.NONE on success, or an error code.
   */
  async stop(): Promise<RecorderError> {
    if (!this.recording || !this.capturer) {
      hilog.warn(DOMAIN, TAG, 'stop: not recording');
      return RecorderError.NONE;
    }

    try {
      await this.capturer.stop();
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'capturer.stop failed: %{public}s', `${e}`);
    }

    try {
      this.capturer.release();
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'capturer.release failed: %{public}s', `${e}`);
    }
    this.capturer = null;
    this.recording = false;

    // Write WAV file
    try {
      this.writeWav();
      hilog.info(DOMAIN, TAG, 'Recording saved to %{public}s (%{public}d bytes)',
                 this.filePath, this.totalRawBytes());
      return RecorderError.NONE;
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'writeWav failed: %{public}s', `${e}`);
      return RecorderError.WRITE_FAILED;
    }
  }

  /** Absolute path to the recorded WAV file (valid after stop). */
  getFilePath(): string {
    return this.filePath;
  }

  /** Duration of the recording in seconds. */
  getDurationSec(): number {
    const totalSamples = this.totalRawBytes() / BYTES_PER_SAMPLE;
    return totalSamples / SAMPLE_RATE;
  }

  /** True while the AudioCapturer is actively capturing samples. */
  isRecording(): boolean {
    return this.recording;
  }

  // ------------------------------------------------------------------
  // Internal helpers
  // ------------------------------------------------------------------

  private totalRawBytes(): number {
    let n = 0;
    for (const b of this.rawData) {
      n += b.byteLength;
    }
    return n;
  }

  private writeWav(): void {
    const dataSize = this.totalRawBytes();
    const fileSize = 44 + dataSize;

    const file = fs.openSync(this.filePath, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
    const fd = file.fd;

    // RIFF header
    const header = new ArrayBuffer(44);
    const view = new DataView(header);

    // "RIFF"
    view.setUint8(0, 0x52); view.setUint8(1, 0x49); view.setUint8(2, 0x46); view.setUint8(3, 0x46);
    // chunkSize = fileSize - 8
    view.setUint32(4, fileSize - 8, true);
    // "WAVE"
    view.setUint8(8, 0x57); view.setUint8(9, 0x41); view.setUint8(10, 0x56); view.setUint8(11, 0x45);
    // "fmt "
    view.setUint8(12, 0x66); view.setUint8(13, 0x6D); view.setUint8(14, 0x74); view.setUint8(15, 0x20);
    // fmtSize = 16
    view.setUint32(16, 16, true);
    // audioFormat = 1 (PCM)
    view.setUint16(20, 1, true);
    // numChannels = 1
    view.setUint16(22, NUM_CHANNELS, true);
    // sampleRate = 16000
    view.setUint32(24, SAMPLE_RATE, true);
    // byteRate = sampleRate * numChannels * bitsPerSample/8
    view.setUint32(28, SAMPLE_RATE * NUM_CHANNELS * BYTES_PER_SAMPLE, true);
    // blockAlign = numChannels * bitsPerSample/8
    view.setUint16(32, NUM_CHANNELS * BYTES_PER_SAMPLE, true);
    // bitsPerSample = 16
    view.setUint16(34, BITS_PER_SAMPLE, true);
    // "data"
    view.setUint8(36, 0x64); view.setUint8(37, 0x61); view.setUint8(38, 0x74); view.setUint8(39, 0x61);
    // dataSize
    view.setUint32(40, dataSize, true);

    fs.writeSync(fd, header);

    // Write PCM data chunks
    for (const buf of this.rawData) {
      if (buf.byteLength > 0) {
        fs.writeSync(fd, buf);
      }
    }

    fs.closeSync(file);
  }
}
