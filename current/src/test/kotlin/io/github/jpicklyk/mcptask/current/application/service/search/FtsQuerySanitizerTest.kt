package io.github.jpicklyk.mcptask.current.application.service.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FtsQuerySanitizerTest {
    // ──────────────────────────────────────────────────────────────────────────
    // sanitize — happy path
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `single word is wrapped in quotes`() {
        assertEquals("\"OAuth\"", FtsQuerySanitizer.sanitize("OAuth"))
    }

    @Test
    fun `two words produce two quoted tokens joined by space`() {
        assertEquals("\"OAuth\" \"flow\"", FtsQuerySanitizer.sanitize("OAuth flow"))
    }

    @Test
    fun `extra whitespace between words is collapsed`() {
        assertEquals("\"hello\" \"world\"", FtsQuerySanitizer.sanitize("hello   world"))
    }

    @Test
    fun `leading and trailing whitespace is ignored`() {
        assertEquals("\"test\"", FtsQuerySanitizer.sanitize("  test  "))
    }

    @Test
    fun `FTS5 operator word AND is treated as literal term`() {
        val result = FtsQuerySanitizer.sanitize("hello AND world")
        assertEquals("\"hello\" \"AND\" \"world\"", result)
    }

    @Test
    fun `FTS5 operator word OR is treated as literal term`() {
        assertEquals("\"foo\" \"OR\" \"bar\"", FtsQuerySanitizer.sanitize("foo OR bar"))
    }

    @Test
    fun `FTS5 operator word NOT is treated as literal term`() {
        assertEquals("\"NOT\" \"bad\"", FtsQuerySanitizer.sanitize("NOT bad"))
    }

    @Test
    fun `FTS5 operator word NEAR is treated as literal term`() {
        assertEquals("\"NEAR\" \"match\"", FtsQuerySanitizer.sanitize("NEAR match"))
    }

    @Test
    fun `parentheses in input are kept inside the quoted token`() {
        // The parens end up inside the quoted phrase — FTS5 ignores them inside quotes.
        val result = FtsQuerySanitizer.sanitize("find (this)")
        assertEquals("\"find\" \"(this)\"", result)
    }

    @Test
    fun `hyphen inside a token does not break quoting`() {
        assertEquals("\"auth-check\"", FtsQuerySanitizer.sanitize("auth-check"))
    }

    @Test
    fun `asterisk inside a token is kept inside the quoted token`() {
        // Inside double-quotes, * is literal in FTS5 (no prefix wildcard behavior).
        assertEquals("\"test*\"", FtsQuerySanitizer.sanitize("test*"))
    }

    @Test
    fun `double-quote inside token is escaped`() {
        val result = FtsQuerySanitizer.sanitize("say \"hello\"")
        assertEquals("\"say\" \"\\\"hello\\\"\"", result)
    }

    @Test
    fun `colon inside token is kept inside the quoted token`() {
        // Column-filter syntax (e.g. "title:foo") is suppressed by wrapping in quotes.
        assertEquals("\"title:foo\"", FtsQuerySanitizer.sanitize("title:foo"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // sanitize — rejection cases
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `empty string returns null`() {
        assertNull(FtsQuerySanitizer.sanitize(""))
    }

    @Test
    fun `whitespace-only string returns null`() {
        assertNull(FtsQuerySanitizer.sanitize("   "))
    }

    @Test
    fun `tab-only string returns null`() {
        assertNull(FtsQuerySanitizer.sanitize("\t\n"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // validateForTrigram — happy path
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `token with exactly 3 chars passes trigram validation`() {
        // Should not throw
        FtsQuerySanitizer.validateForTrigram("abc")
    }

    @Test
    fun `token with more than 3 chars passes trigram validation`() {
        FtsQuerySanitizer.validateForTrigram("authentication")
    }

    @Test
    fun `mixed short and long tokens pass trigram validation`() {
        // "ab" is short but "foo" is ≥3 — validation should pass
        FtsQuerySanitizer.validateForTrigram("ab foo")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // validateForTrigram — rejection cases
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `single token with 2 chars throws for trigram`() {
        assertThrows(IllegalArgumentException::class.java) {
            FtsQuerySanitizer.validateForTrigram("ab")
        }
    }

    @Test
    fun `all tokens shorter than 3 chars throws for trigram`() {
        assertThrows(IllegalArgumentException::class.java) {
            FtsQuerySanitizer.validateForTrigram("ab cd")
        }
    }

    @Test
    fun `single character token throws for trigram`() {
        assertThrows(IllegalArgumentException::class.java) {
            FtsQuerySanitizer.validateForTrigram("a")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // sanitizeForTrigram — combined behaviour
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `sanitizeForTrigram returns sanitized string when token is long enough`() {
        val result = FtsQuerySanitizer.sanitizeForTrigram("auth flow")
        assertNotNull(result)
        assertEquals("\"auth\" \"flow\"", result)
    }

    @Test
    fun `sanitizeForTrigram returns null for empty input without throwing`() {
        assertNull(FtsQuerySanitizer.sanitizeForTrigram(""))
    }

    @Test
    fun `sanitizeForTrigram throws when all tokens are too short for trigram`() {
        assertThrows(IllegalArgumentException::class.java) {
            FtsQuerySanitizer.sanitizeForTrigram("ab")
        }
    }
}
