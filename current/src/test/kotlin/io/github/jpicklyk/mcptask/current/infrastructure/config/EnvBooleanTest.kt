package io.github.jpicklyk.mcptask.current.infrastructure.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [EnvBoolean] -- the single shared boolean parser for every env-var boolean read
 * in the codebase (item 64f7b265-6bca-4a23-8809-449b6e94dae8).
 *
 * Oracle provenance: every expected value below traces to the `test-plan` note frozen at queue
 * phase, before this file's implementation existed -- algorithm: trim + lowercase; `true/1/yes`
 * -> true; `false/0/no` -> false; anything else is unrecognized (falls back to the caller's
 * default, with a WARN from [EnvBoolean.parse]). Nothing here was read off [EnvBoolean]'s
 * implementation to decide correctness.
 *
 * Covers test-plan scenarios S2 (happy: full true/false vocabulary), S3 (edge: trimmed before
 * parsing), S5 (failure: unrecognized value on a real var name falls back to default + exactly one
 * WARN naming var/value/default), S8 (edge: empty string is unrecognized, distinct from absent),
 * plus the adversarial probe catalog (test-author skill §6). S1, S4, S6, S7, S9-S11 (the
 * multi-call-site matrix) live in EnvBooleanSweepTest.kt per the test-plan's file split.
 */
class EnvBooleanTest {
    /**
     * Captures WARN-level log records emitted by [EnvBoolean] during [block]. Mirrors the Logback
     * ListAppender convention already used in DidDocumentJwksExtractorTest.
     */
    private fun captureWarnLogs(block: () -> Unit): List<String> {
        val logbackLogger = LoggerFactory.getLogger(EnvBoolean::class.java.name) as Logger
        val listAppender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                logbackLogger.addAppender(it)
            }
        val savedLevel = logbackLogger.level
        logbackLogger.level = Level.WARN
        try {
            block()
            return listAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logbackLogger.detachAppender(listAppender)
            logbackLogger.level = savedLevel
        }
    }

    // ------------------------------------------------------------------
    // S2 -- happy: full true/false vocabulary, case-insensitive
    // ------------------------------------------------------------------

    @Test
    fun `S2 recognized true spellings parse to true`() {
        assertEquals(true, EnvBoolean.parseOrNull("true"))
        assertEquals(true, EnvBoolean.parseOrNull("TRUE"))
        assertEquals(true, EnvBoolean.parseOrNull("1"))
        assertEquals(true, EnvBoolean.parseOrNull("yes"))
        assertEquals(true, EnvBoolean.parseOrNull("Yes"))
        assertEquals(true, EnvBoolean.parseOrNull("YES"))
    }

    @Test
    fun `S2 recognized false spellings parse to false`() {
        assertEquals(false, EnvBoolean.parseOrNull("false"))
        assertEquals(false, EnvBoolean.parseOrNull("FALSE"))
        assertEquals(false, EnvBoolean.parseOrNull("0"))
        assertEquals(false, EnvBoolean.parseOrNull("no"))
        assertEquals(false, EnvBoolean.parseOrNull("No"))
        assertEquals(false, EnvBoolean.parseOrNull("NO"))
    }

    // ------------------------------------------------------------------
    // S3 -- edge: trimmed before parsing
    // ------------------------------------------------------------------

    @Test
    fun `S3 leading and trailing plain whitespace is trimmed before parsing`() {
        assertEquals(true, EnvBoolean.parseOrNull(" true "))
        assertEquals(false, EnvBoolean.parseOrNull(" 0 "))
    }

    // ------------------------------------------------------------------
    // S5 -- failure: unrecognized value falls back to default + exactly one WARN
    // ------------------------------------------------------------------

    @Test
    fun `S5 unrecognized USE_FLYWAY value falls back to default true and warns once naming var, value, default`() {
        var result: Boolean? = null
        val warnings = captureWarnLogs { result = EnvBoolean.parse("USE_FLYWAY", "maybe", true) }

        // What wrong behavior this catches: silently defaulting to false, throwing instead of
        // falling back, or defaulting without any operator-visible diagnostic.
        assertEquals(true, result)
        assertEquals(1, warnings.size, "exactly one WARN must be logged for one unrecognized read")
        val message = warnings.single()
        assertTrue(message.contains("USE_FLYWAY"), "WARN must name the variable: $message")
        assertTrue(message.contains("maybe"), "WARN must name the raw value: $message")
        assertTrue(message.contains("true"), "WARN must name the default: $message")
    }

    // ------------------------------------------------------------------
    // S8 -- edge: empty string (present, not absent) is unrecognized
    // ------------------------------------------------------------------

    @Test
    fun `S8 empty string is unrecognized and falls back to default in both directions, with WARN`() {
        assertNull(EnvBoolean.parseOrNull(""), "empty is not a recognized boolean spelling")

        val warningsTrueDefault = captureWarnLogs { assertEquals(true, EnvBoolean.parse("VAR_A", "", default = true)) }
        assertEquals(1, warningsTrueDefault.size)

        val warningsFalseDefault =
            captureWarnLogs { assertEquals(false, EnvBoolean.parse("VAR_B", "", default = false)) }
        assertEquals(1, warningsFalseDefault.size)
    }

    // ------------------------------------------------------------------
    // Adversarial probes (test-author skill §6) -- every probe recorded, including clean
    // (no-finding) results. See test-manifest for the full probe ledger.
    // ------------------------------------------------------------------

    @Test
    fun `probe - padded uppercase TRUE parses to true`() {
        assertEquals(true, EnvBoolean.parseOrNull(" TRUE "))
    }

    @Test
    fun `probe - mixed case TrUe parses to true`() {
        assertEquals(true, EnvBoolean.parseOrNull("TrUe"))
    }

    @Test
    fun `probe - trailing space false parses to false`() {
        assertEquals(false, EnvBoolean.parseOrNull("false "))
    }

    @Test
    fun `probe - alternate spelling on is not recognized`() {
        assertNull(EnvBoolean.parseOrNull("on"))
    }

    @Test
    fun `probe - numeric 2 is not recognized`() {
        assertNull(EnvBoolean.parseOrNull("2"))
    }

    @Test
    fun `probe - single-letter abbreviation N is not recognized`() {
        // Only the full "no" spelling is in the vocabulary; "N" is not an accepted abbreviation.
        assertNull(EnvBoolean.parseOrNull("N"))
    }

    @Test
    fun `probe - single-letter abbreviation y is not recognized`() {
        // Only the full "yes" spelling is in the vocabulary; "y" is not an accepted abbreviation.
        assertNull(EnvBoolean.parseOrNull("y"))
    }

    @Test
    fun `probe - suffix 01 is not recognized as the exact literal 1`() {
        assertNull(EnvBoolean.parseOrNull("01"))
    }

    @Test
    fun `probe - the literal string null is not recognized as a boolean`() {
        assertNull(EnvBoolean.parseOrNull("null"))
    }

    @Test
    fun `probe - absent (raw is null) silently returns the default, distinct from an unrecognized value`() {
        assertNull(EnvBoolean.parseOrNull(null))

        val warnings = captureWarnLogs { assertEquals(true, EnvBoolean.parse("VAR_ABSENT", null, default = true)) }
        assertEquals(0, warnings.size, "an unset variable must never warn -- only an unrecognized non-null value does")
    }

    @Test
    fun `probe - NBSP-padded true is trimmed and parses to true (Kotlin trim strips NBSP, unlike java String trim)`() {
        // Oracle, independently derived (not read from EnvBoolean's implementation): Kotlin's
        // CharSequence.trim() (kotlin-stdlib commonMain text/Strings.kt) delegates to
        // Char.isWhitespace(), whose JVM actual (kotlin-stdlib jvmMain text/CharJVM.kt) is
        // `Character.isWhitespace(this) || Character.isSpaceChar(this)`. U+00A0 (NBSP) is Unicode
        // category Zs, so java.lang.Character.isSpaceChar(0x00A0) is true even though
        // java.lang.Character.isWhitespace(0x00A0) alone is false -- so Kotlin's trim() DOES strip
        // a leading/trailing NBSP, unlike plain java.lang.String.trim(). Verified directly against
        // the kotlin-stdlib 2.3.20 sources jar. This probe finds no bug: the NBSP-padded value is
        // trimmed to "true" and parses to true, same as plain-space padding (S3).
        val nbsp = ' '
        assertEquals(true, EnvBoolean.parseOrNull("$nbsp true$nbsp"))
    }
}
