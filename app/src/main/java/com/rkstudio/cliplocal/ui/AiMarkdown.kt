package com.rkstudio.cliplocal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private data class MdBlock(val kind: String, val text: String, val level: Int = 0)

private fun markdownBlocks(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").lines()
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    fun special(line: String) = line.startsWith("#") || line.startsWith("```") ||
        line.trimStart().matches(Regex("^([-*+]\\s+|[0-9]+[.)]\\s+|>\\s?).*"))
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.isBlank() || line == "---" || line == "***") { i++; continue }
        if (line.startsWith("```")) {
            val code = mutableListOf<String>(); i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) { code += lines[i]; i++ }
            if (i < lines.size) i++
            blocks += MdBlock("code", code.joinToString("\n")); continue
        }
        val heading = Regex("^(#{1,4})\\s+(.+)$").matchEntire(line)
        if (heading != null) { blocks += MdBlock("heading", heading.groupValues[2], heading.groupValues[1].length); i++; continue }
        val bullet = Regex("^[-*+]\\s+(.+)$").matchEntire(line)
        if (bullet != null) { blocks += MdBlock("bullet", bullet.groupValues[1]); i++; continue }
        val number = Regex("^([0-9]+)[.)]\\s+(.+)$").matchEntire(line)
        if (number != null) { blocks += MdBlock("number:${number.groupValues[1]}", number.groupValues[2]); i++; continue }
        if (line.startsWith(">")) { blocks += MdBlock("quote", line.removePrefix(">").trim()); i++; continue }
        val paragraph = mutableListOf(line); i++
        while (i < lines.size && lines[i].isNotBlank() && !special(lines[i].trim())) { paragraph += lines[i].trim(); i++ }
        blocks += MdBlock("paragraph", paragraph.joinToString(" "))
    }
    return blocks
}

private fun inlineMarkdown(text: String) = buildAnnotatedString {
    val pattern = Regex("(\\*\\*.+?\\*\\*|__.+?__|`[^`]+`|\\*[^*]+\\*|_[^_]+_)")
    var cursor = 0
    pattern.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") || token.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.substring(2, token.length - 2))
            }
            token.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace,
                background = androidx.compose.ui.graphics.Color(0x1A888888))) {
                append(token.substring(1, token.length - 1))
            }
            else -> withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                append(token.substring(1, token.length - 1))
            }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

@Composable
internal fun AiMarkdown(content: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        markdownBlocks(content).forEach { block ->
            when {
                block.kind == "code" -> Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(block.text, modifier = Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
                block.kind == "bullet" || block.kind.startsWith("number:") -> Row(
                    modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(if (block.kind == "bullet") "•" else block.kind.substringAfter(":"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                }
                block.kind == "quote" -> Text(inlineMarkdown(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(8.dp)).padding(12.dp))
                block.kind == "heading" -> Text(inlineMarkdown(block.text), style = when (block.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                }, fontWeight = FontWeight.SemiBold)
                else -> Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
