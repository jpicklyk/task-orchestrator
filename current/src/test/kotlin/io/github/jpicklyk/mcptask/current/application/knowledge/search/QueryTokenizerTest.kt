package io.github.jpicklyk.mcptask.current.application.knowledge.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tokenizer cases carried over from the retired `FtsQuerySanitizerTest` (the FTS5 phrase quoting and escaping cases
 * moved to the engine contract test, because quoting now lives only in the SQLite adapter).
 *
 * Oracle: the retired `FtsQuerySanitizer` KDoc table (base 5bc03359): the query is split on whitespace, every
 * special character and operator word stays inside its token as a literal, a blank query yields no tokens, and the
 * trigram rule fails only when EVERY token is shorter than 3 characters.
 */
class QueryTokenizerTest {
    // -- tokenize: happy path ------------------------------------------------------------------

    @Test
    fun `single word is one term`() {
        assertEquals(listOf("OAuth"), QueryTokenizer.tokenize("OAuth"))
    }

    @Test
    fun `two words are two terms in order`() {
        assertEquals(listOf("OAuth", "flow"), QueryTokenizer.tokenize("OAuth flow"))
    }

    @Test
    fun `extra whitespace between words is collapsed`() {
        assertEquals(listOf("hello", "world"), QueryTokenizer.tokenize("hello   world"))
    }

    @Test
    fun `leading and trailing whitespace is ignored`() {
        assertEquals(listOf("test"), QueryTokenizer.tokenize("  test  "))
    }

    @Test
    fun `operator words AND OR NOT NEAR stay literal terms`() {
        assertEquals(listOf("hello", "AND", "world"), QueryTokenizer.tokenize("hello AND world"))
        assertEquals(listOf("foo", "OR", "bar"), QueryTokenizer.tokenize("foo OR bar"))
        assertEquals(listOf("NOT", "bad"), QueryTokenizer.tokenize("NOT bad"))
        assertEquals(listOf("NEAR", "match"), QueryTokenizer.tokenize("NEAR match"))
    }

    @Test
    fun `parentheses stay inside their term`() {
        assertEquals(listOf("find", "(this)"), QueryTokenizer.tokenize("find (this)"))
    }

    @Test
    fun `hyphen inside a term does not split it`() {
        assertEquals(listOf("auth-check"), QueryTokenizer.tokenize("auth-check"))
    }

    @Test
    fun `asterisk stays inside its term`() {
        assertEquals(listOf("test*"), QueryTokenizer.tokenize("test*"))
    }

    @Test
    fun `embedded double quotes stay inside their term`() {
        assertEquals(listOf("say", "\"hello\""), QueryTokenizer.tokenize("say \"hello\""))
    }

    @Test
    fun `colon stays inside its term`() {
        assertEquals(listOf("title:foo"), QueryTokenizer.tokenize("title:foo"))
    }

    // -- tokenize: empty rule --------------------------------------------------------------------

    @Test
    fun `empty string yields no terms`() {
        assertEquals(emptyList<String>(), QueryTokenizer.tokenize(""))
    }

    @Test
    fun `whitespace-only string yields no terms`() {
        assertEquals(emptyList<String>(), QueryTokenizer.tokenize("   "))
    }

    @Test
    fun `tab and newline only string yields no terms`() {
        assertEquals(emptyList<String>(), QueryTokenizer.tokenize("\t\n"))
    }

    // -- substringViolation: the >=3-char trigram rule ---------------------------------------------

    @Test
    fun `term with exactly 3 chars passes the substring rule`() {
        assertNull(QueryTokenizer.substringViolation(listOf("abc")))
    }

    @Test
    fun `term with more than 3 chars passes the substring rule`() {
        assertNull(QueryTokenizer.substringViolation(listOf("authentication")))
    }

    @Test
    fun `mixed short and long terms pass the substring rule`() {
        assertNull(QueryTokenizer.substringViolation(listOf("ab", "foo")))
    }

    @Test
    fun `single 2-char term violates the substring rule`() {
        assertNotNull(QueryTokenizer.substringViolation(listOf("ab")))
    }

    @Test
    fun `all terms shorter than 3 chars violate the substring rule`() {
        assertNotNull(QueryTokenizer.substringViolation(listOf("ab", "cd")))
    }

    @Test
    fun `single character term violates the substring rule`() {
        assertNotNull(QueryTokenizer.substringViolation(listOf("a")))
    }

    @Test
    fun `the minimum term length constant is 3`() {
        assertEquals(3, QueryTokenizer.SUBSTRING_MIN_TERM_LENGTH)
    }
}
