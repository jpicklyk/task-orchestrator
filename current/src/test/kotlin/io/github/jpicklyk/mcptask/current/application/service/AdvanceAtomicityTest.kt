package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.ProjectConfigStore
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.transitioned
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Independent P11 tests (item 919d379e) for atomicity and config faults: S9, S11, plus the concurrent-siblings probe.
 *
 * Oracles: AC1 (a store fault in a cascade apply or a lease release rolls back the PRIMARY too: item role,
 * transition rows, events and leases unchanged, and the call returns ApplyFailed / apply_failed) and task-scope 1.1
 * step 5 (a PerRootConfigUnavailableException while loading a PARENT's config skips only that cascade; the primary
 * still commits). Write faults are real SQL (a BEFORE-trigger RAISE(ABORT)); the config outage is an injected ProjectConfigStore failure, each
 * with a same-fixture control proving the scenario would otherwise succeed.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceAtomicityTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun driver(dir: Path) = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))

    private val failSecondTransitionRow =
        "CREATE TRIGGER p11_second_row BEFORE INSERT ON role_transitions " +
            "WHEN (SELECT count(*) FROM role_transitions) >= 1 BEGIN SELECT RAISE(ABORT, 'inj'); END"

    private val failLeaseRelease =
        arrayOf(
            "CREATE TRIGGER p11_l1 BEFORE DELETE ON resource_leases BEGIN SELECT RAISE(ABORT, 'inj'); END",
            "CREATE TRIGGER p11_l2 BEFORE UPDATE ON resource_leases BEGIN SELECT RAISE(ABORT, 'inj'); END",
            "CREATE TRIGGER p11_l3 BEFORE INSERT ON resource_lease_history BEGIN SELECT RAISE(ABORT, 'inj'); END",
            "CREATE TRIGGER p11_l4 BEFORE UPDATE ON resource_lease_history BEGIN SELECT RAISE(ABORT, 'inj'); END",
        )

    // ---------------------------------------------------------------------------------------------
    // S9 -- a fault anywhere in the advance rolls the whole advance back
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 a fault in the cascade's transition row rolls back the primary, the rows and the events`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.item("parent", Role.WORK)
            val child = d.item("child", Role.WORK, parent = parent)
            val mark = d.rig.maxSeq()
            rawExec(d.jdbcUrl, failSecondTransitionRow)

            val r = d.advance(child, "complete")

            assertEquals(false, r.flag("applied"), "$r")
            assertEquals("apply_failed", r.text("errorCode"), "$r")
            assertEquals(Role.WORK, d.role(child), "the PRIMARY rolls back with the failed cascade")
            assertEquals(Role.WORK, d.role(parent))
            assertEquals(0, rawCount(d.jdbcUrl, "SELECT count(*) FROM role_transitions"), "no transition row survives")
            assertEquals(emptyList(), d.rig.rowsAfter(mark), "no event row survives a rolled-back advance")
            assertEquals(null, d.reload(child).statusLabel, "no label stamped")

            // Control: the same fixture shape succeeds once the fault is removed, with both rows and both events.
            rawExec(d.jdbcUrl, "DROP TRIGGER p11_second_row")
            val parent2 = d.item("parent2", Role.WORK)
            val child2 = d.item("child2", Role.WORK, parent = parent2)
            val ok = d.advance(child2, "complete")
            assertEquals(true, ok.flag("applied"), "control: $ok")
            assertEquals(Role.TERMINAL, d.role(child2))
            assertEquals(Role.TERMINAL, d.role(parent2))
            assertEquals(2, rawCount(d.jdbcUrl, "SELECT count(*) FROM role_transitions"))
            assertEquals(
                2,
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .size
            )
        }

    @Test
    fun `S9 a fault releasing the lease on work exit fails the advance and leaves role, row, events and lease unchanged`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("leased", Role.QUEUE, type = "p11-leased")
            val started = d.advance(item, "start")
            assertEquals(true, started.flag("applied"), "fixture: the start takes the lease: $started")
            assertEquals(
                1,
                d.raw
                    .resourceLeaseRepository()
                    .findActiveForItem(item.id)
                    .size,
                "fixture: lease held"
            )
            val mark = d.rig.maxSeq()
            rawExec(d.jdbcUrl, *failLeaseRelease)

            val r = d.advance(item, "complete")

            assertEquals(false, r.flag("applied"), "a release fault now fails the advance: $r")
            assertEquals("apply_failed", r.text("errorCode"), "$r")
            assertEquals(Role.WORK, d.role(item), "role unchanged")
            assertEquals(1, d.transitions(item).size, "only the start row exists")
            assertEquals(emptyList(), d.rig.rowsAfter(mark), "no event row")
            assertEquals(
                1,
                d.raw
                    .resourceLeaseRepository()
                    .findActiveForItem(item.id)
                    .size,
                "the lease is still held"
            )

            // Control: without the fault the same complete applies and the lease is released.
            rawExec(d.jdbcUrl, "DROP TRIGGER p11_l1", "DROP TRIGGER p11_l2", "DROP TRIGGER p11_l3", "DROP TRIGGER p11_l4")
            val ok = d.advance(item, "complete")
            assertEquals(true, ok.flag("applied"), "control: $ok")
            assertEquals(
                0,
                d.raw
                    .resourceLeaseRepository()
                    .findActiveForItem(item.id)
                    .size,
                "control: released on work exit"
            )
        }

    // ---------------------------------------------------------------------------------------------
    // S11 -- a parent root's config fault skips only that cascade
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 an unreadable parent-root config skips the cascade but the primary still commits`(): Unit =
        runBlocking {
            val provider = db.repositoryProvider()
            val items = provider.workItemRepository()
            val failable = FailableConfigStore(provider.projectConfigRepository())
            val ctx = ToolExecutionContext(provider, perRootConfigService = PerRootConfigService(failable), unitOfWork = db.unitOfWork())
            val rootA = items.create(WorkItem(title = "root A", role = Role.WORK))
            val rootB = items.create(WorkItem(title = "root B", role = Role.WORK))
            assertNotNull(failable.upsert(rootA.id, "status_labels:\n  cancel: p11-a\n"), "fixture: push for root A")
            assertNotNull(failable.upsert(rootB.id, "status_labels:\n  cancel: p11-b\n"), "fixture: push for root B")

            suspend fun tree(name: String): Pair<WorkItem, WorkItem> {
                val parent = items.create(WorkItem(title = "parent $name", role = Role.WORK, rootId = rootB.id))
                val child =
                    items.create(WorkItem(title = "child $name", role = Role.WORK, parentId = parent.id, depth = 1, rootId = rootA.id))
                return parent to child
            }

            fun completeParams(id: UUID) =
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", id.toString())
                                    put("trigger", "complete")
                                },
                            )
                        },
                    )
                }

            // Control, config healthy: the same shape cascades.
            val (parent0, child0) = tree("control")
            val control = AdvanceItemTool().execute(completeParams(child0.id), ctx).resultEntry()
            assertEquals(true, control.flag("applied"), "control: $control")
            assertEquals(1, control.arr("cascadeEvents").size, "control: the cascade happens with readable config: $control")
            assertEquals(Role.TERMINAL, items.getById(parent0.id)!!.role)

            // Fault: only root B (the parent's root) is unreadable; the primary's root A stays healthy.
            val (parent, child) = tree("fault")
            failable.failOnlyForRoot = rootB.id
            failable.failReads = true

            val r = AdvanceItemTool().execute(completeParams(child.id), ctx).resultEntry()

            assertEquals(true, r.flag("applied"), "the primary commits although the parent's config is unreadable: $r")
            assertEquals(Role.TERMINAL, items.getById(child.id)!!.role)
            assertEquals(0, r.arr("cascadeEvents").size, "the failed cascade produces no cascade event: $r")
            assertEquals(Role.WORK, items.getById(parent.id)!!.role, "the parent was not cascaded")
            assertEquals(0, provider.roleTransitionRepository().findByItemId(parent.id, limit = 10).size)
            assertEquals(1, provider.roleTransitionRepository().findByItemId(child.id, limit = 10).size)
        }

    private fun JsonElement.resultEntry(): JsonObject = ((this as JsonObject)["data"] as JsonObject).arr("results")[0].jsonObject

    /** Reads for [failOnlyForRoot] throw a plain exception (what a transient store outage looks like to the config service). */
    private class FailableConfigStore(
        private val delegate: ProjectConfigStore,
    ) : ProjectConfigStore by delegate {
        @Volatile var failReads: Boolean = false

        @Volatile var failOnlyForRoot: UUID? = null

        private fun shouldFail(rootItemId: UUID) = failReads && (failOnlyForRoot == null || failOnlyForRoot == rootItemId)

        override suspend fun getFingerprint(rootItemId: UUID) =
            if (shouldFail(rootItemId)) throw IllegalStateException("injected config outage") else delegate.getFingerprint(rootItemId)

        override suspend fun get(rootItemId: UUID) =
            if (shouldFail(rootItemId)) throw IllegalStateException("injected config outage") else delegate.get(rootItemId)
    }

    // ---------------------------------------------------------------------------------------------
    // Probe -- concurrent sibling advances serialize
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `probe two siblings completed concurrently cascade the parent exactly once`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.item("parent", Role.WORK)
            val a = d.item("a", Role.WORK, parent = parent)
            val b = d.item("b", Role.WORK, parent = parent)
            val mark = d.rig.maxSeq()

            val results = listOf(a, b).map { child -> async(Dispatchers.IO) { d.advance(child, "complete") } }.awaitAll()

            results.forEach { assertEquals(true, it.flag("applied"), "both siblings complete: $it") }
            assertEquals(Role.TERMINAL, d.role(parent))
            assertEquals(1, d.transitions(parent).size, "the parent is cascaded exactly once")
            val applied = results.sumOf { r -> r.arr("cascadeEvents").count { it.jsonObject.flag("applied") == true } }
            assertEquals(1, applied, "exactly one of the two results reports the applied cascade: $results")
            assertEquals(
                3,
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .size
            )
        }
}
