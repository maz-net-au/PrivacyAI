package au.net.maz.privacyai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal Markdown renderer matching the subset the prototype's `md()` supports:
 * fenced code blocks, unordered lists, inline code, **bold** and *italic*.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val blocks = remember(text) { parseBlocks(text) }
    val body = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
    // Inline span styles depend on the theme, so build them in composition.
    val codeSpan = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        background = body.copy(alpha = 0.08f),
        color = body,
    )
    // System Roboto has no bundled italic face, so the synthesized slant is barely
    // visible — pair it with a distinct color so *italic* (e.g. roleplay *actions*)
    // reads as clearly different from body text.
    val italicSpan = SpanStyle(fontStyle = FontStyle.Italic, color = body.copy(alpha = 0.72f))
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = inlineAnnotated(block.text, codeSpan, italicSpan),
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    color = body,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                is MdBlock.Bullets -> Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    block.items.forEach { item ->
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            Text("•", fontSize = 15.sp, color = body, modifier = Modifier.padding(end = 8.dp))
                            Text(
                                text = inlineAnnotated(item, codeSpan, italicSpan),
                                fontSize = 15.sp,
                                lineHeight = 23.sp,
                                color = body,
                            )
                        }
                    }
                }
                is MdBlock.Heading -> Text(
                    text = inlineAnnotated(block.text, codeSpan, italicSpan),
                    fontSize = headingSize(block.level),
                    lineHeight = headingSize(block.level) * 1.3f,
                    fontWeight = FontWeight.Bold,
                    color = body,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                is MdBlock.Code -> CodeBlock(block.code, body)
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, color: Color) {
    Text(
        text = code,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = color,
        modifier = Modifier
            .padding(vertical = 8.dp)
            .background(color.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    )
}

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullets(val items: List<String>) : MdBlock
    data class Code(val code: String) : MdBlock
}

/** Heading font sizes for levels 1..6, clamped so deep headings stay readable. */
private fun headingSize(level: Int) = when (level) {
    1 -> 24.sp
    2 -> 20.sp
    3 -> 18.sp
    4 -> 16.sp
    else -> 15.sp
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")

private fun parseBlocks(src: String): List<MdBlock> {
    val lines = src.split("\n")
    val blocks = mutableListOf<MdBlock>()
    var bullets: MutableList<String>? = null
    fun flushBullets() {
        bullets?.let { blocks += MdBlock.Bullets(it) }
        bullets = null
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flushBullets()
                i++
                val code = StringBuilder()
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append("\n")
                    code.append(lines[i])
                    i++
                }
                i++ // closing fence
                blocks += MdBlock.Code(code.toString())
            }
            headingRegex.matchEntire(line.trimStart()) != null -> {
                flushBullets()
                val m = headingRegex.matchEntire(line.trimStart())!!
                blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
                i++
            }
            Regex("^\\s*[-*]\\s+").containsMatchIn(line) -> {
                val item = line.replaceFirst(Regex("^\\s*[-*]\\s+"), "")
                if (bullets == null) bullets = mutableListOf()
                bullets!!.add(item)
                i++
            }
            line.isBlank() -> {
                flushBullets()
                i++
            }
            else -> {
                flushBullets()
                blocks += MdBlock.Paragraph(line)
                i++
            }
        }
    }
    flushBullets()
    return blocks
}

private val boldSpan = SpanStyle(fontWeight = FontWeight.Bold)

/** Inline formatting matching the prototype's `inline()`: `code`, **bold**, *italic*. */
private fun inlineAnnotated(src: String, codeSpan: SpanStyle, italicSpan: SpanStyle): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            c == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(codeSpan) { append(src.substring(i + 1, end)) }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == '*' && i + 1 < src.length && src[i + 1] == '*' -> {
                val end = src.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(boldSpan) { append(src.substring(i + 2, end)) }
                    i = end + 2
                } else { append(c); i++ }
            }
            c == '*' -> {
                val end = src.indexOf('*', i + 1)
                if (end > i) {
                    withStyle(italicSpan) { append(src.substring(i + 1, end)) }
                    i = end + 1
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}
