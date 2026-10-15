package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.bootstrap.CompositionResult
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.make
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Independent tests (test-author seat, item 6a52466e, R2, test-plan S11 and AC7) for the REST leg of the root, fields and
 * multiRoot capture: the same `call_log.root_id` and `request_shape` keys as on MCP, through the real [installRestApiRoutes]
 * wiring over the real composition, bearer auth and event log.
 *
 * Oracles (frozen at queue phase): task-scope AC2, AC3, AC5 and AC7. A REST write reaches `call_log.root_id` through its events
 * (the root of the item it wrote), a REST batch over two roots gives NULL and `request_shape.multiRoot: true`, a REST read that
 * records a target version (`GET /api/v1/items/{id}` is the items_read get since Q19) records the root of the item it read, and
 * the REST `request_shape` carries `fields` (sorted distinct accepted names) exactly as the MCP row does. Fixtures derive a
 * child's root from its parent the way EventLogRig does.
 *
 * Negative / absence assertions and the condition that would fail them without the new capture: the absent `multiRoot` key on
 * a one-root write is paired with the two-root batch of the same fixture; an absent `fields` key is paired with a populated call.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class RestCallLogRootTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun newWriter(composition: CompositionResult) =
        CallLogWriter(composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)

    private fun Application.configureApp(
        composition: CompositionResult,
        writer: CallLogWriter
    ) {
        val authConfig: ApiAuthConfig.Bearer = makeWriteAuthConfig()
        install(ContentNegotiation) { json(McpJson) }
        installRestApiRoutes(
            apiConfig = authConfig,
            eventBus = null,
            effectiveProvider = composition.toolContext.repositoryProvider,
            apiTokenEntries = authConfig.tokens,
            allowQueryToken = false,
            serverName = "r2-rest-call-log",
            serverVersion = "test",
            serverConfig = composition.serverConfig,
            toolContext = composition.toolContext,
            callLog = writer,
            appConfig = AppConfig.fromEnv(),
        )
    }

    private class Forest(
        val rootA: WorkItem,
        val a1: WorkItem,
        val a2: WorkItem,
        val rootB: WorkItem,
        val b1: WorkItem
    )

    private suspend fun EventLogRig.forest(): Forest {
        val rootA = make("REST root A")
        val rootB = make("REST root B")
        return Forest(rootA, make("REST A one", rootA), make("REST A two", rootA), rootB, make("REST B one", rootB))
    }

    private suspend fun HttpClient.ops(
        tool: String,
        body: String,
        token: String
    ): HttpResponse =
        post("/api/v1/ops/$tool") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun update(
        id: UUID,
        title: String
    ) = """{"op":"update","id":"$id","title":"$title"}"""

    private suspend fun rowOf(
        response: HttpResponse,
        writer: CallLogWriter
    ): Map<String, Any?> {
        writer.flushNow()
        val reqId = assertNotNull(response.headers["X-Req-Id"], "X-Req-Id on a recorded response")
        return CallLogRows.read(db.jdbcUrl).single { it["req_id"] == reqId }
    }

    private fun rootOf(row: Map<String, Any?>): UUID? =
        (row["root_id"] as ByteArray?)?.let {
            assertEquals(16, it.size, "root_id is a 16-byte UUID blob")
            ByteBuffer.wrap(it).let { buf -> UUID(buf.long, buf.long) }
        }

    private fun shapeOf(row: Map<String, Any?>): JsonObject? =
        (row["request_shape"] as String?)?.let { Json.parseToJsonElement(it).jsonObject }

    private fun multiRootOf(row: Map<String, Any?>): Boolean? = shapeOf(row)?.get("multiRoot")?.jsonPrimitive?.booleanOrNull

    private fun fieldsOf(row: Map<String, Any?>): List<String>? = shapeOf(row)?.get("fields")?.jsonArray?.map { it.jsonPrimitive.content }

    @Test
    fun `S11 a REST write under one root records that root and no multiRoot`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }
            val f = rig.forest()

            val response = client.ops("items_write", """{"ops":[${update(f.a1.id, "renamed")}]}""", WRITE_TOKEN)

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val row = rowOf(response, writer)
            assertEquals("items_write", row["tool"], "fixture: the row is the items_write call")
            assertEquals(f.rootA.id, rootOf(row))
            assertNull(multiRootOf(row), "${row["request_shape"]}")
        }

    @Test
    fun `S11 a REST batch over two roots records no root and multiRoot true`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }
            val f = rig.forest()

            val response = client.ops("items_write", """{"ops":[${update(f.a1.id, "A")},${update(f.b1.id, "B")}]}""", WRITE_TOKEN)

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val row = rowOf(response, writer)
            assertNull(rootOf(row), "two distinct roots: no single root")
            assertEquals(true, multiRootOf(row), "${row["request_shape"]}")
        }

    @Test
    fun `S11 a REST batch over two items of one root records that root and no multiRoot`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }
            val f = rig.forest()

            val response = client.ops("items_write", """{"ops":[${update(f.a1.id, "one")},${update(f.a2.id, "two")}]}""", WRITE_TOKEN)

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val row = rowOf(response, writer)
            assertEquals(f.rootA.id, rootOf(row))
            assertNull(multiRootOf(row), "${row["request_shape"]}")
        }

    @Test
    fun `AC7 a REST item read records the root of the item it read`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }
            val f = rig.forest()

            val response =
                client.get("/api/v1/items/${f.b1.id}") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val row = rowOf(response, writer)
            assertEquals("GET /api/v1/items/{id}", row["tool"])
            assertEquals(f.rootB.id, rootOf(row))
        }

    @Test
    fun `AC7 a REST ops items_read get records the root and the sorted distinct accepted fields`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }
            val f = rig.forest()

            val withFields =
                client.ops(
                    "items_read",
                    """{"select":"get","itemId":"${f.a1.id}","fields":["createdAt","ancestors","createdAt"]}""",
                    TEST_TOKEN
                )
            val without = client.ops("items_read", """{"select":"get","itemId":"${f.a1.id}"}""", TEST_TOKEN)

            assertEquals(HttpStatusCode.OK, withFields.status, withFields.bodyAsText())
            assertEquals(HttpStatusCode.OK, without.status, without.bodyAsText())
            val populated = rowOf(withFields, writer)
            assertEquals(f.rootA.id, rootOf(populated))
            assertEquals(listOf("ancestors", "createdAt"), fieldsOf(populated), "${populated["request_shape"]}")
            assertNull(fieldsOf(rowOf(without, writer)), "no fields param, no fields key")
        }

    @Test
    fun `AC7 a REST ops items_read with an unknown fields name is refused and records no fields key`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }
            val f = rig.forest()

            val response = client.ops("items_read", """{"select":"get","itemId":"${f.a1.id}","fields":["Ancestors"]}""", TEST_TOKEN)

            assertTrue(response.status.value >= 400, "fixture: an unknown name is refused: ${response.status}")
            assertNull(fieldsOf(rowOf(response, writer)), "a refused call records no fields")
        }
}
