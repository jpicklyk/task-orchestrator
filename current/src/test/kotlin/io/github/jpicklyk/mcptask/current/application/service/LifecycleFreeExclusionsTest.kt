package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.IwKit
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.Reply
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.callIn
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.irApp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.titles
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.treeItems
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.twComplete
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.twIds
import io.github.jpicklyk.mcptask.current.test.blocksDep
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `test-item` is an ordinary auto type; `page` is lifecycle-free. */
private const val EXCLUSIONS_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    notes: []\n" +
        "  page:\n" +
        "    lifecycle: none\n" +
        "    notes: []\n"

private val EXCLUSIONS_REGISTRY = ToolRegistry(buildToolSpecs())

/**
 * Independent tests (test-author seat, item 9e5707b3 / Q31b) for where a lifecycle-free item is excluded: scenarios S11 to
 * S15 of the frozen test-plan and their probes.
 *
 * Oracles (frozen task-scope Build 7 and 9, plan 3.12): grouped counts (overview `childCounts`, anchored full-subtree
 * roll-ups) skip a NULL-role row instead of folding it into `queue`; the terminal cascade ignores a lifecycle-free child, so an
 * AUTO parent terminates when its last role-bearing child does; `next`, `blocked` and claim-by-selector never return a
 * lifecycle-free item (their role predicates exclude it); `excludeTerminal` keeps a lifecycle-free root or child listed, while
 * a TERMINAL item whose only open-looking descendant is lifecycle-free is hidden (a page is not open work); `complete_tree`
 * gives a lifecycle-free descendant no outcome and completes the rest, while an item that is targeted explicitly still fails
 * `invalid_transition`.
 *
 * Absence assertions are paired with a control built from the same call shape and an ordinary (queue) item that does appear;
 * the count assertions are differential (the same tree with and without the lifecycle-free rows) so they cannot pass by
 * coincidence of the folding rule.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class LifecycleFreeExclusionsTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun overview(extra: String = "") = """{"select":"overview"$extra}"""

    private fun JsonObject.counts(): Map<String, Int> =
        (this["childCounts"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.int } ?: emptyMap()

    private fun Reply.entry(title: String): JsonObject = items.single { it.str("title") == title }

    private suspend fun EventLogRig.svc(
        title: String,
        parent: WorkItem? = null,
        type: String = "test-item",
        priority: Priority = Priority.MEDIUM
    ): WorkItem =
        ctx.itemCommandService
            .create(ItemCreateCommand(parentId = parent?.id, title = title, type = type, priority = priority))
            .orFail()

    private suspend fun EventLogRig.get(item: WorkItem): WorkItem = checkNotNull(raw.workItemRepository().getById(item.id))

    private suspend fun EventLogRig.setRole(
        item: WorkItem,
        role: Role
    ) {
        val previous = if (role == Role.BLOCKED) Role.WORK else null
        raw.workItemRepository().update(get(item).copy(role = role, previousRole = previous))
    }

    private suspend fun EventLogRig.dispatch(
        tool: String,
        body: String
    ): JsonObject {
        val spec = checkNotNull(EXCLUSIONS_REGISTRY[tool]) { "$tool is not registered" }
        return buildToolDispatcher(ctx)
            .dispatch(spec, ToolProfile.ORCHESTRATION, Json.parseToJsonElement(body).jsonObject, "k7f3q9ab", "mcp")
            .envelope.body
    }

    private val actor = """"actor":{"id":"agent-1","kind":"subagent"}"""

    private suspend fun EventLogRig.advance(
        item: WorkItem,
        trigger: String
    ): JsonObject = dispatch("advance", """{$actor,"ops":[{"op":"transition","itemId":"${item.id}","trigger":"$trigger"}]}""")

    // ---------------------------------------------------------------------------------------------
    // S11 - counts skip a lifecycle-free row
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 scoped overview childCounts skips a lifecycle-free child and still lists it`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val parent = rig.svc("Parent")
            rig.svc("Queued child", parent)
            val worker = rig.svc("Working child", parent)
            rig.setRole(worker, Role.WORK)
            rig.svc("Page child", parent, "page")

            val r = select(overview(""","itemId":"${parent.id}""""))

            assertEquals(200, r.status, r.raw)
            assertEquals(mapOf("queue" to 1, "work" to 1), r.data.counts(), "the page child is not folded into queue")
            assertEquals(listOf("Page child", "Queued child", "Working child"), r.items.titles().sorted(), "the page is still listed")
            assertFalse("role" in r.entry("Page child"), "a listed page carries no role: ${r.entry("Page child")}")
            assertEquals("queue", r.entry("Queued child").str("role"), "control: a queue child shows its role")
        }

    @Test
    fun `S11 a parent whose only children are lifecycle-free reports no child counts`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val parent = rig.svc("Parent")
            rig.svc("Page one", parent, "page")
            rig.svc("Page two", parent, "page")

            val r = select(overview(""","itemId":"${parent.id}""""))

            assertEquals(200, r.status, r.raw)
            assertEquals(emptyMap(), r.data.counts(), "nothing role-bearing is below the parent")
            assertEquals(2, r.items.size, "control: both pages are listed")
        }

    @Test
    fun `S11 global overview entry counts skip a lifecycle-free child`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val root = rig.svc("Open root")
            rig.svc("Queued child", root)
            rig.svc("Page child", root, "page")

            val r = select(overview())

            assertEquals(mapOf("queue" to 1), r.entry("Open root").counts())
        }

    @Test
    fun `S11 anchored roll-ups skip lifecycle-free rows at every depth, equal to the same tree without them`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val anchor = rig.svc("Anchor")
            val plain = rig.svc("Plain", anchor)
            val padded = rig.svc("Padded", anchor)
            val deepParents = mutableMapOf<String, WorkItem>()
            for (branch in listOf(plain, padded)) {
                rig.svc("Q-${branch.title}", branch)
                val w = rig.svc("W-${branch.title}", branch)
                rig.setRole(w, Role.WORK)
                val g = rig.svc("G-${branch.title}", branch)
                deepParents[branch.title] = g
                rig.svc("GG-${branch.title}", g)
            }
            rig.svc("Page near", padded, "page")
            rig.svc("Page deep", deepParents.getValue("Padded"), "page")

            val r = select(overview(""","anchorId":"${anchor.id}""""))

            assertEquals(200, r.status, r.raw)
            val withPages = r.entry("Padded").counts()
            val without = r.entry("Plain").counts()
            assertEquals(without, withPages, "adding pages near and deep changes no roll-up")
            assertTrue(without.isNotEmpty(), "control: the plain branch has role-bearing descendants: $without")
            assertEquals(without.values.sum(), withPages.values.sum())
        }

    // ---------------------------------------------------------------------------------------------
    // S12 - the terminal cascade ignores a lifecycle-free child
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S12 an auto parent terminates when its only role-bearing child completes, whatever lifecycle-free sibling it has`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val parent = rig.svc("Parent")
            val worker = rig.svc("Worker", parent)
            val page = rig.svc("Page", parent, "page")
            rig.setRole(parent, Role.WORK)
            rig.setRole(worker, Role.WORK)

            rig.advance(worker, "complete").okResult()

            assertEquals(Role.TERMINAL, rig.get(worker).role, "the worker completed")
            assertEquals(Role.TERMINAL, rig.get(parent).role, "the page does not hold the parent open")
            assertNull(rig.get(page).role, "the page is untouched")
        }

    @Test
    fun `S12 control - an open role-bearing sibling still holds the parent open`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val parent = rig.svc("Parent")
            val worker = rig.svc("Worker", parent)
            rig.svc("Open sibling", parent)
            rig.svc("Page", parent, "page")
            rig.setRole(parent, Role.WORK)
            rig.setRole(worker, Role.WORK)

            rig.advance(worker, "complete").okResult()

            assertEquals(Role.TERMINAL, rig.get(worker).role)
            assertEquals(Role.WORK, rig.get(parent).role, "an unfinished queue sibling keeps the parent in work")
        }

    @Test
    fun `S12 starting the first role-bearing child starts a queue parent that also has a lifecycle-free child`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val parent = rig.svc("Parent")
            val worker = rig.svc("Worker", parent)
            val page = rig.svc("Page", parent, "page")

            rig.advance(worker, "start").okResult()

            assertEquals(Role.WORK, rig.get(worker).role)
            assertEquals(Role.WORK, rig.get(parent).role, "the start cascade is not disturbed by the page")
            assertNull(rig.get(page).role)
        }

    // ---------------------------------------------------------------------------------------------
    // S13 - next, blocked and claim-by-selector never return a lifecycle-free item
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S13 next never returns a lifecycle-free item even when it outranks every other item`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val parent = rig.svc("Parent")
            rig.svc("Low queue", parent, priority = Priority.LOW)
            val page = rig.svc("High page", parent, "page", Priority.HIGH)

            val r = select("""{"select":"next","limit":200}""")

            assertEquals(200, r.status, r.raw)
            val titles = r.items.titles()
            assertTrue("Low queue" in titles, "control: the queue item is eligible: $titles")
            assertFalse("High page" in titles, "the page is never eligible: $titles")
            assertNull(rig.get(page).role)
        }

    @Test
    fun `S13 blocked never lists a lifecycle-free item, although an equal queue item with the same edge is listed`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val parent = rig.svc("Parent")
            val gate = rig.svc("Open gate", parent)
            val waiting = rig.svc("Waiting queue", parent)
            val page = rig.svc("Waiting page", parent, "page")
            rig.raw.dependencyRepository().create(blocksDep(gate.id, waiting.id))
            rig.raw.dependencyRepository().create(blocksDep(gate.id, page.id))

            val r = select("""{"select":"blocked","limit":200}""")

            assertEquals(200, r.status, r.raw)
            val titles = r.items.titles()
            assertTrue("Waiting queue" in titles, "control: the queue item with an unmet edge is blocked: $titles")
            assertFalse("Waiting page" in titles, "the page with the same raw edge is not: $titles")
        }

    @Test
    fun `S13 claim by selector skips a lifecycle-free item and claims the queue item`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val parent = rig.svc("Parent")
            val page = rig.svc("High page", parent, "page", Priority.HIGH)
            val plain = rig.svc("Low queue", parent, priority = Priority.LOW)

            val body =
                rig.dispatch(
                    "advance",
                    """{$actor,"ops":[{"op":"claim","select":{"parentId":"${parent.id}","orderBy":"priority"}}]}"""
                )

            body.okResult()
            assertNotNull(rig.get(plain).claimedBy, "control: the queue item was claimed: $body")
            assertNull(rig.get(page).claimedBy, "the page was not claimed")
            assertNull(rig.get(page).role)
        }

    @Test
    fun `S13 claim by selector over a parent with only lifecycle-free children claims nothing`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val parent = rig.svc("Parent")
            val page = rig.svc("Only page", parent, "page")

            rig.dispatch(
                "advance",
                """{$actor,"ops":[{"op":"claim","select":{"parentId":"${parent.id}","orderBy":"priority"}}]}"""
            )

            assertNull(rig.get(page).claimedBy, "no selector may land on a page")
            assertNull(rig.get(page).claimExpiresAt)
        }

    // ---------------------------------------------------------------------------------------------
    // S14 - excludeTerminal
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S14 global excludeTerminal keeps a lifecycle-free root listed and still drops a terminal root`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            rig.svc("Page root", null, "page")
            rig.svc("Open root")
            val done = rig.svc("Done root")
            rig.setRole(done, Role.TERMINAL)

            val all = select(overview())
            val open = select(overview(""","excludeTerminal":true"""))

            assertEquals(listOf("Done root", "Open root", "Page root"), all.items.titles().sorted())
            assertEquals(listOf("Open root", "Page root"), open.items.titles().sorted(), "the page root stays, the terminal root goes")
            assertEquals(2, open.total, "the total is the filtered count")
        }

    @Test
    fun `S14 scoped excludeTerminal keeps lifecycle-free children and hides a terminal child that only has a page below`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val box = rig.svc("Box")
            rig.svc("Page child", box, "page")
            rig.svc("Queued child", box)
            val pageOnly = rig.svc("Terminal with only a page", box)
            rig.svc("Page below", pageOnly, "page")
            rig.setRole(pageOnly, Role.TERMINAL)
            val withWork = rig.svc("Terminal with open work", box)
            rig.svc("Open below", withWork)
            rig.setRole(withWork, Role.TERMINAL)
            val plainDone = rig.svc("Plain terminal", box)
            rig.setRole(plainDone, Role.TERMINAL)

            val r = select(overview(""","itemId":"${box.id}","excludeTerminal":true"""))

            assertEquals(200, r.status, r.raw)
            assertEquals(
                listOf("Page child", "Queued child", "Terminal with open work"),
                r.items.titles().sorted(),
                "pages stay, a page below does not keep a terminal item visible, real open work does"
            )
        }

    // ---------------------------------------------------------------------------------------------
    // S15 - complete_tree
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S15 complete_tree over a root with a lifecycle-free descendant gives it no outcome and completes the rest`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val root = rig.svc("Root")
            val a = rig.svc("A", root)
            val b = rig.svc("B", root)
            val page = rig.svc("Page", root, "page")
            val nested = rig.svc("Nested page", a, "page")
            val k = IwKit(rig)

            val ok =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twComplete("rootId" to JsonPrimitive(root.id.toString())))
                    .envelope.body
                    .okResult()

            val ids = ok.treeItems().map { it.str("itemId") }
            assertTrue(a.id.toString() in ids && b.id.toString() in ids, "control: the role-bearing descendants have outcomes: $ids")
            assertFalse(page.id.toString() in ids, "the page has no outcome: $ids")
            assertFalse(nested.id.toString() in ids, "the nested page has no outcome: $ids")
            assertEquals(Role.TERMINAL, rig.get(a).role)
            assertEquals(Role.TERMINAL, rig.get(b).role)
            assertEquals(Role.TERMINAL, rig.get(root).role, "the root completes without the page")
            assertNull(rig.get(page).role, "the page stays role-free")
            assertNull(rig.get(nested).role)
        }

    @Test
    fun `S15 an explicitly targeted lifecycle-free item still fails with invalid_transition`() =
        irApp(db.db, dir, yaml = EXCLUSIONS_YAML) { rig ->
            val page = rig.svc("Page", null, "page")
            val plain = rig.svc("Plain")
            val k = IwKit(rig)

            val ok =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twComplete("itemIds" to twIds(page.id, plain.id)))
                    .envelope.body
                    .okResult()

            val byId = ok.treeItems().associateBy { it.str("itemId") }
            val failed = byId.getValue(page.id.toString())
            assertEquals("failed", failed.str("outcome"), "entry: $failed")
            assertEquals("invalid_transition", failed.getValue("error").jsonObject.str("code"))
            assertEquals("applied", byId.getValue(plain.id.toString()).str("outcome"), "control: the plain item completes in the same call")
            assertNull(rig.get(page).role)
        }
}
