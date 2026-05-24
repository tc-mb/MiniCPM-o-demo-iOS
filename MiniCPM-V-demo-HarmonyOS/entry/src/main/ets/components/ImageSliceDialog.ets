// Chat-page popup that lets the user pick the image slice cap (1..9).
//
// Mirrors Android's `dialog_image_slice.xml` and iOS's slider sheet:
//   * Big number on top showing the live slider value
//   * Slider 1..9 step 1
//   * Min / max labels under the slider
//   * Long form hint at the bottom (slices ↑ → clarity ↑ but latency ↑)
//   * Cancel / OK buttons at the bottom
//
// On confirm we call `engine.updateImageMaxSliceNums(...)` which both
// persists the new value in preferences and (if mmproj is loaded)
// patches the live clip context so the next image picks the new cap up.

import { LlamaEngine, MIN_IMAGE_SLICE, MAX_IMAGE_SLICE } from '../engine/LlamaEngine';
import promptAction from '@ohos.promptAction';
import hilog from '@ohos.hilog';
import resourceManager from '@ohos.resourceManager';

const DOMAIN = 0xC0DE;
const TAG = 'ImageSliceDialog';

@CustomDialog
export struct ImageSliceDialog {
  controller?: CustomDialogController;
  // Engine + initial value supplied by the caller.  Initial value is
  // already clamped because LlamaEngine.getImageMaxSliceNumsCached() does
  // it, but we coerce again just in case (cheap, defensive).
  engine: LlamaEngine | null = null;
  initialValue: number = 1;

  @State currentValue: number = 1;
  private resMgr: resourceManager.ResourceManager | null = null;

  private str(name: string): string {
    return this.resMgr!.getStringByNameSync(name);
  }

  aboutToAppear(): void {
    this.resMgr = getContext(this).resourceManager;
    this.currentValue = Math.max(MIN_IMAGE_SLICE, Math.min(MAX_IMAGE_SLICE, this.initialValue));
  }

  build() {
    Column() {
      // Title row.  Sits at the top of the rounded card.
      Text($r('app.string.image_slice_title'))
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor($r('app.color.on_surface'))
        .margin({ top: 20, bottom: 8 });

      // Live value preview - big primary-colored number so the slider's
      // stepwise feedback is unmistakable.
      Text(`${this.currentValue}`)
        .fontSize(40)
        .fontWeight(FontWeight.Bold)
        .fontColor($r('app.color.primary'))
        .margin({ top: 4, bottom: 4 });

      // The slider itself.  step=1 + showSteps so users see ticks for
      // each integer position.
      Slider({
        value: this.currentValue,
        min: MIN_IMAGE_SLICE,
        max: MAX_IMAGE_SLICE,
        step: 1,
        style: SliderStyle.OutSet,
      })
        .blockColor($r('app.color.primary'))
        .selectedColor($r('app.color.primary'))
        .showSteps(true)
        .width('88%')
        .onChange((value: number, _mode: SliderChangeMode) => {
          // Snap to integer (Slider returns floats around step boundaries).
          this.currentValue = Math.round(value);
        });

      // Min / max captions under the slider.
      Row() {
        Text($r('app.string.image_slice_label_min'))
          .fontSize(12)
          .fontColor($r('app.color.on_surface_variant'))
          .layoutWeight(1)
          .textAlign(TextAlign.Start);
        Text($r('app.string.image_slice_label_max'))
          .fontSize(12)
          .fontColor($r('app.color.on_surface_variant'))
          .layoutWeight(1)
          .textAlign(TextAlign.End);
      }
      .width('88%')
      .margin({ top: 4 });

      // Long-form hint.  Same wording as Android / iOS so the three apps
      // teach the user the same trade-off.
      Text($r('app.string.image_slice_hint'))
        .fontSize(13)
        .fontColor($r('app.color.on_surface_variant'))
        .lineHeight(20)
        .width('88%')
        .margin({ top: 16, bottom: 12 });

      // Action row.  Cancel left / OK right matches the rest of the app
      // (see onClickClear's AlertDialog ordering).
      Row() {
        Button($r('app.string.btn_cancel'))
          .backgroundColor(Color.Transparent)
          .fontColor($r('app.color.on_surface_variant'))
          .layoutWeight(1)
          .height(44)
          .onClick(() => {
            this.controller?.close();
          });

        Button($r('app.string.btn_confirm'))
          .backgroundColor(Color.Transparent)
          .fontColor($r('app.color.primary'))
          .layoutWeight(1)
          .height(44)
          .onClick(() => {
            this.applyAndClose();
          });
      }
      .width('100%')
      .border({ width: { top: 0.5 }, color: $r('app.color.outline') });
    }
    .width('86%')
    .backgroundColor($r('app.color.surface'))
    .borderRadius(20);
  }

  private async applyAndClose(): Promise<void> {
    const chosen = this.currentValue;
    this.controller?.close();
    if (!this.engine) {
      promptAction.showToast({ message: this.str('image_slice_saved_toast').replace('%d', `${chosen}`), duration: 1500 });
      return;
    }
    try {
      await this.engine.updateImageMaxSliceNums(chosen);
      const visionReady = this.engine.isVisionSupported();
      const message = visionReady
        ? this.str('image_slice_apply_toast').replace('%d', `${chosen}`)
        : this.str('image_slice_pending_toast').replace('%d', `${chosen}`);
      promptAction.showToast({ message, duration: 1800 });
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'updateImageMaxSliceNums failed: %{public}s', `${e}`);
      promptAction.showToast({ message: this.str('image_slice_switch_failed').replace('%s', `${e}`), duration: 2500 });
    }
  }
}
