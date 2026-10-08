package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.config.ManagePlanDocumentsTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.OutsideUnitPolicy
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.ADMIN_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.WRITE_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.makeWriteAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Write paths that no other test runs under the production [OutsideUnitPolicy.FAIL]: `complete_tree`,
 * `manage_plan_documents` stash (MCP) and `PUT /roots/{rootId}/plans/{slug}` (REST), `claim_item` claim and
 * release, and the operator lease force-release `DELETE /resources/leases/{key}`. Each runs over a REAL
 * [ServerComposition] whose database refuses any store write outside a unit of work, so a write site that
 * escapes its unit fails here instead of only in production. Fixtures are seeded inside a unit.
 *
 * Assertions are deliberately limited to "the call succeeds and the row is persisted"; the behaviour of each
 * path is covered by its own tests under the test fixture's IMPLICIT policy.
 */
class FailPolicyWritePathsTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod(OutsideUnitPolicy.FAIL)

    private fun buildComposition(tempDir: Path): CompositionResult {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        Files.write(configDir.resolve("config.yaml"), "work_item_schemas: {}\n".toByteArray(Charsets.UTF_8))
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        return ServerComposition(
            appConfig = appConfig,
            databaseManager = db.databaseManager,
            shutdownCoordinator = ShutdownCoordinator()
        ).build()
    }

    private fun Application.configureProductionApp(composition: CompositionResult) {
        val authConfig: ApiAuthConfig = makeWriteAuthConfig()
        install(ContentNegotiation) { json(McpJson) }
        installRestApiRoutes(
            apiConfig = authConfig,
            eventBus = null,
            effectiveProvider = composition.toolContext.repositoryProvider,
            apiTokenEntries =
                (authConfig as ApiAuthConfig.Bearer).tokens.mapValues { (_, principal) ->
                    BearerTokenStore.TokenEntry(principal, expiresAt = null)
                },
            allowQueryToken = false,
            serverName = "fail-policy-write-paths",
            serverVersion = "test",
            actorAuthEnabled = composition.actorAuthEnabled,
            noteSchemaService = composition.noteSchemaService,
            toolContext = composition.toolContext,
            degradedModePolicy = composition.degradedModePolicy,
            idempotencyCache = composition.idempotencyCache,
        )
    }

    /** Seeds a fixture inside one write unit, so the FAIL policy accepts it. */
    private suspend fun <T> CompositionResult.seed(
        op: String,
        block: suspend () -> T
    ): T =
        when (val outcome = toolContext.unitOfWork.write(op) { Outcome.Ok(block()) }) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("fixture seed '$op' failed: ${outcome.error.message}")
        }

    private suspend fun CompositionResult.seedItem(
        title: String,
        parentId: UUID? = null
    ): WorkItem =
        seed("FailPolicy.seedItem") {
            toolContext.repositoryProvider.workItemRepository().create(
                WorkItem(title = title, parentId = parentId, depth = if (parentId == null) 0 else 1)
            )
        }

    private fun assertSuccess(result: JsonElement) {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "expected a successful call: $result")
    }

    @Test
    fun `complete_tree commits under the FAIL policy`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val composition = buildComposition(tempDir)
            val root = composition.seedItem("FAIL complete_tree root")
            val child = composition.seedItem("FAIL complete_tree child", parentId = root.id)

            val result =
                CompleteTreeTool().execute(
                    buildJsonObject {
                        put("rootId", root.id.toString())
                        put("trigger", "complete")
                    },
                    composition.toolContext
                )

            assertSuccess(result)
            val stored =
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .getById(child.id)
            assertEquals(Role.TERMINAL, assertNotNull(stored).role, "the completed child must be persisted: $result")
        }

    @Test
    fun `manage_plan_documents stash commits under the FAIL policy`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val composition = buildComposition(tempDir)
            val root = composition.seedItem("FAIL plan-doc MCP root")

            val result =
                ManagePlanDocumentsTool().execute(
                    buildJsonObject {
                        put("operation", "stash")
                        put("rootId", root.id.toString())
                        put("slug", "fail-policy-mcp")
                        put("body", "A plan document stashed under the FAIL policy.")
                    },
                    composition.toolContext
                )

            assertSuccess(result)
            assertNotNull(
                composition.toolContext.repositoryProvider
                    .planDocumentRepository()
                    .get(root.id, "fail-policy-mcp"),
                "the stashed document must be persisted: $result"
            )
        }

    @Test
    fun `PUT plans commits under the FAIL policy`(
        @TempDir tempDir: Path
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            val root = runBlocking { composition.seedItem("FAIL plan-doc REST root") }
            application { configureProductionApp(composition) }

            val response =
                client.put("/api/v1/roots/${root.id}/plans/fail-policy-rest") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("A plan document pushed under the FAIL policy.")
                }

            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            assertNotNull(
                runBlocking {
                    composition.toolContext.repositoryProvider
                        .planDocumentRepository()
                        .get(root.id, "fail-policy-rest")
                },
                "the pushed document must be persisted"
            )
        }

    @Test
    fun `claim_item claim and release commit under the FAIL policy`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val composition = buildComposition(tempDir)
            val item = composition.seedItem("FAIL claim item")
            val items = composition.toolContext.repositoryProvider.workItemRepository()

            fun params(key: String): JsonObject =
                buildJsonObject {
                    put(key, buildJsonArray { add(buildJsonObject { put("itemId", item.id.toString()) }) })
                    put(
                        "actor",
                        buildJsonObject {
                            put("id", "fail-policy-agent")
                            put("kind", "subagent")
                        }
                    )
                    put("requestId", UUID.randomUUID().toString())
                }

            val claimed = ClaimItemTool().execute(params("claims"), composition.toolContext)
            assertSuccess(claimed)
            assertEquals("fail-policy-agent", assertNotNull(items.getById(item.id)).claimedBy, "the claim must be persisted: $claimed")

            val released = ClaimItemTool().execute(params("releases"), composition.toolContext)
            assertSuccess(released)
            assertNull(assertNotNull(items.getById(item.id)).claimedBy, "the release must be persisted: $released")
        }

    @Test
    fun `DELETE resources leases key commits under the FAIL policy`(
        @TempDir tempDir: Path
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            val leases = composition.toolContext.repositoryProvider.resourceLeaseRepository()
            runBlocking {
                val holder = composition.seedItem("FAIL lease holder")
                composition.seed(
                    "FailPolicy.seedLease"
                ) { leases.acquireAll(holder.id, "fail-policy-agent", listOf("fail-policy-res" to 600)) }
                assertEquals(1, leases.findActiveByKeys(listOf("fail-policy-res")).size, "sanity: the lease is held")
            }
            application { configureProductionApp(composition) }

            val response =
                client.delete("/api/v1/resources/leases/fail-policy-res") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            assertTrue(
                runBlocking { leases.findActiveByKeys(listOf("fail-policy-res")) }.isEmpty(),
                "the force-release must be persisted"
            )
        }
}
