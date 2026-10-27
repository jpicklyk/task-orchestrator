package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.P11_LABELS_YAML
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testClaimService
import io.github.jpicklyk.mcptask.current.test.text
import io.github.jpicklyk.mcptask.current.test.upsert
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
 *
 * Q32 port: the configured-label scenarios drive the production `advance` tool (P11Driver.advance); its element carries the
 * primary's `statusLabel`, and a cascade's label is read from the parent row (the cascade entry has no label member).
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
            dependencyRepository = provider.dependencyRepository(),
            noteRepository = provider.noteRepository(),
            labelFor = labelFor,
            schemaResolver = { null },
            unitOfWork = db.unitOfWork(),
            claimService = testClaimService(provider.workItemRepository(), null, db.unitOfWork())
        )
    }

    private suspend fun AdvanceService.go(
        item: WorkItem,
        trigger: String,
    ): AdvanceOutcome = advance(item, trigger, null, null, null)

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

            assertEquals(true, r.flag("ok"), "$r")
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

            assertEquals(true, r.flag("ok"), "$r")
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
                d.raw.configStore().upsert(rootA.id, "status_labels:\n  cancel: p11-root-cancel\n"),
                "fixture: the per-root config push must succeed",
            )
            val underA = d.item("under A", Role.QUEUE, parent = rootA)
            val underB = d.item("under B", Role.QUEUE, parent = rootB)

            val a = d.advance(underA, "cancel")
            val b = d.advance(underB, "cancel")

            assertEquals(true, a.flag("ok"), "$a")
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

            assertEquals(true, toTerminal.flag("ok"), "$toTerminal")
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
            val cascade = r.arr("cascades").single().jsonObject
            assertEquals(parent.id.toString(), cascade.text("itemId"), "$cascade")
            assertEquals(true, cascade.flag("applied"), "$cascade")
            assertEquals("p11-cascade-label", d.reload(parent).statusLabel, "the cascade label is stamped on the parent")
            assertEquals("p11-complete-label", d.reload(child).statusLabel)
        }

    @Test
    fun `a start cascade labels the parent with the start label, not the terminal cascade label`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            // Bug f328cceb: a child start cascaded its parent queue -> work under the "cascade" key, so the working
            // parent carried the terminal "done" label.
            val d = driver(dir, P11_LABELS_YAML)
            val parent = d.item("parent", Role.QUEUE)
            val child = d.item("child", Role.QUEUE, parent = parent)

            val r = d.advance(child, "start")

            assertEquals(true, r.flag("ok"), "$r")
            val cascade = r.arr("cascades").single().jsonObject
            assertEquals(parent.id.toString(), cascade.text("itemId"), "$cascade")
            assertEquals(true, cascade.flag("applied"), "$cascade")
            assertEquals(Role.WORK, d.reload(parent).role)
            assertEquals("p11-start-label", d.reload(parent).statusLabel, "a parent entering work is labelled as started")
        }

    @Test
    fun `a start cascade uses the default in-progress label when nothing is configured`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir, P11_BASE_YAML)
            val parent = d.item("parent", Role.QUEUE)
            val child = d.item("child", Role.QUEUE, parent = parent)

            assertEquals(true, d.advance(child, "start").flag("ok"))

            assertEquals(Role.WORK, d.reload(parent).role)
            assertEquals("in-progress", d.reload(parent).statusLabel, "documented default for start, never the terminal 'done'")
        }

    @Test
    fun `a reopen cascade clears the parent's terminal label`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir, P11_LABELS_YAML)
            val parent = d.item("parent", Role.TERMINAL, statusLabel = "p11-cascade-label")
            val child = d.item("child", Role.TERMINAL, parent = parent, statusLabel = "p11-complete-label")

            val r = d.advance(child, "reopen")

            assertEquals(true, r.flag("ok"), "$r")
            val cascade = r.arr("cascades").single().jsonObject
            assertEquals(true, cascade.flag("applied"), "$cascade")
            assertEquals(Role.WORK, d.reload(parent).role)
            assertNull(d.reload(parent).statusLabel, "a reopened parent takes the reopen label (none configured), not 'done'")
        }

    @Test
    fun `a reopen cascade stamps a configured reopen label on the parent`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            // The null case above cannot tell the reopen key from any other unconfigured key; a configured reopen label
            // pins the key itself.
            val d = driver(dir, P11_LABELS_YAML + "  reopen: p11-reopen-label\n")
            val parent = d.item("parent", Role.TERMINAL, statusLabel = "p11-cascade-label")
            val child = d.item("child", Role.TERMINAL, parent = parent, statusLabel = "p11-complete-label")

            val r = d.advance(child, "reopen")

            assertEquals(true, r.flag("ok"), "$r")
            assertEquals("p11-reopen-label", r.text("statusLabel"), "the reopened child takes the reopen label: $r")
            val cascade = r.arr("cascades").single().jsonObject
            assertEquals(parent.id.toString(), cascade.text("itemId"), "$cascade")
            assertEquals(true, cascade.flag("applied"), "$cascade")
            assertEquals(Role.WORK, d.reload(parent).role)
            assertEquals("p11-reopen-label", d.reload(parent).statusLabel, "the reopen cascade looks up the reopen key")
        }

    @Test
    fun `a terminal cascade that began with a cancel stamps the cascade label on the parent`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            // Cascade.Complete(cancelOrigin = true): the parent completes because its last child was cancelled. It is
            // still the terminal cascade, so it uses the cascade label, not the cancel label of the child.
            val d = driver(dir, P11_LABELS_YAML)
            val parent = d.item("parent", Role.WORK)
            val child = d.item("child", Role.WORK, parent = parent)

            val r = d.advance(child, "cancel")

            assertEquals(true, r.flag("ok"), "$r")
            assertEquals("p11-cancel-label", r.text("statusLabel"), "$r")
            val cascade = r.arr("cascades").single().jsonObject
            assertEquals(parent.id.toString(), cascade.text("itemId"), "$cascade")
            assertEquals(true, cascade.flag("applied"), "$cascade")
            assertEquals(Role.TERMINAL, d.reload(parent).role)
            assertEquals("p11-cascade-label", d.reload(parent).statusLabel, "a cancel-origin cascade is still the terminal cascade")
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

            assertEquals(true, resumed.flag("ok"), "$resumed")
            assertNull(resumed.text("statusLabel"), "$resumed")
            assertNull(d.reload(blocked).statusLabel)
            assertEquals(true, reopened.flag("ok"), "$reopened")
            assertNull(reopened.text("statusLabel"), "$reopened")
            assertNull(d.reload(done).statusLabel)
        }
}
