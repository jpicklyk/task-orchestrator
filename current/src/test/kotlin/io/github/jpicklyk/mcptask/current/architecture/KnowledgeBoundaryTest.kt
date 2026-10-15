package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Knowledge-boundary guard (item 23faed48, seat test-author; task-scope D14, AC6, test-plan S19): the knowledge kernel
 * must not depend on the orchestration lifecycle.
 *
 * Scope: production files under `application/knowledge/` and `infrastructure/sqlite/knowledge/`. Rule: such a file must not
 * import or fully-qualify
 *  - anything under `io.github.jpicklyk.mcptask.current.domain.lifecycle`, nor
 *  - any `io.github.jpicklyk.mcptask.current.*` type whose simple name starts with `Claim`, `Seat`, `Transition`,
 *    `RoleTransition`, `Advance`, `Run` (the run lifecycle, R17) or `DecisionRequest` (the decision request entity, R24).
 * `Role` (a plain value), `ActorClaim` and `ProofClaims` are allowed (their simple names do not start with a forbidden
 * prefix). Comments and string contents are ignored.
 *
 * Ratchet: violations are compared with the checked-in baseline `architecture/knowledge-boundary-baseline.txt`
 * (one `<path> -> <referenced FQN>` per line, intentionally EMPTY), both ways like LayeringTest: a violation not in the
 * baseline fails, and so does a baseline line that no longer matches a real violation.
 *
 * Vacuity: the matcher is proven on shapes it must flag and shapes it must ignore, the scope function on included and
 * excluded paths, the ratchet function on both failure directions, the scan scope on the files the plan names, and a control
 * proves the same matcher flags real lifecycle references in `application/service/AdvanceService.kt` (a blind matcher
 * would otherwise pass the scan vacuously).
 *
 * Known limit (accepted, not hidden): a same-package or wildcard-imported simple name with no fully-qualified token is not
 * seen; the rule is import and FQN based like LayeringTest.
 *
 * Oracle: task-scope D14 and AC6.
 */
class KnowledgeBoundaryTest {
    companion object {
        private const val ROOT = "io.github.jpicklyk.mcptask.current"
        private const val LIFECYCLE = "$ROOT.domain.lifecycle"
        private val FORBIDDEN_NAME_PREFIXES = listOf("Claim", "Seat", "Transition", "RoleTransition", "Advance", "Run", "DecisionRequest")
        private val FQN = Regex("""io\.github\.jpicklyk\.mcptask\.current(?:\.[A-Za-z0-9_]+)+""")
        private val SCOPE_DIRS = listOf("application/knowledge/", "infrastructure/sqlite/knowledge/")
        private const val BASELINE = "src/test/resources/architecture/knowledge-boundary-baseline.txt"

        fun inScope(path: String): Boolean = SCOPE_DIRS.any { path.startsWith(it) }

        private fun codeOf(text: String): String =
            text
                .lines()
                .filter { GuardSupport.isCodeLine(it) }
                .joinToString("\n") { GuardSupport.stripStrings(it).substringBefore("//") }

        fun forbidden(fqn: String): Boolean {
            if (fqn == LIFECYCLE || fqn.startsWith("$LIFECYCLE.")) return true
            return fqn.removePrefix("$ROOT.").split('.').any { segment -> FORBIDDEN_NAME_PREFIXES.any { segment.startsWith(it) } }
        }

        /** Forbidden `current.*` references in [text]: comment lines dropped and string contents blanked before matching. */
        fun violations(text: String): Set<String> =
            FQN
                .findAll(codeOf(text))
                .map { it.value }
                .filter { forbidden(it) }
                .toSet()

        fun readBaseline(): Set<String> =
            File(BASELINE)
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()

        /** Two-way ratchet: null when [actual] equals [baseline], otherwise a message naming both directions. */
        fun ratchet(
            actual: Set<String>,
            baseline: Set<String>
        ): String? {
            val unbaselined = actual - baseline
            val stale = baseline - actual
            if (unbaselined.isEmpty() && stale.isEmpty()) return null
            val parts = mutableListOf<String>()
            if (unbaselined.isNotEmpty()) {
                parts += "New knowledge-boundary violation(s) not in baseline:\n" + unbaselined.sorted().joinToString("\n") { "  - $it" }
            }
            if (stale.isNotEmpty()) {
                parts += "Baseline entry(ies) no longer match a real violation - fixed, delete this line:\n" +
                    stale.sorted().joinToString("\n") { "  - $it" }
            }
            return parts.joinToString("\n\n")
        }
    }

    private fun knowledgeSources() = GuardSupport.productionSources().filter { inScope(it.path) }

    @Test
    fun `knowledge kernel production source has no lifecycle dependency beyond the empty baseline`() {
        val scope = knowledgeSources()
        assertTrue(scope.size >= 8, "fixture: the knowledge kernel scope is found, saw ${scope.size} files")
        val actual =
            scope
                .flatMap { source -> violations(source.text).map { "${source.path} -> $it" } }
                .toSet()

        val message = ratchet(actual, readBaseline())

        assertNull(message, message)
    }

    @Test
    fun `the checked-in baseline is empty`() {
        assertEquals(emptySet(), readBaseline(), "AC6: the knowledge-boundary baseline starts and stays empty")
    }

    @Test
    fun `the scan scope includes the document search link service and link store files`() {
        val paths = knowledgeSources().map { it.path }
        listOf("DocumentService.kt", "SearchService.kt", "SqliteLinkStore.kt", "LinkService.kt", "LinkParser.kt").forEach { name ->
            assertTrue(paths.any { it.substringAfterLast('/') == name }, "scope must include $name, saw $paths")
        }
        assertTrue(paths.any { it.startsWith("infrastructure/sqlite/knowledge/") }, "the SQLite kernel directory is scanned")
        assertTrue(paths.any { it.startsWith("application/knowledge/") }, "the application kernel directory is scanned")
    }

    @Test
    fun `the scope function includes the two kernel directories and excludes everything else`() {
        listOf(
            "application/knowledge/link/LinkService.kt",
            "application/knowledge/document/DocumentService.kt",
            "application/knowledge/search/SearchService.kt",
            "infrastructure/sqlite/knowledge/document/SqliteLinkStore.kt",
            "infrastructure/sqlite/knowledge/search/SqliteSearchEngine.kt"
        ).forEach { assertTrue(inScope(it), "must be scanned: $it") }
        listOf(
            "application/service/AdvanceService.kt",
            "application/service/NoteCommandService.kt",
            "application/port/LinkStore.kt",
            "application/knowledgebase/Other.kt",
            "infrastructure/sqlite/repository/SqliteItemStore.kt",
            "infrastructure/knowledge/Other.kt",
            "domain/lifecycle/TransitionTable.kt",
            "CurrentMain.kt"
        ).forEach { assertFalse(inScope(it), "must be excluded: $it") }
    }

    @Test
    fun `the matcher flags each forbidden reference shape`() {
        listOf(
            "import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionTable",
            "import io.github.jpicklyk.mcptask.current.domain.lifecycle.*",
            "import io.github.jpicklyk.mcptask.current.domain.lifecycle.SomethingElseEntirely",
            "import io.github.jpicklyk.mcptask.current.application.service.ClaimService",
            "import io.github.jpicklyk.mcptask.current.domain.model.SeatOwnership",
            "import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition",
            "import io.github.jpicklyk.mcptask.current.application.service.AdvanceService",
            "import io.github.jpicklyk.mcptask.current.application.run.RunService",
            "import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest",
            "import io.github.jpicklyk.mcptask.current.application.port.DecisionRequestStore",
            "import io.github.jpicklyk.mcptask.current.application.service.AdvanceService as Adv",
            "val s = io.github.jpicklyk.mcptask.current.application.service.ClaimService(x)",
            "val t = io.github.jpicklyk.mcptask.current.domain.lifecycle.RoleTable.lookup(r)",
            "fun f(t: io.github.jpicklyk.mcptask.current.domain.model.TransitionRow) = t"
        ).forEach { assertEquals(1, violations(it).size, "not flagged: $it") }
    }

    @Test
    fun `the matcher ignores allowed types comments strings and kdoc`() {
        listOf(
            "import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim",
            "import io.github.jpicklyk.mcptask.current.domain.model.ProofClaims",
            "import io.github.jpicklyk.mcptask.current.domain.model.Role",
            "import io.github.jpicklyk.mcptask.current.domain.model.Link",
            "import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider",
            "import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService",
            "import io.github.jpicklyk.mcptask.current.domain.lifecycleish.NotLifecycle",
            "// import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionTable",
            "     * see [io.github.jpicklyk.mcptask.current.application.service.ClaimService]",
            "/* io.github.jpicklyk.mcptask.current.domain.lifecycle.X */",
            "val msg = \"io.github.jpicklyk.mcptask.current.application.service.AdvanceService\"",
            "val x = 1 // io.github.jpicklyk.mcptask.current.domain.lifecycle.X trailing comment",
            "import kotlinx.coroutines.flow.Flow",
            "import java.util.UUID"
        ).forEach { assertEquals(emptySet(), violations(it), "wrongly flagged: $it") }
    }

    @Test
    fun `the matcher reports the offending token so a baseline line names it`() {
        assertEquals(
            setOf("io.github.jpicklyk.mcptask.current.application.service.ClaimService"),
            violations("import io.github.jpicklyk.mcptask.current.application.service.ClaimService\nimport java.util.UUID")
        )
    }

    @Test
    fun `control - the matcher flags real lifecycle references in AdvanceService`() {
        val advance = GuardSupport.productionSources().singleOrNull { it.path == "application/service/AdvanceService.kt" }
        assertNotNull(advance, "fixture: application/service/AdvanceService.kt exists")
        assertTrue(
            violations(advance.text).isNotEmpty(),
            "control: AdvanceService is the lifecycle owner, so the same matcher must see lifecycle references there " +
                "(a blind matcher would pass the kernel scan vacuously)"
        )
    }

    @Test
    fun `the ratchet fails on a new violation and on a stale baseline line and passes when they match`() {
        assertNull(ratchet(emptySet(), emptySet()))
        assertNull(ratchet(setOf("a.kt -> x"), setOf("a.kt -> x")))

        val unbaselined = ratchet(setOf("a.kt -> x"), emptySet())
        assertNotNull(unbaselined)
        assertTrue("a.kt -> x" in unbaselined && "not in baseline" in unbaselined, unbaselined)

        val stale = ratchet(emptySet(), setOf("b.kt -> y"))
        assertNotNull(stale)
        assertTrue("b.kt -> y" in stale && "delete this line" in stale, stale)

        val both = ratchet(setOf("a.kt -> x"), setOf("b.kt -> y"))
        assertNotNull(both)
        assertTrue("a.kt -> x" in both && "b.kt -> y" in both, both)
    }
}
