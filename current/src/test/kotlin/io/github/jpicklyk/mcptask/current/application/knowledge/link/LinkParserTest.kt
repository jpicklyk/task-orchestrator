package io.github.jpicklyk.mcptask.current.application.knowledge.link

import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored tests of [LinkParser] (item 23faed48, seat test-author) against the frozen queue-phase
 * `task-scope` decisions D1-D3 and `test-plan` S1-S6. No implementation source, diff or commit was opened; every expected
 * value is derived from the cited decision, never from what the parser returns.
 *
 *  - D1 grammar: target = text between `[[` and the next `]]` on one line, trimmed; empty, or containing `[`, `]` or a
 *    newline, is no link. Prefix `plan:|rule:|page:|item:` case-insensitive; any other `x:` prefix is no link. `#` is split
 *    once for document refs, an empty anchor is null. `item:` takes a canonical UUID or 8..32 hex, otherwise no link.
 *    The slug is case-sensitive.
 *  - D2: `[[...]]` inside inline backtick spans or fenced (``` / ~~~) blocks is ignored.
 *  - D3: one target per (kind?, slug) or item id, the first occurrence's anchor kept; at most [LinkParser.MAX_LINK_TARGETS]
 *    distinct targets, the first 256 in document order.
 *
 * Representation tolerance: D1 does not say how an item id's text is normalized (case, dashes). Item assertions therefore
 * compare ids after removing dashes and lowercasing (see [norm]); the arbitration record is in the test-manifest.
 */
class LinkParserTest {
    private fun doc(
        slug: String,
        kind: DocumentKind? = null,
        anchor: String? = null
    ) = DocumentTarget(kind, slug, anchor)

    private fun parse(body: String): List<LinkTarget> = LinkParser.parse(body)

    private fun ItemTarget.norm(): String = id.replace("-", "").lowercase()

    private fun single(body: String): LinkTarget = parse(body).single()

    // ---------------------------------------------------------------- S1 happy

    @Test
    fun `S1 the four document forms and an item form parse in document order`() {
        val parsed = parse("see [[alpha]], [[plan:beta#intro]], [[item:0123abcd]]")

        assertEquals(
            listOf(doc("alpha"), doc("beta", DocumentKind.PLAN, "intro"), ItemTarget("0123abcd")),
            parsed
        )
    }

    @Test
    fun `S1 each documented document form maps to its kind slug and anchor`() {
        assertEquals(doc("s"), single("[[s]]"))
        assertEquals(doc("s", anchor = "h"), single("[[s#h]]"))
        assertEquals(doc("s", DocumentKind.PLAN), single("[[plan:s]]"))
        assertEquals(doc("s", DocumentKind.RULE, "h"), single("[[rule:s#h]]"))
        assertEquals(doc("s", DocumentKind.PAGE), single("[[page:s]]"))
        assertEquals(doc("s", DocumentKind.PAGE, "h"), single("[[page:s#h]]"))
    }

    @Test
    fun `S1 toRef is the slug or kind colon slug with no anchor and anchor is exposed separately`() {
        val bare = single("[[alpha#frag]]")
        assertEquals("alpha", bare.toRef)
        assertEquals("frag", bare.anchor)

        val qualified = single("[[plan:beta#intro]]")
        assertEquals("plan:beta", qualified.toRef)
        assertEquals("intro", qualified.anchor)

        val rule = single("[[rule:r1]]")
        assertEquals("rule:r1", rule.toRef)
        assertNull(rule.anchor)
    }

    @Test
    fun `S1 links adjacent to each other and glued to words are all found`() {
        assertEquals(listOf(doc("a"), doc("b")), parse("[[a]][[b]]"))
        assertEquals(listOf(doc("a")), parse("word[[a]]word"))
    }

    @Test
    fun `S1 a body with no double brackets yields nothing`() {
        assertEquals(emptyList(), parse(""))
        assertEquals(emptyList(), parse("plain text with [single] brackets and (parens)"))
    }

    // ---------------------------------------------------------------- S2 code is not prose (D2)

    @Test
    fun `S2 control - the same links outside code are found, so the exclusion cases below are not vacuous`() {
        assertEquals(listOf(doc("x"), doc("y")), parse("inline [[x]] here\n\n[[y]]\n"))
    }

    @Test
    fun `S2 a link inside an inline backtick span and one inside a fenced block are ignored`() {
        val body = "inline `[[x]]` here\n\n```\n[[y]]\n```\n"

        assertEquals(emptyList(), parse(body))
    }

    @Test
    fun `S2 a tilde fence with an info string hides its links`() {
        assertEquals(emptyList(), parse("~~~text\n[[y]]\n~~~\n"))
        assertEquals(emptyList(), parse("```kotlin\n[[y]]\n```\n"))
    }

    @Test
    fun `S2 text after a closed fence and links around spans are still parsed`() {
        assertEquals(listOf(doc("z")), parse("```\n[[y]]\n```\n[[z]]"))
        assertEquals(listOf(doc("k")), parse("`a` [[k]] `b`"))
        assertEquals(listOf(doc("w")), parse("`[[x]]` and [[w]]"))
        assertEquals(listOf(doc("before"), doc("after")), parse("[[before]]\n```\n[[inside]]\n```\n[[after]]"))
    }

    @Test
    fun `S2 probe - a lone unmatched backtick is literal text, not a span`() {
        // Oracle: D2 says "inline backtick spans"; an unmatched backtick opens no span (CommonMark 6.1).
        assertEquals(listOf(doc("z")), parse("it`s [[z]]"))
    }

    @Test
    fun `S2 probe - a double-backtick code span hides its link`() {
        // Oracle: D2 plus the CommonMark code-span definition (a run of N backticks closes with a run of N).
        assertEquals(emptyList(), parse("``[[x]]`` and nothing else"))
    }

    // ---------------------------------------------------------------- S3 failure (D1)

    @Test
    fun `S3 malformed targets produce no link`() {
        listOf(
            "[[]]",
            "[[ ]]",
            "[[plan:]]",
            "[[PLAN:]]",
            "[[#x]]",
            "[[plan:#x]]",
            "[[zz:a]]",
            "[[x:y]]",
            "[[item:0123abc]]",
            "[[item:xyz]]",
            "[[item:0123abcg]]",
            "[[item:" + "a".repeat(33) + "]]",
            "[[item:]]",
            "[[a",
            "[[a]",
            "[[a\nb]]",
            "[[a\r\nb]]"
        ).forEach { body -> assertEquals(emptyList(), parse(body), "must not link: ${body.replace("\n", "\\n").replace("\r", "\\r")}") }
    }

    @Test
    fun `S3 a target containing a square bracket is not a link`() {
        assertEquals(emptyList(), parse("[[a[b]]"))
        assertEquals(emptyList(), parse("[[a]b]]"))
    }

    @Test
    fun `S3 item ids at the 8 and 32 hex bounds and as a canonical UUID are links`() {
        val eight = single("[[item:01234567]]") as ItemTarget
        assertEquals("01234567", eight.norm())

        val thirtyTwo = single("[[item:123e4567e89b12d3a456426614174000]]") as ItemTarget
        assertEquals("123e4567e89b12d3a456426614174000", thirtyTwo.norm())

        val canonical = single("[[item:123e4567-e89b-12d3-a456-426614174000]]") as ItemTarget
        assertEquals("123e4567e89b12d3a456426614174000", canonical.norm())
    }

    // ---------------------------------------------------------------- S4 dedupe (D3)

    @Test
    fun `S4 a repeated slug keeps the first anchor and the slug is case-sensitive`() {
        assertEquals(listOf(doc("a", anchor = "x"), doc("A")), parse("[[a#x]] [[a#y]] [[A]]"))
    }

    @Test
    fun `S4 a later duplicate with an anchor does not replace an earlier anchorless occurrence`() {
        assertEquals(listOf(doc("a")), parse("[[a]] [[a#x]]"))
    }

    @Test
    fun `S4 the same slug with different kinds are distinct targets`() {
        assertEquals(
            listOf(doc("a"), doc("a", DocumentKind.PLAN), doc("a", DocumentKind.RULE), doc("a", DocumentKind.PAGE)),
            parse("[[a]] [[plan:a]] [[rule:a]] [[page:a]] [[a]] [[plan:a#again]]")
        )
    }

    @Test
    fun `S4 a repeated item id is one target`() {
        assertEquals(1, parse("[[item:0123abcd]] [[item:0123abcd]]").size)
    }

    // ---------------------------------------------------------------- S5 cap (D3)

    @Test
    fun `S5 the cap constant is 256`() {
        assertEquals(256, LinkParser.MAX_LINK_TARGETS)
    }

    @Test
    fun `S5 257 distinct slugs yield the first 256 in order and 256 yield 256`() {
        val body257 = (0 until 257).joinToString(" ") { "[[s$it]]" }
        val got257 = parse(body257)
        assertEquals(256, got257.size)
        assertEquals((0 until 256).map { doc("s$it") }, got257)

        val body256 = (0 until 256).joinToString(" ") { "[[s$it]]" }
        assertEquals((0 until 256).map { doc("s$it") }, parse(body256))

        val body255 = (0 until 255).joinToString(" ") { "[[s$it]]" }
        assertEquals(255, parse(body255).size)
    }

    @Test
    fun `S5 the cap counts distinct targets, not occurrences`() {
        val repeated = (0 until 600).joinToString(" ") { "[[s${it % 100}]]" }
        assertEquals((0 until 100).map { doc("s$it") }, parse(repeated))

        val twiceEach = (0 until 256).joinToString(" ") { "[[s$it]] [[s$it]]" }
        assertEquals(256, parse(twiceEach).size)
    }

    @Test
    fun `S5 mixed document and item targets share one cap`() {
        val docs = (0 until 200).joinToString(" ") { "[[d$it]]" }
        val items = (0 until 100).joinToString(" ") { "[[item:%08x]]".format(it + 0x10000000) }
        val got = parse("$docs $items")
        assertEquals(256, got.size)
        assertEquals(200, got.count { it is DocumentTarget })
        assertEquals(56, got.count { it is ItemTarget })
    }

    // ---------------------------------------------------------------- S6 normalization (D1)

    @Test
    fun `S6 the kind prefix is case-insensitive, an empty anchor is null, and the target is trimmed`() {
        assertEquals(doc("b", DocumentKind.PLAN), single("[[PLAN:b]]"))
        assertEquals(doc("b", DocumentKind.RULE), single("[[Rule:b]]"))
        assertEquals(doc("b"), single("[[b#]]"))
        assertEquals(doc("a"), single("[[ a ]]"))
        assertEquals(doc("a", DocumentKind.PLAN, "h"), single("[[ plan:a#h ]]"))
        assertIs<ItemTarget>(single("[[ITEM:0123abcd]]"))
    }

    @Test
    fun `S6 the anchor is split once so later hashes and spaces stay in it`() {
        assertEquals(doc("a", anchor = "x#y"), single("[[a#x#y]]"))
        assertEquals(doc("a", anchor = "two words"), single("[[a#two words]]"))
    }

    // ---------------------------------------------------------------- probes

    @Test
    fun `probe - a CRLF body yields clean slugs and anchors with no carriage return`() {
        val got = parse("[[a]]\r\n[[b#h]]\r\n")

        assertEquals(listOf(doc("a"), doc("b", anchor = "h")), got)
    }

    @Test
    fun `probe - nested and unbalanced brackets never produce a slug containing a bracket`() {
        // Oracle: D1 "containing [ or ] -> no link". Whether `[[[a]]]` links `a` or nothing is not frozen, so only the
        // invariant is asserted (arbitration record in the test-manifest).
        listOf("[[[a]]]", "[[a [[b]]", "[[[a]]", "[[a]]]").forEach { body ->
            val bad = parse(body).filterIsInstance<DocumentTarget>().filter { '[' in it.slug || ']' in it.slug }
            assertEquals(emptyList(), bad, "bracket leaked into a slug for: $body")
        }
    }

    @Test
    fun `probe - a non-ASCII slug is kept verbatim`() {
        val japanese = "\u65e5\u672c\u8a9e"
        val cafe = "caf\u00e9"
        val uber = "\u00fcber"
        assertEquals(
            listOf(doc(japanese), doc(cafe, DocumentKind.PAGE, uber)),
            parse("[[$japanese]] [[page:$cafe#$uber]]")
        )
    }

    @Test
    fun `probe - uppercase hex and an uppercase UUID are accepted as item targets`() {
        // Oracle: D1 "8..32 hex"; hex digits are case-insensitive. The stored case is implementation-defined, so compare normalized.
        val upperEight = single("[[item:0123ABCD]]") as ItemTarget
        assertEquals("0123abcd", upperEight.norm())
        val upperUuid = single("[[item:123E4567-E89B-12D3-A456-426614174000]]") as ItemTarget
        assertEquals("123e4567e89b12d3a456426614174000", upperUuid.norm())
    }

    @Test
    fun `probe - a 64 KiB body of one repeated link yields exactly one target`() {
        val body = "[[x]]".repeat(13_000)
        assertTrue(body.length >= 64_000)

        assertEquals(listOf(doc("x")), parse(body))
    }

    @Test
    fun `probe - a 64 KiB body of distinct links is capped at 256`() {
        val body = (0 until 13_000).joinToString("") { "[[s$it]]" }

        val got = parse(body)

        assertEquals(256, got.size)
        assertEquals(doc("s0"), got.first())
        assertEquals(doc("s255"), got.last())
    }

    @Test
    fun `probe - a link on the first and last line of a body without trailing newline is found`() {
        assertEquals(listOf(doc("a"), doc("z")), parse("[[a]]\nmiddle\n[[z]]"))
    }
}
