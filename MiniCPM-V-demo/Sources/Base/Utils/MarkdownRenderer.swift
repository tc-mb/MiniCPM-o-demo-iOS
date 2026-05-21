//
//  MarkdownRenderer.swift
//  MiniCPM-V-demo
//
//  Lightweight Markdown -> NSAttributedString renderer for streaming AI
//  responses.  Mirrors the feature set of the HarmonyOS MarkdownParser.ets
//  and Android Markwon-core so all three platforms render identically.
//
//  Supported block-level syntax:
//    # .. ######   ATX headings
//    ```lang        fenced code blocks
//    - / * / +      unordered list items
//    1. / 2.        ordered list items
//    >              blockquotes
//    blank line     paragraph separator
//
//  Supported inline syntax (within paragraphs / lists / headings):
//    **bold**
//    *italic*  /  _italic_
//    `inline code`
//    ~~strikethrough~~
//    [text](url)    (rendered as styled text; UILabel cannot make them tappable)
//
//  Streaming-friendly: re-parse the full accumulated string on every token.
//  Partial / unclosed markers render as literal text until they complete,
//  matching Markwon behavior on Android.
//

import UIKit

enum MarkdownRenderer {

    // MARK: - Public API

    /// Render Markdown text into an NSAttributedString using the given base
    /// attributes (font, color, paragraph style).  The base font size drives
    /// heading scale and code styling.
    static func render(
        _ markdown: String,
        baseAttributes: [NSAttributedString.Key: Any]
    ) -> NSAttributedString {

        let baseFont = baseAttributes[.font] as? UIFont
            ?? UIFont.systemFont(ofSize: 16)
        let baseColor = baseAttributes[.foregroundColor] as? UIColor ?? .label
        let result = NSMutableAttributedString()
        let blocks = parseBlocks(markdown)

        for (idx, block) in blocks.enumerated() {
            switch block.type {
            case .heading:
                let fontSize = headingFontSize(level: block.level, base: baseFont.pointSize)
                let font = UIFont.systemFont(ofSize: fontSize, weight: .bold)
                let attrs = mergedAttributes(base: baseAttributes, overrides: [.font: font])
                let rendered = renderInlineSpans(block.spans, baseAttributes: attrs, baseFont: font, baseColor: baseColor)
                result.append(rendered)

            case .codeBlock:
                let codeFont = UIFont(name: "Menlo", size: baseFont.pointSize - 2)
                    ?? UIFont.monospacedSystemFont(ofSize: baseFont.pointSize - 2, weight: .regular)
                let codePara = NSMutableParagraphStyle()
                codePara.lineSpacing = 2
                codePara.lineBreakMode = .byWordWrapping
                let codeAttrs: [NSAttributedString.Key: Any] = [
                    .font: codeFont,
                    .foregroundColor: UIColor(red: 0.12, green: 0.14, blue: 0.16, alpha: 1.0),
                    .backgroundColor: UIColor(red: 0.95, green: 0.96, blue: 0.97, alpha: 1.0),
                    .paragraphStyle: codePara
                ]
                result.append(NSAttributedString(string: block.raw, attributes: codeAttrs))

            case .listItem:
                let bullet: String
                if block.level == 0 {
                    bullet = "  •  "
                } else {
                    bullet = "  \(block.level).  "
                }
                let bulletAttrs = mergedAttributes(base: baseAttributes, overrides: [
                    .foregroundColor: baseColor
                ])
                result.append(NSAttributedString(string: bullet, attributes: bulletAttrs))
                let rendered = renderInlineSpans(block.spans, baseAttributes: baseAttributes, baseFont: baseFont, baseColor: baseColor)
                result.append(rendered)

            case .blockquote:
                let quoteColor = UIColor.gray
                let quoteFont = UIFont.italicSystemFont(ofSize: baseFont.pointSize)
                let quoteAttrs = mergedAttributes(base: baseAttributes, overrides: [
                    .foregroundColor: quoteColor,
                    .font: quoteFont
                ])
                let rendered = renderInlineSpans(block.spans, baseAttributes: quoteAttrs, baseFont: quoteFont, baseColor: quoteColor)
                let bar = NSAttributedString(string: "  ▎ ", attributes: quoteAttrs)
                result.append(bar)
                result.append(rendered)

            case .horizontalRule:
                let rulePara = NSMutableParagraphStyle()
                rulePara.lineBreakMode = .byClipping
                rulePara.maximumLineHeight = 22
                rulePara.minimumLineHeight = 22
                let ruleAttrs: [NSAttributedString.Key: Any] = [
                    .foregroundColor: UIColor.separator,
                    .font: baseFont,
                    .paragraphStyle: rulePara
                ]
                let ruleStr = String(repeating: "─", count: 80)
                result.append(NSAttributedString(string: ruleStr, attributes: ruleAttrs))

            case .table:
                let tableFont = UIFont(name: "Menlo", size: baseFont.pointSize - 2)
                    ?? UIFont.monospacedSystemFont(ofSize: baseFont.pointSize - 2, weight: .regular)
                let tablePara = NSMutableParagraphStyle()
                tablePara.lineSpacing = 2
                tablePara.lineBreakMode = .byWordWrapping
                let tableAttrs: [NSAttributedString.Key: Any] = [
                    .font: tableFont,
                    .foregroundColor: baseColor,
                    .paragraphStyle: tablePara
                ]
                result.append(NSAttributedString(string: block.raw, attributes: tableAttrs))

            case .paragraph:
                let rendered = renderInlineSpans(block.spans, baseAttributes: baseAttributes, baseFont: baseFont, baseColor: baseColor)
                result.append(rendered)
            }

            if idx < blocks.count - 1 {
                let nextBlock = blocks[idx + 1]
                let sep: String
                if block.type == .listItem && nextBlock.type == .listItem {
                    sep = "\n"
                } else if block.type == .heading {
                    sep = "\n"
                } else {
                    sep = "\n\n"
                }
                result.append(NSAttributedString(string: sep, attributes: baseAttributes))
            }
        }

        return result
    }

    // MARK: - Block parsing

    private enum BlockType {
        case paragraph
        case heading
        case listItem
        case codeBlock
        case blockquote
        case horizontalRule
        case table
    }

    private struct Block {
        let type: BlockType
        let level: Int       // heading 1-6; ordered list index; 0 for unordered/others
        let language: String // code block language hint
        let spans: [InlineSpan]
        let raw: String      // code block raw content
    }

    private static func parseBlocks(_ text: String) -> [Block] {
        var blocks = [Block]()
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
            .map { String($0).replacingOccurrences(of: "\r", with: "") }
        var i = 0

        while i < lines.count {
            let line = lines[i]

            // Fenced code block
            if line.hasPrefix("```") {
                let lang = String(line.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                var codeLines = [String]()
                i += 1
                while i < lines.count && !lines[i].hasPrefix("```") {
                    codeLines.append(lines[i])
                    i += 1
                }
                if i < lines.count { i += 1 } // skip closing ```
                blocks.append(Block(
                    type: .codeBlock, level: 0, language: lang,
                    spans: [], raw: codeLines.joined(separator: "\n")))
                continue
            }

            // Heading
            if let match = line.range(of: #"^(#{1,6})\s+(.*)$"#, options: .regularExpression) {
                let fullMatch = String(line[match])
                let hashEnd = fullMatch.firstIndex(of: " ")!
                let level = fullMatch.distance(from: fullMatch.startIndex, to: hashEnd)
                let content = String(fullMatch[fullMatch.index(after: hashEnd)...])
                    .trimmingCharacters(in: .whitespaces)
                blocks.append(Block(
                    type: .heading, level: level, language: "",
                    spans: parseInline(content), raw: content))
                i += 1
                continue
            }

            // Horizontal rule (---, ***, ___)
            if isHorizontalRule(line) {
                blocks.append(Block(
                    type: .horizontalRule, level: 0, language: "",
                    spans: [], raw: ""))
                i += 1
                continue
            }

            // Blockquote
            if line.hasPrefix("> ") || line == ">" {
                let content = line.hasPrefix("> ") ? String(line.dropFirst(2)) : ""
                blocks.append(Block(
                    type: .blockquote, level: 0, language: "",
                    spans: parseInline(content), raw: content))
                i += 1
                continue
            }

            // Table: lines starting with | (collect consecutive table rows)
            if line.hasPrefix("|") && line.hasSuffix("|") {
                var tableLines = [line]
                i += 1
                while i < lines.count {
                    let tl = lines[i]
                    if tl.hasPrefix("|") {
                        tableLines.append(tl)
                        i += 1
                    } else {
                        break
                    }
                }
                let tableRaw = tableLines.joined(separator: "\n")
                blocks.append(Block(
                    type: .table, level: 0, language: "",
                    spans: [], raw: tableRaw))
                continue
            }

            // Unordered list item (- * + and en-dash – em-dash —)
            if let _ = line.range(of: #"^\s*[-*+–—]\s+(.*)$"#, options: .regularExpression) {
                let content = extractListContent(line, pattern: #"^\s*[-*+–—]\s+"#)
                blocks.append(Block(
                    type: .listItem, level: 0, language: "",
                    spans: parseInline(content), raw: content))
                i += 1
                continue
            }

            // Ordered list item
            if let _ = line.range(of: #"^\s*(\d+)\.\s+(.*)$"#, options: .regularExpression) {
                let (num, content) = extractOrderedListContent(line)
                blocks.append(Block(
                    type: .listItem, level: num, language: "",
                    spans: parseInline(content), raw: content))
                i += 1
                continue
            }

            // Blank line
            if line.trimmingCharacters(in: .whitespaces).isEmpty {
                i += 1
                continue
            }

            // Paragraph: gather contiguous non-special lines
            var paraLines = [line]
            i += 1
            while i < lines.count {
                let peek = lines[i]
                if peek.trimmingCharacters(in: .whitespaces).isEmpty { break }
                if peek.hasPrefix("```") { break }
                if peek.range(of: #"^#{1,6}\s+"#, options: .regularExpression) != nil { break }
                if peek.range(of: #"^\s*[-*+–—]\s+"#, options: .regularExpression) != nil { break }
                if peek.range(of: #"^\s*\d+\.\s+"#, options: .regularExpression) != nil { break }
                if peek.hasPrefix("> ") || peek == ">" { break }
                if peek.hasPrefix("|") && peek.hasSuffix("|") { break }
                if isHorizontalRule(peek) { break }
                paraLines.append(peek)
                i += 1
            }
            let paragraph = paraLines.joined(separator: "\n")
            blocks.append(Block(
                type: .paragraph, level: 0, language: "",
                spans: parseInline(paragraph), raw: paragraph))
        }

        return blocks
    }

    private static func extractListContent(_ line: String, pattern: String) -> String {
        guard let range = line.range(of: pattern, options: .regularExpression) else {
            return line
        }
        return String(line[range.upperBound...])
    }

    private static func extractOrderedListContent(_ line: String) -> (Int, String) {
        guard let dotRange = line.range(of: #"^\s*(\d+)\.\s+"#, options: .regularExpression) else {
            return (1, line)
        }
        let prefix = String(line[line.startIndex..<dotRange.upperBound])
        let digits = prefix.filter { $0.isNumber }
        let num = Int(digits) ?? 1
        let content = String(line[dotRange.upperBound...])
        return (num, content)
    }

    // MARK: - Inline parsing

    private enum InlineType {
        case text
        case bold
        case italic
        case boldItalic
        case code
        case strikethrough
        case link
    }

    private struct InlineSpan {
        let type: InlineType
        let text: String
        let url: String // only for .link
    }

    private static func parseInline(_ text: String) -> [InlineSpan] {
        var spans = [InlineSpan]()
        var buf = ""
        var i = text.startIndex

        func flushBuf() {
            if !buf.isEmpty {
                spans.append(InlineSpan(type: .text, text: buf, url: ""))
                buf = ""
            }
        }

        func remaining(_ from: String.Index) -> Int {
            text.distance(from: from, to: text.endIndex)
        }

        while i < text.endIndex {
            let ch = text[i]

            // ***bold italic*** (three asterisks)
            if ch == "*", remaining(i) >= 6 {
                let i1 = text.index(after: i)
                let i2 = text.index(after: i1)
                if text[i1] == "*" && text[i2] == "*" {
                    let searchStart = text.index(i, offsetBy: 3)
                    if let closeRange = text.range(of: "***", range: searchStart..<text.endIndex) {
                        let inner = String(text[searchStart..<closeRange.lowerBound])
                        if !inner.isEmpty && !inner.contains("\n") {
                            flushBuf()
                            spans.append(InlineSpan(type: .boldItalic, text: inner, url: ""))
                            i = closeRange.upperBound
                            continue
                        }
                    }
                }
            }

            // **bold**
            if ch == "*", remaining(i) >= 4 {
                let nextIdx = text.index(after: i)
                if text[nextIdx] == "*" {
                    let searchStart = text.index(i, offsetBy: 2)
                    if let closeRange = text.range(of: "**", range: searchStart..<text.endIndex) {
                        let inner = String(text[searchStart..<closeRange.lowerBound])
                        if !inner.isEmpty && !inner.contains("\n") {
                            flushBuf()
                            spans.append(InlineSpan(type: .bold, text: inner, url: ""))
                            i = closeRange.upperBound
                            continue
                        }
                    }
                }
            }

            // ~~strikethrough~~
            if ch == "~", remaining(i) >= 4 {
                let nextIdx = text.index(after: i)
                if text[nextIdx] == "~" {
                    let searchStart = text.index(i, offsetBy: 2)
                    if let closeRange = text.range(of: "~~", range: searchStart..<text.endIndex) {
                        let inner = String(text[searchStart..<closeRange.lowerBound])
                        if !inner.isEmpty && !inner.contains("\n") {
                            flushBuf()
                            spans.append(InlineSpan(type: .strikethrough, text: inner, url: ""))
                            i = closeRange.upperBound
                            continue
                        }
                    }
                }
            }

            // *italic* or _italic_ (single marker, NOT followed by same char)
            if (ch == "*" || ch == "_"), remaining(i) >= 3 {
                let nextIdx = text.index(after: i)
                let next = text[nextIdx]
                if next != ch && next != " " && next != "\n" {
                    if let closeIdx = text[nextIdx...].firstIndex(of: ch) {
                        // Ensure closing marker is not part of a `**`
                        let afterClose = text.index(after: closeIdx)
                        let closingIsDouble = afterClose < text.endIndex && text[afterClose] == ch
                        if !closingIsDouble {
                            let inner = String(text[nextIdx..<closeIdx])
                            if !inner.isEmpty && !inner.contains("\n") {
                                flushBuf()
                                spans.append(InlineSpan(type: .italic, text: inner, url: ""))
                                i = afterClose
                                continue
                            }
                        }
                    }
                }
            }

            // `inline code`
            if ch == "`" {
                let nextIdx = text.index(after: i)
                if nextIdx < text.endIndex,
                   let closeIdx = text[nextIdx...].firstIndex(of: "`") {
                    let inner = String(text[nextIdx..<closeIdx])
                    if !inner.isEmpty {
                        flushBuf()
                        spans.append(InlineSpan(type: .code, text: inner, url: ""))
                        i = text.index(after: closeIdx)
                        continue
                    }
                }
            }

            // ![text](url) — image syntax (render as link with label)
            if ch == "!", remaining(i) >= 5 {
                let nextIdx = text.index(after: i)
                if text[nextIdx] == "[" {
                    if let result = parseLinkAt(text, from: nextIdx) {
                        flushBuf()
                        spans.append(InlineSpan(type: .link, text: "[\(result.text)]", url: result.url))
                        i = result.end
                        continue
                    }
                }
            }

            // [text](url)
            if ch == "[" {
                if let result = parseLinkAt(text, from: i) {
                    flushBuf()
                    spans.append(InlineSpan(type: .link, text: result.text, url: result.url))
                    i = result.end
                    continue
                }
            }

            buf.append(ch)
            i = text.index(after: i)
        }

        flushBuf()
        return spans
    }

    private struct LinkParseResult {
        let text: String
        let url: String
        let end: String.Index
    }

    private static func parseLinkAt(_ text: String, from start: String.Index) -> LinkParseResult? {
        guard text[start] == "[" else { return nil }
        let afterBracket = text.index(after: start)
        guard let closeBracket = text[afterBracket...].firstIndex(of: "]") else { return nil }
        let linkText = String(text[afterBracket..<closeBracket])

        let parenStart = text.index(after: closeBracket)
        guard parenStart < text.endIndex, text[parenStart] == "(" else { return nil }
        let afterParen = text.index(after: parenStart)
        guard let closeParen = text[afterParen...].firstIndex(of: ")") else { return nil }
        let url = String(text[afterParen..<closeParen])

        return LinkParseResult(text: linkText, url: url, end: text.index(after: closeParen))
    }

    // MARK: - Inline span -> NSAttributedString

    private static func renderInlineSpans(
        _ spans: [InlineSpan],
        baseAttributes: [NSAttributedString.Key: Any],
        baseFont: UIFont,
        baseColor: UIColor
    ) -> NSAttributedString {
        let result = NSMutableAttributedString()

        for span in spans {
            switch span.type {
            case .text:
                result.append(NSAttributedString(string: span.text, attributes: baseAttributes))

            case .bold:
                let boldFont = UIFont.systemFont(ofSize: baseFont.pointSize, weight: .bold)
                let attrs = mergedAttributes(base: baseAttributes, overrides: [.font: boldFont])
                result.append(NSAttributedString(string: span.text, attributes: attrs))

            case .italic:
                let italicFont = UIFont.italicSystemFont(ofSize: baseFont.pointSize)
                let attrs = mergedAttributes(base: baseAttributes, overrides: [.font: italicFont])
                result.append(NSAttributedString(string: span.text, attributes: attrs))

            case .boldItalic:
                let descriptor = UIFont.systemFont(ofSize: baseFont.pointSize, weight: .bold)
                    .fontDescriptor.withSymbolicTraits([.traitBold, .traitItalic])
                let biFont = descriptor.map { UIFont(descriptor: $0, size: baseFont.pointSize) }
                    ?? UIFont.systemFont(ofSize: baseFont.pointSize, weight: .bold)
                let attrs = mergedAttributes(base: baseAttributes, overrides: [.font: biFont])
                result.append(NSAttributedString(string: span.text, attributes: attrs))

            case .code:
                let codeFont = UIFont(name: "Menlo", size: baseFont.pointSize - 1)
                    ?? UIFont.monospacedSystemFont(ofSize: baseFont.pointSize - 1, weight: .regular)
                let attrs = mergedAttributes(base: baseAttributes, overrides: [
                    .font: codeFont,
                    .foregroundColor: UIColor(red: 0.78, green: 0.15, blue: 0.30, alpha: 1.0),
                    .backgroundColor: UIColor(red: 0.97, green: 0.93, blue: 0.94, alpha: 1.0)
                ])
                result.append(NSAttributedString(string: span.text, attributes: attrs))

            case .strikethrough:
                let attrs = mergedAttributes(base: baseAttributes, overrides: [
                    .strikethroughStyle: NSUnderlineStyle.single.rawValue
                ])
                result.append(NSAttributedString(string: span.text, attributes: attrs))

            case .link:
                let linkColor = UIColor.systemBlue
                let attrs = mergedAttributes(base: baseAttributes, overrides: [
                    .foregroundColor: linkColor,
                    .underlineStyle: NSUnderlineStyle.single.rawValue
                ])
                result.append(NSAttributedString(string: span.text, attributes: attrs))
            }
        }

        return result
    }

    // MARK: - Helpers

    private static func isHorizontalRule(_ line: String) -> Bool {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        if trimmed.count < 3 { return false }
        let stripped = trimmed.filter { $0 != " " }
        let chars = Set(stripped)
        return chars.count == 1 && (chars.contains("-") || chars.contains("*") || chars.contains("_"))
            && stripped.count >= 3
    }

    private static func headingFontSize(level: Int, base: CGFloat) -> CGFloat {
        switch level {
        case 1: return base + 8
        case 2: return base + 5
        case 3: return base + 3
        case 4: return base + 1
        default: return base
        }
    }

    private static func mergedAttributes(
        base: [NSAttributedString.Key: Any],
        overrides: [NSAttributedString.Key: Any]
    ) -> [NSAttributedString.Key: Any] {
        var merged = base
        for (key, value) in overrides {
            merged[key] = value
        }
        return merged
    }
}
