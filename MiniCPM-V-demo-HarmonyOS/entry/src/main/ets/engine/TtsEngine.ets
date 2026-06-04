// TTS Engine for VoxCPM2. Mirrors Android TtsEngine.kt: singleton + serial
// Promise chain + state machine. Calls omni_napi.cpp native functions.
//
// State machine:
//   Uninitialized → LoadingModel → Ready → Generating → Ready (or Error)
//
// Key differences from LlamaEngine.ets:
//   - Native calls go through omni_napi.cpp (initOmni / ttsGenerate / ttsFree),
//     not llama_napi.cpp.
//   - No vision / mmproj / system prompt path. VoxCPM2 is a pure TTS model.
//   - generate() produces a WAV file synchronously (native side performs the
//     full CFM decode loop). The call is wrapped in setTimeout to avoid
//     blocking the UI thread.

import nativeEngine from 'libentry.so';
import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';

import { LlamaEngine } from './LlamaEngine';
import { ModelInfo, DEFAULT_MODEL } from './ModelInfo';

const TAG = 'TtsEngine';
const DOMAIN = 0xC0DE;

export type TtsStateName =
  | 'Uninitialized'
  | 'LoadingModel'
  | 'Ready'
  | 'Generating'
  | 'Error';

export class TtsState {
  readonly name: TtsStateName;
  readonly errorMessage: string | null;

  private constructor(name: TtsStateName, errorMessage: string | null = null) {
    this.name = name;
    this.errorMessage = errorMessage;
  }

  static readonly Uninitialized = new TtsState('Uninitialized');
  static readonly LoadingModel  = new TtsState('LoadingModel');
  static readonly Ready         = new TtsState('Ready');
  static readonly Generating    = new TtsState('Generating');

  static error(message: string): TtsState {
    return new TtsState('Error', message);
  }
}

export type TtsStateListener = (s: TtsState) => void;

// Default generation parameters. Mirrors Android TtsEngine.kt defaults and
// the VoxCPM2 CLI demo defaults.
export const DEFAULT_CFG_VALUE = 2.0;
export const DEFAULT_TIMESTEPS = 5;
export const MAX_TIMESTEPS = 20;

export class TtsEngine {
  private static instance: TtsEngine | null = null;

  private readonly context: common.Context;
  private state: TtsState = TtsState.Uninitialized;
  private listeners: TtsStateListener[] = [];
  private chain: Promise<void> = Promise.resolve();

  getCurrentState(): TtsState {
    return this.state;
  }

  private constructor(context: common.Context) {
    this.context = context;
  }

  static getInstance(context: common.Context): TtsEngine {
    if (TtsEngine.instance == null) {
      TtsEngine.instance = new TtsEngine(context);
    }
    return TtsEngine.instance;
  }

  // ------------------------------------------------------------------
  // State machine plumbing (mirrors LlamaEngine.ets)
  // ------------------------------------------------------------------

  addListener(l: TtsStateListener): void {
    this.listeners.push(l);
    l(this.state);
  }

  removeListener(l: TtsStateListener): void {
    const i = this.listeners.indexOf(l);
    if (i >= 0) this.listeners.splice(i, 1);
  }

  private setState(s: TtsState): void {
    this.state = s;
    for (const l of this.listeners) {
      try { l(s); } catch (e) {
        hilog.error(DOMAIN, TAG, 'state listener threw: %{public}s', `${e}`);
      }
    }
  }

  private serial<T>(fn: () => Promise<T>): Promise<T> {
    let resolveT: (v: T) => void;
    let rejectT:  (e: object) => void;
    const out = new Promise<T>((res, rej) => { resolveT = res; rejectT = rej; });
    this.chain = this.chain.then(async () => {
      try {
        resolveT(await fn());
      } catch (e) {
        rejectT(e as object);
      }
    });
    return out;
  }

  // ------------------------------------------------------------------
  // Lifecycle
  // ------------------------------------------------------------------

  loadModel(baseLmPath: string, acousticPath: string): Promise<void> {
    return this.serial(async () => {
      // Reload support: if a TTS model is already loaded, free it first.
      // The native singleton g_runtime is thread-safe because all
      // operations go through this same serial chain.
      if (this.state.name === 'Ready' || this.state.name === 'Generating') {
        hilog.info(DOMAIN, TAG, 'Freeing existing VoxCPM2 runtime before reload');
        nativeEngine.ttsFree();
        this.setState(TtsState.Uninitialized);
      }
      if (this.state.name !== 'Uninitialized') {
        throw new Error(`Cannot load in state ${this.state.name}`);
      }
      this.setState(TtsState.LoadingModel);

      LlamaEngine.requireRegularFile(baseLmPath, 'BaseLM');
      LlamaEngine.requireRegularFile(acousticPath, 'acoustic');

      hilog.info(DOMAIN, TAG, 'Loading VoxCPM2: baseLm=%{public}s acoustic=%{public}s',
                 baseLmPath, acousticPath);

      // initOmni resolves to "OK" on success, rejects with error text on failure.
      const result = await nativeEngine.initOmni(baseLmPath, acousticPath);
      // On success (should be "OK"), proceed.
      hilog.info(DOMAIN, TAG, 'initOmni success: %{public}s', result);

      hilog.info(DOMAIN, TAG, 'VoxCPM2 loaded successfully');
      this.setState(TtsState.Ready);
    });
  }

  /**
   * Generate WAV via native VoxCPM2Runtime.  The CFM decode loop (10-60 s on
   * a phone-class CPU) now runs on a libuv worker thread (napi_async_work),
   * so the JS main thread stays responsive.
   */
  generate(
    text: string,
    cfgValue: number = DEFAULT_CFG_VALUE,
    timesteps: number = DEFAULT_TIMESTEPS,
    refWavPath: string = '',
    outputPath: string,
  ): Promise<void> {
    return this.serial(async () => {
      if (this.state.name !== 'Ready') {
        throw new Error(`Cannot generate in state ${this.state.name}`);
      }
      if (!text.trim()) {
        throw new Error('Text must not be empty');
      }

      this.setState(TtsState.Generating);
      hilog.info(DOMAIN, TAG, 'Generating TTS: text=%{public}s cfg=%{public}f steps=%{public}d ref=%{public}s',
                 text, cfgValue, timesteps, refWavPath || '(none)');

      const result = await nativeEngine.ttsGenerate(text, cfgValue, timesteps, refWavPath, outputPath);
      hilog.info(DOMAIN, TAG, 'ttsGenerate success: %{public}s', result);

      if (!fs.accessSync(outputPath)) {
        const err = new Error('TTS generation succeeded but output WAV not found');
        this.setState(TtsState.error(err.message));
        throw err;
      }

      hilog.info(DOMAIN, TAG, 'TTS generated: %{public}s', outputPath);
      this.setState(TtsState.Ready);
    });
  }

  free(): Promise<void> {
    return this.serial(async () => {
      if (this.state.name !== 'Uninitialized') {
        hilog.info(DOMAIN, TAG, 'Freeing TTS engine...');
        nativeEngine.ttsFree();
        this.setState(TtsState.Uninitialized);
      }
    });
  }

  destroy(): void {
    this.chain = this.chain.then(() => {
      if (this.state.name !== 'Uninitialized') {
        nativeEngine.ttsFree();
        this.setState(TtsState.Uninitialized);
      }
    });
  }

  // ------------------------------------------------------------------
  // Static helpers (mirrors LlamaEngine.ets static methods for TTS)
  // ------------------------------------------------------------------

  static baseLmPath(context: common.Context): string {
    return LlamaEngine.modelPath(context);
  }

  static acousticPath(context: common.Context): string {
    return LlamaEngine.acousticPath(context);
  }

  static modelsExist(context: common.Context): boolean {
    return LlamaEngine.modelsExist(context);
  }
}
