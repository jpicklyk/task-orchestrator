package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.treeCompletionService
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Service-level tests of [TreeCompletionService] (item c820b3d0, implementer seat, task-scope B7): the order, skip
 * propagation and cascade rules the retired `complete_tree` tests pinned, over a real SQLite database and the production
 * composition. Each run happens in one write unit, as the `complete_tree` op runs it.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TreeCompletionServiceTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun rig(dir: Path) = EventLogRig.build(db.db, dir, EventLogRig.GATED_CONFIG)

    private suspend fun EventLogRig.blocks(
        blocker: WorkItem,
        blocked: WorkItem
    ) {
        val out =
            ctx.dependencyCommandService.create(
                listOf(Dependency(fromItemId = blocker.id, toItemId = blocked.id, type = DependencyType.BLOCKS))
            )
        check(out is Outcome.Ok) { "fixture dependency failed: $out" }
    }

    private suspend fun EventLogRig.run(
        targets: List<WorkItem>,
        root: WorkItem? = null,
        trigger: String = "complete"
    ): Outcome<TreeCompletionResult> {
        var result: Outcome<TreeCompletionResult>? = null
        composition.unitOfWork.write("TreeCompletionServiceTest") {
            val r = treeCompletionService(ctx).run(targets, root, trigger, null, null)
            result = r
            when (r) {
                is Outcome.Ok -> Outcome.Ok(Unit)
                is Outcome.Err -> r
            }
        }
        return checkNotNull(result)
    }

    private fun Outcome<TreeCompletionResult>.outcomes(): List<TreeItemOutcome> =
        when (this) {
            is Outcome.Ok -> value.outcomes
            is Outcome.Err -> error("expected Ok, got ${error.code.wire}: ${error.message}")
        }

    private suspend fun EventLogRig.role(item: WorkItem): Role = raw.workItemRepository().getById(item.id)!!.role

    @Test
    fun `blockers run before what they block, ties in target order, the root last`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val root = rig.seed("R")
            val a = rig.seed("A", root)
            val b = rig.seed("B", root)
            val c = rig.seed("C", root)
            rig.blocks(a, b)

            val outcomes = rig.run(listOf(b, c, a), root).outcomes()

            // c and a are ready first (c precedes a in target order); b follows once a is done.
            assertEquals(listOf(c.id, a.id, b.id), outcomes.take(3).map { it.itemId })
            assertEquals(root.id, outcomes.last().itemId, "the root is processed last")
            assertEquals(3, outcomes.take(3).count { it is TreeItemOutcome.Applied })
            assertIs<TreeItemOutcome.AlreadyTerminal>(outcomes.last(), "the last child's cascade already closed the root")
        }

    @Test
    fun `a gate failure skips its in-set dependents transitively with blockedBy naming the failed item`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val a = rig.seed("A", tags = EventLogRig.GATED_TAG)
            val b = rig.seed("B")
            val c = rig.seed("C")
            rig.blocks(a, b)
            rig.blocks(b, c)

            val outcomes = rig.run(listOf(a, b, c)).outcomes().associateBy { it.itemId }

            val failed = assertIs<TreeItemOutcome.Failed>(outcomes.getValue(a.id))
            assertIs<AdvanceFailure.GateBlocked>(failed.failure)
            assertEquals(listOf(a.id), assertIs<TreeItemOutcome.UpstreamFailed>(outcomes.getValue(b.id)).blockedBy)
            assertEquals(listOf(a.id), assertIs<TreeItemOutcome.UpstreamFailed>(outcomes.getValue(c.id)).blockedBy)
            assertEquals(Role.QUEUE, rig.role(a))
            assertEquals(Role.QUEUE, rig.role(c))
        }

    @Test
    fun `an already terminal item is skipped without a gate check and does not skip its dependents`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val done = rig.seed("Done", role = Role.TERMINAL, tags = EventLogRig.GATED_TAG)
            val next = rig.seed("Next")
            rig.blocks(done, next)

            val outcomes = rig.run(listOf(done, next)).outcomes()

            assertIs<TreeItemOutcome.AlreadyTerminal>(outcomes[0])
            assertIs<TreeItemOutcome.Applied>(outcomes[1])
            assertEquals(Role.TERMINAL, rig.role(next))
        }

    @Test
    fun `a resolution failure does not skip its dependents, which run and meet their own dependency check`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val held = rig.seed("Held", role = Role.BLOCKED)
            val after = rig.seed("After")
            rig.blocks(held, after)

            val outcomes = rig.run(listOf(held, after)).outcomes()

            assertIs<AdvanceFailure.ResolutionFailed>(assertIs<TreeItemOutcome.Failed>(outcomes[0]).failure)
            val dependent = assertIs<TreeItemOutcome.Failed>(outcomes[1], "the dependent was attempted, not skipped")
            assertIs<AdvanceFailure.ValidationFailed>(dependent.failure)
        }

    @Test
    fun `cancel bypasses the note gate`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val gated = rig.seed("Gated", tags = EventLogRig.GATED_TAG)

            val outcomes = rig.run(listOf(gated), trigger = "cancel").outcomes()

            assertIs<TreeItemOutcome.Applied>(outcomes.single())
            assertEquals(Role.TERMINAL, rig.role(gated))
        }

    @Test
    fun `descendantTargets returns the descendants and the root only when asked`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val root = rig.seed("R")
            val a = rig.seed("A", root)
            val aa = rig.seed("AA", a)
            val service = treeCompletionService(rig.ctx)

            val (withRoot, rootItem) = service.descendantTargets(root.id, includeRoot = true)!!
            assertEquals(setOf(a.id, aa.id), withRoot.map { it.id }.toSet())
            assertEquals(root.id, rootItem?.id)
            assertEquals(null, service.descendantTargets(root.id, includeRoot = false)!!.second)
        }
}
