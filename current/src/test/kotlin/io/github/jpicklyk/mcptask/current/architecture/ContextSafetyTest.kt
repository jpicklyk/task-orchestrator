package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Context-safety guard for the application layer (item 9343ad8d, S10; the layer is `application/` plus the `platform/`
 * folders that count as application in [LayeringRules], plus each slice's top package and its `internal/app/`): code in it must not block a thread or escape structured
 * concurrency. A code line matching [PATTERN] is a violation. Oracle: task-scope
 * section 7 (ContextSafetyTest) and carry-in 3: two-way ratcheted baseline
 * `context-safety-baseline.txt` (`<path> <count>`), seeded from the 7 `runBlocking` bridge files that P9 deletes.
 */
class ContextSafetyTest {
    companion object {
        /** The layers the guard scans: the application layer, and a slice's top package, which holds what other slices and the platform name. */
        val SCANNED_LAYERS = setOf("application", LayeringRules.SLICE_TOP)

        val PATTERN =
            Regex(
                """\brunBlocking\s*[({]|withContext\s*\(\s*Dispatchers\.|Dispatchers\.(IO|Default|Unconfined|Main)|GlobalScope\.|CoroutineScope\("""
            )

        fun violations(text: String): Int =
            text.lineSequence().filter(GuardSupport::isCodeLine).count {
                PATTERN.containsMatchIn(GuardSupport.stripStrings(it))
            }
    }

    @Test
    fun `application code matches the ratcheted context-safety baseline`() {
        val sources = GuardSupport.productionSources().filter { LayeringRules.layerOf(it.path) in SCANNED_LAYERS }
        assertTrue(sources.isNotEmpty(), "no application sources in scope: misrooted Konsist scope")
        val actual = GuardSupport.countByFile(sources) { violations(it.text) }
        val message = GuardSupport.ratchet(actual, GuardSupport.readBaseline("context-safety-baseline.txt"), "context-safety-baseline.txt")
        assertNull(message, "$message\n\nActual set (for re-seeding):\n${GuardSupport.asBaselineText(actual)}")
    }

    @Test
    fun `a slice top package and its internal app folder are scanned but its wire and sqlite folders are not`() {
        assertTrue(LayeringRules.layerOf("telemetry/A.kt") in SCANNED_LAYERS)
        assertTrue(LayeringRules.layerOf("telemetry/internal/app/A.kt") in SCANNED_LAYERS)
        assertTrue(LayeringRules.layerOf("telemetry/internal/wire/A.kt") !in SCANNED_LAYERS)
        assertTrue(LayeringRules.layerOf("telemetry/internal/sqlite/A.kt") !in SCANNED_LAYERS)
        assertTrue(
            LayeringRules.layerOf("telemetry/internal/A.kt") !in SCANNED_LAYERS,
            "a misshaped slice path is a layering violation, not a scanned file"
        )
    }

    @Test
    fun `the detector flags blocking and unstructured constructs on code lines`() {
        assertEquals(1, violations("val x = runBlocking { work() }"))
        assertEquals(1, violations("fun f() = runBlocking(Dispatchers.IO) { work() }"))
        assertEquals(1, violations("withContext(Dispatchers.Default) { work() }"))
        assertEquals(1, violations("val d = Dispatchers.Unconfined"))
        assertEquals(1, violations("GlobalScope.launch { work() }"))
        assertEquals(1, violations("val scope = CoroutineScope(Job())"))
        assertEquals(2, violations("a()\nrunBlocking {\n}\nGlobalScope.async { }"))
    }

    @Test
    fun `the detector ignores comments and structured concurrency`() {
        assertEquals(0, violations("// runBlocking { work() }"))
        assertEquals(0, violations("/* GlobalScope.launch */"))
        assertEquals(0, violations(" * Dispatchers.IO is forbidden here"))
        assertEquals(0, violations("suspend fun f() = coroutineScope { launch { work() } }"))
        assertEquals(0, violations("withContext(NonCancellable) { cleanup() }"))
    }

    @Test
    fun `the ratchet fails on a new violation and on a stale baseline line and passes on equality`() {
        assertNull(GuardSupport.ratchet(mapOf("a.kt" to 2), mapOf("a.kt" to 2), "b.txt"))
        val newer = assertNotNull(GuardSupport.ratchet(mapOf("a.kt" to 3), mapOf("a.kt" to 2), "b.txt"))
        assertTrue("a.kt" in newer && "New violation" in newer, newer)
        val unlisted = assertNotNull(GuardSupport.ratchet(mapOf("new.kt" to 1), emptyMap(), "b.txt"))
        assertTrue("new.kt" in unlisted, unlisted)
        val stale = assertNotNull(GuardSupport.ratchet(emptyMap(), mapOf("a.kt" to 1), "b.txt"))
        assertTrue("fixed" in stale && "a.kt" in stale, stale)
        val lowered = assertNotNull(GuardSupport.ratchet(mapOf("a.kt" to 1), mapOf("a.kt" to 2), "b.txt"))
        assertTrue("fixed" in lowered, lowered)
    }
}
