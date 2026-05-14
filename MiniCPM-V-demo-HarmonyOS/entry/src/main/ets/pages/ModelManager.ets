// Model management page. Mirrors ModelManagerActivity.kt + activity_model_manager.xml
// from the Android demo.

import promptAction from '@ohos.promptAction';
import router from '@ohos.router';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';

import { LlamaEngine } from '../engine/LlamaEngine';
import { LlamaState, LlamaStateName } from '../engine/LlamaState';
import {
  ModelInfo,
  AVAILABLE_MODELS,
  DEFAULT_MODEL,
  findModelById,
} from '../engine/ModelInfo';
import { deleteSelectedModelFiles } from '../utils/DownloadManager';

const DOMAIN = 0xC0DE;
const TAG = 'ModelManager';

@Entry
@Component
struct ModelManager {
  @State stateName: LlamaStateName = 'Uninitialized';
  @State statusText: string = '';
  @State selectedId: string = DEFAULT_MODEL.id;
  @State modelExists: boolean = false;
  @State downloading: boolean = false;
  @State busy: boolean = false;

  private engine: LlamaEngine | null = null;
  private engineListener = (s: LlamaState) => this.onEngineState(s);

  aboutToAppear(): void {
    this.engine = LlamaEngine.getInstance(getContext(this));
    const m = LlamaEngine.getSelectedModel(getContext(this));
    this.selectedId = m.id;
    this.modelExists = LlamaEngine.modelsExist(getContext(this));
    this.engine.addListener(this.engineListener);
  }

  aboutToDisappear(): void {
    if (this.engine) this.engine.removeListener(this.engineListener);
  }

  // ------------------------------------------------------------------
  // State machine wiring (mirrors ModelManagerActivity.observeEngineState)
  // ------------------------------------------------------------------

  private onEngineState(state: LlamaState): void {
    this.stateName = state.name;
    switch (state.name) {
      case 'Uninitialized':           this.statusText = '未初始化'; break;
      case 'Initializing':            this.statusText = '正在初始化...'; break;
      case 'Initialized':             this.statusText = '已初始化，等待加载模型'; break;
      case 'LoadingModel':            this.statusText = '正在加载模型...'; break;
      case 'ModelReady':              this.statusText = '模型已就绪'; break;
      case 'ProcessingSystemPrompt':
      case 'ProcessingUserPrompt':
      case 'Generating':              this.statusText = '正在生成...'; break;
      case 'PrefillingImage':         this.statusText = '正在处理图片...'; break;
      case 'UnloadingModel':          this.statusText = '正在卸载模型...'; break;
      case 'Error':                   this.statusText = `错误: ${state.errorMessage ?? ''}`; break;
      default:                        this.statusText = ''; break;
    }
    this.modelExists = LlamaEngine.modelsExist(getContext(this));
  }

  // ------------------------------------------------------------------
  // Actions
  // ------------------------------------------------------------------

  private onSelectModel(modelId: string): void {
    this.selectedId = modelId;
    LlamaEngine.setSelectedModel(getContext(this), modelId).then(() => {
      this.modelExists = LlamaEngine.modelsExist(getContext(this));
      const m = findModelById(modelId);
      promptAction.showToast({ message: `已选择: ${m.displayName}`, duration: 1500 });
    });
  }

  private async onClickDownload(): Promise<void> {
    if (this.modelExists) {
      promptAction.showToast({ message: '模型文件已存在，无需重复下载', duration: 1500 });
      return;
    }
    this.downloading = true;
    this.busy = true;
    this.statusText = '正在下载模型...';
    try {
      await LlamaEngine.downloadModels(getContext(this), (msg) => {
        this.statusText = msg;
      });
      this.modelExists = LlamaEngine.modelsExist(getContext(this));
      this.statusText = '下载完成! 请点击加载模型';
      promptAction.showToast({ message: '下载完成!', duration: 1500 });
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'download failed: %{public}s', `${e}`);
      this.statusText = `下载失败: ${e}`;
      promptAction.showToast({ message: `下载失败: ${e}`, duration: 4000 });
    } finally {
      this.downloading = false;
      this.busy = false;
    }
  }

  private async onClickLoad(): Promise<void> {
    if (!this.engine) return;
    if (this.stateName === 'LoadingModel') {
      promptAction.showToast({ message: '模型正在加载中...', duration: 1500 });
      return;
    }
    const ctx = getContext(this);
    const modelPath = LlamaEngine.modelPath(ctx);
    const mmprojPath = LlamaEngine.mmprojPath(ctx);
    if (!fs.accessSync(modelPath)) {
      promptAction.showToast({ message: '模型文件不存在，请先下载', duration: 4000 });
      return;
    }
    const isReload = this.stateName === 'ModelReady';

    this.busy = true;
    try {
      if (isReload) {
        hilog.info(DOMAIN, TAG, 'Unloading model for reload...');
        await this.engine.unloadModel();
      }
      const mm = fs.accessSync(mmprojPath) ? mmprojPath : null;
      await this.engine.loadModel(modelPath, mm);
      // No default system prompt: aligned with iOS opt-r1.
      promptAction.showToast({ message: '模型加载成功!', duration: 1500 });
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'load failed: %{public}s', `${e}`);
      this.engine.resetToInitialized();
      this.statusText = `加载失败: ${e}`;
    } finally {
      this.busy = false;
    }
  }

  private onClickDelete(): void {
    const m = findModelById(this.selectedId);
    AlertDialog.show({
      title: '删除模型文件',
      message: `确定要删除 ${m.displayName} 的模型文件吗？\n\n这将删除:\n• ${m.ggufFileName}\n• ${m.mmprojFileName}\n\n删除后需要重新下载才能使用。`,
      autoCancel: true,
      primaryButton: { value: '删除', action: () => this.doDelete() },
      secondaryButton: { value: '取消', action: () => undefined },
    });
  }

  private async doDelete(): Promise<void> {
    try {
      const deleted = await deleteSelectedModelFiles(getContext(this));
      this.modelExists = LlamaEngine.modelsExist(getContext(this));
      if (deleted) {
        this.statusText = '模型文件已删除';
        promptAction.showToast({ message: '模型文件已删除', duration: 1500 });
      } else {
        promptAction.showToast({ message: '没有可删除的文件', duration: 1500 });
      }
    } catch (e) {
      promptAction.showToast({ message: `删除失败: ${e}`, duration: 3000 });
    }
  }

  // ------------------------------------------------------------------
  // UI
  // ------------------------------------------------------------------

  build() {
    Column() {
      this.AppBar();
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

  @Builder StatusBar() {
    Column() {
      Text(this.statusText)
        .fontSize(14)
        .fontColor($r('app.color.on_surface_variant'))
        .width('100%')
        .padding({ left: 16, right: 16, top: 12, bottom: 8 });
      if (this.downloading) {
        Progress({ value: 0, total: 100, type: ProgressType.Linear })
          .width('100%').height(4)
          .margin({ left: 16, right: 16, bottom: 8 });
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
              Text(m.description)
                .fontSize(13)
                .fontColor($r('app.color.on_surface_variant'))
                .margin({ top: 4 });
              Text(`${m.ggufFileName} + ${m.mmprojFileName}`)
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
      Button(this.modelExists ? '已下载' : '下载模型')
        .layoutWeight(1).height(44)
        .backgroundColor($r('app.color.surface_variant'))
        .fontColor($r('app.color.on_surface'))
        .enabled(!this.modelExists && !this.busy)
        .onClick(() => this.onClickDownload());

      Button(this.stateName === 'ModelReady' ? '重新加载' : '加载模型')
        .layoutWeight(1).height(44)
        .backgroundColor($r('app.color.primary'))
        .fontColor($r('app.color.on_primary'))
        .enabled(this.modelExists && !this.busy)
        .onClick(() => this.onClickLoad());

      Button($r('app.string.btn_delete'))
        .layoutWeight(1).height(44)
        .backgroundColor($r('app.color.surface_variant'))
        .fontColor($r('app.color.on_surface'))
        .enabled(this.modelExists && !this.busy)
        .onClick(() => this.onClickDelete());
    }
    .width('100%')
    .padding(12)
    .border({ width: { top: 0.5 }, color: $r('app.color.outline') });
  }
}
