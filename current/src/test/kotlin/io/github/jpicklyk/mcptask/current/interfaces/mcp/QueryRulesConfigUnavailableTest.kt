package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.port.ProjectConfigStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.config.QueryRulesTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Independently authored (blind test author) against the frozen `task-scope-addendum` note on
 * item `840e700a` (A3) -- scenario S9: "item mode + per-root read throws
 * PerRootConfigUnavailableException -> adapter isError, structuredContent.error{kind:transient,
 * code:config_unavailable}; rootId+key mode same state succeeds." Oracle: `EffectiveConfigResolver.kt:26-28`
 * (cited in `test-plan`) plus `McpToolAdapterConfigUnavailableTest`'s already-verified adapter
 * catch shape (`errorKind`/`kind` = "transient", `errorCode`/`code` = "config_unavailable", no
 * `retryAfterMs`).
 *
 * Harness: mirrors [McpToolAdapterConfigUnavailableTest]'s real [Server]/[Client] pair over
 * [ChannelTransport.createLinkedPair], but drives the REAL [QueryRulesTool] (not an anonymous
 * always-throwing tool) registered via [McpToolAdapter.registerToolWithServer] -- item mode
 * (`itemId`+`noteKey`) forces `ToolExecutionContext.resolveSchema(item)` to perform a per-root
 * config read, which is made to fail cold via a [ProjectConfigStore] wrapper whose
 * `getFingerprint`/`get` return `Result.Error`, mirroring [ConfigUnavailableRoutesTest] /
 * [ManageNotesConfigUnavailableTest]'s "own copy per file" `FailableProjectConfigRepository`
 * pattern (this item's file-ownership rule forbids a shared harness file). The second half of S9
 * ("rootId+key mode same state succeeds") is exercised against the SAME failing provider to prove
 * rootId+key mode never touches per-root config at all.
 */
class QueryRulesConfigUnavailableTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var server: Server
    private lateinit var client: Client
    private lateinit var adapter: McpToolAdapter

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            server =
                Server(
                    serverInfo = Implementation(name = "test-server", version = "1.0.0"),
                    options = inMemoryTestServerOptions(),
                )
            adapter = McpToolAdapter()
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            client =
                Client(
                    clientInfo = Implementation(name = "test-client", version = "1.0.0"),
                    options = ClientOptions(capabilities = ClientCapabilities()),
                )
            server.createSession(serverTransport)
            client.connect(clientTransport)
        }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            closeInMemoryPair(client, server)
        }

    private fun buildFailingContext(): Triple<ToolExecutionContext, WorkItem, FailableProjectConfigRepository> {
        val sqlite = db.repositoryProvider()

        val (root, item) =
            runBlocking {
                val r = sqlite.workItemRepository().create(WorkItem(title = "S9 Root", depth = 0))!!
                // A real row must exist so getFingerprint succeeds with a non-null value first --
                // resolve() only reaches the .get() read (the one failGet intercepts) once the
                // fingerprint check has NOT short-circuited on Success(null)/absence. Mirrors
                // ConfigUnavailableRoutesTest's S11 / ItemSchemaRouteTest's S11b 503 fixtures.
                sqlite.projectConfigRepository().upsert(r.id, "work_item_schemas:\n  s9-type:\n    notes: []\n")
                val i =
                    sqlite
                        .workItemRepository()
                        .create(
                            WorkItem(
                                title = "S9 item",
                                type = "s9-type",
                                role = Role.QUEUE,
                                parentId = r.id,
                                rootId = r.id,
                                depth = 1,
                            ),
                        )!!
                r to i
            }

        val failable = FailableProjectConfigRepository(sqlite.projectConfigRepository())
        failable.failGet = true
        val provider = FailableRepositoryProvider(sqlite, failable)
        val context =
            ToolExecutionContext(
                provider,
                perRootConfigService = PerRootConfigService(provider.projectConfigRepository()),
                unitOfWork = db.unitOfWork(),
            )
        return Triple(context, item, failable)
    }

    @Test
    fun `S9 item mode with a failing per-root config read fails the whole call as transient config_unavailable via the adapter`(): Unit =
        runBlocking {
            val (context, item, _) = buildFailingContext()
            adapter.registerToolWithServer(server, QueryRulesTool(), context)

            val result =
                client.callTool(
                    name = "query_rules",
                    arguments =
                        mapOf(
                            "operation" to "get",
                            "itemId" to item.id.toString(),
                            "noteKey" to "some-note",
                        ),
                )

            assertEquals(true, result.isError, "expected an error result: $result")
            val structured = assertNotNull(result.structuredContent, "error response must carry structuredContent")
            val error = assertNotNull(structured["error"]?.jsonObject, "structuredContent must contain the error object")
            assertEquals("transient", error["kind"]?.jsonPrimitive?.content)
            assertEquals("config_unavailable", error["code"]?.jsonPrimitive?.content)
            assertNull(error["retryAfterMs"], "no retryAfterMs on config_unavailable -- only SHEDDING carries one")
        }

    @Test
    fun `S9 rootId plus key mode succeeds against the SAME failing per-root config provider, since it never reads config`(): Unit =
        runBlocking {
            val (context, item, _) = buildFailingContext()
            // Stash the rule via the plan document repository (unaffected by the projectConfigRepository
            // failure) -- rootId+key mode must succeed without ever calling getFingerprint/get on the
            // failing project-config wrapper.
            runBlocking {
                context.repositoryProvider.planDocumentRepository().stash(
                    item.rootId!!,
                    "rule/direct-mode",
                    "Direct mode body."
                )
            }
            adapter.registerToolWithServer(server, QueryRulesTool(), context)

            val result =
                client.callTool(
                    name = "query_rules",
                    arguments =
                        mapOf(
                            "operation" to "get",
                            "rootId" to item.rootId!!.toString(),
                            "key" to "direct-mode",
                        ),
                )

            assertEquals(false, result.isError == true, "rootId+key mode must succeed even while per-root config reads are cold: $result")
        }
}

/**
 * Wraps a real [ProjectConfigStore] and lets tests force [get]/[getFingerprint] to return
 * `throw IllegalStateException("x")` on demand. Own copy for this file -- see the
 * identical class in the sibling config-unavailable test files for the full rationale (no shared
 * harness file per this item's file-ownership rule).
 */
private class FailableProjectConfigRepository(
    private val delegate: ProjectConfigStore,
) : ProjectConfigStore by delegate {
    @Volatile var failFingerprint: Boolean = false

    @Volatile var failGet: Boolean = false

    override suspend fun getFingerprint(rootItemId: UUID) =
        if (failFingerprint) throw IllegalStateException("x") else delegate.getFingerprint(rootItemId)

    override suspend fun get(rootItemId: UUID) = if (failGet) throw IllegalStateException("x") else delegate.get(rootItemId)
}

/** An SQLite-backed provider with only [projectConfigRepository] swapped. */
private class FailableRepositoryProvider(
    private val delegate: RepositoryProvider,
    private val failable: FailableProjectConfigRepository,
) : RepositoryProvider by delegate {
    override fun projectConfigRepository(): ProjectConfigStore = failable
}
