package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteProjectConfigRepositoryTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var projectConfigRepository: SQLiteProjectConfigRepository
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var rootItemId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            database = sqliteDb.database
            databaseManager = sqliteDb.databaseManager
            projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)
            workItemRepository = SQLiteWorkItemRepository(databaseManager)

            val root = WorkItem(title = "Project Root")
            workItemRepository.create(root)
            rootItemId = root.id
        }

    // --- get: no row ---

    @Test
    fun `get returns null when no config row exists`() =
        runBlocking {
            val result = projectConfigRepository.get(rootItemId)
            assertNull(result)
        }

    @Test
    fun `getFingerprint returns null when no config row exists`() =
        runBlocking {
            val result = projectConfigRepository.getFingerprint(rootItemId)
            assertNull(result)
        }

    // --- upsert: fresh insert ---

    @Test
    fun `upsert stores config and returns it with a computed fingerprint`() =
        runBlocking {
            val yaml = "work_item_schemas:\n  default: {}\n"
            val result = projectConfigRepository.upsert(rootItemId, yaml)
            assertNotNull(result)
            val stored = result
            assertEquals(rootItemId, stored.rootItemId)
            assertEquals(yaml, stored.configYaml)
            assertTrue(stored.fingerprint.isNotBlank())

            val fetched = projectConfigRepository.get(rootItemId)
            assertNotNull(fetched)
            assertEquals(stored.configYaml, fetched?.configYaml)
            assertEquals(stored.fingerprint, fetched?.fingerprint)
        }

    @Test
    fun `fingerprint is stable for identical content and changes when content changes`() =
        runBlocking {
            val yamlA = "work_item_schemas:\n  default: {}\n"
            val firstUpsert = projectConfigRepository.upsert(rootItemId, yamlA)
            val secondUpsertSameContent = projectConfigRepository.upsert(rootItemId, yamlA)
            assertEquals(
                firstUpsert.fingerprint,
                secondUpsertSameContent.fingerprint,
                "Fingerprint must be stable across upserts of identical content"
            )

            val yamlB = "work_item_schemas:\n  default: {}\n  other: {}\n"
            val thirdUpsertDifferentContent = projectConfigRepository.upsert(rootItemId, yamlB)
            assertTrue(
                thirdUpsertDifferentContent.fingerprint != firstUpsert.fingerprint,
                "Fingerprint must change when content changes"
            )
        }

    // --- upsert: replaces existing row (one row per root) ---

    @Test
    fun `upsert replaces the existing row for the same root rather than inserting a second row`() =
        runBlocking {
            projectConfigRepository.upsert(rootItemId, "work_item_schemas:\n  default: {}\n")
            val second = projectConfigRepository.upsert(rootItemId, "work_item_schemas:\n  other: {}\n")
            assertNotNull(second)

            val fetched = projectConfigRepository.get(rootItemId)
            assertEquals("work_item_schemas:\n  other: {}\n", fetched?.configYaml)
        }

    // --- delete ---

    @Test
    fun `delete removes the config row and returns true`() =
        runBlocking {
            projectConfigRepository.upsert(rootItemId, "work_item_schemas:\n  default: {}\n")

            val deleteResult = projectConfigRepository.delete(rootItemId)
            assertNotNull(deleteResult)
            assertTrue(deleteResult)

            val fetched = projectConfigRepository.get(rootItemId)
            assertNull(fetched)
        }

    @Test
    fun `delete returns false when no config row exists`() =
        runBlocking {
            val deleteResult = projectConfigRepository.delete(rootItemId)
            assertNotNull(deleteResult)
            assertTrue(!deleteResult)
        }

    // --- FK cascade: deleting the root work item removes its config row ---

    @Test
    fun `deleting the root work item cascades to delete its project_config row`() =
        runBlocking {
            projectConfigRepository.upsert(rootItemId, "work_item_schemas:\n  default: {}\n")

            workItemRepository.delete(rootItemId)

            val fetched = projectConfigRepository.get(rootItemId)
            assertNull(fetched, "Expected CASCADE delete to remove the project_config row")
        }

    // --- computeFingerprint ---

    @Test
    fun `computeFingerprint is stable for identical content and matches the fingerprint stored by upsert`() =
        runBlocking {
            val yaml = "work_item_schemas:\n  default: {}\n"
            val computed = projectConfigRepository.computeFingerprint(yaml)
            assertEquals(computed, projectConfigRepository.computeFingerprint(yaml))

            val stored = projectConfigRepository.upsert(rootItemId, yaml)
            assertEquals(computed, stored.fingerprint)
        }

    // --- fingerprint history: append on change, no-op on identical re-push, prune at 20 ---

    @Test
    fun `upsert appends the outgoing fingerprint to history, newest first, when the fingerprint changes`() =
        runBlocking {
            val yamlA = "work_item_schemas:\n  a: {}\n"
            val yamlB = "work_item_schemas:\n  b: {}\n"
            val yamlC = "work_item_schemas:\n  c: {}\n"

            val fingerprintA = projectConfigRepository.computeFingerprint(yamlA)
            val fingerprintB = projectConfigRepository.computeFingerprint(yamlB)

            projectConfigRepository.upsert(rootItemId, yamlA)
            projectConfigRepository.upsert(rootItemId, yamlB)
            projectConfigRepository.upsert(rootItemId, yamlC)

            // A is superseded (in history, not current); B is superseded too (was current, then
            // replaced by C); newest-first means B (the most recent outgoing) precedes A.
            assertEquals(
                FingerprintRelation.SUPERSEDED,
                projectConfigRepository.classifyFingerprint(rootItemId, fingerprintB)
            )
            assertEquals(
                FingerprintRelation.SUPERSEDED,
                projectConfigRepository.classifyFingerprint(rootItemId, fingerprintA)
            )
        }

    @Test
    fun `re-pushing identical content does not consume fingerprint history budget`() =
        runBlocking {
            val v1 = "work_item_schemas:\n  v1: {}\n"
            val fingerprintV1 = projectConfigRepository.computeFingerprint(v1)

            projectConfigRepository.upsert(rootItemId, v1)
            // Nine redundant re-pushes of identical content — if these incorrectly appended to
            // history, they would consume prune budget and evict v1 from history early below.
            repeat(9) { projectConfigRepository.upsert(rootItemId, v1) }

            // 19 further distinct pushes create exactly 19 real fingerprint transitions
            // (v1->v2, v2->v3, ..., v19->v20) — within the 20-entry cap, so v1 must still be
            // present in history. If the redundant re-pushes above had wrongly added entries,
            // this history would already be full and v1 would have been pruned before this point.
            for (i in 2..20) {
                projectConfigRepository.upsert(rootItemId, "work_item_schemas:\n  v$i: {}\n")
            }

            val result = projectConfigRepository.classifyFingerprint(rootItemId, fingerprintV1)
            assertEquals(
                FingerprintRelation.SUPERSEDED,
                result,
                "v1 should still be in history — redundant re-pushes must not consume prune budget"
            )
        }

    @Test
    fun `fingerprint history is pruned to 20 entries`() =
        runBlocking {
            val fingerprints = mutableListOf<String>()
            // 22 pushes v1..v22 (current = v22): each push v_k (k>=2) prepends the OUTGOING
            // fingerprint fp(v_{k-1}) to history. After all 22 pushes, history holds the 20 most
            // recent outgoing fingerprints — fp(v21) down to fp(v2) — and fp(v1) (the oldest
            // outgoing entry, from the v1->v2 transition) has been pruned out.
            for (i in 1..22) {
                val yaml = "work_item_schemas:\n  v$i: {}\n"
                fingerprints.add(projectConfigRepository.computeFingerprint(yaml))
                projectConfigRepository.upsert(rootItemId, yaml)
            }

            assertEquals(
                FingerprintRelation.UNKNOWN,
                projectConfigRepository.classifyFingerprint(rootItemId, fingerprints[0]),
                "v1's fingerprint should have been pruned from history (the 21st-oldest entry)"
            )
            // The most recent 20 outgoing fingerprints (v2..v21, indices 1..20) must remain — check
            // the retained boundary (v2, index 1) and the most-recently-superseded (v21, index 20).
            assertEquals(
                FingerprintRelation.SUPERSEDED,
                projectConfigRepository.classifyFingerprint(rootItemId, fingerprints[1]),
                "v2's fingerprint should still be in history (the oldest retained entry)"
            )
            assertEquals(
                FingerprintRelation.SUPERSEDED,
                projectConfigRepository.classifyFingerprint(rootItemId, fingerprints[20]),
                "v21's fingerprint (the most recently superseded) should still be in history"
            )
        }

    // --- classifyFingerprint ---

    @Test
    fun `classifyFingerprint returns CURRENT for the stored current fingerprint`() =
        runBlocking {
            val yaml = "work_item_schemas:\n  default: {}\n"
            val stored = projectConfigRepository.upsert(rootItemId, yaml)

            val result = projectConfigRepository.classifyFingerprint(rootItemId, stored.fingerprint)
            assertEquals(FingerprintRelation.CURRENT, result)
        }

    @Test
    fun `classifyFingerprint returns UNKNOWN for a fingerprint with no stored row at all`() =
        runBlocking {
            val result = projectConfigRepository.classifyFingerprint(rootItemId, "0".repeat(64))
            assertEquals(FingerprintRelation.UNKNOWN, result)
        }

    @Test
    fun `classifyFingerprint returns UNKNOWN for a NULL-history row and any non-current fingerprint`() =
        runBlocking {
            // A single upsert leaves fingerprint_history NULL (no prior row to derive history from)
            // — this is also what a pre-V11 row looks like. Any non-current fingerprint must
            // classify as UNKNOWN, not SUPERSEDED, since there is no known ancestor to compare against.
            projectConfigRepository.upsert(rootItemId, "work_item_schemas:\n  default: {}\n")

            val result = projectConfigRepository.classifyFingerprint(rootItemId, "1".repeat(64))
            assertEquals(FingerprintRelation.UNKNOWN, result)
        }

    @Test
    fun `classifyFingerprint returns UNKNOWN for a divergent fingerprint never seen by this root`() =
        runBlocking {
            val yamlA = "work_item_schemas:\n  a: {}\n"
            val yamlB = "work_item_schemas:\n  b: {}\n"
            projectConfigRepository.upsert(rootItemId, yamlA)
            projectConfigRepository.upsert(rootItemId, yamlB)

            val result = projectConfigRepository.classifyFingerprint(rootItemId, "2".repeat(64))
            assertEquals(FingerprintRelation.UNKNOWN, result)
        }
}
