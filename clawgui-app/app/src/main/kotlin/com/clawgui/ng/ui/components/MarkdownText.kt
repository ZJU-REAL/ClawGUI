package com.clawgui.ng.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Tiny home-rolled markdown renderer — supports paragraphs, headings, fenced
 * code blocks, inline code, bold, italic, links (rendered as colored text),
 * blockquotes and bullet lists. Not full CommonMark, but covers what an LLM
 * assistant produces in chat. Avoids pulling in a 400KB markdown lib.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is Block.Code -> CodeBlock(block.lang, block.body)
                is Block.Heading -> Text(
                    text = block.body,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.headlineSmall
                        else -> MaterialTheme.typography.titleLarge
                    },
                    color = color,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                is Block.Quote -> Text(
                    text = renderInline(block.body, accent),
                    color = color.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                is Block.Bullet -> Text(
                    text = renderInline("\u2022  " + block.body, accent),
                    color = color,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                )
                is Block.Paragraph -> Text(
                    text = renderInline(block.body, accent),
                    color = color,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String?, body: String) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (!lang.isNullOrBlank()) {
            Text(
                text = lang,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .horizontalScroll(scroll)
                .padding(PaddingValues(horizontal = 14.dp, vertical = 10.dp)),
        )
    }
}

private sealed interface Block {
    data class Paragraph(val body: String) : Block
    data class Heading(val level: Int, val body: String) : Block
    data class Bullet(val body: String) : Block
    data class Quote(val body: String) : Block
    data class Code(val lang: String?, val body: String) : Block
}

private fun parseBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = text.split("\n")
    var i = 0
    val paraBuf = StringBuilder()
    fun flushPara() {
        if (paraBuf.isNotEmpty()) {
            out += Block.Paragraph(paraBuf.toString().trim())
            paraBuf.clear()
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                flushPara()
                val lang = line.removePrefix("```").trim().ifBlank { null }
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    body.append(lines[i]).append('\n'); i++
                }
                out += Block.Code(lang, body.toString().trimEnd())
                if (i < lines.size) i++
            }
            line.startsWith("# ") -> { flushPara(); out += Block.Heading(1, line.removePrefix("# ").trim()); i++ }
            line.startsWith("## ") -> { flushPara(); out += Block.Heading(2, line.removePrefix("## ").trim()); i++ }
            line.startsWith("### ") -> { flushPara(); out += Block.Heading(3, line.removePrefix("### ").trim()); i++ }
            line.startsWith("> ") -> { flushPara(); out += Block.Quote(line.removePrefix("> ").trim()); i++ }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flushPara(); out += Block.Bullet(line.substring(2).trim()); i++
            }
            line.matches(Regex("^\\s*\\d+\\.\\s.*")) -> {
                flushPara(); out += Block.Bullet(line.replaceFirst(Regex("^\\s*\\d+\\.\\s"), "").trim()); i++
            }
            line.isBlank() -> { flushPara(); i++ }
            else -> { if (paraBuf.isNotEmpty()) paraBuf.append('\n'); paraBuf.append(line); i++ }
        }
    }
    flushPara()
    return out
}

private fun renderInline(text: String, accent: Color) = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        // Code `…`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x14000000))) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1; continue
            }
        }
        // Bold **…**
        if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end > i) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2; continue
            }
        }
        // Italic *…*
        if (text[i] == '*') {
            val end = text.indexOf('*', i + 1)
            if (end > i) {
                withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1; continue
            }
        }
        // Link [text](url) — render as colored underlined text only
        if (text[i] == '[') {
            val close = text.indexOf(']', i)
            if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                val paren = text.indexOf(')', close + 2)
                if (paren > close) {
                    withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) {
                        append(text.substring(i + 1, close))
                    }
                    i = paren + 1; continue
                }
            }
        }
        append(text[i]); i++
    }
}
