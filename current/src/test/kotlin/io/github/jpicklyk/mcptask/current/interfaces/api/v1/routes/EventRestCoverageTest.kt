package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.bootstrap.CompositionResult
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent P8 REST write coverage (item ea2b9b63): every REST write route, driven over the real production
 * composition and the production route installer, must leave the documented rows. Closes carry-in (1): REST note PUT
 * yields `note.upserted` and REST note DELETE yields `204` plus `note.deleted` (the decorator-level note publishing
 * was previously unguarded at route level), and carry-in (2): the 204 status is asserted at route level.
 *
 * Oracles: api-rest.md (status codes of each route: note PUT 201 on create and 200 on update, DELETE note 204, advance
 * 422 gate_blocked, config PUT 200, plans PUT 200), plan section 3.7 / 8 (a row per write; rejection rows), the P8
 * task-scope type catalog and payload keys, and the api-rest section 21 actor rule (an API write is attributed to
 * `api:<tokenId>`, kind external). Fixtures are seeded through the undecorated provider, so every asserted row was
 * written by the request under test.
 */
class EventRestCoverageTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun Application.configureProductionApp(composition: CompositionResult) {
        val authConfig: ApiAuthConfig = makeWriteAuthConfig()
        install(ContentNegotiation) { json(McpJson) }
        installRestApiRoutes(
            apiConfig = authConfig,
            eventBus = null,
            effectiveProvider = composition.toolContext.repositoryProvider,
            apiTokenEntries =
                (authConfig as ApiAuthConfig.Bearer).tokens,
            allowQueryToken = false,
            serverName = "p8-event-rest-coverage",
            serverVersion = "test",
            actorAuthEnabled = composition.actorAuthEnabled,
            serverConfig = composition.serverConfig,
            toolContext = composition.toolContext,
            appConfig = AppConfig.fromEnv(),
        )
    }

    private fun List<EventRecord>.types() = map { it.type }

    private suspend fun HttpClient.send(
        method: String,
        path: String,
        body: String? = null,
        token: String = WRITE_TOKEN,
        extra: List<Pair<String, String>> = emptyList(),
    ): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("Authorization", "Bearer $token")
            extra.forEach { (k, v) -> header(k, v) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "POST" -> post("/api/v1$path", build)
            "PATCH" -> patch("/api/v1$path", build)
            "PUT" -> put("/api/v1$path", build)
            "DELETE" -> delete("/api/v1$path", build)
            else -> error(method)
        }
    }

    private fun apiActor() = "api:$WRITE_TOKEN_ID"

    // ---------------------------------------------------------------------------------------------
    // Notes -- the carry-in gap
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `REST notes_write records note upserted for create and update and note deleted for delete`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            application { configureProductionApp(rig.composition) }
            val item = rig.seed("rest note host")

            fun upsert(
                role: String,
                body: String
            ) = """{"ops":[{"op":"upsert","itemId":"${item.id}","key":"k1","role":"$role","body":"$body"}]}"""

            val created = client.send("POST", "/ops/notes_write", upsert("work", "hello"))
            assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
            val first = rig.rows()
            assertEquals(listOf("note.upserted"), first.types(), "the REST notes_write must reach the event log: $first")
            val up = first.single()
            assertEquals(item.id.toString(), up.str("itemId"))
            assertEquals("k1", up.str("key"))
            assertEquals("work", up.str("role"))
            assertEquals(
                5,
                up
                    .payload()["bodyLength"]!!
                    .jsonPrimitive.content
                    .toInt()
            )
            assertEquals(item.id, up.rootId)
            assertEquals(apiActor(), up.principalId)

            val mark = rig.maxSeq()
            val updated = client.send("POST", "/ops/notes_write", upsert("review", "hello world"))
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
            val again = rig.rowsAfter(mark)
            assertEquals(listOf("note.upserted"), again.types())
            assertEquals(
                11,
                again
                    .single()
                    .payload()["bodyLength"]!!
                    .jsonPrimitive.content
                    .toInt()
            )
            assertEquals("review", again.single().str("role"))
            assertEquals(up.entityId, again.single().entityId, "an update is the same note id")

            val markDelete = rig.maxSeq()
            val deleted =
                client.send("POST", "/ops/notes_write", """{"ops":[{"op":"delete","itemId":"${item.id}","key":"k1"}]}""")
            assertEquals(HttpStatusCode.OK, deleted.status, deleted.bodyAsText())
            val gone = rig.rowsAfter(markDelete)
            assertEquals(listOf("note.deleted"), gone.types())
            assertEquals("explicit", gone.single().str("cause"))
            assertEquals("k1", gone.single().str("key"))
            assertEquals(up.entityId, gone.single().entityId)
        }

    @Test
    fun `REST notes_write delete of a missing note fails the element and records nothing`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            application { configureProductionApp(rig.composition) }
            val item = rig.seed("rest no note")
            // Control: the same route does record when the note exists.
            rig.raw.noteRepository().upsert(Note(itemId = item.id, key = "present", role = "work", body = "x"))

            fun delete(key: String) = """{"ops":[{"op":"delete","itemId":"${item.id}","key":"$key"}]}"""

            val missing = client.send("POST", "/ops/notes_write", delete("absent"))

            assertTrue(missing.bodyAsText().contains("\"code\":\"not_found\""), "the missing note is not_found")
            assertEquals(emptyList(), rig.rows())
            assertEquals(HttpStatusCode.OK, client.send("POST", "/ops/notes_write", delete("present")).status)
            assertEquals(listOf("note.deleted"), rig.rows().types())
        }

    // ---------------------------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------
    // Project config, leases
    // ---------------------------------------------------------------------------------------------

    /** A `POST /ops/config` body with one [op] element for [root], plus string [fields]. */
    private fun configOps(
        op: String,
        root: String,
        vararg fields: Pair<String, String>,
    ): String =
        buildJsonObject {
            put(
                "ops",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("op", op)
                            put("rootId", root)
                            fields.forEach { (k, v) -> put(k, v) }
                        },
                    )
                },
            )
        }.toString()

    @Test
    fun `REST config tool push and reset record their rows`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            application { configureProductionApp(rig.composition) }
            val root = rig.seed("rest cfg root")

            val mark = rig.maxSeq()
            val config =
                client.post("/api/v1/ops/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody(configOps("push", root.id.toString(), "configYaml" to EventLogRig.GATED_CONFIG))
                }
            assertEquals(HttpStatusCode.OK, config.status, config.bodyAsText())
            val configRows = rig.rowsAfter(mark)
            assertEquals(listOf("config.version_created"), configRows.types())
            assertEquals(root.id, configRows.single().rootId)

            val markDelete = rig.maxSeq()
            val removed =
                client.post("/api/v1/ops/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody(configOps("reset", root.id.toString()))
                }
            assertTrue(removed.status.value in 200..299, "config delete status ${removed.status}: ${removed.bodyAsText()}")
            assertEquals(listOf("config.version_created"), rig.rowsAfter(markDelete).types())
        }

    @Test
    fun `REST operator force release of a lease records a forced lease released row`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            application { configureProductionApp(rig.composition) }
            val holder: WorkItem = rig.seed("rest lease holder")
            rig.raw.resourceLeaseRepository().acquireAll(holder.id, "agent", listOf("rest-res" to 600))

            val response = client.send("DELETE", "/resources/leases/rest-res", token = ADMIN_TOKEN)

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val rows = rig.rows()
            assertEquals(listOf("lease.released"), rows.types(), "$rows")
            assertEquals("rest-res", rows.single().str("key"))
            assertTrue(
                rows
                    .single()
                    .payload()["forced"]!!
                    .jsonPrimitive.content
                    .toBoolean()
            )
        }

    // ---------------------------------------------------------------------------------------------
    // Absence control
    // ---------------------------------------------------------------------------------------------
}
