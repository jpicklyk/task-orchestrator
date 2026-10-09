package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.P11_LABELS_YAML
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Independent P11 tests (item 919d379e) for the single label policy: scenario S5.
 *
 * Oracles: task-scope 1.2 (key = "cascade" for a cascade, "complete" for a start whose target is TERMINAL, else the
 * trigger wire; per-root override then global then the documented defaults start=in-progress, complete=done,
 * block=blocked, cancel=cancelled, cascade=done, resume/reopen/hold=null) and the apply rule (explicit label, else
 * entering BLOCKED preserves, else clears); task-scope 3(f) (a per-root or global status_labels.cancel now takes
 * effect). Labels asserted against configured values use test-chosen strings, never an implementation default.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceLabelPolicyTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun driver(
        dir: Path,
        yaml: String,
    ) = P11Driver(EventLogRig.build(db.db, dir, yaml))

    /** An AdvanceService over the SQLite fixture whose labels come from [labelFor] (the label-apply rule in isolation). */
    private fun service(labelFor: suspend (io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger, Role) -> String?): AdvanceService {
        val provider = db.repositoryProvider()
        return AdvanceService(
            workItemRepository = provider.workItemRepository(),
            roleTransitionRepository = provider.roleTransitionRepository(),
            dependencyRepository = provider.dependencyRepository(),
            noteRepository = provider.noteRepository(),
            labelFor = labelFor,
            schemaResolver = { null },
            unitOfWork = db.unitOfWork(),
        )
    }

    private suspend fun AdvanceService.go(
        item: WorkItem,
        trigger: String,
    ): AdvanceOutcome = advance(item, trigger, null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = false)

    // ---------------------------------------------------------------------------------------------
    // The apply rule: explicit label, else entering BLOCKED preserves, else clears
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 entering blocked with no label preserves the current label and an explicit block label replaces it`(): Unit =
        runBlocking {
            val repo = db.repositoryProvider().workItemRepository()

            val preserving = service { trigger, _ -> if (trigger.wire == "block") null else "other" }
            val a = repo.create(WorkItem(title = "A", role = Role.WORK, statusLabel = "mine"))
            assertIs<AdvanceOutcome.Success>(preserving.go(a, "block"))
            assertEquals("mine", repo.getById(a.id)!!.statusLabel, "block with a null label preserves the existing label")
            assertEquals(Role.BLOCKED, repo.getById(a.id)!!.role)

            val explicit = service { trigger, _ -> if (trigger.wire == "block") "blk" else null }
            val b = repo.create(WorkItem(title = "B", role = Role.WORK, statusLabel = "mine"))
            assertIs<AdvanceOutcome.Success>(explicit.go(b, "block"))
            assertEquals("blk", repo.getById(b.id)!!.statusLabel, "an explicit block label wins over preservation")
        }

    @Test
    fun `S5 a null label on resume or complete clears the label`(): Unit =
        runBlocking {
            val repo = db.repositoryProvider().workItemRepository()
            val svc = service { _, _ -> null }

            val blocked = repo.create(WorkItem(title = "blocked", role = Role.BLOCKED, previousRole = Role.WORK, statusLabel = "stale"))
            assertIs<AdvanceOutcome.Success>(svc.go(blocked, "resume"))
            assertEquals(Role.WORK, repo.getById(blocked.id)!!.role)
            assertNull(repo.getById(blocked.id)!!.statusLabel, "resume with a null label clears")

            val working = repo.create(WorkItem(title = "working", role = Role.WORK, statusLabel = "stale"))
            assertIs<AdvanceOutcome.Success>(svc.go(working, "complete"))
            assertNull(repo.getById(working.id)!!.statusLabel, "leaving without a label clears, it does not preserve")
        }

    // ---------------------------------------------------------------------------------------------
    // Configured labels through the real composition
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 a global status_labels cancel override stamps a cancelled item`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir, P11_LABELS_YAML)
            val item = d.item("to cancel", Role.QUEUE)

            val r = d.advance(item, "cancel")

            assertEquals(true, r.flag("applied"), "$r")
            assertEquals("terminal", r.text("newRole"))
            assertEquals("p11-cancel-label", r.text("statusLabel"), "the configured cancel label wins over any built-in one: $r")
            assertEquals("p11-cancel-label", d.reload(item).statusLabel)
        }

    @Test
    fun `S5 without a status_labels section cancel uses the documented default label`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir, P11_BASE_YAML)
            val item = d.item("to cancel", Role.QUEUE)

            val r = d.advance(item, "cancel")

            assertEquals(true, r.flag("applied"), "$r")
            assertEquals("cancelled", r.text("statusLabel"), "documented default for cancel (control for the override test): $r")
        }

    @Test
    fun `S5 a per-root status_labels override beats the global one and other roots keep the global label`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir, P11_LABELS_YAML)
            val rootA = d.item("root A", Role.WORK)
            val rootB = d.item("root B", Role.WORK)
            assertNotNull(
                d.raw.projectConfigRepository().upsert(rootA.id, "status_labels:\n  cancel: p11-root-cancel\n"),
                "fixture: the per-root config push must succeed",
            )
            val underA = d.item("under A", Role.QUEUE, parent = rootA)
            val underB = d.item("under B", Role.QUEUE, parent = rootB)

            val a = d.advance(underA, "cancel")
            val b = d.advance(underB, "cancel")

            assertEquals(true, a.flag("applied"), "$a")
            assertEquals("p11-root-cancel", a.text("statusLabel"), "per-root override wins in its own root: $a")
            assertEquals("p11-cancel-label", b.text("statusLabel"), "a root without an override falls back to the global label: $b")
            assertEquals("p11-root-cancel", d.reload(underA).statusLabel)
        }

    @Test
    fun `S5 a start that lands in terminal uses the complete label, a plain start uses the start label`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir, P11_LABELS_YAML)
            // p11-plain has no review phase: start from WORK goes straight to TERMINAL (needs the work-phase note).
            val working = d.item("working", Role.WORK, type = "p11-plain")
            d.note(working, "impl", "work")
            val queued = d.item("queued", Role.QUEUE)

            val toTerminal = d.advance(working, "start")
            val toWork = d.advance(queued, "start")

            assertEquals(true, toTerminal.flag("applied"), "$toTerminal")
            assertEquals("terminal", toTerminal.text("newRole"))
            assertEquals("p11-complete-label", toTerminal.text("statusLabel"), "start -> TERMINAL is labelled as a completion: $toTerminal")
            assertEquals("work", toWork.text("newRole"))
            assertEquals("p11-start-label", toWork.text("statusLabel"), "$toWork")
        }

    @Test
    fun `S5 a start that lands in terminal uses the default done label when nothing is configured`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir, P11_BASE_YAML)
            val working = d.item("working", Role.WORK, type = "p11-plain")
            d.note(working, "impl", "work")

            val r = d.advance(working, "start")

            assertEquals("terminal", r.text("newRole"), "$r")
            assertEquals("done", r.text("statusLabel"), "documented default for complete: $r")
        }

    @Test
    fun `S5 a cascade uses the cascade label while the triggering child keeps the complete label`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir, P11_LABELS_YAML)
            val parent = d.item("parent", Role.WORK)
            val child = d.item("child", Role.WORK, parent = parent)

            val r = d.advance(child, "complete")

            assertEquals("p11-complete-label", r.text("statusLabel"), "$r")
            val cascade = r.arr("cascadeEvents").single().jsonObject
            assertEquals("p11-cascade-label", cascade.text("statusLabel"), "$cascade")
            assertEquals("p11-cascade-label", d.reload(parent).statusLabel)
            assertEquals("p11-complete-label", d.reload(child).statusLabel)
        }

    @Test
    fun `S5 resume and reopen leave no label when none is configured for them`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir, P11_LABELS_YAML)
            val blocked = d.item("blocked", Role.BLOCKED, previousRole = Role.WORK, statusLabel = "stale")
            val done = d.item("done", Role.TERMINAL, statusLabel = "stale")

            val resumed = d.advance(blocked, "resume")
            val reopened = d.advance(done, "reopen")

            assertEquals(true, resumed.flag("applied"), "$resumed")
            assertNull(resumed.text("statusLabel"), "$resumed")
            assertNull(d.reload(blocked).statusLabel)
            assertEquals(true, reopened.flag("applied"), "$reopened")
            assertNull(reopened.text("statusLabel"), "$reopened")
            assertNull(d.reload(done).statusLabel)
        }
}
