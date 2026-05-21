// Renders the first ListItem in the chat. Mirrors item_welcome_card.xml from
// the Android demo: a heading, a short description, and two suggestion chips
// the user can tap to seed the input field.
//
// When `isTextOnly` is true, switches to text-model-appropriate strings
// (no image-related suggestions). Mirrors Android's dynamic welcome card.

@Component
export struct WelcomeCard {
  onSuggestion: (s: string) => void = () => undefined;
  isTextOnly: boolean = false;

  build() {
    Column() {
      Column() {
        Text(this.isTextOnly ? $r('app.string.welcome_title_text') : $r('app.string.welcome_title'))
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor($r('app.color.on_surface'))
          .margin({ bottom: 8 });

        Text(this.isTextOnly ? $r('app.string.welcome_desc_text') : $r('app.string.welcome_desc'))
          .fontSize(14)
          .fontColor($r('app.color.on_surface_variant'))
          .lineHeight(20)
          .margin({ bottom: 16 });

        Row({ space: 12 }) {
          Button(this.isTextOnly ? $r('app.string.suggestion_1_text') : $r('app.string.suggestion_1'))
            .fontSize(14)
            .height(36)
            .layoutWeight(1)
            .backgroundColor($r('app.color.primary_container'))
            .fontColor($r('app.color.primary'))
            .onClick(() => {
              const resId = this.isTextOnly
                ? $r('app.string.suggestion_1_text').id
                : $r('app.string.suggestion_1').id;
              this.onSuggestion(getContext(this).resourceManager.getStringSync(resId));
            });
          Button(this.isTextOnly ? $r('app.string.suggestion_2_text') : $r('app.string.suggestion_2'))
            .fontSize(14)
            .height(36)
            .layoutWeight(1)
            .backgroundColor($r('app.color.primary_container'))
            .fontColor($r('app.color.primary'))
            .onClick(() => {
              const resId = this.isTextOnly
                ? $r('app.string.suggestion_2_text').id
                : $r('app.string.suggestion_2').id;
              this.onSuggestion(getContext(this).resourceManager.getStringSync(resId));
            });
        }
        .width('100%');
      }
      .width('100%')
      .padding(16)
      .backgroundColor($r('app.color.surface_variant'))
      .borderRadius(12);
    }
    .width('100%')
    .padding({ top: 12, bottom: 12, left: 16, right: 16 });
  }
}
