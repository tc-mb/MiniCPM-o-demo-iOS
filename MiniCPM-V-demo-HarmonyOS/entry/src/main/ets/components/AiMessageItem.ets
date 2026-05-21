// Left-aligned assistant bubble with an inline "stop generating" button while
// `isGenerating` is true. Mirrors item_ai_message.xml + ChatAdapter
// AiMessageViewHolder from the Android demo.
//
// Renders the text through MarkdownParser so headings, lists, **bold**,
// *italic*, `code`, and ``` fenced ``` blocks come out with proper visual
// styling instead of as literal characters.
//
// Think block rendering: MiniCPM5 (and other thinking models) emit
// `<think>...</think>` around reasoning content. This component parses
// that block and renders it as a collapsible section above the actual
// response, matching the Android ChatAdapter's renderWithThinking logic.

import { ChatMessage } from '../data/ChatMessage';
import { parseMarkdown, BlockType, InlineType, MdBlock, InlineSpan } from '../utils/MarkdownParser';
import { normalizeResponseText } from '../utils/MarkdownEscape';

// Extracts thinking text and response text from raw assistant output.
// Returns [thinkingText, responseText, isStillThinking].
function getThinkingText(text: string): string {
  const thinkStart = text.indexOf('<think>');
  if (thinkStart < 0) return '';
  const contentAfterTag = text.substring(thinkStart + '<think>'.length);
  const thinkEnd = contentAfterTag.indexOf('</think>');
  if (thinkEnd < 0) return contentAfterTag.trim();
  return contentAfterTag.substring(0, thinkEnd).trim();
}

function getResponseText(text: string): string {
  const thinkStart = text.indexOf('<think>');
  if (thinkStart < 0) return text;
  const contentAfterTag = text.substring(thinkStart + '<think>'.length);
  const thinkEnd = contentAfterTag.indexOf('</think>');
  if (thinkEnd < 0) return '';
  let response = contentAfterTag.substring(thinkEnd + '</think>'.length);
  while (response.length > 0 && response[0] === '\n') {
    response = response.substring(1);
  }
  return response;
}

function hasThinkTag(text: string): boolean {
  return text.indexOf('<think>') >= 0;
}

function isStillThinking(text: string): boolean {
  if (text.indexOf('<think>') < 0) return false;
  const contentAfterTag = text.substring(text.indexOf('<think>') + '<think>'.length);
  return contentAfterTag.indexOf('</think>') < 0;
}

@Component
export struct AiMessageItem {
  @ObjectLink message: ChatMessage;
  onStop: () => void = () => undefined;
  @State private thinkingExpanded: boolean = true;

  private headingFontSize(level: number): number {
    if (level <= 1) return 22;
    if (level === 2) return 19;
    if (level === 3) return 17;
    return 16;
  }

  @Builder
  inlineRow(spans: InlineSpan[], baseFontSize: number, baseWeight: FontWeight) {
    Text() {
      ForEach(spans, (span: InlineSpan, _idx: number) => {
        if (span.type === InlineType.BOLD) {
          Span(span.text)
            .fontSize(baseFontSize)
            .fontWeight(FontWeight.Bold);
        } else if (span.type === InlineType.ITALIC) {
          Span(span.text)
            .fontSize(baseFontSize)
            .fontStyle(FontStyle.Italic)
            .fontWeight(baseWeight);
        } else if (span.type === InlineType.CODE) {
          Span(' ' + span.text + ' ')
            .fontSize(baseFontSize - 1)
            .fontFamily('HarmonyOS Sans Condensed')
            .fontColor('#C7254E')
            .backgroundColor('#33FF6680')
            .fontWeight(baseWeight);
        } else {
          Span(span.text)
            .fontSize(baseFontSize)
            .fontWeight(baseWeight);
        }
      })
    }
    .fontColor($r('app.color.on_surface'))
    .lineHeight(baseFontSize + 7);
  }

  @Builder
  block(b: MdBlock) {
    if (b.type === BlockType.HEADING) {
      this.inlineRow(b.spans, this.headingFontSize(b.level), FontWeight.Bold);
    } else if (b.type === BlockType.LIST_ITEM) {
      Row({ space: 6 }) {
        Text(b.level === 0 ? '•' : `${b.level}.`)
          .fontSize(15)
          .fontColor($r('app.color.on_surface'))
          .width(b.level === 0 ? 12 : 24);
        Column() {
          this.inlineRow(b.spans, 15, FontWeight.Normal);
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start);
      }
      .alignItems(VerticalAlign.Top)
      .width('100%');
    } else if (b.type === BlockType.CODE_BLOCK) {
      Text(b.raw)
        .fontSize(13)
        .fontFamily('HarmonyOS Sans Condensed')
        .fontColor('#1F2328')
        .backgroundColor('#33000000')
        .padding(10)
        .borderRadius(6)
        .width('100%');
    } else {
      this.inlineRow(b.spans, 15, FontWeight.Normal);
    }
  }

  build() {
    Row() {
      Column({ space: 6 }) {
        Column({ space: 6 }) {
          if (hasThinkTag(this.message.text)) {
            // --- Thinking section (collapsible) ---
            Column({ space: 0 }) {
              // Header: arrow + label
              Row({ space: 6 }) {
                Text(this.thinkingExpanded ? '▾' : '▸')
                  .fontSize(14)
                  .fontColor($r('app.color.on_surface_variant'));
                Text(isStillThinking(this.message.text) ? '思考中…' : '思考过程')
                  .fontSize(13)
                  .fontColor($r('app.color.on_surface_variant'))
                  .fontWeight(FontWeight.Medium);
              }
              .width('100%')
              .padding({ top: 6, bottom: 6 })
              .onClick(() => {
                this.thinkingExpanded = !this.thinkingExpanded;
              });

              // Thinking content body
              if (this.thinkingExpanded && getThinkingText(this.message.text).length > 0) {
                Column({ space: 4 }) {
                  ForEach(
                    parseMarkdown(normalizeResponseText(getThinkingText(this.message.text))),
                    (b: MdBlock, _idx: number) => {
                      this.block(b);
                    }
                  );
                }
                .width('100%')
                .padding({ left: 4, bottom: 6 });
              }

              // Divider (only after thinking is complete)
              if (!isStillThinking(this.message.text)) {
                Divider()
                  .color('#33000000')
                  .width('100%')
                  .margin({ top: 4, bottom: 4 });
              }
            }
            .width('100%');

            // --- Response text (after </think>) ---
            if (getResponseText(this.message.text).length > 0) {
              ForEach(
                parseMarkdown(normalizeResponseText(getResponseText(this.message.text))),
                (b: MdBlock, _idx: number) => {
                  this.block(b);
                }
              );
            }
          } else {
            // No thinking block - render normally
            ForEach(
              parseMarkdown(normalizeResponseText(
                this.message.text.length > 0 ? this.message.text : ' '
              )),
              (b: MdBlock, idx: number) => {
                this.block(b);
              }
            );
          }
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .backgroundColor($r('app.color.ai_bubble'))
        .borderRadius(12)
        .alignItems(HorizontalAlign.Start);

        if (this.message.isGenerating) {
          Button($r('app.string.btn_stop'))
            .fontSize(13)
            .height(28)
            .backgroundColor($r('app.color.surface_variant'))
            .fontColor($r('app.color.on_surface'))
            .onClick(() => this.onStop());
        }
      }
      .alignItems(HorizontalAlign.Start)
      .constraintSize({ maxWidth: '78%' });

      // right spacer keeps the bubble pinned to the left
      Blank().layoutWeight(1);
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 6, bottom: 6 })
    .alignItems(VerticalAlign.Top);
  }
}
