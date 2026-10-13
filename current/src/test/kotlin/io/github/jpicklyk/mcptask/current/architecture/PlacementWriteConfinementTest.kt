package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Placement-write confinement guard (P7, carry-in F1, plan 3.5): `parent_id`, `root_id` and `depth` define where an
 * item sits in the tree and must be written in ONE place so a reparent cannot stamp them inconsistently. Production
 * code outside `SqliteHierarchyStore.kt` that writes any of the three - an Exposed insert/update assignment
 * (`it[parentId] = ...`) or a SQL `SET parent_id = ...` / `parent_id = ?` assignment - is counted per file against the
 * two-way ratcheted baseline `placement-write-baseline.txt`. In P7 the hierarchy store has no write methods, so the
 * baseline holds today's item-store insert/update and a later item burns it down to empty.
 */
class PlacementWriteConfinementTest {
    companion object {
        const val HIERARCHY_STORE = "infrastructure/sqlite/repository/SqliteHierarchyStore.kt"
        const val BASELINE = "placement-write-baseline.txt"

        val WRITES =
            listOf(
                // Any receiver name (it, stmt, row, insert, ...) and any table qualifier; `==` is a comparison, not a write.
                Regex("""\b\w+\[(\w+\.)*(parentId|rootId|depth)]\s*=(?!=)"""),
                Regex("""(?i)\bSET\s+(parent_id|root_id|depth)\b"""),
                Regex("""(?i)^\s*,?\s*(parent_id|root_id|depth)\s*=\s*\?""")
            )

        /** Placement columns belong to work_items; a file that never references that table cannot write them. */
        fun touchesWorkItems(text: String): Boolean = text.contains("WorkItemsTable") || text.contains("work_items")

        fun violations(text: String): Int =
            if (!touchesWorkItems(text)) {
                0
            } else {
                lineViolations(text)
            }

        fun lineViolations(text: String): Int =
            text
                .lines()
                .filter { GuardSupport.isCodeLine(it) }
                .count { line -> WRITES.any { it.containsMatchIn(line) } }
    }

    @Test
    fun `placement columns are written only inside the baselined files`() {
        val actual =
            GuardSupport.countByFile(GuardSupport.productionSources().filter { it.path != HIERARCHY_STORE }) { violations(it.text) }
        val message = GuardSupport.ratchet(actual, GuardSupport.readBaseline(BASELINE), BASELINE)
        assertEquals(null, message, message)
    }

    @Test
    fun `the guard flags each write shape and ignores reads`() {
        val flagged =
            listOf(
                "            it[parentId] = item.parentId",
                "            it[WorkItemsTable.rootId] = null",
                "UPDATE work_items SET parent_id = ? WHERE id = ?",
                "      parent_id = ?,",
                "            stmt[WorkItemsTable.parentId] = id",
                "            row[WorkItemsTable.depth] = 2",
                "            it[WorkItemsTable.parentId] = id",
                "            insert[io.x.WorkItemsTable.rootId] = id"
            )
        flagged.forEach { assertTrue(lineViolations(it) == 1, "not flagged: $it") }
        listOf(
            "val p = row[WorkItemsTable.parentId]",
            "WorkItemsTable.rootId inList ids",
            "// it[parentId] = x",
            "if (row[WorkItemsTable.depth] == 1) x()"
        ).forEach {
            assertEquals(0, lineViolations(it), "wrongly flagged: $it")
        }
    }

    @Test
    fun `a file that never references work_items is not counted but one that does is`() {
        val foreign = "            it[rootId] = record.rootId"
        assertEquals(0, violations(foreign), "non-work_items table write wrongly flagged")
        assertEquals(1, violations("import x.WorkItemsTable" + System.lineSeparator() + foreign), "work_items write not counted")
        assertEquals(1, violations("UPDATE work_items SET root_id = ? WHERE id = ?"), "raw SQL write not counted")
    }
}
