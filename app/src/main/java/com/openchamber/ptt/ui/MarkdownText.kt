package com.openchamber.ptt.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class BulletItem(val marker: String, val text: String) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    data class CodeFence(val language: String?, val code: String) : MarkdownBlock
    data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

internal object MarkdownParser {
    private val HEADING_REGEX = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
    private val FENCE_REGEX = Regex("""^\s{0,3}```(.*)$""")
    private val UNORDERED_LIST_REGEX = Regex("""^\s*[-*+]\s+(.*)$""")
    private val ORDERED_LIST_REGEX = Regex("""^\s*(\d+\.)\s+(.*)$""")
    private val HR_REGEX = Regex("""^\s*(?:-{3,}|\*{3,}|_{3,})\s*$""")
    private val TABLE_DIVIDER_REGEX = Regex("""^\s*\|?(?:\s*:?-{3,}:?\s*\|)+\s*:?-{0,}:?\s*$""")

    fun parseBlocks(markdown: String): List<MarkdownBlock> {
        val lines = markdown.replace("\r\n", "\n").lines()
        val blocks = mutableListOf<MarkdownBlock>()
        var idx = 0

        while (idx < lines.size) {
            val line = lines[idx]
            if (line.isBlank()) {
                idx++
                continue
            }

            val fenceMatch = FENCE_REGEX.matchEntire(line)
            if (fenceMatch != null) {
                val lang = fenceMatch.groupValues[1].trim().ifEmpty { null }
                val codeLines = mutableListOf<String>()
                idx++
                while (idx < lines.size && FENCE_REGEX.matchEntire(lines[idx]) == null) {
                    codeLines += lines[idx]
                    idx++
                }
                if (idx < lines.size) idx++ // consume closing fence
                blocks += MarkdownBlock.CodeFence(lang, codeLines.joinToString("\n"))
                continue
            }

            if (HR_REGEX.matches(line)) {
                blocks += MarkdownBlock.Divider
                idx++
                continue
            }

            val headingMatch = HEADING_REGEX.matchEntire(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                blocks += MarkdownBlock.Heading(level, headingMatch.groupValues[2].trim())
                idx++
                continue
            }

            if (idx + 1 < lines.size && line.contains('|') && TABLE_DIVIDER_REGEX.matches(lines[idx + 1])) {
                val headers = parseTableRow(line)
                idx += 2
                val rows = mutableListOf<List<String>>()
                while (idx < lines.size && lines[idx].contains('|') && lines[idx].isNotBlank()) {
                    rows += parseTableRow(lines[idx])
                    idx++
                }
                blocks += MarkdownBlock.TableBlock(headers, rows)
                continue
            }

            if (line.trimStart().startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (idx < lines.size && lines[idx].trimStart().startsWith(">")) {
                    quoteLines += lines[idx].trimStart().removePrefix(">").trimStart()
                    idx++
                }
                blocks += MarkdownBlock.BlockQuote(quoteLines.joinToString(" "))
                continue
            }

            val bulletMatch = UNORDERED_LIST_REGEX.matchEntire(line)
            if (bulletMatch != null) {
                blocks += MarkdownBlock.BulletItem("•", bulletMatch.groupValues[1].trim())
                idx++
                continue
            }

            val orderedMatch = ORDERED_LIST_REGEX.matchEntire(line)
            if (orderedMatch != null) {
                blocks += MarkdownBlock.BulletItem(orderedMatch.groupValues[1], orderedMatch.groupValues[2].trim())
                idx++
                continue
            }

            val paraLines = mutableListOf<String>()
            while (idx < lines.size) {
                val cur = lines[idx]
                if (cur.isBlank() ||
                    FENCE_REGEX.matchEntire(cur) != null ||
                    HR_REGEX.matches(cur) ||
                    HEADING_REGEX.matchEntire(cur) != null ||
                    cur.trimStart().startsWith(">") ||
                    UNORDERED_LIST_REGEX.matchEntire(cur) != null ||
                    ORDERED_LIST_REGEX.matchEntire(cur) != null ||
                    (idx + 1 < lines.size && cur.contains('|') && TABLE_DIVIDER_REGEX.matches(lines[idx + 1]))
                ) {
                    break
                }
                paraLines += cur.trim()
                idx++
            }
            if (paraLines.isNotEmpty()) {
                blocks += MarkdownBlock.Paragraph(paraLines.joinToString(" "))
            }
        }

        return blocks
    }

    private fun parseTableRow(line: String): List<String> =
        line.trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split('|')
            .map { it.trim() }
}

@Composable
internal fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    baseStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val blocks = remember(markdown) { MarkdownParser.parseBlocks(markdown) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeFg = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val quoteBorder = MaterialTheme.colorScheme.outline

    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val headingStyle = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    }.copy(fontWeight = FontWeight.SemiBold, color = textColor)
                    Text(
                        text = buildInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                        style = headingStyle,
                    )
                }

                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = buildInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                        style = baseStyle.copy(color = textColor),
                    )
                }

                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = block.marker,
                            style = baseStyle.copy(color = textColor, fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = buildInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                            style = baseStyle.copy(color = textColor),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MarkdownBlock.BlockQuote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(quoteBorder, RoundedCornerShape(2.dp)),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = buildInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                            style = baseStyle.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = FontStyle.Italic,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MarkdownBlock.CodeFence -> {
                    Surface(
                        color = codeBg,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            if (!block.language.isNullOrBlank()) {
                                Text(
                                    text = block.language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            Text(
                                text = block.code,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = codeFg,
                                ),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }

                is MarkdownBlock.TableBlock -> {
                    val borderColor = MaterialTheme.colorScheme.outlineVariant
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            Column(
                                modifier = Modifier.width(IntrinsicSize.Max),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(codeBg)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    block.headers.forEach { header ->
                                        Text(
                                            text = buildInlineMarkdown(header, codeBg, codeFg, linkColor),
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = textColor,
                                            ),
                                            modifier = Modifier.widthIn(min = 96.dp, max = 220.dp),
                                        )
                                    }
                                }
                                block.rows.forEach { row ->
                                    HorizontalDivider(color = borderColor, modifier = Modifier.fillMaxWidth())
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        block.headers.indices.forEach { colIdx ->
                                            val cell = row.getOrElse(colIdx) { "" }
                                            Text(
                                                text = buildInlineMarkdown(cell, codeBg, codeFg, linkColor),
                                                style = MaterialTheme.typography.bodySmall.copy(color = textColor),
                                                modifier = Modifier.widthIn(min = 96.dp, max = 220.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                MarkdownBlock.Divider -> {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            }
        }
    }
}

internal fun buildInlineMarkdown(
    text: String,
    codeBg: Color,
    codeFg: Color,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val len = text.length
    while (i < len) {
        // Inline code: `code`
        if (text[i] == '`') {
            val end = text.indexOf('`', startIndex = i + 1)
            if (end > i + 1) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                        color = codeFg,
                    ),
                ) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }

        // Markdown link: [label](url)
        if (text[i] == '[') {
            val closeBracket = text.indexOf(']', startIndex = i + 1)
            if (closeBracket > i + 1 &&
                closeBracket + 1 < len &&
                text[closeBracket + 1] == '('
            ) {
                val closeParen = text.indexOf(')', startIndex = closeBracket + 2)
                if (closeParen > closeBracket + 2) {
                    val label = text.substring(i + 1, closeBracket)
                    val url = text.substring(closeBracket + 2, closeParen).trim()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        pushLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                            ),
                        )
                        appendInlineFormattedLabel(label, codeBg, codeFg)
                        pop()
                    } else {
                        appendInlineFormattedLabel(label, codeBg, codeFg)
                    }
                    i = closeParen + 1
                    continue
                }
            }
        }

        // Bold: **text** or __text__
        if (i + 1 < len && ((text[i] == '*' && text[i + 1] == '*') || (text[i] == '_' && text[i + 1] == '_'))) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, startIndex = i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(buildInlineMarkdown(text.substring(i + 2, end), codeBg, codeFg, linkColor))
                }
                i = end + 2
                continue
            }
        }

        // Strikethrough: ~~text~~
        if (i + 1 < len && text[i] == '~' && text[i + 1] == '~') {
            val end = text.indexOf("~~", startIndex = i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(buildInlineMarkdown(text.substring(i + 2, end), codeBg, codeFg, linkColor))
                }
                i = end + 2
                continue
            }
        }

        // Italic: *text* or _text_ (avoid splitting snake_case words on '_')
        if (text[i] == '*' || (text[i] == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()))) {
            val delim = text[i]
            val end = text.indexOf(delim, startIndex = i + 1)
            val validClose = end > i + 1 && (
                delim == '*' || end + 1 >= len || !text[end + 1].isLetterOrDigit()
                )
            if (validClose) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(buildInlineMarkdown(text.substring(i + 1, end), codeBg, codeFg, linkColor))
                }
                i = end + 1
                continue
            }
        }

        append(text[i])
        i++
    }
}

private fun AnnotatedString.Builder.appendInlineFormattedLabel(
    label: String,
    codeBg: Color,
    codeFg: Color,
) {
    if (label.startsWith('`') && label.endsWith('`') && label.length >= 2) {
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeFg)) {
            append(label.substring(1, label.length - 1))
        }
    } else {
        append(label)
    }
}
