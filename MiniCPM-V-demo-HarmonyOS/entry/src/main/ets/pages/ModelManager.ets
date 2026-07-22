// Model management page. Mirrors ModelManagerActivity.kt + activity_model_manager.xml
// from the Android demo.

import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';
import resourceManager from '@ohos.resourceManager';

import { LlamaEngine } from '../engine/LlamaEngine';
import { LlamaState, LlamaStateName } from '../engine/LlamaState';
import { TtsEngine, TtsState } from '../engine/TtsEngine';
import {
  ModelInfo,
  AVAILABLE_MODELS,
  DEFAULT_MODEL,
  findModelById,
} from '../engine/ModelInfo';
import { deleteSelectedModelFiles } from '../utils/DownloadManager';
import { LocaleManager, AppLanguage } from '../manager/LocaleManager';
import { ModelDownloadService } from '../download/ModelDownloadService';
import {
  DownloadSnapshot,
  DownloadStatusKind,
  IDLE_SNAPSHOT,
  DOWNLOAD_STATE_KEY,
} from '../download/DownloadState';

const DOMAIN = 0xC0DE;
const TAG = 'ModelManager';

@Entry
@Component
struct ModelManager {
  @State stateName: LlamaStateName = 'Uninitialized';
  @State statusText: string = '';
  @State selectedId: string = DEFAULT_MODEL.id;
  @State modelExists: boolean = false;
  @State loading: boolean = false;
  @State isModelReady: boolean = false;
  @State currentLang: AppLanguage = AppLanguage.ZH;
  // Download state is owned by ModelDownloadService and broadcast through
  // AppStorage; the page is a pure observer. @Watch bridges terminal
  // status transitions back into local @State (modelExists) + toasts.
  // Decorator argument must be a string literal (ArkTS restricts decorator
  // expressions to literals); keep it in sync with DOWNLOAD_STATE_KEY.
  @StorageLink('minicpmv_download_state') @Watch('onDlStateChange') dlState: DownloadSnapshot = IDLE_SNAPSHOT;
  private prevDlStatusKind: DownloadStatusKind = 'idle';
  private loadingLock: boolean = false;

  private engine: LlamaEngine | null = null;
  private engineListener = (s: LlamaState) => this.onEngineState(s);
  private ttsEngine: TtsEngine | null = null;
  private ttsEngineListener = (s: TtsState) => this.onTtsEngineState(s);
  private resMgr: resourceManager.ResourceManager | null = null;

  private str(name: string): string {
    return this.resMgr!.getStringByNameSync(name);
  }

  /**
   * Time-based throttle for action buttons. Prevents the double-tap /
   * accidental-triple-click scenario from kicking off concurrent state
   * transitions (e.g. user taps Pause twice and the second tap races with
   * the first tap's async publish, ending in an inconsistent snapshot).
   * 500 ms is chosen as a balance: too short and a single human tap can
   * span two windows (typical tap duration ~100 ms + travel time); too
   * long and rapid intentional back-to-back actions feel sluggish.
   */
  private lastActionAt: number = 0;
  private shouldHandleAction(): boolean {
    const now = Date.now();
    if (now - this.lastActionAt < 500) return false;
    this.lastActionAt = now;
    return true;
  }

  /**
   * True when the SELECTED model is the one currently downloading — used
   * to decide whether to show a progress bar vs a one-line notice.
   */
  private isThisModelDownloading(): boolean {
    return this.dlState.status.kind === 'running'
      && this.dlState.status.modelId === this.selectedId;
  }

  /**
   * True when the SELECTED model has a paused (partial) download. While
   * paused, the canonical resume action is the Resume button in the status
   * area — the bottom Download button must stay disabled so it cannot
   * start a second, conflicting download task against the same .tmp file.
   * This matters most after a cold-start reattach: start() would launch a
   * fresh task instead of resuming the registered one, and two tasks
   * writing the same .tmp corrupt the download.
   */
  private isThisModelPaused(): boolean {
    return this.dlState.status.kind === 'paused'
      && this.dlState.status.modelId === this.selectedId;
  }

  /**
   * True when ANY model is downloading, regardless of selection.
   */
  private isAnyModelDownloading(): boolean {
    return this.dlState.status.kind === 'running';
  }

  /**
   * Display name of the model currently being downloaded (for the
   * "正在下载 X" notice shown when the user is browsing a different model).
   * Returns empty string if no download is active.
   */
  private downloadingModelName(): string {
    const id = this.dlState.status.modelId;
    if (id === undefined) return '';
    try {
      return findModelById(id).displayName;
    } catch (_) {
      return id;
    }
  }

  aboutToAppear(): void {
    this.resMgr = getContext(this).resourceManager;
    this.engine = LlamaEngine.getInstance(getContext(this));
    this.ttsEngine = TtsEngine.getInstance(getContext(this));
    const m = LlamaEngine.getSelectedModel(getContext(this));
    this.selectedId = m.id;
    this.modelExists = LlamaEngine.modelsExist(getContext(this));
    this.engine.addListener(this.engineListener);
    this.ttsEngine.addListener(this.ttsEngineListener);
    this.currentLang = LocaleManager.currentLanguageSync();
    // Attach the download service to this ability's context (idempotent)
    // and trigger a filesystem-authoritative reconcile so the snapshot
    // reflects truth on page re-entry, not a stale callback value.
    ModelDownloadService.shared.attach(getContext(this));
    // Sync local prev-status tracker to whatever the service already published
    this.prevDlStatusKind = this.dlState.status.kind;
  }

  aboutToDisappear(): void {
    if (this.engine) this.engine.removeListener(this.engineListener);
    if (this.ttsEngine) this.ttsEngine.removeListener(this.ttsEngineListener);
  }

  // ------------------------------------------------------------------
  // State machine wiring (mirrors ModelManagerActivity.observeEngineState)
  // ------------------------------------------------------------------

  private onEngineState(state: LlamaState): void {
    // Reflect LLM engine state only when (a) a non-TTS model is selected
    // and (b) that model is the one actually loaded in the engine. The
    // engine is a singleton holding ONE model; without the loadedModelId
    // check the status line would mirror a global engine state that doesn't
    // belong to the selected model (e.g. "已就绪" for a model that isn't
    // loaded, after switching selection). addListener() also emits on
    // subscribe, so this additionally stops the unused engine's state from
    // clobbering on page re-entry.
    if (findModelById(this.selectedId).isTts) return;
    if (this.engine !== null && this.engine.getLoadedModelId() !== this.selectedId) return;
    this.stateName = state.name;
    switch (state.name) {
      case 'Uninitialized':           this.statusText = this.str('status_uninitialized');
                                       this.isModelReady = false; break;
      case 'Initializing':            this.statusText = this.str('status_initializing'); break;
      case 'Initialized':             this.statusText = this.str('status_initialized'); break;
      case 'LoadingModel':            this.statusText = this.str('status_loading'); break;
      case 'ModelReady':              this.statusText = this.str('status_ready');
                                       this.isModelReady = true; break;
      case 'ProcessingSystemPrompt':
      case 'ProcessingUserPrompt':
      case 'Generating':              this.statusText = this.str('status_generating'); break;
      case 'PrefillingImage':         this.statusText = this.str('status_prefilling_image'); break;
      case 'UnloadingModel':          this.statusText = this.str('status_unloading'); break;
      case 'Error':                   this.statusText = `${this.str('status_error')}: ${state.errorMessage ?? ''}`; break;
      default:                        this.statusText = ''; break;
    }
    // modelExists is managed by onSelectModel / download / delete —
    // do NOT update it here because the engine state callback may fire
    // for a stale model while getSelectedModel() already points to
    // the newly selected one.
  }

  private onTtsEngineState(state: TtsState): void {
    // Reflect TTS engine state only when a TTS model is selected AND it is
    // the one loaded. See onEngineState.
    if (!findModelById(this.selectedId).isTts) return;
    if (this.ttsEngine !== null && this.ttsEngine.getLoadedModelId() !== this.selectedId) return;
    switch (state.name) {
      case 'Uninitialized':  this.statusText = this.str('status_uninitialized');
                              this.isModelReady = false; break;
      case 'LoadingModel':   this.statusText = this.str('status_loading'); break;
      case 'Ready':          this.statusText = this.str('status_ready');
                              this.isModelReady = true; break;
      case 'Generating':     this.statusText = this.str('status_generating'); break;
      case 'Error':          this.statusText = `${this.str('status_error')}: ${state.errorMessage ?? ''}`; break;
      default:               this.statusText = ''; break;
    }
  }

  /**
   * Re-derive statusText / isModelReady for the SELECTED model. The status
   * line should describe the selected model — which is "ready" only when
   * the engine actually has THAT model loaded (loadedModelId === selectedId).
   * When the user selects a different, not-loaded model we drop to a neutral
   * status instead of mirroring the still-loaded model's state.
   */
  private syncStatusFromSelectedEngine(): void {
    const isTts = findModelById(this.selectedId).isTts;
    const loadedId = isTts
      ? (this.ttsEngine !== null ? this.ttsEngine.getLoadedModelId() : null)
      : (this.engine !== null ? this.engine.getLoadedModelId() : null);
    if (loadedId === this.selectedId) {
      if (isTts) {
        if (this.ttsEngine) this.onTtsEngineState(this.ttsEngine.getCurrentState());
      } else {
        if (this.engine) this.onEngineState(this.engine.getCurrentState());
      }
    } else {
      this.statusText = '';
      this.isModelReady = false;
    }
  }

  // ------------------------------------------------------------------
  // Actions
  // ------------------------------------------------------------------

  private onSelectModel(modelId: string): void {
    this.selectedId = modelId;
    // setSelectedModel syncs cachedSelectedId immediately so the
    // following modelsExist() reads the *new* model, not the old one.
    LlamaEngine.setSelectedModel(getContext(this), modelId);
    // dlState may reflect the previously-selected model — re-derive from fs
    // so the snapshot matches the new selection.
    ModelDownloadService.shared.reconcileNow();
    this.modelExists = LlamaEngine.modelsExist(getContext(this));
    // Selection changed → the status line is now owned by a different
    // engine. Re-derive it so we don't leave the previous model's engine
    // state (e.g. an LLM 'Ready') showing for a now-selected TTS model.
    this.syncStatusFromSelectedEngine();
    const m = findModelById(modelId);
    promptAction.showToast({ message: this.str('toast_model_selected').replace('%s', m.displayName), duration: 1500 });
    // Loading is an explicit action via the Load button — selecting a model
    // (even one already on disk) no longer auto-loads it.
  }

  private onClickDownload(): void {
    if (!this.shouldHandleAction()) return;
    if (this.modelExists) {
      promptAction.showToast({ message: this.str('toast_already_downloaded'), duration: 1500 });
      return;
    }
    if (this.isThisModelDownloading()) {
      // Double-tap on the model that's already downloading — ignore.
      return;
    }
    if (this.isThisModelPaused()) {
      // A paused download is resumed via the status-area Resume button,
      // not the Download button. Defensive guard (the button is also
      // disabled) — without it a cold-start paused task would be hit by
      // start(), spawning a SECOND task against the same .tmp file the
      // reattached task already owns.
      return;
    }
    if (this.isAnyModelDownloading()) {
      // A different model is downloading — refuse; can't queue.
      promptAction.showToast({
        message: this.str('toast_another_downloading').replace('%s', this.downloadingModelName()),
        duration: 2500,
      });
      return;
    }
    // A different model is paused — start() would route to resume() and
    // silently keep downloading the OLD model while the user thinks
    // they're starting the new one. Refuse and ask the user to resolve
    // the paused task first.
    if (this.dlState.status.kind === 'paused' && this.dlState.status.modelId !== this.selectedId) {
      promptAction.showToast({
        message: this.str('toast_another_paused').replace('%s', this.downloadingModelName()),
        duration: 3000,
      });
      return;
    }
    // Fire-and-forget — the service runs downloadModelsForSelected in the
    // background and publishes progress / completion / failure through
    // AppStorage. We observe via @StorageLink + @Watch.
    ModelDownloadService.shared.start();
  }

  private onClickPause(): void {
    if (!this.shouldHandleAction()) return;
    ModelDownloadService.shared.pause();
  }

  private onClickResume(): void {
    if (!this.shouldHandleAction()) return;
    ModelDownloadService.shared.resume();
  }

  private onClickCancel(): void {
    if (!this.shouldHandleAction()) return;
    ModelDownloadService.shared.cancel();
  }

  /**
   * Bridge download-status transitions back into local @State and toasts.
   * ArkUI fires this on every change to dlState; we de-dupe by tracking
   * the previous status kind and only reacting to actual transitions.
   */
  private onDlStateChange(): void {
    const kind = this.dlState.status.kind;
    if (kind === this.prevDlStatusKind) return;

    if (kind === 'completed' && this.prevDlStatusKind !== 'completed') {
      this.modelExists = LlamaEngine.modelsExist(getContext(this));
      promptAction.showToast({ message: this.str('download_complete_toast'), duration: 1500 });
    } else if (kind === 'failed' && this.prevDlStatusKind !== 'failed') {
      this.modelExists = LlamaEngine.modelsExist(getContext(this));
      const err = this.dlState.status.error ?? '';
      promptAction.showToast({
        message: this.str('toast_download_failed').replace('%s', err),
        duration: 4000,
      });
    }
    this.prevDlStatusKind = kind;
  }

  private onClickLoad(): void {
    if (!this.shouldHandleAction()) return;
    if (this.loadingLock) return;
    this.loadingLock = true;

    const ctx = getContext(this);
    const selectedModel = LlamaEngine.getSelectedModel(ctx);

    // TTS models (VoxCPM2): load both GGUF files via TtsEngine.
    if (selectedModel.isTts) {
      this.loadTtsModel();
      return;
    }

    // Non-TTS: load via LlamaEngine.
    if (!this.engine) { this.loadingLock = false; return; }
    const modelPath = LlamaEngine.modelPath(ctx);
    const mmprojPath = LlamaEngine.mmprojPath(ctx);
    if (!fs.accessSync(modelPath)) {
      promptAction.showToast({ message: this.str('toast_model_not_found'), duration: 4000 });
      this.loadingLock = false;
      return;
    }

    this.loading = true;
    this.statusText = this.str('status_loading');

    setTimeout(async () => {
      try {
        // loadModel auto-unloads if a model is already loaded,
        // so we don't need to call unloadModel() explicitly.
        const mm = (mmprojPath && fs.accessSync(mmprojPath)) ? mmprojPath : null;
        await this.engine!.loadModel(modelPath, mm);
        this.loading = false;
        this.loadingLock = false;
        promptAction.showToast({ message: this.str('toast_model_loaded'), duration: 1500 });
        router.replaceUrl({ url: 'pages/Index' });
        return;
      } catch (e) {
        hilog.error(DOMAIN, TAG, 'load failed: %{public}s', `${e}`);
        this.engine!.resetToInitialized();
        this.statusText = this.str('toast_load_failed').replace('%s', `${e}`);
        this.loading = false;
        this.loadingLock = false;
      }
    }, 50);
  }

  private loadTtsModel(): void {
    if (!this.ttsEngine) { this.loadingLock = false; return; }
    const ctx = getContext(this);
    const baseLmPath = LlamaEngine.modelPath(ctx);
    const acousticPath = LlamaEngine.acousticPath(ctx);

    if (!fs.accessSync(baseLmPath) || !acousticPath || !fs.accessSync(acousticPath)) {
      promptAction.showToast({ message: this.str('toast_model_not_found'), duration: 4000 });
      this.loadingLock = false;
      return;
    }

    // TtsEngine.loadModel handles reload internally (free + re-init).
    // No need to call free() separately here.
    this.loading = true;
    this.statusText = this.str('status_loading');

    (async () => {
      try {
        await this.ttsEngine!.loadModel(baseLmPath, acousticPath);
        this.loading = false;
        this.loadingLock = false;
        promptAction.showToast({ message: this.str('toast_model_loaded'), duration: 1500 });
        router.replaceUrl({ url: 'pages/TtsPage' });
        return;
      } catch (e) {
        hilog.error(DOMAIN, TAG, 'TTS load failed: %{public}s', `${e}`);
        this.statusText = this.str('toast_load_failed').replace('%s', `${e}`);
        this.loading = false;
        this.loadingLock = false;
      }
    })();
  }

  private onClickDelete(): void {
    if (!this.shouldHandleAction()) return;
    const m = findModelById(this.selectedId);
    const filesLine = m.mmprojFileName
      ? `\n• ${m.ggufFileName}\n• ${m.mmprojFileName}`
      : `\n• ${m.ggufFileName}`;
    AlertDialog.show({
      title: this.str('delete_dialog_title'),
      message: `${this.str('delete_dialog_message_prefix').replace('%s', m.displayName)}\n${filesLine}\n\n${this.str('delete_dialog_message_suffix')}`,
      autoCancel: true,
      primaryButton: { value: this.str('btn_delete'), action: () => this.doDelete() },
      secondaryButton: { value: this.str('btn_cancel'), action: () => undefined },
    });
  }

  private async doDelete(): Promise<void> {
    try {
      const deleted = await deleteSelectedModelFiles(getContext(this));
      this.modelExists = LlamaEngine.modelsExist(getContext(this));
      // After delete, the previous "completed" snapshot is stale — force a
      // fs reconcile so dlState reflects the now-empty model dir.
      ModelDownloadService.shared.reconcileNow();
      if (deleted) {
        this.statusText = this.str('model_files_deleted');
        promptAction.showToast({ message: this.str('model_files_deleted'), duration: 1500 });
      } else {
        promptAction.showToast({ message: this.str('toast_no_files_to_delete'), duration: 1500 });
      }
    } catch (e) {
      promptAction.showToast({ message: this.str('toast_delete_failed').replace('%s', `${e}`), duration: 3000 });
    }
  }

  private showLanguagePicker(): void {
    const options: AppLanguage[] = [AppLanguage.ZH, AppLanguage.EN];
    const current = this.currentLang;
    AlertDialog.show({
      title: this.str('language_picker_title'),
      message: ' ',
      autoCancel: true,
      primaryButton: {
        value: current === AppLanguage.ZH ? `${LocaleManager.displayName(AppLanguage.ZH)}  ✓` : LocaleManager.displayName(AppLanguage.ZH),
        action: () => {
          if (current !== AppLanguage.ZH) {
            this.switchLanguage(AppLanguage.ZH);
          }
        },
      },
      secondaryButton: {
        value: current === AppLanguage.EN ? `${LocaleManager.displayName(AppLanguage.EN)}  ✓` : LocaleManager.displayName(AppLanguage.EN),
        action: () => {
          if (current !== AppLanguage.EN) {
            this.switchLanguage(AppLanguage.EN);
          }
        },
      },
    });
  }

  private async switchLanguage(lang: AppLanguage): Promise<void> {
    await LocaleManager.setLanguage(getContext(this), lang);
    this.currentLang = lang;
    // Re-resolve all non-$r() strings after locale change.
    // Force a re-render by refreshing state-driven text.
    this.refreshLocalizedState();
  }

  private refreshLocalizedState(): void {
    // Re-evaluate statusText for the current engine state.
    if (this.engine) {
      const state = this.engine.getCurrentState();
      this.onEngineState(state);
    }
  }

  // ------------------------------------------------------------------
  // UI
  // ------------------------------------------------------------------

  build() {
    Column() {
      this.AppBar();
      this.LanguageRow();
      this.StatusBar();
      this.ModelList();
      this.ActionBar();
    }
    .width('100%').height('100%')
    .backgroundColor($r('app.color.surface'));
  }

  @Builder AppBar() {
    Row() {
      Button({ type: ButtonType.Circle, stateEffect: true }) {
        Text('←').fontSize(18);
      }
      .width(36).height(36)
      .backgroundColor($r('app.color.surface_variant'))
      .fontColor($r('app.color.on_surface'))
      .margin({ left: 12, right: 12 })
      .onClick(() => router.back());

      Text($r('app.string.page_title_model'))
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .fontColor($r('app.color.on_surface'))
        .layoutWeight(1);
    }
    .width('100%').height(56)
    .alignItems(VerticalAlign.Center)
    .border({ width: { bottom: 0.5 }, color: $r('app.color.outline') });
  }

  @Builder LanguageRow() {
    Row() {
      Text($r('app.string.settings_language'))
        .fontSize(16)
        .fontColor($r('app.color.on_surface'))
        .layoutWeight(1);
      Text(LocaleManager.displayName(this.currentLang))
        .fontSize(14)
        .fontColor($r('app.color.on_surface_variant'));
      Text(' ›')
        .fontSize(16)
        .fontColor($r('app.color.on_surface_variant'));
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor($r('app.color.surface'))
    .border({ width: { bottom: 0.5 }, color: $r('app.color.outline') })
    .onClick(() => this.showLanguagePicker());
  }

  @Builder StatusBar() {
    Column() {
      // Case 1: the selected model is the one downloading — full progress UI.
      if (this.isThisModelDownloading()) {
        Text(this.dlState.statusText)
          .fontSize(14)
          .fontColor($r('app.color.on_surface_variant'))
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 8 });
        Progress({ value: this.dlState.pct * 100, total: 100, type: ProgressType.Linear })
          .width('100%').height(4)
          .margin({ left: 16, right: 16, bottom: 8 });
        Row({ space: 8 }) {
          Button(this.str('btn_pause'))
            .layoutWeight(1).height(36)
            .backgroundColor($r('app.color.surface_variant'))
            .fontColor($r('app.color.on_surface'))
            .onClick(() => this.onClickPause());
          Button(this.str('btn_cancel'))
            .layoutWeight(1).height(36)
            .backgroundColor($r('app.color.surface_variant'))
            .fontColor($r('app.color.on_surface'))
            .onClick(() => this.onClickCancel());
        }
        .width('100%')
        .padding({ left: 16, right: 16, bottom: 8 });
      }
      // Case 2: a DIFFERENT model is downloading — one-line notice so the
      // user knows why their Download button is greyed out.
      else if (this.isAnyModelDownloading()) {
        Text(
          this.str('download_status_other_running')
            .replace('%s', this.downloadingModelName())
            .replace('%d', `${Math.floor(this.dlState.pct * 100)}`)
        )
          .fontSize(12)
          .fontColor($r('app.color.on_surface_variant'))
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 8 });
      }
      // Case 3: selected model has partial .tmp on disk (cold-start state
      // or after Pause). Offer Resume + Cancel buttons alongside the
      // status text — without these, the user has no in-page way to
      // resolve a paused task and would be stuck.
      else if (this.dlState.status.kind === 'paused' && this.dlState.status.modelId === this.selectedId) {
        Column() {
          Text(this.dlState.statusText)
            .fontSize(12)
            .fontColor($r('app.color.on_surface_variant'))
            .width('100%')
            .padding({ left: 16, right: 16, top: 12, bottom: 4 });
          Row({ space: 8 }) {
            Button(this.str('btn_resume'))
              .layoutWeight(1).height(36)
              .backgroundColor($r('app.color.surface_variant'))
              .fontColor($r('app.color.on_surface'))
              .onClick(() => this.onClickResume());
            Button(this.str('btn_cancel'))
              .layoutWeight(1).height(36)
              .backgroundColor($r('app.color.surface_variant'))
              .fontColor($r('app.color.on_surface'))
              .onClick(() => this.onClickCancel());
          }
          .width('100%')
          .padding({ left: 16, right: 16, bottom: 8 });
        }
      }
      // Case 3b: a DIFFERENT model is paused — surface it so the user
      // knows why their Download button is going to be refused.
      else if (this.dlState.status.kind === 'paused') {
        Text(this.str('toast_another_paused').replace('%s', this.downloadingModelName()))
          .fontSize(12)
          .fontColor($r('app.color.on_surface_variant'))
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 8 });
      }
      // Case 4: nothing in flight — show engine state (loading / ready / etc.)
      else {
        Text(this.statusText)
          .fontSize(14)
          .fontColor($r('app.color.on_surface_variant'))
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 8 });
      }
    }
    .width('100%')
    .backgroundColor($r('app.color.surface'));
  }

  @Builder ModelList() {
    List() {
      ForEach(AVAILABLE_MODELS, (m: ModelInfo) => {
        ListItem() {
          Row() {
            Radio({ value: m.id, group: 'model' })
              .checked(this.selectedId === m.id)
              .onChange((checked: boolean) => {
                if (checked) this.onSelectModel(m.id);
              })
              .margin({ right: 12 });
            Column() {
              Text(m.displayName)
                .fontSize(16)
                .fontWeight(FontWeight.Medium)
                .fontColor($r('app.color.on_surface'));
              Text(this.str(m.descriptionResName))
                .fontSize(13)
                .fontColor($r('app.color.on_surface_variant'))
                .margin({ top: 4 });
              Text(m.mmprojFileName ? `${m.ggufFileName} + ${m.mmprojFileName}` : m.ggufFileName)
                .fontSize(11)
                .fontColor($r('app.color.on_surface_variant'))
                .margin({ top: 6 });
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1);
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 12 })
          .alignItems(VerticalAlign.Center);
        }
        .onClick(() => this.onSelectModel(m.id));
      }, (m: ModelInfo) => m.id)
    }
    .layoutWeight(1)
    .width('100%')
    .divider({ strokeWidth: 0.5, color: $r('app.color.outline') });
  }

  @Builder ActionBar() {
    Row({ space: 12 }) {
      Button(this.modelExists ? this.str('btn_downloaded') : this.str('btn_download'))
        .layoutWeight(1).height(44)
        .backgroundColor($r('app.color.surface_variant'))
        .fontColor($r('app.color.on_surface'))
        .enabled(!this.modelExists && !this.isThisModelDownloading() && !this.isThisModelPaused())
        .onClick(() => this.onClickDownload());

      Button(this.loading ? this.str('status_loading') : (this.isModelReady ? this.str('btn_reload') : this.str('btn_load')))
        .layoutWeight(1).height(44)
        .backgroundColor(this.loading ? $r('app.color.surface_variant') : $r('app.color.primary'))
        .fontColor(this.loading ? $r('app.color.on_surface_variant') : $r('app.color.on_primary'))
        .enabled(this.modelExists && !this.loading)
        .onClick(() => this.onClickLoad());

      Button($r('app.string.btn_delete'))
        .layoutWeight(1).height(44)
        .backgroundColor($r('app.color.surface_variant'))
        .fontColor($r('app.color.on_surface'))
        .enabled(this.modelExists && !this.loading)
        .onClick(() => this.onClickDelete());
    }
    .width('100%')
    .padding(12)
    .border({ width: { top: 0.5 }, color: $r('app.color.outline') });
  }
}
