// VoxCPM2 TTS page. Mirrors Android's TtsActivity.kt.
//
// Layout (top → bottom):
//   * AppBar: title + model manager button
//   * Text input area (multi-line, Voice Design hint)
//   * Parameter sliders (CFG, inference steps)
//   * Reference audio section (record / presets / preview / clear)
//   * Generate button
//   * Playback area (progress bar + play/stop)
//   * Status bar (engine state)

import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';
import resourceManager from '@ohos.resourceManager';
import abilityAccessCtrl, { Permissions } from '@ohos.abilityAccessCtrl';
import common from '@ohos.app.ability.common';
import { BusinessError } from '@ohos.base';

import { TtsEngine, TtsState, TtsStateName, DEFAULT_CFG_VALUE, DEFAULT_TIMESTEPS, MAX_TIMESTEPS } from '../engine/TtsEngine';
import { LlamaEngine } from '../engine/LlamaEngine';
import { AudioRecorder, RecorderError } from '../utils/AudioRecorder';
import { AudioPlayer } from '../utils/AudioPlayer';

const DOMAIN = 0xC0DE;
const TAG = 'TtsPage';

// Preset reference audio files bundled in rawfile/.
const PRESET_FEMALE = 'ref_female.wav';
const PRESET_MALE = 'ref_male.wav';

@Entry
@Component
struct TtsPage {
  @State stateName: TtsStateName = 'Uninitialized';
  @State errorText: string = '';
  @State inputText: string = '';
  @State cfgValue: number = DEFAULT_CFG_VALUE;
  @State timesteps: number = DEFAULT_TIMESTEPS;
  @State recording: boolean = false;
  @State recordDuration: number = 0;
  @State refWavPath: string = '';
  @State refWavLabel: string = '';
  @State showStepsWarning: boolean = false;
  @State playing: boolean = false;
  @State playProgress: number = 0;
  @State playTotal: number = 0;
  @State generating: boolean = false;
  @State outputWavPath: string = '';

  private engine: TtsEngine | null = null;
  private engineListener = (s: TtsState) => this.onEngineState(s);
  private recorder: AudioRecorder = new AudioRecorder();
  private player: AudioPlayer = new AudioPlayer();
  private hasAutoLoaded: boolean = false;
  private resMgr: resourceManager.ResourceManager | null = null;

  private str(name: string): string {
    return this.resMgr ? this.resMgr.getStringByNameSync(name) : name;
  }

  aboutToAppear(): void {
    this.resMgr = getContext(this).resourceManager;
    this.engine = TtsEngine.getInstance(getContext(this));
    this.engine.addListener(this.engineListener);
  }

  aboutToDisappear(): void {
    if (this.engine) {
      this.engine.removeListener(this.engineListener);
    }
    this.player.stop();
  }

  onPageShow(): void {
    hilog.info(DOMAIN, TAG, 'onPageShow: state=%{public}s', this.stateName);
    if (this.engine) {
      const st = this.engine.getCurrentState();
      if (st.name === 'Uninitialized') {
        this.loadDefaultModel();
      }
    }
  }

  // ------------------------------------------------------------------
  // State machine
  // ------------------------------------------------------------------

  private onEngineState(state: TtsState): void {
    this.stateName = state.name;
    this.errorText = state.errorMessage ?? '';
    if (state.name === 'Error' && state.errorMessage) {
      promptAction.showToast({ message: `${this.str('status_error')}: ${state.errorMessage}`, duration: 3000 });
    }
  }

  private async loadDefaultModel(): Promise<void> {
    if (!this.engine) return;
    const ctx = getContext(this);
    const baseLmPath = LlamaEngine.modelPath(ctx);
    const acousticPath = LlamaEngine.acousticPath(ctx);
    if (!fs.accessSync(baseLmPath) || !acousticPath || !fs.accessSync(acousticPath)) {
      promptAction.showToast({ message: this.str('toast_model_file_missing'), duration: 4000 });
      return;
    }
    try {
      await this.engine.loadModel(baseLmPath, acousticPath);
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'loadDefaultModel failed: %{public}s', `${e}`);
    }
  }

  // ------------------------------------------------------------------
  // Recording
  // ------------------------------------------------------------------

  private async onClickRecord(): Promise<void> {
    // Runtime microphone permission — request once before first recording.
    if (this.recording) {
      // Stop recording
      const err = await this.recorder.stop();
      if (err === RecorderError.NONE) {
        this.refWavPath = this.recorder.getFilePath();
        this.refWavLabel = `${this.str('tts_recording')} (${this.recorder.getDurationSec().toFixed(1)}s)`;
        this.recordDuration = this.recorder.getDurationSec();
      } else {
        promptAction.showToast({ message: this.str('toast_record_failed'), duration: 2000 });
      }
      this.recording = false;
    } else {
      // Request microphone permission before starting
      const atManager = abilityAccessCtrl.createAtManager();
      try {
        const ctx = getContext(this) as common.UIAbilityContext;
        let grantStatus = atManager.checkAccessTokenSync(ctx.applicationInfo.accessTokenId, 'ohos.permission.MICROPHONE');
        if (grantStatus !== abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) {
          const result = await atManager.requestPermissionsFromUser(ctx, ['ohos.permission.MICROPHONE']);
          grantStatus = result.authResults[0];
        }
        if (grantStatus !== abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) {
          promptAction.showToast({ message: this.str('toast_mic_permission_denied'), duration: 2000 });
          return;
        }
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'Microphone permission check failed: %{public}s', `${e}`);
      }

      // Start recording
      const outPath = `${getContext(this).cacheDir}/ref_record.wav`;
      const err = await this.recorder.start(outPath);
      if (err === RecorderError.NONE) {
        this.recording = true;
      } else {
        promptAction.showToast({ message: this.str('toast_record_failed'), duration: 2000 });
      }
    }
  }

  // ------------------------------------------------------------------
  // Preset reference audio
  // ------------------------------------------------------------------

  private async onClickPreset(name: string, rawfileName: string): Promise<void> {
    try {
      const ctx = getContext(this);
      const rawBuf = await ctx.resourceManager.getRawFileContent(rawfileName);
      const outPath = `${ctx.cacheDir}/${rawfileName}`;
      const file = fs.openSync(outPath, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      fs.writeSync(file.fd, rawBuf.buffer);
      fs.closeSync(file);

      this.refWavPath = outPath;
      this.refWavLabel = name;
      promptAction.showToast({ message: this.str('tts_ref_selected').replace('%s', name), duration: 1500 });
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'load preset %{public}s failed: %{public}s', rawfileName, `${e}`);
      promptAction.showToast({ message: this.str('toast_preset_load_failed'), duration: 2000 });
    }
  }

  // ------------------------------------------------------------------
  // Generate
  // ------------------------------------------------------------------

  private async onClickGenerate(): Promise<void> {
    if (!this.engine) return;
    if (!this.inputText.trim()) {
      promptAction.showToast({ message: this.str('tts_empty_text'), duration: 1500 });
      return;
    }
    if (this.generating) return;

    this.generating = true;
    const outPath = `${getContext(this).cacheDir}/tts_output.wav`;
    // Delete previous output so AudioPlayer doesn't play stale data.
    try { if (fs.accessSync(outPath)) fs.unlinkSync(outPath); } catch (_) { /* ignore */ }

    try {
      await this.engine.generate(
        this.inputText.trim(),
        this.cfgValue,
        this.timesteps,
        this.refWavPath,
        outPath,
      );
      this.outputWavPath = outPath;
      promptAction.showToast({ message: this.str('tts_generation_done'), duration: 1500 });
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'generate failed: %{public}s', `${e}`);
      this.outputWavPath = '';
    } finally {
      this.generating = false;
    }
  }

  // ------------------------------------------------------------------
  // Playback
  // ------------------------------------------------------------------

  private async onClickPlay(): Promise<void> {
    if (!this.outputWavPath || !fs.accessSync(this.outputWavPath)) {
      promptAction.showToast({ message: this.str('tts_no_output'), duration: 1500 });
      return;
    }
    if (this.playing) {
      await this.player.stop();
      this.playing = false;
      return;
    }

    this.playing = true;
    this.playProgress = 0;
    try {
      await this.player.play(this.outputWavPath, (currentMs: number, totalMs: number) => {
        this.playProgress = currentMs;
        this.playTotal = totalMs;
      });
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'playback failed: %{public}s', `${e}`);
    } finally {
      this.playing = false;
    }
  }

  // ------------------------------------------------------------------
  // Navigation
  // ------------------------------------------------------------------

  private onClickModelManager(): void {
    router.pushUrl({ url: 'pages/ModelManager' }).catch((e: object) => {
      hilog.error(DOMAIN, TAG, 'pushUrl failed: %{public}s', `${e}`);
    });
  }

  // ------------------------------------------------------------------
  // Params
  // ------------------------------------------------------------------

  private onCfgChange(v: number): void {
    this.cfgValue = Math.round(v * 10) / 10; // snap to 0.1
  }

  private onStepsChange(v: number): void {
    this.timesteps = v;
    this.showStepsWarning = v > 8;
  }

  // ------------------------------------------------------------------
  // UI
  // ------------------------------------------------------------------

  build() {
    Column() {
      this.AppBar();
      Scroll() {
        Column({ space: 16 }) {
          // Big title
          Text($r('app.string.tts_title_brand'))
            .fontSize(28)
            .fontWeight(FontWeight.Bold)
            .fontColor($r('app.color.primary'))
            .width('100%')
            .textAlign(TextAlign.Center)
            .padding({ top: 8, bottom: 4 });
          // Subtitle
          Text($r('app.string.tts_subtitle'))
            .fontSize(14)
            .fontColor($r('app.color.on_surface_variant'))
            .width('100%')
            .textAlign(TextAlign.Center)
            .padding({ bottom: 4 });

          this.TextInputArea();
          this.ParameterSliders();
          this.ReferenceAudioSection();
          this.GenerateButton();
          this.PlaybackArea();
          this.StatusBar();
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 12, bottom: 24 });
      }
      .layoutWeight(1)
      .scrollable(ScrollDirection.Vertical)
      .scrollBar(BarState.Auto);
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.surface'));
  }

  @Builder AppBar() {
    Row() {
      Text($r('app.string.page_title_tts'))
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .fontColor($r('app.color.on_surface'))
        .layoutWeight(1)
        .padding({ left: 16 });

      Button({ type: ButtonType.Circle, stateEffect: true }) {
        Image($r('app.media.ic_settings'))
          .width(22).height(22)
          .fillColor($r('app.color.on_surface_variant'));
      }
      .width(40).height(40).margin({ right: 12 })
      .backgroundColor(Color.Transparent)
      .onClick(() => this.onClickModelManager());
    }
    .width('100%')
    .height(56)
    .alignItems(VerticalAlign.Center)
    .backgroundColor($r('app.color.surface'))
    .border({ width: { bottom: 0.5 }, color: $r('app.color.outline') });
  }

  @Builder TextInputArea() {
    Column({ space: 8 }) {
      Text($r('app.string.tts_text_label'))
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .fontColor($r('app.color.on_surface'))
        .width('100%');
      TextArea({ placeholder: $r('app.string.tts_voice_design_hint'), text: this.inputText })
        .height(120)
        .fontSize(15)
        .backgroundColor($r('app.color.surface_variant'))
        .borderRadius(12)
        .padding(12)
        .enabled(!this.generating)
        .onChange((v: string) => { this.inputText = v; });
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start);
  }

  @Builder ParameterSliders() {
    Column({ space: 16 }) {
      // CFG slider
      Column({ space: 4 }) {
        Row() {
          Text(`${this.str('tts_cfg_label')}: ${this.cfgValue.toFixed(1)}`)
            .fontSize(14)
            .fontColor($r('app.color.on_surface'));
        }
        .width('100%');
        Slider({
          value: this.cfgValue,
          min: 0.5,
          max: 5.0,
          step: 0.1,
          style: SliderStyle.InSet,
        })
          .width('100%')
          .enabled(!this.generating)
          .onChange((v: number) => this.onCfgChange(v));
      }
      .width('100%');

      // Inference steps slider
      Column({ space: 4 }) {
        Row() {
          Text(`${this.str('tts_steps_label')}: ${this.timesteps}`)
            .fontSize(14)
            .fontColor($r('app.color.on_surface'));
          if (this.showStepsWarning) {
            Text(`  ⚠ ${this.str('tts_steps_warning')}`)
              .fontSize(12)
              .fontColor('#E6A817');
          }
        }
        .width('100%');
        Slider({
          value: this.timesteps,
          min: 1,
          max: MAX_TIMESTEPS,
          step: 1,
          style: SliderStyle.InSet,
        })
          .width('100%')
          .enabled(!this.generating)
          .onChange((v: number) => this.onStepsChange(v));
      }
      .width('100%');
    }
    .width('100%');
  }

  @Builder ReferenceAudioSection() {
    Column({ space: 8 }) {
      Text(this.str('tts_ref_audio_label'))
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .fontColor($r('app.color.on_surface'))
        .width('100%');

      Row({ space: 8 }) {
        // Record button
        Button(this.recording ? this.str('tts_stop_recording_item') : this.str('tts_recording_item'))
          .height(36)
          .fontSize(13)
          .backgroundColor(this.recording ? '#FF4444' : $r('app.color.surface_variant'))
          .fontColor(this.recording ? Color.White : $r('app.color.on_surface'))
          .borderRadius(18)
          .onClick(() => this.onClickRecord());

        // Preset female
        Button(this.str('tts_preset_female'))
          .height(36)
          .fontSize(13)
          .backgroundColor($r('app.color.surface_variant'))
          .fontColor($r('app.color.on_surface'))
          .borderRadius(18)
          .onClick(() => this.onClickPreset(this.str('tts_preset_female'), PRESET_FEMALE));

        // Preset male
        Button(this.str('tts_preset_male'))
          .height(36)
          .fontSize(13)
          .backgroundColor($r('app.color.surface_variant'))
          .fontColor($r('app.color.on_surface'))
          .borderRadius(18)
          .onClick(() => this.onClickPreset(this.str('tts_preset_male'), PRESET_MALE));
      }
      .width('100%');

      // Current reference display
      if (this.refWavLabel) {
        Row({ space: 8 }) {
          Text(this.refWavLabel)
            .fontSize(13)
            .fontColor($r('app.color.primary'))
            .layoutWeight(1);
          Text(this.str('tts_clear_ref'))
            .fontSize(13)
            .fontColor($r('app.color.on_surface_variant'))
            .onClick(() => {
              this.refWavPath = '';
              this.refWavLabel = '';
            });
        }
        .width('100%')
        .padding({ top: 4 });
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start);
  }

  @Builder GenerateButton() {
    Button(this.generating ? this.str('tts_generating') : this.str('tts_generate'))
      .width('100%')
      .height(48)
      .fontSize(16)
      .backgroundColor(this.generating ? $r('app.color.surface_variant') : $r('app.color.primary'))
      .fontColor(this.generating ? $r('app.color.on_surface_variant') : $r('app.color.on_primary'))
      .borderRadius(24)
      .enabled(this.stateName === 'Ready' && !this.generating)
      .onClick(() => this.onClickGenerate());
  }

  @Builder PlaybackArea() {
    if (this.outputWavPath && fs.accessSync(this.outputWavPath)) {
      Column({ space: 8 }) {
        Text(this.str('tts_output_label'))
          .fontSize(14)
          .fontWeight(FontWeight.Medium)
          .fontColor($r('app.color.on_surface'))
          .width('100%');

        Row({ space: 12 }) {
          Button(this.playing ? this.str('tts_stop') : this.str('tts_play'))
            .height(36)
            .fontSize(14)
            .backgroundColor($r('app.color.primary'))
            .fontColor($r('app.color.on_primary'))
            .borderRadius(18)
            .padding({ left: 20, right: 20 })
            .enabled(this.stateName !== 'Generating')
            .onClick(() => this.onClickPlay());

          Column({ space: 2 }) {
            if (this.playing || this.playProgress > 0) {
              Progress({ value: this.playTotal > 0 ? this.playProgress : 0, total: this.playTotal > 0 ? this.playTotal : 100, type: ProgressType.Linear })
                .width('100%')
                .height(4);
              Text(`${(this.playProgress / 1000).toFixed(1)}s / ${(this.playTotal / 1000).toFixed(1)}s`)
                .fontSize(11)
                .fontColor($r('app.color.on_surface_variant'));
            }
          }
          .layoutWeight(1);
        }
        .width('100%');
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start);
    }
  }

  @Builder StatusBar() {
    Text(this.statusText())
      .fontSize(13)
      .fontColor($r('app.color.on_surface_variant'))
      .width('100%')
      .textAlign(TextAlign.Center)
      .padding({ top: 4, bottom: 4 });
  }

  private statusText(): string {
    switch (this.stateName) {
      case 'Uninitialized': return this.str('status_uninitialized');
      case 'LoadingModel':  return this.str('status_loading');
      case 'Ready':         return this.str('status_ready');
      case 'Generating':    return this.str('tts_generating') + '...';
      case 'Error':         return `${this.str('status_error')}: ${this.errorText}`;
      default:              return '';
    }
  }
}
