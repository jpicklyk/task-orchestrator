package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.service.CLAIM_T
import io.github.jpicklyk.mcptask.current.application.service.ClaimRig
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.application.service.ok
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.P7Raw
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.findByFilters
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.StatusGraphDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.StatusGraphBuilder
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.AdvanceTwin
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.CfgKit
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.effectiveOp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.list
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.obj
import io.github.jpicklyk.mcptask.current.parity.AdvanceParityScenarios
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `test-item` is an ordinary auto type; `page` is lifecycle-free. */
private const val TOOLS_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    notes: []\n" +
        "  page:\n" +
        "    lifecycle: none\n" +
        "    notes: []\n"

private val TOOLS_REGISTRY = ToolRegistry(buildToolSpecs())

/**
 * Independent tests (test-author seat, item 9e5707b3 / Q31b) for how the tool surfaces treat a lifecycle-free item:
 * scenarios S2 (the read view), S5, S8 and S9 of the frozen test-plan and their probes.
 *
 * Oracles (frozen task-scope Build 6, 8, 10 and plan 3.12): a lifecycle-free item has no `role` member in a read view and a
 * claim or heartbeat on it is `invalid_transition` with detail `{itemId, fromRole: "none", trigger: "claim", allowed: []}`,
 * writes nothing and releases no prior claim of the caller (the service answers `ClaimResult.LifecycleFree`); a BLOCKS edge
 * (after normalization) with a lifecycle-free endpoint is `invalid_request` with a field violation naming the endpoint field,
 * for `deps_write` and for `create_tree`, and stores nothing, while a RELATES_TO edge is stored; the status graph emits a
 * lifecycle-free type with `lifecycleMode: "none"` and no transitions.
 *
 * Absence and rejection assertions are paired with a control through the same call shape that does write.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class LifecycleFreeToolsTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private suspend fun EventLogRig.svc(
        title: String,
        parent: WorkItem? = null,
        type: String = "test-item",
        id: UUID = UUID.randomUUID()
    ): WorkItem = ctx.itemCommandService.create(ItemCreateCommand(id = id, parentId = parent?.id, title = title, type = type)).orFail()

    private suspend fun EventLogRig.get(item: WorkItem): WorkItem = checkNotNull(raw.workItemRepository().getById(item.id))

    private fun uid(n: Int): UUID = UUID.fromString("c%07d-0000-4000-8000-%012d".format(n, n))

    // ---------------------------------------------------------------------------------------------
    // S2 - the read view carries no role for a lifecycle-free item
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 get and list omit the role of a lifecycle-free item and keep it for a queue item`() =
        irApp(db.db, dir, yaml = TOOLS_YAML) { rig ->
            val page = rig.svc("Page", null, "page")
            val plain = rig.svc("Plain")

            val pageGet = select("""{"select":"get","itemId":"${page.id}"}""")
            val plainGet = select("""{"select":"get","itemId":"${plain.id}"}""")
            val list = select("""{"select":"list"}""")

            assertEquals(200, pageGet.status, pageGet.raw)
            assertFalse("role" in pageGet.data, "a page has no role: ${pageGet.data}")
            assertEquals("Page", pageGet.data.str("title"), "control: the rest of the view is there")
            assertEquals("queue", plainGet.data.str("role"), "control: a queue item shows its role")
            val listed = list.items.associateBy { it.str("title") }
            assertEquals(setOf("Page", "Plain"), listed.keys)
            assertFalse("role" in listed.getValue("Page"), "listed page: ${listed.getValue("Page")}")
            assertEquals("queue", listed.getValue("Plain").str("role"))
        }

    // ---------------------------------------------------------------------------------------------
    // S5 - the status graph
    // ---------------------------------------------------------------------------------------------

    private fun typeEntry(
        graph: JsonObject,
        type: String
    ): JsonObject =
        graph
            .getValue("types")
            .jsonArray
            .map { it.jsonObject }
            .single { it.getValue("type").jsonPrimitive.content == type }

    @Test
    fun `S5 the status graph builder emits a none type with lifecycleMode none and no transitions`() {
        val none = WorkItemSchema(type = "golden-none", lifecycleMode = LifecycleMode.NONE, notes = emptyList())
        val auto = WorkItemSchema(type = "golden-auto", notes = emptyList())

        val encoded =
            McpJson.encodeToString(
                StatusGraphDto.serializer(),
                StatusGraphBuilder().buildStatusGraph(
                    linkedMapOf(
                        "golden-none" to none,
                        "golden-auto" to auto
                    )
                )
            )

        val graph = Json.parseToJsonElement(encoded).jsonObject
        val noneEntry = typeEntry(graph, "golden-none")
        assertEquals("none", noneEntry.getValue("lifecycleMode").jsonPrimitive.content)
        assertEquals(emptyMap(), noneEntry.getValue("transitions").jsonObject.toMap(), "a lifecycle-free type has no transitions")
        val autoEntry = typeEntry(graph, "golden-auto")
        assertEquals("auto", autoEntry.getValue("lifecycleMode").jsonPrimitive.content)
        assertTrue(autoEntry.getValue("transitions").jsonObject.isNotEmpty(), "control: an auto type keeps its transitions")
    }

    @Test
    fun `S5 config effective reports the none type in its status graph with no transitions`(): Unit =
        runBlocking {
            val k = CfgKit(EventLogRig.build(db.db, dir, TOOLS_YAML))

            val view = k.call(effectiveOp()).okResult()

            val types = view.obj("statusGraph").list("types")
            val page = types.single { it.getValue("type").jsonPrimitive.content == "page" }
            assertEquals("none", page.getValue("lifecycleMode").jsonPrimitive.content)
            assertEquals(emptyMap(), page.obj("transitions").toMap())
            val plain = types.single { it.getValue("type").jsonPrimitive.content == "test-item" }
            assertEquals("auto", plain.getValue("lifecycleMode").jsonPrimitive.content, "control")
            assertTrue(plain.obj("transitions").isNotEmpty(), "control: the auto type keeps its transitions")
            assertTrue(view.list("schemas").any { it.getValue("type").jsonPrimitive.content == "page" }, "the schema itself is listed")
        }

    // ---------------------------------------------------------------------------------------------
    // S8 - claims
    // ---------------------------------------------------------------------------------------------

    private fun ops(vararg elements: String) = """{"ops":[${elements.joinToString(",")}]}"""

    private fun claim(id: UUID) = """{"op":"claim","itemId":"$id"}"""

    private fun heartbeat(id: UUID) = """{"op":"heartbeat","itemId":"$id"}"""

    /**
     * Seeds a row straight into the twin's database with fixed content, so both legs hold equal rows (an item created through
     * the command service is pinned to a generated config version id, which differs per database). A null [role] is paired
     * with lifecycle 'none' as the V26 relation requires, and a role-bearing row carries a NULL lifecycle.
     */
    private fun EventLogRig.addRaw(
        id: UUID,
        title: String,
        role: String?,
        type: String
    ) {
        val ts = P7Raw.canon(CLAIM_T)
        P7Raw.exec(
            db.jdbcUrl,
            "INSERT INTO work_items (id, title, role, lifecycle, type, depth, root_id, created_at, modified_at, role_changed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id,
            title,
            role,
            if (role == null) "none" else null,
            type,
            0,
            id,
            ts,
            ts,
            ts
        )
    }

    private fun AdvanceTwin.load(id: UUID): WorkItem = runBlocking { checkNotNull(rig.raw.workItemRepository().getById(id)) { "item $id" } }

    @Test
    fun `S8 claiming a lifecycle-free item is invalid_transition on both surfaces, claims nothing and keeps the caller's prior claim`() {
        val held = uid(1)
        val page = uid(2)
        val control = uid(3)
        AdvanceTwin(
            configYaml = TOOLS_YAML,
            seed = {
                addRaw(held, "Held", "queue", "test-item")
                addRaw(page, "Page", null, "page")
                addRaw(control, "Control", "queue", "test-item")
            }
        ).use { twin ->
            val agent = AdvanceParityScenarios.agent
            assertEquals(
                "true",
                twin
                    .call(agent, ops(claim(held)))
                    .element(0)
                    .getValue("claimed")
                    .jsonPrimitive.content,
                "fixture: the agent holds one item"
            )
            val prior = twin.load(held)
            assertNotNull(prior.claimedBy, "fixture: the claim is held")

            val result = twin.call(agent, ops(claim(page)))

            assertEquals("invalid_transition", result.elementCode(0))
            val detail = result.elementDetail(0)
            assertEquals(page.toString(), detail.getValue("itemId").jsonPrimitive.content)
            assertEquals("none", detail.getValue("fromRole").jsonPrimitive.content)
            assertEquals("claim", detail.getValue("trigger").jsonPrimitive.content)
            assertTrue((detail["allowed"]?.jsonArray ?: JsonArray(emptyList())).isEmpty())
            val after = twin.load(page)
            assertNull(after.claimedBy, "the page is not claimed")
            assertNull(after.role)
            assertEquals(prior.claimedBy, twin.load(held).claimedBy, "the caller's prior claim is not released")
            assertEquals(prior.claimExpiresAt, twin.load(held).claimExpiresAt)
            assertEquals(
                "true",
                twin
                    .call(agent, ops(claim(control)))
                    .element(0)
                    .getValue("claimed")
                    .jsonPrimitive.content,
                "control: an ordinary item still claims"
            )
        }
    }

    @Test
    fun `S8 probe - a heartbeat on an unheld lifecycle-free item is rejected and claims nothing`() {
        val page = uid(11)
        AdvanceTwin(configYaml = TOOLS_YAML, seed = { addRaw(page, "Page", null, "page") }).use { twin ->
            val result = twin.call(AdvanceParityScenarios.agent, ops(heartbeat(page)))

            // Arbitration (see the test-manifest): the plan gives no oracle for which rejection a heartbeat of an item nobody holds
            // reports. not_claim_holder is the documented answer for any unheld item (S12 of the advance tests) and invalid_transition
            // is what the claim path answers for a lifecycle-free row; both are rejections that must leave the row untouched.
            assertTrue(result.elementCode(0) in setOf("not_claim_holder", "invalid_transition"), "element: ${result.element(0)}")
            assertEquals(
                page.toString(),
                result
                    .elementDetail(0)
                    .getValue("itemId")
                    .jsonPrimitive.content
            )
            val after = twin.load(page)
            assertNull(after.claimedBy, "a heartbeat never takes a lifecycle-free item over")
            assertNull(after.claimExpiresAt)
            assertNull(after.role)
        }
    }

    @Test
    fun `S8 the claim service answers LifecycleFree for a stored lifecycle-free row and releases no prior claim`(): Unit =
        runBlocking {
            val clock = SettableClock(CLAIM_T)
            val rig = ClaimRig(db.db, clock)
            val pageId = UUID.randomUUID()
            val ts = P7Raw.canon(CLAIM_T)
            P7Raw.exec(
                db.jdbcUrl,
                "INSERT INTO work_items (id, title, role, lifecycle, depth, created_at, modified_at, role_changed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                pageId,
                "Stored page",
                null,
                "none",
                0,
                ts,
                ts,
                ts
            )
            val held = rig.seed("Held")
            val plain = rig.seed("Plain")
            assertIs<ClaimResult.Success>(rig.service.claim(held.id, "agent-a", 600).ok(), "fixture: agent-a holds one item")

            val (result, rows) = rig.written { rig.service.claim(pageId, "agent-a", 600).ok() }

            val free = assertIs<ClaimResult.LifecycleFree>(result)
            assertEquals(pageId, free.itemId)
            assertEquals("agent-a", rig.reload(held.id).claimedBy, "the prior claim is kept")
            assertNull(rig.reload(pageId).claimedBy, "the page is not claimed")
            assertTrue(rows.none { it.type.startsWith("claim.") }, "no claim event for a rejected claim: ${rows.map { it.type }}")
            assertIs<ClaimResult.Success>(rig.service.claim(plain.id, "agent-b", 600).ok(), "control: an ordinary item claims")
        }

    // ---------------------------------------------------------------------------------------------
    // S9 - BLOCKS edges and lifecycle-free endpoints
    // ---------------------------------------------------------------------------------------------

    private fun JsonObject.firstField(): JsonObject =
        getValue("detail")
            .jsonObject
            .getValue("fields")
            .jsonArray
            .first()
            .jsonObject

    private suspend fun EventLogRig.depsWrite(body: String): JsonObject =
        callMcp(ctx, TOOLS_REGISTRY, null, "deps_write", Json.parseToJsonElement(body).jsonObject).structured

    private suspend fun EventLogRig.allEdges(): List<Dependency> =
        raw
            .workItemRepository()
            .findByFilters()
            .items
            .flatMap { raw.dependencyRepository().findByItemId(it.id) }
            .distinctBy { it.id }

    @Test
    fun `S9 deps_write refuses a BLOCKS edge touching a page on either side and stores nothing, RELATES_TO is stored`(): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir, TOOLS_YAML)
            val root = rig.svc("Root")
            val plain = rig.svc("Plain", root)
            val other = rig.svc("Other", root)
            val page = rig.svc("Page", root, "page")
            val page2 = rig.svc("Page two", root, "page")

            val intoPage = rig.depsWrite("""{"ops":[{"op":"create","from":"${plain.id}","to":"${page.id}"}]}""")
            val outOfPage = rig.depsWrite("""{"ops":[{"op":"create","from":"${page.id}","to":"${plain.id}"}]}""")
            val pageToPage = rig.depsWrite("""{"ops":[{"op":"create","from":"${page.id}","to":"${page2.id}","type":"blocks"}]}""")

            val toError = intoPage.errorAt()
            assertEquals("invalid_request", toError.getValue("code").jsonPrimitive.content, "$intoPage")
            val toField = toError.firstField()
            assertTrue(
                toField
                    .getValue("field")
                    .jsonPrimitive.content
                    .contains("to", ignoreCase = true),
                "names the to field: $toField"
            )
            assertTrue(
                toField
                    .getValue("received")
                    .jsonPrimitive.content
                    .replace("-", "")
                    .startsWith(page.id.toString().take(8)),
                "received is the page id: $toField"
            )
            val fromError = outOfPage.errorAt()
            assertEquals("invalid_request", fromError.getValue("code").jsonPrimitive.content, "$outOfPage")
            val fromField = fromError.firstField()
            assertTrue(
                fromField
                    .getValue("field")
                    .jsonPrimitive.content
                    .contains("from", ignoreCase = true),
                "names the from field: $fromField"
            )
            assertEquals(
                "invalid_request",
                pageToPage
                    .errorAt()
                    .getValue("code")
                    .jsonPrimitive.content,
                "$pageToPage"
            )
            assertEquals(0, rig.allEdges().size, "nothing was stored by the three refusals")

            val ok1 = rig.depsWrite("""{"ops":[{"op":"create","from":"${plain.id}","to":"${page.id}","type":"relates_to"}]}""")
            val ok2 = rig.depsWrite("""{"ops":[{"op":"create","from":"${plain.id}","to":"${other.id}"}]}""")
            ok1.okResult()
            ok2.okResult()
            assertEquals(
                setOf(DependencyType.RELATES_TO, DependencyType.BLOCKS),
                rig.allEdges().map { it.type }.toSet(),
                "control: RELATES_TO to a page and BLOCKS between ordinary items are stored"
            )
            assertEquals(2, rig.allEdges().size)
        }

    @Test
    fun `S9 create_tree refuses a BLOCKS dependency on a lifecycle-free child and writes nothing, RELATES_TO is stored`(): Unit =
        runBlocking {
            val k = IwKit(EventLogRig.build(db.db, dir, TOOLS_YAML))
            val before = k.tableCounts()

            val refused =
                k.callIn(
                    ToolProfile.ORCHESTRATION,
                    twTree(
                        twRoot("Root"),
                        listOf(twChild("c1"), twChild("pg", type = "page")),
                        listOf(twDep("c1", "pg"))
                    )
                )

            val err = refused.envelope.body.errorAt()
            assertEquals("invalid_request", err.getValue("code").jsonPrimitive.content, "$err")
            assertTrue(
                err
                    .firstField()
                    .getValue("field")
                    .jsonPrimitive.content
                    .contains("to", ignoreCase = true),
                "names the to field: $err"
            )
            assertEquals(before, k.tableCounts(), "the whole tree was rejected and nothing was written")

            val accepted =
                k.callIn(
                    ToolProfile.ORCHESTRATION,
                    twTree(
                        twRoot("Root"),
                        listOf(twChild("c1"), twChild("pg", type = "page")),
                        listOf(twDep("c1", "pg", type = "relates_to"))
                    )
                )
            val ok = accepted.envelope.body.okResult()
            assertEquals(1, ok.getValue("deps").jsonArray.size, "control: a RELATES_TO dependency to the page is stored")
            assertNull(k.item(ok.refId("pg"))?.role, "the page was created without a role")
        }

    @Test
    fun `S9 probe - an is_blocked_by edge normalizing to BLOCKS is refused when it touches a lifecycle-free item`(): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir, TOOLS_YAML)
            val plain = rig.svc("Plain")
            val page = rig.svc("Page", null, "page")
            val service = rig.ctx.dependencyCommandService

            val refused =
                service.create(
                    listOf(Dependency(fromItemId = plain.id, toItemId = page.id, type = DependencyType.IS_BLOCKED_BY)),
                    caller = null
                )

            val error = assertIs<Outcome.Err>(refused)
            assertEquals("invalid_request", error.error.code.wire)
            assertEquals(0, rig.allEdges().size, "nothing was stored")
            val other = rig.svc("Other")
            assertIs<Outcome.Ok<*>>(
                service.create(
                    listOf(Dependency(fromItemId = plain.id, toItemId = other.id, type = DependencyType.IS_BLOCKED_BY)),
                    caller = null
                ),
                "control: the same shape between ordinary items is accepted"
            )
            assertEquals(1, rig.allEdges().size)
        }
}
