package com.openchamber.ptt.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun `parseBlocks parses headings, bullets, code fences, blockquotes, tables, and paragraphs`() {
        val md = """
            ### Summary
            - **First** item with `TtsClient.kt`
            1. Ordered item

            > Caveat note

            ```kotlin
            val x = 1
            ```

            | Mode | Status |
            |---|---|
            | Spoken | Ready |
        """.trimIndent()

        val blocks = MarkdownParser.parseBlocks(md)
        assertEquals(6, blocks.size)
        assertEquals(MarkdownBlock.Heading(3, "Summary"), blocks[0])
        assertEquals(MarkdownBlock.BulletItem("•", "**First** item with `TtsClient.kt`"), blocks[1])
        assertEquals(MarkdownBlock.BulletItem("1.", "Ordered item"), blocks[2])
        assertEquals(MarkdownBlock.BlockQuote("Caveat note"), blocks[3])
        assertEquals(MarkdownBlock.CodeFence("kotlin", "val x = 1"), blocks[4])
        assertEquals(
            MarkdownBlock.TableBlock(listOf("Mode", "Status"), listOf(listOf("Spoken", "Ready"))),
            blocks[5],
        )
    }

    @Test
    fun `buildInlineMarkdown preserves snake_case identifiers and formats bold, code, and links`() {
        val annotated = buildInlineMarkdown(
            text = "Check `SEND_GRACE` and **openchamber_api35** in [Docs](https://example.com).",
            codeBg = Color.LightGray,
            codeFg = Color.Black,
            linkColor = Color.Blue,
        )
        assertEquals("Check SEND_GRACE and openchamber_api35 in Docs.", annotated.text)
        assertTrue(annotated.spanStyles.any { it.item.background == Color.LightGray })
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isNotEmpty())

        val relative = buildInlineMarkdown(
            text = "Open [Local](/tmp/file.kt).",
            codeBg = Color.LightGray,
            codeFg = Color.Black,
            linkColor = Color.Blue,
        )
        assertEquals("Open Local.", relative.text)
        assertTrue(relative.getLinkAnnotations(0, relative.length).isEmpty())
    }
}
