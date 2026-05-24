// Bare-bones EntryAbility. Mirrors the MainActivity onCreate/onDestroy
// lifecycle from the Android demo: load the ArkUI page, start a one-shot
// migration of legacy model layout, and wire up the engine destroy path.

import UIAbility from '@ohos.app.ability.UIAbility';
import AbilityConstant from '@ohos.app.ability.AbilityConstant';
import Want from '@ohos.app.ability.Want';
import window from '@ohos.window';
import hilog from '@ohos.hilog';

import { LlamaEngine } from '../engine/LlamaEngine';
import { LocaleManager } from '../manager/LocaleManager';

const DOMAIN = 0xC0DE;
const TAG = 'EntryAbility';

export default class EntryAbility extends UIAbility {
  async onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): Promise<void> {
    hilog.info(DOMAIN, TAG, 'Ability onCreate (launchReason=%{public}d)',
               launchParam.launchReason);
    // Apply persisted locale before any UI renders (must complete before
    // onWindowStageCreate so the first page uses the correct language).
    await LocaleManager.applyOnAppStart(this.context);
    LlamaEngine.migrateLegacyLayoutIfNeeded(this.context);
    LlamaEngine.warmSelectedId(this.context);
  }

  onDestroy(): void {
    hilog.info(DOMAIN, TAG, 'Ability onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(DOMAIN, TAG, 'loadContent failed: %{public}s', JSON.stringify(err));
        return;
      }
      hilog.info(DOMAIN, TAG, 'Index page loaded');
    });
  }

  onWindowStageDestroy(): void {
    hilog.info(DOMAIN, TAG, 'Window stage destroyed');
  }

  onForeground(): void {
    hilog.info(DOMAIN, TAG, 'Foreground');
  }

  onBackground(): void {
    hilog.info(DOMAIN, TAG, 'Background');
  }
}
