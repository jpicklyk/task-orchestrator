package io.github.jpicklyk.mcptask.current.application.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownSectionSplitterTest {
    // ──────────────────────────────────────────────────────────────────────────
    // anchors()
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `anchors returns a slug per heading in document order across all levels`() {
        val body =
            """
            # Title
            Intro text.
            ## Section One
            Body one.
            ### Subsection
            Body sub.
            ## Section Two
            Body two.
            """.trimIndent()

        assertEquals(
            listOf("title", "section-one", "subsection", "section-two"),
            MarkdownSectionSplitter.anchors(body)
        )
    }

    @Test
    fun `anchors returns empty list for a document with no headings`() {
        val body = "Just plain text.\nNo headings here.\n"
        assertTrue(MarkdownSectionSplitter.anchors(body).isEmpty())
    }

    @Test
    fun `anchors dedups repeated headings with -2, -3 suffixes in document order`() {
        val body =
            """
            # Overview
            First.
            # Overview
            Second.
            # Overview
            Third.
            """.trimIndent()

        assertEquals(listOf("overview", "overview-2", "overview-3"), MarkdownSectionSplitter.anchors(body))
    }

    @Test
    fun `anchors slugifies punctuation and mixed case deterministically`() {
        val body = "## Plan: Auth & Session (v2)!\nBody.\n"
        assertEquals(listOf("plan-auth-session-v2"), MarkdownSectionSplitter.anchors(body))
    }

    @Test
    fun `anchors falls back to 'section' for an all-punctuation heading`() {
        val body = "# !!!\nBody.\n"
        assertEquals(listOf("section"), MarkdownSectionSplitter.anchors(body))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // slice()
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `slice returns null when the anchor does not match any heading`() {
        val body = "# Title\nBody.\n"
        assertNull(MarkdownSectionSplitter.slice(body, "does-not-exist"))
    }

    @Test
    fun `slice returns null for a document with no headings at all`() {
        assertNull(MarkdownSectionSplitter.slice("Just plain text.\n", "anything"))
    }

    @Test
    fun `slice of the sole top-level heading includes all nested sub-headings through the end of the document`() {
        // A level-1 heading only ends at the next level-1 heading (or end of document) — level-2/3
        // headings are LOWER level, not "same or higher", so they stay part of the H1's section.
        val body =
            """
            # Title
            Intro.
            ## Section One
            Body one.
            ### Subsection
            Sub body.
            ## Section Two
            Body two.
            """.trimIndent()

        assertEquals(body, MarkdownSectionSplitter.slice(body, "title"))
    }

    @Test
    fun `slice of a top-level heading stops at the next heading of the same level`() {
        val body =
            """
            # Title One
            Intro.
            ## Section One
            Body one.
            # Title Two
            Body two.
            """.trimIndent()

        val expected =
            """
            # Title One
            Intro.
            ## Section One
            Body one.
            """.trimIndent()

        assertEquals(expected, MarkdownSectionSplitter.slice(body, "title-one"))
    }

    @Test
    fun `slice of a sub-heading stops at the next heading of the same or higher level`() {
        val body =
            """
            # Title
            Intro.
            ## Section One
            Body one.
            ### Subsection
            Sub body.
            ## Section Two
            Body two.
            """.trimIndent()

        val expected =
            """
            ## Section One
            Body one.
            ### Subsection
            Sub body.
            """.trimIndent()

        assertEquals(expected, MarkdownSectionSplitter.slice(body, "section-one"))
    }

    @Test
    fun `slice of the last heading runs to the end of the document`() {
        val body =
            """
            # Title
            Intro.
            ## Section Two
            Body two.
            More text.
            """.trimIndent()

        val expected =
            """
            ## Section Two
            Body two.
            More text.
            """.trimIndent()

        assertEquals(expected, MarkdownSectionSplitter.slice(body, "section-two"))
    }

    @Test
    fun `slice is deterministic across repeated calls on the same document`() {
        val body =
            """
            # Overview
            First.
            # Overview
            Second.
            """.trimIndent()

        val first = MarkdownSectionSplitter.slice(body, "overview-2")
        val second = MarkdownSectionSplitter.slice(body, "overview-2")
        assertEquals(first, second)
        assertEquals("# Overview\nSecond.", first)
    }

    @Test
    fun `slice supports heading levels beyond h2 h3`() {
        val body =
            """
            #### Deep Heading
            Deep body.
            ##### Deeper Heading
            Deeper body.
            ###### Deepest Heading
            Deepest body.
            """.trimIndent()

        assertEquals(
            "##### Deeper Heading\nDeeper body.\n###### Deepest Heading\nDeepest body.",
            MarkdownSectionSplitter.slice(body, "deeper-heading")
        )
    }
}
