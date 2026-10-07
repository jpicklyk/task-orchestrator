package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transaction-opener guard (item 9343ad8d, S10 and S16): outside `infrastructure/database/UnitRunner.kt` and
 * `infrastructure/repository/TransactionHelper.kt` no production code line may open an Exposed transaction, and no
 * file may import `...jdbc.transactions.transaction` or `...suspendTransaction`. Oracle: task-scope section 7
 * (TransactionOpenerTest). No baseline: P5a sweeps every opener site, so the expected violation count is zero.
 * The lookbehind keeps names such as `inTransaction(` and member calls such as `x.transaction(` clean.
 */
class TransactionOpenerTest {
    companion object {
        val OPENER =
            Regex(
                """(?<![A-Za-z0-9_.])(suspendTransaction|transaction|inTopLevelTransaction|inTopLevelSuspendTransaction|newSuspendedTransaction)\s*\("""
            )
        val IMPORT = Regex("""^\s*import\s+[\w.]*jdbc\.transactions\.(transaction|suspendTransaction)\s*$""")
        val ALLOWED = setOf("infrastructure/database/UnitRunner.kt", "infrastructure/repository/TransactionHelper.kt")

        fun violations(text: String): List<String> =
            text
                .lineSequence()
                .filter(GuardSupport::isCodeLine)
                .filter { GuardSupport.stripStrings(it).let { code -> OPENER.containsMatchIn(code) || IMPORT.containsMatchIn(code) } }
                .map { it.trim() }
                .toList()
    }

    @Test
    fun `no production code outside UnitRunner and TransactionHelper opens an Exposed transaction`() {
        val sources = GuardSupport.productionSources()
        assertTrue(ALLOWED.all { allowed -> sources.any { it.path == allowed } }, "both allowed files must exist: $ALLOWED")
        val found =
            sources
                .filter { it.path !in ALLOWED }
                .associate { it.path to violations(it.text) }
                .filterValues { it.isNotEmpty() }
        assertTrue(
            found.isEmpty(),
            "Exposed transactions may only be opened in $ALLOWED; offenders:\n" +
                found.entries.joinToString("\n") { (p, v) -> "  $p: ${v.joinToString(" | ")}" },
        )
    }

    @Test
    fun `the detector flags every opener form`() {
        assertEquals(1, violations("transaction(db) { work() }").size)
        assertEquals(1, violations("    return suspendTransaction(db = writer()) { }").size)
        assertEquals(1, violations("inTopLevelTransaction(level) { }").size)
        assertEquals(1, violations("inTopLevelSuspendTransaction(level) { }").size)
        assertEquals(1, violations("newSuspendedTransaction(Dispatchers.IO) { }").size)
        assertEquals(1, violations("transaction (db) { }").size)
        assertEquals(1, violations("import org.jetbrains.exposed.v1.jdbc.transactions.transaction").size)
        assertEquals(1, violations("import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction").size)
    }

    @Test
    fun `the detector leaves helper names members comments and other imports clean`() {
        assertEquals(0, violations("inTransaction(db) { }").size)
        assertEquals(0, violations("manager.transaction(db) { }").size)
        assertEquals(0, violations("// transaction(db) { }").size)
        assertEquals(0, violations(" * calls transaction(db) { }").size)
        assertEquals(0, violations("import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager").size)
        assertEquals(0, violations("import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager").size)
        assertEquals(0, violations("val transactionCount = 3").size)
        assertEquals(0, violations("fail(\"modified by another transaction (version mismatch)\")").size)
    }

    // S16 (oracle: plans/v4-phase1-core.md lines 104-105, task-scope section 8): RepositoryProvider moved to
    // application.port, so the four layering-baseline lines that mentioned it are gone, and the dependency write
    // route reaches the database only through the UnitOfWork (no Exposed import).
    @Test
    fun `S16 the layering baseline carries no RepositoryProvider lines`() {
        val lines = java.io.File("src/test/resources/architecture/layering-baseline.txt").readLines()
        val offending = lines.filter { !it.trim().startsWith("#") && "RepositoryProvider" in it }
        assertTrue(offending.isEmpty(), "RepositoryProvider violations were fixed by the move; delete: $offending")
    }

    @Test
    fun `S16 DependencyWriteRoutes imports no Exposed type`() {
        val route = GuardSupport.productionSources().single { it.path == "interfaces/api/v1/routes/DependencyWriteRoutes.kt" }
        val exposed =
            route.text
                .lineSequence()
                .filter { it.trim().startsWith("import ") && "org.jetbrains.exposed" in it }
                .toList()
        assertTrue(exposed.isEmpty(), "DependencyWriteRoutes must go through UnitOfWork, found: $exposed")
    }
}
