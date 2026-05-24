// Right-aligned user message bubble. Mirrors item_user_message.xml from the
// Android demo: optional image attached above the text, optional progress
// indicator while the image is being prefilled into mtmd, then the text.

import { ChatMessage } from '../data/ChatMessage';

@Component
export struct UserMessageItem {
  @ObjectLink message: ChatMessage;

  build() {
    Row() {
      // left spacer pushes the bubble to the right edge
      Blank().layoutWeight(1);

      Column({ space: 6 }) {
        if (this.message.imagePixelMap !== null) {
          // Stack so we can overlay a play-arrow badge on top of the
          // thumbnail for video messages (matches iOS demo
          // MBImageTableViewCell's video overlay and the Android
          // item_user_message.xml FrameLayout wrapper).
          Stack({ alignContent: Alignment.Center }) {
            Image(this.message.imagePixelMap)
              .width(220)
              .height(220)
              .objectFit(ImageFit.Cover)
              .borderRadius(8);
            if (this.message.isVideo) {
              Image($r('app.media.ic_play_arrow'))
                .width(28)
                .height(28)
                .fillColor(Color.White)
                .padding(10)
                .borderRadius(24)
                .backgroundColor('#80000000');
            }
          }
          .width(220)
          .height(220);
          if (this.message.imageInfo !== null && this.message.imageInfo.length > 0) {
            Text(this.message.imageInfo)
              .fontSize(12)
              .fontColor($r('app.color.on_surface_variant'));
          }
          if (this.message.isPrefilling) {
            LoadingProgress()
              .width(24)
              .height(24)
              .color($r('app.color.primary'));
          }
        }
        if (this.message.text.length > 0) {
          Text(this.message.text)
            .fontSize(15)
            .fontColor($r('app.color.on_surface'))
            .lineHeight(22)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .backgroundColor($r('app.color.user_bubble'))
            .borderRadius(12);
        }
      }
      .alignItems(HorizontalAlign.End)
      .constraintSize({ maxWidth: '78%' });
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 6, bottom: 6 })
    .alignItems(VerticalAlign.Top);
  }
}
