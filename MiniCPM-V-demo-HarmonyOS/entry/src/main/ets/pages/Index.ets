// Chat page. Mirrors MainActivity.kt + activity_main.xml from the Android demo.
//
// Layout (top → bottom):
//   * AppBar: title + 3 trailing icons (image / clear / model manager)
//   * List: LazyForEach over ChatMessageList, three ItemTemplates
//   * Input bar: TextArea + send button, keyboard-aware
//
// State observed:
//   * engine.state — drives input enabled, keeps title status-line in sync
//   * messageList  — drives the list

import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import hilog from '@ohos.hilog';
import fs from '@ohos.file.fs';

import { LlamaEngine, DEFAULT_PREDICT_LENGTH, TokenStream } from '../engine/LlamaEngine';
import { LlamaState, LlamaStateName } from '../engine/LlamaState';
import { ChatMessage } from '../data/ChatMessage';
import { ChatMessageList } from '../components/ChatMessageList';
import { WelcomeCard } from '../components/WelcomeCard';
import { UserMessageItem } from '../components/UserMessageItem';
import { AiMessageItem } from '../components/AiMessageItem';
import { ImageSliceDialog } from '../components/ImageSliceDialog';
import {
  pickImageOrVideoUri,
  decodeImageFromUri,
  imageInfoString,
  PickedImage,
  PickedMediaUri,
} from '../utils/ImageCodec';
import {
  extractFromUri as extractVideoFromUri,
  videoInfoString,
  PickedVideo,
} from '../utils/VideoFrameExtractor';

const DOMAIN = 0xC0DE;
const TAG = 'Index';

// Debug-only: list of test images bundled in `entry/src/main/resources/rawfile/`.
// They are surfaced through a long-press on the image button so QA can verify
// the vision path on a fresh device with no media permissions / gallery items.
class DebugImageChoice {
  readonly name: string;
  readonly file: string;
  readonly info: string;
  constructor(name: string, file: string, info: string) {
    this.name = name;
    this.file = file;
    this.info = info;
  }
}

const DEBUG_IMAGE_CHOICES: DebugImageChoice[] = [
  new DebugImageChoice('红方框 + 蓝圆 (几何)',         'test_image.png',        '480x320 PNG'),
  new DebugImageChoice('火车票 D705 北京→上海 (中文OCR)', 'test_ticket.jpg',       '1000x675 JPG'),
  new DebugImageChoice('中国南航 A380 飞机 (实拍)',      'test_airplane.jpg',     '1920x1440 JPG'),
];

@Entry
@Component
struct Index {
  @State stateName: LlamaStateName = 'Uninitialized';
  @State isModelReady: boolean = false;
  @State isVisionSupported: boolean = false;
  @State inputText: string = '';
  @State messageList: ChatMessageList = new ChatMessageList();

  private engine: LlamaEngine | null = null;
  private engineListener = (s: LlamaState) => this.onEngineState(s);
  private hasAutoLoaded: boolean = false;
  private messageIdCounter: number = 1;
  private currentStream: TokenStream | null = null;
  private isImagePrefilled: boolean = false;
  private listScroller: Scroller = new Scroller();

  // Slice cap currently shown by the dialog.  We pre-warm it from prefs
  // in aboutToAppear so the dialog opens to the right starting position
  // even if the user hasn't loaded a model yet.
  @State imageMaxSlice: number = 1;
  // Lazy: rebuilt each time the user taps the slider button so that
  // `initialValue` reflects the latest persisted choice.
  private imageSliceDialogController: CustomDialogController | null = null;

  aboutToAppear(): void {
    this.engine = LlamaEngine.getInstance(getContext(this));
    this.messageList.push(ChatMessage.welcome());
    this.engine.addListener(this.engineListener);
    // Warm the slice-cap cache so the dialog shows the persisted value
    // on first open without flickering through the default.
    LlamaEngine.getImageMaxSliceNums(getContext(this)).then((v: number) => {
      this.imageMaxSlice = v;
    });
  }

  aboutToDisappear(): void {
    if (this.engine) {
      this.engine.removeListener(this.engineListener);
    }
  }

  // ------------------------------------------------------------------
  // State machine wiring (mirrors MainActivity.observeEngineState)
  // ------------------------------------------------------------------

  private onEngineState(state: LlamaState): void {
    this.stateName = state.name;
    switch (state.name) {
      case 'Initialized':
        this.isModelReady = false;
        if (!this.hasAutoLoaded) {
          this.hasAutoLoaded = true;
          this.loadDefaultModel();
        }
        break;
      case 'ModelReady':
        this.isModelReady = true;
        this.isVisionSupported = !!this.engine && this.engine.isVisionSupported();
        break;
      case 'PrefillingImage':
        this.isModelReady = true;
        break;
      case 'Error':
        this.isModelReady = false;
        if (state.errorMessage) {
          promptAction.showToast({ message: `错误: ${state.errorMessage}`, duration: 3000 });
        }
        break;
      default:
        this.isModelReady = false;
        break;
    }
  }

  private async loadDefaultModel(): Promise<void> {
    if (!this.engine) return;
    const ctx = getContext(this);

    // Step 1: if the selected model is missing on disk, download it first.
    // The downloader is already serial-safe (LlamaEngine.serial chain) so we
    // can call it directly here.  This way users with a clean install don't
    // need to manually open the Model Manager page just to fetch a model.
    let modelPath = LlamaEngine.modelPath(ctx);
    let mmprojPath = LlamaEngine.mmprojPath(ctx);
    if (!fs.accessSync(modelPath) || !fs.accessSync(mmprojPath)) {
      hilog.info(DOMAIN, TAG, 'Default model missing, auto-downloading...');
      promptAction.showToast({ message: '首次使用，正在下载模型...', duration: 3000 });
      try {
        await LlamaEngine.downloadModels(ctx, (msg: string) => {
          hilog.info(DOMAIN, TAG, 'download: %{public}s', msg);
        });
      } catch (e) {
        hilog.error(DOMAIN, TAG, 'auto download failed: %{public}s', `${e}`);
        this.engine.resetToInitialized();
        this.hasAutoLoaded = false;
        promptAction.showToast({ message: `下载失败: ${e}`, duration: 5000 });
        return;
      }
      // Path strings may have been refreshed by legacy-name migrations.
      modelPath = LlamaEngine.modelPath(ctx);
      mmprojPath = LlamaEngine.mmprojPath(ctx);
    }

    if (!fs.accessSync(modelPath)) {
      promptAction.showToast({ message: '模型文件不存在', duration: 4000 });
      return;
    }
    try {
      const mm = fs.accessSync(mmprojPath) ? mmprojPath : null;
      await this.engine.loadModel(modelPath, mm);
      // No default system prompt: aligned with iOS opt-r1 (see LlamaEngine.ets).
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'loadDefaultModel failed: %{public}s', `${e}`);
      this.engine.resetToInitialized();
      this.hasAutoLoaded = false;
      promptAction.showToast({ message: `模型加载失败: ${e}`, duration: 4000 });
    }
  }

  // ------------------------------------------------------------------
  // User actions
  // ------------------------------------------------------------------

  private onClickClear(): void {
    // ArkUI AlertDialog renders `primaryButton` on the left and `secondaryButton`
    // on the right. Most users (especially those coming from iOS / Material)
    // expect the confirm action on the right, so we put `取消` first (left)
    // and `确定` second (right), and tint `确定` with the primary color to
    // keep it visually emphasised.
    AlertDialog.show({
      title: '清空对话',
      message: '确定要清空当前对话历史吗？模型上下文将被重置。',
      autoCancel: true,
      primaryButton: {
        value: '取消',
        action: () => undefined,
      },
      secondaryButton: {
        value: '确定',
        fontColor: $r('app.color.primary'),
        action: () => this.doClear(),
      },
    });
  }

  private async doClear(): Promise<void> {
    if (!this.engine) return;
    try {
      await this.engine.clearContext();
      // No default system prompt: aligned with iOS opt-r1.
      this.messageList.clear();
      this.messageList.push(ChatMessage.welcome());
      this.messageIdCounter = 1;
      this.isImagePrefilled = false;
      promptAction.showToast({ message: '对话已清空', duration: 1500 });
    } catch (e) {
      promptAction.showToast({ message: `清空失败: ${e}`, duration: 3000 });
    }
  }

  // Debug-only: long-press the image button to cycle through the bundled
  // rawfile test images.  Each long-press picks the next image (wraps around)
  // and prefills it (after clearing context).  Useful on a fresh device with
  // no media permission / no gallery items.
  private debugChoiceCursor: number = 0;
  private onLongPressImageDebug(): void {
    if (!this.isModelReady || !this.isVisionSupported) {
      promptAction.showToast({ message: '模型未就绪', duration: 1500 });
      return;
    }
    const c = DEBUG_IMAGE_CHOICES[this.debugChoiceCursor % DEBUG_IMAGE_CHOICES.length];
    this.debugChoiceCursor = (this.debugChoiceCursor + 1) % DEBUG_IMAGE_CHOICES.length;
    promptAction.showToast({ message: `调试图: ${c.name}`, duration: 1500 });
    this.prefillDebugImage(c);
  }

  private async prefillDebugImage(c: DebugImageChoice): Promise<void> {
    try {
      // Reset context first so each debug image starts in a clean state and
      // does not get confused with previously prefilled debug images.
      await this.engine!.clearContext();
      // No default system prompt: aligned with iOS opt-r1.
      this.messageList.clear();
      this.messageList.push(ChatMessage.welcome());
      this.messageIdCounter = 1;
      this.isImagePrefilled = false;

      const ctx = getContext(this);
      const rawBuf = await ctx.resourceManager.getRawFileContent(c.file);
      const bytes: ArrayBuffer = rawBuf.buffer.slice(
        rawBuf.byteOffset, rawBuf.byteOffset + rawBuf.byteLength
      );
      hilog.info(DOMAIN, TAG, 'Debug: prefilling rawfile/%{public}s (%{public}d bytes)',
                 c.file, bytes.byteLength);
      const id = this.messageIdCounter++;
      const msg = ChatMessage.user(id, `[调试图: ${c.name}]`, null, c.info, true);
      this.messageList.push(msg);
      this.scrollToEnd();
      await this.engine!.prefillImage(bytes);
      this.isImagePrefilled = true;
      const idx = this.messageList.indexOfId(id);
      if (idx >= 0) {
        const m = this.messageList.getData(idx);
        m.isPrefilling = false;
        this.messageList.notifyChange(idx);
      }
      promptAction.showToast({ message: `${c.name} 已 prefill`, duration: 2000 });
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'prefillDebugImage failed: %{public}s', `${e}`);
      promptAction.showToast({ message: `调试图 prefill 失败: ${e}`, duration: 3000 });
    }
  }

  private async onClickPickImage(): Promise<void> {
    if (!this.isModelReady) {
      promptAction.showToast({ message: '请先加载模型', duration: 1500 });
      return;
    }
    if (!this.isVisionSupported) {
      promptAction.showToast({ message: '当前模型不支持图像输入', duration: 1500 });
      return;
    }
    let pickedUri: PickedMediaUri | null = null;
    try {
      pickedUri = await pickImageOrVideoUri();
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'pickImageOrVideoUri failed: %{public}s', `${e}`);
      promptAction.showToast({ message: `选择媒体失败: ${e}`, duration: 3000 });
      return;
    }
    if (!pickedUri) return;

    if (pickedUri.isVideo) {
      await this.handlePickedVideo(pickedUri.uri);
    } else {
      await this.handlePickedImage(pickedUri.uri);
    }
  }

  private async handlePickedImage(uri: string): Promise<void> {
    let picked: PickedImage;
    try {
      picked = await decodeImageFromUri(uri);
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'decodeImageFromUri failed: %{public}s', `${e}`);
      promptAction.showToast({ message: `图片解码失败: ${e}`, duration: 3000 });
      return;
    }

    const id = this.messageIdCounter++;
    const message = ChatMessage.user(id, '', picked.pixelMap, imageInfoString(picked), true, false);
    this.messageList.push(message);
    this.scrollToEnd();

    try {
      await this.engine!.prefillImage(picked.pngBytes);
      this.isImagePrefilled = true;
      const idx = this.messageList.indexOfId(id);
      if (idx >= 0) {
        const m = this.messageList.getData(idx);
        m.isPrefilling = false;
        this.messageList.notifyChange(idx);
      }
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'prefillImage failed: %{public}s', `${e}`);
      promptAction.showToast({ message: `图片处理失败: ${e}`, duration: 3000 });
    }
  }

  /**
   * Video-understanding pipeline (iOS-equivalent
   * MBHomeViewController+CaptureVideo.processVideoFrame):
   * extract up to 64 uniformly-sampled frames, append a single chat
   * cell with the first frame as thumbnail, then hand the frames to
   * LlamaEngine.prefillVideoFrames which loops `prefillImage(...)`
   * under a temporary slice=1 cap.
   *
   * Gated to MiniCPM-V-4.6 because that's where iOS enables the
   * feature and where the native nCtx bump to 8192 takes effect (see
   * Prepare() in llama_napi.cpp).
   */
  private async handlePickedVideo(uri: string): Promise<void> {
    if (!this.engine!.isVideoUnderstandingSupported()) {
      promptAction.showToast({
        message: '视频理解仅在 MiniCPM-V-4.6 上可用，请前往“模型管理”切换模型',
        duration: 4000,
      });
      return;
    }
    const id = this.messageIdCounter++;
    const startMs = Date.now();
    let video: PickedVideo | null = null;
    try {
      video = await extractVideoFromUri(uri);
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'video extract failed: %{public}s', `${e}`);
      promptAction.showToast({ message: `视频抽帧失败: ${e}`, duration: 4000 });
      return;
    }
    if (!video) return;

    const baseInfo = videoInfoString(video);
    hilog.info(DOMAIN, TAG, 'Video info: %{public}s', baseInfo);

    const message = ChatMessage.user(id, '', video.thumbnail, baseInfo, true, true);
    this.messageList.push(message);
    this.scrollToEnd();

    try {
      await this.engine!.prefillVideoFrames(video.frames, (current: number, total: number): void => {
        const idx = this.messageList.indexOfId(id);
        if (idx >= 0) {
          const m = this.messageList.getData(idx);
          m.imageInfo = `${baseInfo} · 处理中 ${current}/${total}`;
          this.messageList.notifyChange(idx);
        }
      });
      this.isImagePrefilled = true;
      const elapsedSec = ((Date.now() - startMs) / 1000.0).toFixed(1);
      const idx = this.messageList.indexOfId(id);
      if (idx >= 0) {
        const m = this.messageList.getData(idx);
        m.imageInfo = `${baseInfo} · 预处理 ${elapsedSec}s`;
        m.isPrefilling = false;
        this.messageList.notifyChange(idx);
      }
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'prefillVideoFrames failed: %{public}s', `${e}`);
      const idx = this.messageList.indexOfId(id);
      if (idx >= 0) {
        this.messageList.removeAt(idx);
      }
      promptAction.showToast({ message: `视频处理失败: ${e}`, duration: 4000 });
    }
  }

  private onClickModelManager(): void {
    router.pushUrl({ url: 'pages/ModelManager' }).catch((e: object) => {
      hilog.error(DOMAIN, TAG, 'pushUrl failed: %{public}s', `${e}`);
    });
  }

  // Open the slice-cap picker. Re-create the dialog controller every
  // time so `initialValue` reflects whatever the user picked last (or
  // the persisted-prefs value on a cold open).
  private async onClickImageSlice(): Promise<void> {
    const initial = await LlamaEngine.getImageMaxSliceNums(getContext(this));
    this.imageMaxSlice = initial;
    this.imageSliceDialogController = new CustomDialogController({
      builder: ImageSliceDialog({
        engine: this.engine,
        initialValue: initial,
      }),
      autoCancel: true,
      customStyle: true,
      alignment: DialogAlignment.Center,
    });
    this.imageSliceDialogController.open();
  }

  private async onClickSend(): Promise<void> {
    if (!this.engine) return;
    const text = this.inputText.trim();
    if (text.length === 0) {
      promptAction.showToast({ message: '请输入文字消息', duration: 1500 });
      return;
    }
    this.inputText = '';

    const userId = this.messageIdCounter++;
    this.messageList.push(ChatMessage.user(userId, text));

    const aiId = this.messageIdCounter++;
    const aiMsg = ChatMessage.ai(aiId, '', true);
    this.messageList.push(aiMsg);
    const aiIdx = this.messageList.totalCount() - 1;
    this.scrollToEnd();
    this.isImagePrefilled = false;

    try {
      const stream = this.engine.sendUserPrompt(text, DEFAULT_PREDICT_LENGTH);
      this.currentStream = stream;
      let acc = '';
      while (true) {
        const chunk = await stream.next();
        if (chunk.done) break;
        acc += chunk.value;
        aiMsg.text = acc;
        this.messageList.notifyChange(aiIdx);
        this.scrollToEnd();
      }
      aiMsg.isGenerating = false;
      this.messageList.notifyChange(aiIdx);
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'sendUserPrompt failed: %{public}s', `${e}`);
      aiMsg.text = `${aiMsg.text}\n\n[生成失败: ${e}]`;
      aiMsg.isGenerating = false;
      this.messageList.notifyChange(aiIdx);
    } finally {
      this.currentStream = null;
    }
  }

  private onClickStopGenerating(): void {
    if (this.currentStream) {
      this.currentStream.cancel();
    } else if (this.engine) {
      this.engine.cancelGeneration();
    }
  }

  private scrollToEnd(): void {
    // Defer one frame so the new item has been measured.
    setTimeout(() => {
      const idx = this.messageList.totalCount() - 1;
      if (idx >= 0) {
        this.listScroller.scrollToIndex(idx);
      }
    }, 16);
  }

  // ------------------------------------------------------------------
  // UI
  // ------------------------------------------------------------------

  build() {
    Column() {
      this.AppBar();
      this.ChatList();
      this.InputBar();
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.surface'));
  }

  @Builder AppBar() {
    Row() {
      Column() {
        Text($r('app.string.page_title_chat'))
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor($r('app.color.on_surface'));
        Text(this.statusText())
          .fontSize(12)
          .fontColor($r('app.color.on_surface_variant'))
          .margin({ top: 2 });
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      .padding({ left: 16 });

      // Trailing icons (mirror Android demo's toolbar):
      //   * clear chat (delete icon)
      //   * model manager (settings icon)
      // The image picker icon used to live here too, but it has been moved
      // next to the input field at the bottom of the screen, matching the
      // Android/iOS layout (gallery button sits inside the input bar).
      Button({ type: ButtonType.Circle, stateEffect: true }) {
        Image($r('app.media.ic_image_slice'))
          .width(22).height(22)
          .fillColor($r('app.color.on_surface_variant'));
      }
      .width(40).height(40).margin({ right: 4 })
      .backgroundColor(Color.Transparent)
      .onClick(() => { this.onClickImageSlice(); });

      Button({ type: ButtonType.Circle, stateEffect: true }) {
        Image($r('app.media.ic_delete'))
          .width(22).height(22)
          .fillColor($r('app.color.on_surface_variant'));
      }
      .width(40).height(40).margin({ right: 4 })
      .backgroundColor(Color.Transparent)
      .enabled(this.isModelReady)
      .onClick(() => this.onClickClear());

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

  @Builder ChatList() {
    List({ space: 0, scroller: this.listScroller }) {
      LazyForEach(this.messageList, (msg: ChatMessage) => {
        ListItem() {
          if (msg.kind === 'welcome') {
            WelcomeCard({
              onSuggestion: (s: string) => {
                this.inputText = s;
                this.onClickSend();
              },
            });
          } else if (msg.kind === 'user') {
            UserMessageItem({ message: msg });
          } else {
            AiMessageItem({
              message: msg,
              onStop: () => this.onClickStopGenerating(),
            });
          }
        }
      }, (msg: ChatMessage) => ChatMessageList.keyForMessage(msg))
    }
    .layoutWeight(1)
    .width('100%')
    .listDirection(Axis.Vertical)
    .scrollBar(BarState.Auto);
  }

  // Bottom input bar.  Layout (mirrors Android `activity_main.xml`):
  //   [ TextArea (flex 1)  |  image-icon-button  |  send-icon-button ]
  //
  // Long-press on the image button triggers the bundled rawfile test images
  // for QA without a media library.
  @Builder InputBar() {
    Row({ space: 4 }) {
      TextArea({ placeholder: $r('app.string.input_hint'), text: this.inputText })
        .layoutWeight(1)
        .height(48)
        .padding({ left: 16, right: 12 })
        .backgroundColor(Color.Transparent)
        .borderRadius(24)
        .enabled(this.isModelReady)
        .onChange((v: string) => { this.inputText = v; });

      // Image picker button.  Single tap → system photo picker.  Long-press
      // → cycles through the bundled rawfile debug images.  Same gesture
      // wiring as the icon buttons in the AppBar (Button + onClick + gesture).
      Button({ type: ButtonType.Circle, stateEffect: true }) {
        Image($r('app.media.ic_image'))
          .width(22).height(22)
          .fillColor($r('app.color.on_surface_variant'));
      }
      .width(40).height(40)
      .backgroundColor(Color.Transparent)
      .enabled(this.isModelReady && this.isVisionSupported)
      .onClick(() => this.onClickPickImage())
      .gesture(
        LongPressGesture({ repeat: false, duration: 500 })
          .onAction(() => this.onLongPressImageDebug()),
      );

      Button({ type: ButtonType.Circle, stateEffect: true }) {
        Image($r('app.media.ic_send'))
          .width(22).height(22)
          .fillColor(this.isModelReady && this.inputText.trim().length > 0
                       ? $r('app.color.primary')
                       : $r('app.color.on_surface_variant'));
      }
      .width(40).height(40)
      .backgroundColor(Color.Transparent)
      .enabled(this.isModelReady && this.inputText.trim().length > 0)
      .onClick(() => this.onClickSend());
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .padding({ left: 12, right: 8, top: 8, bottom: 8 })
    .margin({ left: 12, right: 12, top: 6, bottom: 12 })
    .backgroundColor($r('app.color.surface_variant'))
    .borderRadius(28);
  }

  // Translates the engine state into a one-line status under the title.
  private statusText(): string {
    switch (this.stateName) {
      case 'Uninitialized':           return '未初始化';
      case 'Initializing':            return '正在初始化...';
      case 'Initialized':             return '已初始化';
      case 'LoadingModel':            return '正在加载模型...';
      case 'ModelReady':              return '模型已就绪';
      case 'ProcessingSystemPrompt':  return '处理系统提示词...';
      case 'PrefillingImage':         return '正在处理图片...';
      case 'ProcessingUserPrompt':    return '处理用户输入...';
      case 'Generating':              return '正在生成...';
      case 'UnloadingModel':          return '正在卸载...';
      case 'Error':                   return '错误';
      default:                        return '';
    }
  }
}
