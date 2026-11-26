package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.Reply
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.irApp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.titles
import io.github.jpicklyk.mcptask.current.itemwrite.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.model.item.Priority
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.platform.tool.ToolProfile
import io.github.jpicklyk.mcptask.current.test.dispatchAsTestActor
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.testToolDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

/** `test-item` builds; `plan-only` sets `builds: false`; `page` is lifecycle-free; trait `no-build` only sets `builds: false`. */
private const val STARTABLE_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    notes: []\n" +
        "  plan-only:\n" +
        "    builds: false\n" +
        "    notes: []\n" +
        "  page:\n" +
        "    lifecycle: none\n" +
        "    notes: []\n" +
        "traits:\n" +
        "  no-build:\n" +
        "    builds: false\n"

private val STARTABLE_REGISTRY = ToolRegistry(buildToolSpecs())

/**
 * Tests T1-T5 and T8-T10 of item 6aa122da (decision 0004, `next` returns startable work), through the production topology.
 *
 * Oracle: decision 0004 with task-scope R1-R5. In the queue role `next` and claim select return only leaves (an item with a
 * child whose role is set is a container; a lifecycle-free child does not count) unless `includeParents`, and only items whose
 * pinned schema and traits build (`builds: false` excludes either way). In any other role the rules do not apply. A container
 * is not a match at all, so an all-container queue answers an empty page with no `reason` and claim select `queue_empty`
 * (claimed false, no reason, no excluded). A pin taken before V38 (NULL builds) builds until `repin`.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class NextStartableTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val actor = """"actor":{"id":"agent-1","kind":"subagent"}"""

    private suspend fun EventLogRig.svc(
        title: String,
        parent: WorkItem? = null,
        type: String = "test-item",
        priority: Priority = Priority.MEDIUM,
        traits: List<String> = emptyList()
    ): WorkItem =
        ctx.itemCommandService
            .create(ItemCreateCommand(parentId = parent?.id, title = title, type = type, priority = priority, traits = traits))
            .orFail()

    private suspend fun EventLogRig.get(item: WorkItem): WorkItem = checkNotNull(raw.workItemRepository().getById(item.id))

    private suspend fun EventLogRig.setRole(
        item: WorkItem,
        role: Role
    ) {
        raw.workItemRepository().update(get(item).copy(role = role))
    }

    private suspend fun EventLogRig.dispatch(
        tool: String,
        body: String
    ): JsonObject {
        val spec = checkNotNull(STARTABLE_REGISTRY[tool]) { "$tool is not registered" }
        return testToolDispatcher(ctx)
            .dispatchAsTestActor(spec, ToolProfile.ORCHESTRATION, Json.parseToJsonElement(body).jsonObject, "k7f3q9ab", "mcp")
            .envelope.body
    }

    private suspend fun EventLogRig.claimSelect(select: String): JsonObject =
        dispatch("advance", """{$actor,"ops":[{"op":"claim","select":{$select}}]}""").okResult()

    private suspend fun EventLogRig.claimById(item: WorkItem): JsonObject =
        dispatch("advance", """{$actor,"ops":[{"op":"claim","itemId":"${item.id}"}]}""").okResult()

    private fun Reply.reason(): String? = data["reason"]?.jsonPrimitive?.content

    private fun JsonObject.flag(name: String): Boolean? = this[name]?.jsonPrimitive?.content?.toBoolean()

    // ------------------------------------------------------------------ T1-T3 the leaf rule

    @Test
    fun `T1 default next omits a queue parent with a role-bearing child and lists the child`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val parent = rig.svc("Parent", priority = Priority.HIGH)
            rig.svc("Child", parent, priority = Priority.LOW)

            val r = select("""{"select":"next","limit":200}""")

            assertEquals(200, r.status, r.raw)
            assertEquals(listOf("Child"), r.items.titles(), "the container is not startable work")
            assertEquals(1, r.total)
        }

    @Test
    fun `T1 a terminal child still makes its parent a container`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val parent = rig.svc("Parent")
            val child = rig.svc("Done child", parent)
            rig.setRole(child, Role.TERMINAL)

            val r = select("""{"select":"next","limit":200}""")

            assertEquals(emptyList(), r.items.titles(), "a child in any role, terminal included, makes a container")
        }

    @Test
    fun `T2 includeParents lists the queue parent next to its child`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val parent = rig.svc("Parent", priority = Priority.HIGH)
            rig.svc("Child", parent, priority = Priority.LOW)

            val r = select("""{"select":"next","limit":200,"includeParents":true}""")

            assertEquals(200, r.status, r.raw)
            assertEquals(listOf("Parent", "Child"), r.items.titles())
        }

    @Test
    fun `T3 a parent whose children are all lifecycle-free is a leaf and is listed`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val parent = rig.svc("Parent")
            val page = rig.svc("Page", parent, "page")

            val r = select("""{"select":"next","limit":200}""")

            assertNull(rig.get(page).role, "fixture: the page is lifecycle-free")
            assertEquals(listOf("Parent"), r.items.titles())
        }

    // ------------------------------------------------------------------ T4 other roles

    @Test
    fun `T4 next with role review lists a parent in review without the flag`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val parent = rig.svc("Parent")
            rig.svc("Child", parent)
            rig.setRole(parent, Role.REVIEW)

            val r = select("""{"select":"next","role":"review","limit":200}""")

            assertEquals(200, r.status, r.raw)
            assertEquals(listOf("Parent"), r.items.titles(), "the leaf rule applies to the queue role only")
        }

    // ------------------------------------------------------------------ T5-T6 builds

    @Test
    fun `T5 a leaf whose schema sets builds false is excluded from next with or without includeParents`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val plan = rig.svc("Plan", type = "plan-only", priority = Priority.HIGH)
            rig.svc("Build", priority = Priority.LOW)

            assertEquals(false, rig.get(plan).pin?.builds, "fixture: the pin records builds false")
            assertEquals(listOf("Build"), select("""{"select":"next","limit":200}""").items.titles())
            assertEquals(listOf("Build"), select("""{"select":"next","limit":200,"includeParents":true}""").items.titles())
        }

    @Test
    fun `T6 a trait that only sets builds false excludes the item and a building sibling is listed`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val traited = rig.svc("Traited", priority = Priority.HIGH, traits = listOf("no-build"))
            val plain = rig.svc("Plain", priority = Priority.LOW)

            assertEquals(false, rig.get(traited).pin?.builds, "the trait with no notes or seats still applies builds false")
            assertEquals(true, rig.get(plain).pin?.builds, "control: a plain item pins builds true")
            assertEquals(listOf("Plain"), select("""{"select":"next","limit":200}""").items.titles())
        }

    @Test
    fun `T5 claim select skips a builds false leaf even with includeParents`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val plan = rig.svc("Plan", type = "plan-only")

            val element = rig.claimSelect(""""type":"plan-only","includeParents":true""")

            assertEquals(false, element.flag("claimed"), "$element")
            assertNull(rig.get(plan).claimedBy)
        }

    @Test
    fun `T5 claim select skips a builds false leaf without includeParents`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val plan = rig.svc("Plan", type = "plan-only")

            val element = rig.claimSelect(""""type":"plan-only"""")

            assertEquals(false, element.flag("claimed"), "$element")
            assertNull(rig.get(plan).claimedBy)
        }

    // ------------------------------------------------------------------ T8 pinned before the push

    @Test
    fun `T8 an item pinned before V38 builds until repin, then repin answers repinned true and excludes it`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val plan = rig.svc("Plan", type = "plan-only")
            val pin = checkNotNull(rig.get(plan).pin) { "fixture: the item is pinned" }
            // A pin taken before V38 carries no builds value.
            rig.raw.workItemRepository().update(rig.get(plan).copy(pin = pin.copy(builds = null)))
            assertNull(rig.get(plan).pin?.builds)

            assertEquals(listOf("Plan"), select("""{"select":"next","limit":200}""").items.titles(), "NULL builds reads as builds")

            val repinned =
                rig.ctx.itemCommandService
                    .repin(plan.id, null)
                    .orFail()

            assertTrue(repinned.repinned, "the pin differs in builds, so repin writes")
            assertEquals(false, rig.get(plan).pin?.builds)
            assertEquals(emptyList(), select("""{"select":"next","limit":200}""").items.titles())
        }

    @Test
    fun `T8 repin of an item pinned before V38 whose schema builds answers repinned false and writes no item pinned row`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val build = rig.svc("Build")
            val pin = checkNotNull(rig.get(build).pin) { "fixture: the item is pinned" }
            assertEquals(true, pin.builds, "fixture: the schema builds")
            // A pin taken before V38 carries no builds value; the config is unchanged since.
            rig.raw.workItemRepository().update(rig.get(build).copy(pin = pin.copy(builds = null)))

            val (repinned, rows) =
                rig.written {
                    rig.ctx.itemCommandService
                        .repin(build.id, null)
                        .orFail()
                }

            assertFalse(repinned.repinned, "NULL builds equals the default, so the pin is unchanged")
            assertTrue(rows.none { it.type == "item.pinned" }, "no item.pinned row: ${rows.map { it.type }}")
            assertNull(rig.get(build).pin?.builds, "the stored pin is left as it was")
        }

    // ------------------------------------------------------------------ S1-S2 item.pinned records builds

    @Test
    fun `S1 a builds-only repin writes one row whose sides differ only in builds`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val plan = rig.svc("Plan", type = "plan-only")
            val pin = checkNotNull(rig.get(plan).pin) { "fixture: the item is pinned" }
            rig.raw.workItemRepository().update(rig.get(plan).copy(pin = pin.copy(builds = null)))

            val (_, rows) =
                rig.written {
                    rig.ctx.itemCommandService
                        .repin(plan.id, null)
                        .orFail()
                }

            val pinned = rows.filter { it.type == "item.pinned" }
            assertEquals(1, pinned.size, "one row: ${rows.map { it.type }}")
            val row = pinned.single()
            assertEquals("repin", row.payload()["reason"]?.jsonPrimitive?.content)
            val from = row.payload()["from"]!!.jsonObject
            val to = row.payload()["to"]!!.jsonObject
            assertEquals(true, from.flag("builds"), "a NULL builds reads as true: $from")
            assertEquals(false, to.flag("builds"), "$to")
            for (key in listOf("server", "root", "key", "layer")) {
                assertEquals(from[key], to[key], "$key is equal across sides")
            }
        }

    @Test
    fun `S2 a create row records builds on the to side and none on from`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            suspend fun createRow(
                type: String,
                traits: List<String> = emptyList()
            ): JsonObject {
                val (_, rows) = rig.written { rig.svc("Item $type $traits", type = type, traits = traits) }
                val row = rows.single { it.type == "item.pinned" }
                assertEquals("create", row.payload()["reason"]?.jsonPrimitive?.content)
                assertEquals(JsonNull, row.payload()["from"], "a create has no previous pin")
                return row.payload()["to"]!!.jsonObject
            }

            assertEquals(true, createRow("test-item").flag("builds"))
            assertEquals(false, createRow("plan-only").flag("builds"))
            assertEquals(false, createRow("test-item", listOf("no-build")).flag("builds"))
        }

    // ------------------------------------------------------------------ T9-T10 claim select and empty answers

    @Test
    fun `T9 claim select skips a queue container, claims it with includeParents, and claim by itemId claims either`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val parent = rig.svc("Parent")
            val child = rig.svc("Child", parent)
            rig.setRole(child, Role.WORK)

            val skipped = rig.claimSelect(""""type":"test-item"""")
            assertEquals(false, skipped.flag("claimed"), "the only queue match is a container: $skipped")
            assertNull(rig.get(parent).claimedBy)

            val claimed = rig.claimSelect(""""type":"test-item","includeParents":true""")
            assertEquals(true, claimed.flag("claimed"), "$claimed")
            assertEquals(parent.id.toString(), claimed["itemId"]?.jsonPrimitive?.content)
            assertNotNull(rig.get(parent).claimedBy)
        }

    @Test
    fun `T9 claim by itemId still claims a queue container`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val parent = rig.svc("Parent")
            rig.svc("Child", parent)

            val element = rig.claimById(parent)

            assertEquals(true, element.flag("claimed"), "$element")
            assertNotNull(rig.get(parent).claimedBy)
        }

    @Test
    fun `T10 when every match is a container next is empty with no reason and claim select reports queue_empty`(): Unit =
        irApp(db.db, dir, yaml = STARTABLE_YAML) { rig ->
            val parent = rig.svc("Parent")
            val child = rig.svc("Child", parent)
            rig.setRole(child, Role.WORK)

            val r = select("""{"select":"next","limit":200}""")
            assertEquals(200, r.status, r.raw)
            assertEquals(emptyList(), r.items.titles())
            assertEquals(0, r.total)
            assertNull(r.reason(), "a container is not a match, so there is no reason")

            val element = rig.claimSelect("")
            assertEquals(false, element.flag("claimed"), "$element")
            assertFalse("reason" in element, "queue_empty carries no reason: $element")
            assertFalse("excluded" in element, "queue_empty carries no exclusion counts: $element")
        }
}
