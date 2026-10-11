package io.github.jpicklyk.mcptask.current.application.telemetry

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item ab14346c, Q7 host capture) for [HostName] and [CallTelemetryFactory].
 * Scenarios S11, S12, S16 and the probes of the frozen test-plan, plus the unit-level halves of S3, S4, S5, S8 and S9.
 *
 * Oracles (task-scope, frozen at queue phase, never read from the implementation):
 * - Normalization N1-N6: null stays null; remove every char with `Character.isISOControl`; trim (Kotlin whitespace);
 *   `lowercase(Locale.ROOT)`; more than 64 code points keeps the first 64 code points then trimEnd (a surrogate pair is
 *   never split); an empty result becomes null.
 * - UA product (RFC 9110 10.1.5, `product = token ["/" product-version]`): trim; empty or starting with `(` means none;
 *   else the text before the first whitespace, then the text before the first `/`; later products are never consulted.
 * - REST host: normalize(first X-Client-Host value) when non-null, else the normalized UA product when non-null, else `rest`.
 * - MCP host: normalize(clientInfo.name); no fallback literal.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HostNameTest {
    private fun rep(
        ch: Char,
        n: Int
    ): String = ch.toString().repeat(n)

    private fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    private fun hasLoneSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return true
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                return true
            } else {
                i++
            }
        }
        return false
    }

    /** The task-scope N1-N6 spelled out independently, used only for the differential corpus test. */
    private fun reference(raw: String?): String? {
        if (raw == null) return null
        var s = raw.filterNot { Character.isISOControl(it) }
        s = s.trim()
        s = s.lowercase(Locale.ROOT)
        if (s.codePointCount(0, s.length) > 64) {
            s = s.substring(0, s.offsetByCodePoints(0, 64)).trimEnd()
        }
        return s.ifEmpty { null }
    }

    // ------------------------------------------------------------------ constants

    @Test
    fun `constants are the documented bound and fallback literal`() {
        assertEquals(64, HostName.MAX_LENGTH)
        assertEquals("rest", HostName.REST_FALLBACK)
    }

    // ------------------------------------------------------------------ S12 N1-N6

    @Test
    fun `S12 null blank and control-only inputs normalize to null`() {
        assertNull(HostName.normalize(null), "N1")
        assertNull(HostName.normalize(""), "N6 empty")
        assertNull(HostName.normalize("  "), "N3 then N6")
        assertNull(HostName.normalize("\n"), "N2 removes the newline, N6")
        assertNull(HostName.normalize("\u0007"), "a lone control char")
        assertNull(HostName.normalize("\t \r\n"), "controls and spaces only")
        assertNull(HostName.normalize("\u0000\u0000\u0000"), "NULs only")
    }

    @Test
    fun `S12 control characters are removed not replaced and the rest is lowercased`() {
        assertEquals("abc", HostName.normalize("A\u0000B\tC"), "the planner's worked case, recomputed from N2 and N4")
        assertEquals("ab", HostName.normalize("a\u0085b"), "U+0085 is an ISO control (C1)")
        assertEquals("ab", HostName.normalize("a\u007Fb"), "U+007F DEL is an ISO control")
    }

    @Test
    fun `S1 interior spaces are kept and case folds to lower`() {
        assertEquals("claude code", HostName.normalize("Claude Code"), "N3 and N4 only touch the ends and the case")
        assertEquals("pi", HostName.normalize("  Pi  "))
        assertEquals("a b c", HostName.normalize("A B C"))
    }

    @Test
    fun `probe the order of N2 and N3 means controls are stripped before the trim`() {
        // trim-then-strip would leave " pi " because BEL is not whitespace; strip-then-trim gives "pi".
        assertEquals("pi", HostName.normalize("\u0007 pi \u0007"))
        assertEquals("pi", HostName.normalize("\u0000 \tpi\t \u0000"))
    }

    @Test
    fun `probe non-ASCII whitespace is trimmed as Kotlin whitespace`() {
        assertEquals("pi", HostName.normalize("\u00A0 pi \u00A0"), "N3 is Kotlin trim, which treats NBSP as whitespace")
        assertEquals("pi", HostName.normalize("\u2003pi\u2003"), "EM SPACE")
    }

    @Test
    fun `probe U+202E is not an ISO control so N2 keeps it`() {
        assertEquals("a\u202Eb", HostName.normalize("A\u202EB"))
    }

    @Test
    fun `probe lowercasing covers non-ASCII letters too`() {
        assertEquals("\u00E9cole", HostName.normalize("\u00C9COLE"))
    }

    // ------------------------------------------------------------------ S11 N5

    @Test
    fun `S11 the 63 64 and 65 code point boundary on ASCII`() {
        val a63 = rep('a', 63)
        val a64 = rep('a', 64)
        val a65 = rep('a', 65)
        assertEquals(a63, HostName.normalize(a63))
        assertEquals(a64, HostName.normalize(a64), "exactly 64 is kept whole")
        assertEquals(a64, HostName.normalize(a65), "65 keeps the first 64")
        assertEquals(a64, HostName.normalize(rep('A', 200)), "far over the limit still gives 64 lowercase chars")
    }

    @Test
    fun `S11 a supplementary character at the boundary is kept whole and never split`() {
        val smile = "\uD83D\uDE00"
        val a63 = rep('a', 63)
        // 63 ASCII + U+1F600 is 64 code points (65 UTF-16 chars) and is kept whole.
        val whole = HostName.normalize(a63 + smile)
        assertEquals(a63 + smile, whole)
        assertEquals(64, codePoints(whole!!))
        // 63 ASCII + two smileys is 65 code points: the first 64 are 63 ASCII + one smiley, never a lone high surrogate.
        val cut = HostName.normalize(a63 + smile + smile)
        assertEquals(a63 + smile, cut)
        assertTrue(!hasLoneSurrogate(cut!!), "no lone surrogate")
        // 62 ASCII + two smileys is exactly 64 code points (66 UTF-16 chars): kept whole although length > 64.
        val sixtyTwo = rep('a', 62) + smile + smile
        assertEquals(66, sixtyTwo.length)
        assertEquals(sixtyTwo, HostName.normalize(sixtyTwo))
    }

    @Test
    fun `S11 a space at the cut is trimmed after truncation`() {
        val a63 = rep('a', 63)
        // 63 a + space + b = 65 code points; the first 64 end with the space, which N5's trimEnd removes.
        assertEquals(a63, HostName.normalize("$a63 b"))
        // two spaces at the cut
        val a62 = rep('a', 62)
        assertEquals(a62, HostName.normalize("$a62  bb"))
    }

    @Test
    fun `S11 the length bound applies after control removal and trimming not before`() {
        // 64 'a' plus a NUL is 65 raw chars, but N2 removes the NUL first so the value is exactly 64 and untouched.
        val a64 = rep('a', 64)
        assertEquals(a64, HostName.normalize(a64 + "\u0000"))
        // 60 a + ten NULs + ten b: 80 raw chars, 70 after N2, cut to 64.
        val raw = rep('a', 60) + rep('\u0000', 10) + rep('b', 10)
        assertEquals(rep('a', 60) + rep('b', 4), HostName.normalize(raw))
        // leading spaces do not count toward the bound
        assertEquals(a64, HostName.normalize(rep(' ', 30) + a64))
    }

    @Test
    fun `S11 trailing spaces are trimmed before the bound is considered`() {
        val spaces = "x" + rep(' ', 80)
        assertEquals("x", HostName.normalize(spaces))
    }

    // ------------------------------------------------------------------ probe: locale

    @Test
    fun `probe PI lowercases to pi under a Turkish default locale because N4 is Locale ROOT`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("\u0131", "I".lowercase(Locale.getDefault()), "fixture: the Turkish default really maps I to dotless i")
            assertEquals("pi", HostName.normalize("PI"))
            assertEquals("pi", HostName.userAgentProduct("PI/1.0"))
            assertEquals("pi", HostName.forRest("PI", null))
            assertEquals("pi", HostName.forRest(null, "PI/2"))
            assertEquals("pi", CallTelemetryFactory.mcp("req00001", null, "PI").host)
        } finally {
            Locale.setDefault(saved)
        }
    }

    // ------------------------------------------------------------------ differential corpus

    @Test
    fun `normalize agrees with an independent spelling of N1-N6 over a fixed corpus`() {
        val smile = "\uD83D\uDE00"
        val corpus =
            listOf(
                null,
                "",
                " ",
                "Pi",
                "PI",
                "Claude Code",
                "  mixed Case \t",
                "a\u0000b",
                "\u0007",
                "x" + rep('y', 100),
                rep('a', 63) + smile,
                rep('a', 63) + smile + smile + "tail",
                rep('a', 63) + " b",
                "\u00C9COLE",
                "A\u202EB",
                "\u00A0nbsp\u00A0",
                "a\u0085b",
                "Cursor/0.42",
                smile,
                smile + smile + smile
            )
        for (raw in corpus) {
            assertEquals(reference(raw), HostName.normalize(raw), "input: ${raw?.map { it.code }}")
        }
    }

    @Test
    fun `normalize is idempotent`() {
        val smile = "\uD83D\uDE00"
        for (raw in listOf("Claude Code", rep('A', 90), rep('a', 63) + smile + smile, "  X  ", "a\u0000b")) {
            val once = HostName.normalize(raw)
            assertEquals(once, HostName.normalize(once), "normalizing a normalized value changes nothing")
        }
    }

    // ------------------------------------------------------------------ S16 / S9 / S4 user agent product

    @Test
    fun `S16 userAgentProduct takes the first product token and drops its version`() {
        assertEquals("mozilla", HostName.userAgentProduct("Mozilla/5.0 (X11)"))
        assertEquals("curl", HostName.userAgentProduct("curl/8.4.0"))
        assertEquals("curl", HostName.userAgentProduct("curl"), "a product without a version")
        assertEquals("curl", HostName.userAgentProduct("curl/"), "an empty version")
        assertEquals("pi", HostName.userAgentProduct("Pi"))
        assertEquals(
            "python-requests",
            HostName.userAgentProduct("python-requests/2.31.0 CPython/3.12"),
            "later products are never consulted"
        )
    }

    @Test
    fun `probe a leading tab or spaces on the user agent are trimmed first`() {
        assertEquals("curl", HostName.userAgentProduct("\tcurl/8"))
        assertEquals("curl", HostName.userAgentProduct("   curl/8   "))
    }

    @Test
    fun `probe whitespace after the product name ends it even without a slash`() {
        assertEquals("my", HostName.userAgentProduct("my agent/1.0"))
        assertEquals("curl", HostName.userAgentProduct("curl\t/1"))
    }

    @Test
    fun `S9 a user agent with no product gives null`() {
        assertNull(HostName.userAgentProduct(null))
        assertNull(HostName.userAgentProduct(""))
        assertNull(HostName.userAgentProduct("   "))
        assertNull(HostName.userAgentProduct("(compatible; x)"), "starts with an opening parenthesis")
        assertNull(HostName.userAgentProduct("  (compatible; x)"), "the trim comes before the parenthesis check")
        assertNull(HostName.userAgentProduct("/1.0 foo"), "an empty token before the first slash")
        assertNull(HostName.userAgentProduct("/"))
    }

    @Test
    fun `userAgentProduct applies the same normalization as N1-N6 to the product`() {
        assertEquals("ab", HostName.userAgentProduct("a\u0007b/1"))
        assertEquals(rep('a', 64), HostName.userAgentProduct(rep('A', 100) + "/1.0"))
        assertEquals("curl", HostName.userAgentProduct("CURL/8"))
    }

    // ------------------------------------------------------------------ forRest chain (S3 S4 S5 S8 S9)

    @Test
    fun `S3 the client host header wins over the user agent`() {
        assertEquals("pi", HostName.forRest("Pi", "curl/8.4.0"))
        assertEquals("pi", HostName.forRest("pi", null))
    }

    @Test
    fun `S4 without a client host header the user agent product is used`() {
        assertEquals("curl", HostName.forRest(null, "curl/8.4.0"))
    }

    @Test
    fun `S5 neither input gives the fallback literal`() {
        assertEquals("rest", HostName.forRest(null, null))
        assertEquals(HostName.REST_FALLBACK, HostName.forRest(null, null))
    }

    @Test
    fun `S8 an empty or blank or control-only header is absent and falls through to the user agent`() {
        assertEquals("curl", HostName.forRest("   ", "curl/1"))
        assertEquals("curl", HostName.forRest("", "curl/1"))
        assertEquals("curl", HostName.forRest("\u0007", "curl/1"), "probe: a control-char-only header is absent")
        assertEquals("curl", HostName.forRest("\t\n", "curl/1"))
    }

    @Test
    fun `S9 a user agent without a product falls through to the fallback`() {
        assertEquals("rest", HostName.forRest(null, "(compatible; x)"))
        assertEquals("rest", HostName.forRest(null, "/1.0 foo"))
        assertEquals("rest", HostName.forRest(null, ""))
        assertEquals("rest", HostName.forRest(null, "   "))
        assertEquals("rest", HostName.forRest("   ", "   "))
        assertEquals("rest", HostName.forRest("", null))
    }

    @Test
    fun `probe absent versus empty versus blank are three inputs with the same outcome`() {
        val ua = "curl/8"
        assertEquals("curl", HostName.forRest(null, ua))
        assertEquals("curl", HostName.forRest("", ua))
        assertEquals("curl", HostName.forRest("   ", ua))
    }

    @Test
    fun `a long client host header is bounded to 64 code points`() {
        assertEquals(rep('a', 64), HostName.forRest(rep('A', 200), "curl/1"))
    }

    @Test
    fun `a present header never consults the user agent even when the agent is long`() {
        assertEquals("pi", HostName.forRest("pi", rep('x', 500) + "/1"))
    }

    // ------------------------------------------------------------------ factory

    @Test
    fun `mcp factory carries ids surface and the normalized client name`() {
        val t = CallTelemetryFactory.mcp("reqabc12", "session-1", "Claude Code")
        assertEquals("reqabc12", t.reqId)
        assertEquals("session-1", t.sessionId)
        assertEquals(CallLogRecord.SURFACE_MCP, t.surface)
        assertEquals("claude code", t.host)
    }

    @Test
    fun `mcp factory has no fallback literal for a missing or blank client name`() {
        assertNull(CallTelemetryFactory.mcp("reqabc12", "s", null).host)
        assertNull(CallTelemetryFactory.mcp("reqabc12", "s", "").host)
        assertNull(CallTelemetryFactory.mcp("reqabc12", "s", "   ").host)
        assertNull(CallTelemetryFactory.mcp("reqabc12", "s", "\u0000").host)
        assertNull(CallTelemetryFactory.mcp("reqabc12", null, null).host)
        assertNull(CallTelemetryFactory.mcp("reqabc12", null, null).sessionId)
    }

    @Test
    fun `mcp factory bounds a long client name`() {
        assertEquals(rep('x', 64), CallTelemetryFactory.mcp("reqabc12", "s", rep('X', 500)).host)
    }

    @Test
    fun `rest factory applies the chain and has no session`() {
        val viaHeader = CallTelemetryFactory.rest("reqabc12", "Pi", "curl/8.4.0")
        assertEquals("reqabc12", viaHeader.reqId)
        assertEquals(CallLogRecord.SURFACE_REST, viaHeader.surface)
        assertNull(viaHeader.sessionId, "REST has no MCP session")
        assertEquals("pi", viaHeader.host)
        assertEquals("curl", CallTelemetryFactory.rest("reqabc12", null, "curl/8.4.0").host)
        assertEquals("rest", CallTelemetryFactory.rest("reqabc12", null, null).host)
        assertEquals("curl", CallTelemetryFactory.rest("reqabc12", "  ", "curl/8.4.0").host)
        assertEquals("rest", CallTelemetryFactory.rest("reqabc12", null, "(compatible; x)").host)
    }

    @Test
    fun `each factory call builds a fresh telemetry because accumulators are per call`() {
        val a = CallTelemetryFactory.mcp("reqabc12", "s", "x")
        val b = CallTelemetryFactory.mcp("reqabc12", "s", "x")
        assertNotSame(a, b)
        val c = CallTelemetryFactory.rest("reqabc12", "x", null)
        val d = CallTelemetryFactory.rest("reqabc12", "x", null)
        assertNotSame(c, d)
    }
}
