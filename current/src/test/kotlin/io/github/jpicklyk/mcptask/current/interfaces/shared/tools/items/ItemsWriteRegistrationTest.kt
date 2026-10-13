package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import io.github.jpicklyk.mcptask.current.parity.ParityCoverage
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.ProductionParity
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ToolSurfaceChecks
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 5462835d) for S18 and AC1 / AC8 of the frozen test-plan: the registry holds
 * `items_write` with ops create, update, delete (capability write-items, knowledge profile, delete destructive), `manage_items` is
 * gone from the MCP tool list, the production parity catalog covers the registry, the surface stays within the budget (target of
 * 3,000 chars for this tool in the knowledge profile, D8), goldens exist, and the 3.x REST item write routes answer 404 while
 * `POST /items/{id}/advance` still exists.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsWriteRegistrationTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val registry = ToolRegistry(buildToolSpecs())

    @Test
    fun `S18 the registry holds items_write with its ops, their capability, profile and destructive flags`() {
        val spec = assertNotNull(registry["items_write"])
        assertTrue(spec is OpsToolSpec<*>)
        // Q24 (934b25e4) adds repin.
        assertEquals(listOf("create", "update", "delete", "create_tree", "complete_tree", "repin"), spec.ops.map { it.name })
        for (op in spec.ops.filter { it.name != "complete_tree" }) {
            assertEquals(ApiCapability.WRITE_ITEMS, op.capability, op.name)
            // Amendment U23: create_tree and repin are orchestration-only; create/update/delete stay in the knowledge profile.
            val expected = if (op.name == "create_tree" || op.name == "repin") orchestration() else knowledge()
            assertEquals(expected, op.profiles, op.name)
        }
        // Q21: complete_tree is N transitions through the advance pipeline (capability advance, orchestration profile).
        val completeTree = spec.ops.single { it.name == "complete_tree" }
        assertEquals(ApiCapability.ADVANCE, completeTree.capability)
        assertEquals(orchestration(), completeTree.profiles)
        assertEquals(listOf(false, false, true, false, false, false), spec.ops.map { it.destructive }, "only delete is destructive")
    }

    @Test
    fun `S18 manage_items is gone from the MCP tool list while other tools remain`() {
        val names = ToolRegistry(buildToolSpecs()).tools.map { it.name }
        // Control: the served surface still holds other tools (Q32 removed the 3.x tool list itself).
        val served = names
        assertTrue("config" in served && "items_write" in served, "control: the served surface still holds other tools: $served")
        assertTrue("manage_items" !in names, "manage_items must not be registered: $names")
        assertTrue("create_work_tree" !in names, "create_work_tree must not be registered (Q21): $names")
        assertTrue("complete_tree" !in names, "complete_tree must not be registered (Q21): $names")
    }

    @Test
    fun `S18 the production parity catalog covers the registry and names no stale member`() {
        assertEquals(emptyList(), ParityCoverage.missing(registry, ProductionParity.scenarios, ProductionParity.exemptions))
        assertEquals(emptyList(), ParityCoverage.stale(registry, ProductionParity.scenarios))
        val itemsWriteMembers = ParityCoverage.members(registry).filter { it.first == "items_write" }
        assertEquals(
            listOf(
                "items_write" to "create",
                "items_write" to "update",
                "items_write" to "delete",
                "items_write" to "create_tree",
                "items_write" to "complete_tree",
                "items_write" to "repin"
            ),
            itemsWriteMembers
        )
    }

    @Test
    fun `S18 the tool surface stays within the budget and documentation checks`() {
        assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(registry))
        assertEquals(emptyList(), ToolSurfaceChecks.docViolations(registry))
        val size = assertNotNull(ToolSurfaceChecks.sizes(registry, ToolProfile.KNOWLEDGE)["items_write"])
        // Q21 D7 raised the target to 6000 with create_tree (Q18 D8 was 3000).
        assertTrue(size in 1..6_000, "items_write knowledge surface is $size chars; the Q21 D7 target is at most 6000")
    }

    @Test
    fun `S18 the tool goldens exist for both profiles`() {
        for (profile in listOf("knowledge", "orchestration")) {
            val resource = ItemsWriteRegistrationTest::class.java.getResource("/golden/tools/items_write.$profile.json")
            assertNotNull(resource, "golden for $profile")
        }
    }

    // ------------------------------------------------------------------ AC1 REST removal

    @Test
    fun `AC1 POST PATCH and DELETE on api v1 items answer 404 or 405 and change nothing while the advance route still exists`() {
        val admin = ParityToken("iw-reg-admin", setOf(ApiCapability.ADMIN))
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            application { installParityRestApp(rig, parityAuthConfig(admin), registry) }
            val item = kotlinx.coroutines.runBlocking { IwKit(rig).make("Exists") }
            val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                header(HttpHeaders.Authorization, "Bearer ${admin.raw}")
                contentType(ContentType.Application.Json)
            }
            val before = IwKit(rig).snapshot()
            val post =
                client.post("/api/v1/items") {
                    auth()
                    setBody("""{"title":"x","type":"test-item"}""")
                }
            val patch =
                client.patch("/api/v1/items/${item.id}") {
                    auth()
                    setBody("""{"title":"y"}""")
                }
            val del = client.delete("/api/v1/items/${item.id}") { auth() }
            // Ruling C1 (fix-decisions-r1, item 5462835d) amends AC1: a removed REST write method never succeeds or mutates;
            // it answers 404 when no route serves the path and 405 when the path still serves GET (GET /items remains).
            val statuses = listOf(post.status.value, patch.status.value, del.status.value)
            assertTrue(
                statuses.all { it == 404 || it == 405 },
                "statuses of POST /items, PATCH /items/{id}, DELETE /items/{id} must each be 404 or 405 (AC1, C1): $statuses"
            )
            assertEquals(before, IwKit(rig).snapshot(), "the removed write routes changed no table (AC1, C1)")
            val advance =
                client.post("/api/v1/ops/advance") {
                    auth()
                    setBody("""{"ops":[{"op":"transition","itemId":"${item.id}","trigger":"start"}]}""")
                }
            assertNotEquals(404, advance.status.value, "control: POST /ops/advance is routed for an existing item")
            assertEquals(1, kotlinx.coroutines.runBlocking { IwKit(rig).all().size }, "the 404 routes did not touch the item")
        }
    }
}
