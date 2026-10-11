package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deleted-class guard (item 947f0230, task-scope AC6, test-plan S15; the store-write half of this file moved to
 * [WriteConfinementTest] in item 0f3eec2d): no production source mentions the seven removed classes, none of their files
 * exists, and no two-way ratchet baseline still names one.
 *
 * Vacuity: [deletedReferences] is proven on the shapes it must flag and the shapes it must ignore.
 *
 * Oracle: task-scope section 4 "Guard" and AC6; plan 3.7 (each service records its own rows).
 */
class DeletedClassGuardTest {
    companion object {
        private fun codeOf(text: String): String =
            text
                .lines()
                .filter { GuardSupport.isCodeLine(it) }
                .joinToString("\n") { GuardSupport.stripStrings(it).substringBefore("//") }

        val DELETED_CLASSES =
            listOf(
                "EventPublishingRepositoryProvider",
                "DeferredEventPublisher",
                "WorkTreeExecutor",
                "SQLiteWorkTreeService",
                "WorkItemPlacementService",
                "ItemHierarchyValidator",
                "WorkItemDeletion"
            )

        /** Code references to a deleted class name in [text] (comments and string contents are not references). */
        fun deletedReferences(text: String): Int {
            val code = codeOf(text)
            return DELETED_CLASSES.sumOf { name -> Regex("""\b$name\b""").findAll(code).count() }
        }
    }

    @Test
    fun `no production source references a deleted class and none of their files exists`() {
        val sources = GuardSupport.productionSources()
        val actual = GuardSupport.countByFile(sources) { deletedReferences(it.text) }
        assertEquals(emptyMap(), actual, "production references to deleted classes (file to count): $actual")
        val deletedFiles = DELETED_CLASSES.map { "$it.kt" }.toSet()
        val surviving = sources.filter { it.path.substringAfterLast('/') in deletedFiles }.map { it.path }
        assertEquals(emptyList(), surviving, "files of deleted classes still exist")
    }

    @Test
    fun `the deleted class reference matcher flags code and ignores comments and strings`() {
        listOf(
            "import io.x.EventPublishingRepositoryProvider",
            "val p = DeferredEventPublisher(bus)",
            "class Foo(private val executor: WorkTreeExecutor)",
            "x as SQLiteWorkTreeService",
            "WorkItemPlacementService(repo).place(a)",
            "ItemHierarchyValidator.recompute(x)",
            "WorkItemDeletion(ctx).delete(ids)"
        ).forEach { assertEquals(1, deletedReferences(it), "not flagged: $it") }
        listOf(
            "// the old EventPublishingRepositoryProvider decorated every store",
            "     * DeferredEventPublisher is gone",
            "val s = \"WorkTreeExecutor\"",
            "val x = MyWorkTreeExecutorAdapter()",
            "val y = WorkItemDeletionResult()"
        ).forEach { assertEquals(0, deletedReferences(it), "wrongly flagged: $it") }
    }

    @Test
    fun `the placement write baseline is unchanged and no ratchet baseline still names a deleted file`() {
        assertEquals(
            mapOf("infrastructure/sqlite/repository/SqliteItemStore.kt" to 6),
            GuardSupport.readBaseline("placement-write-baseline.txt"),
            "AC6: placement-write-baseline.txt stays exactly the item store's six insert/update writes"
        )
        val deletedFiles = DELETED_CLASSES.map { "$it.kt" }
        val dir = File("src/test/resources/architecture")
        val baselines = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty().toList()
        assertTrue(baselines.size >= 5, "fixture: the architecture baselines are found, saw ${baselines.map { it.name }}")
        val stale =
            baselines.flatMap { f ->
                f
                    .readLines()
                    .filter { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
                    .filter { line -> deletedFiles.any { line.contains(it) } }
                    .map { "${f.name}: $it" }
            }
        assertEquals(emptyList(), stale, "baseline lines naming deleted files")
    }
}
