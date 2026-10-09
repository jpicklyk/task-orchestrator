package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.ProjectConfigStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.put
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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored (item 6adda27b, seat test-author) REST-surface scenarios for the note write policy,
 * against the frozen queue-phase `task-scope` ("Per-surface mapping") and `test-plan`: S3, S5, S7, S8, S12, S14,
 * S16 (REST half), S17, S19 and the REST probes (If-Match precedes validation, empty body accepted, cap at the
 * byte boundary, no `warning` when within the limit).
 *
 * Wire oracles (task-scope, REST PUT): maxLength reject -> 422 `note_body_too_long`; warn -> 201/200 with a
 * `warning` string in the body (absent otherwise); byte cap -> 413 `payload_too_large`; schema-role or bad role
 * -> 400 `validation_error`; config unavailable -> 503 `config_unavailable`; If-Match precedes validation;
 * the 1 MiB request bound stays. The error code strings are matched as substrings of the body, the same way
 * WriteRoutesTest / WriteRoutesBodyLimitTest match them.
 *
 * Harness: `configureWriteTestApp` (WriteRoutesTest.kt) for S3-S16 - global `note_limits` mode comes from the
 * schema service object, per-root mode from a row in the project config table. S17 needs a failing config store,
 * which `configureWriteTestApp` cannot take (it builds the PerRootConfigService itself from a concrete
 * DefaultRepositoryProvider), so a local app config is used there. S19 runs over the REAL production
 * composition (EventLogRig + installRestApiRoutes, the FailPolicyWritePathsTest pattern).
 */
class NoteWritePolicyRestTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun schema(globalMode: String): WorkItemSchemaService =
        object : WorkItemSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                if (tags.contains("lim-type")) listOf(NoteSchemaEntry(key = "lim", role = Role.WORK, maxLength = 10)) else null

            override fun getNoteLimitsMode(): String = globalMode
        }

    private fun item(
        tags: String? = "lim-type",
        rootId: UUID? = null
    ): WorkItem =
        runBlocking { db.repositoryProvider().workItemRepository().create(WorkItem(title = "host", tags = tags, rootId = rootId)) }

    private fun stored(
        itemId: UUID,
        key: String
    ): Note? = runBlocking { db.repositoryProvider().noteRepository().findByItemIdAndKey(itemId, key) }

    private fun jsonBody(
        role: String,
        body: String
    ): String =
        buildJsonObject {
            put("role", role)
            put("body", body)
        }.toString()

    private suspend fun ApplicationTestBuilder.putNote(
        itemId: UUID,
        key: String,
        role: String,
        body: String,
        ifMatch: String? = null
    ): HttpResponse =
        client.put("/api/v1/items/$itemId/notes/$key") {
            header("Authorization", "Bearer $WRITE_TOKEN")
            if (ifMatch != null) header(HttpHeaders.IfMatch, ifMatch)
            contentType(ContentType.Application.Json)
            setBody(jsonBody(role, body))
        }

    /** First string value for [key] anywhere in the JSON document, null when no such string exists. */
    private fun findString(
        element: JsonElement,
        key: String
    ): String? =
        when (element) {
            is JsonObject ->
                (element[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: element.values.firstNotNullOfOrNull { findString(it, key) }
            is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
            else -> null
        }

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    // ---------------------------------------------------------------------------------------------
    // S3 normalization
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 PUT with role Queue and a CRLF body stores queue and LF`(): Unit =
        testApplication {
            val target = item(tags = null)
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

            val response = putNote(target.id, "k", "Queue", "a\r\nb")

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            assertEquals("queue", findString(parse(response.bodyAsText()), "role"))
            val note = assertNotNull(stored(target.id, "k"))
            assertEquals("queue", note.role)
            assertEquals("a\nb", note.body)
        }

    @Test
    fun `S3 probe mixed casing and a lone CR on update`(): Unit =
        testApplication {
            val target = item(tags = null)
            runBlocking {
                db.repositoryProvider().noteRepository().upsert(
                    Note(itemId = target.id, key = "k", role = "work", body = "old")
                )
            }
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }
            val etag =
                client
                    .put("/api/v1/items/${target.id}/notes/k") {
                        header("Authorization", "Bearer $WRITE_TOKEN")
                        contentType(ContentType.Application.Json)
                        setBody(jsonBody("wORK", "a\rb"))
                    }.also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
                    .headers[HttpHeaders.ETag]

            assertNotNull(etag)
            val note = assertNotNull(stored(target.id, "k"))
            assertEquals("work", note.role)
            assertEquals("a\rb", note.body)
        }

    // ---------------------------------------------------------------------------------------------
    // S5 / S21 maxLength warn
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 an 11-char lim body in warn mode is created with a warning naming the key and the limit`(): Unit =
        testApplication {
            val target = item()
            application { configureWriteTestApp(db.repositoryProvider(), schemaService = schema("warn"), unitOfWork = db.unitOfWork()) }

            val response = putNote(target.id, "lim", "work", "x".repeat(11))

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val warning = findString(parse(response.bodyAsText()), "warning")
            assertNotNull(warning, "a warning string is expected in the body: ${response.bodyAsText()}")
            assertTrue("lim" in warning && "10" in warning, "warning: $warning")
            assertEquals("x".repeat(11), stored(target.id, "lim")?.body)
        }

    @Test
    fun `S5 control - a lim body of exactly 10 chars has no warning in the body`(): Unit =
        testApplication {
            val target = item()
            application { configureWriteTestApp(db.repositoryProvider(), schemaService = schema("warn"), unitOfWork = db.unitOfWork()) }

            val response = putNote(target.id, "lim", "work", "x".repeat(10))

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            assertNull(findString(parse(response.bodyAsText()), "warning"), "no warning at exactly maxLength: ${response.bodyAsText()}")
        }

    @Test
    fun `S21 a lim body with two CRLF is 10 normalized chars so no warning and no rejection even under reject`(): Unit =
        testApplication {
            val target = item()
            application { configureWriteTestApp(db.repositoryProvider(), schemaService = schema("reject"), unitOfWork = db.unitOfWork()) }

            val response = putNote(target.id, "lim", "work", "ab\r\ncdef\r\ngh")

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            assertNull(findString(parse(response.bodyAsText()), "warning"))
            assertEquals("ab\ncdef\ngh", stored(target.id, "lim")?.body)
        }

    // ---------------------------------------------------------------------------------------------
    // S7 / S8 layered note_limits
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S7 per-root reject answers 422 note_body_too_long and stores nothing`(): Unit =
        testApplication {
            val repos = db.repositoryProvider()
            val root = runBlocking { repos.workItemRepository().create(WorkItem(title = "root")) }
            runBlocking { repos.projectConfigRepository().upsert(root.id, "note_limits:\n  mode: reject\n") }
            val target = item(rootId = root.id)
            application { configureWriteTestApp(repos, schemaService = schema("warn"), unitOfWork = db.unitOfWork()) }

            val response = putNote(target.id, "lim", "work", "x".repeat(11))

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("note_body_too_long"), response.bodyAsText())
            assertNull(stored(target.id, "lim"), "the rejected note must not exist")
        }

    @Test
    fun `S8 global reject with an explicit per-root warn accepts with a warning`(): Unit =
        testApplication {
            val repos = db.repositoryProvider()
            val root = runBlocking { repos.workItemRepository().create(WorkItem(title = "root")) }
            runBlocking { repos.projectConfigRepository().upsert(root.id, "note_limits:\n  mode: warn\n") }
            val target = item(rootId = root.id)
            application { configureWriteTestApp(repos, schemaService = schema("reject"), unitOfWork = db.unitOfWork()) }

            val response = putNote(target.id, "lim", "work", "x".repeat(11))

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            assertNotNull(findString(parse(response.bodyAsText()), "warning"), response.bodyAsText())
            assertEquals("x".repeat(11), stored(target.id, "lim")?.body)
        }

    @Test
    fun `S7 control - the same over-length body for a root without a per-root value follows the global mode`(): Unit =
        testApplication {
            val repos = db.repositoryProvider()
            val root = runBlocking { repos.workItemRepository().create(WorkItem(title = "bare root")) }
            val target = item(rootId = root.id)
            application { configureWriteTestApp(repos, schemaService = schema("reject"), unitOfWork = db.unitOfWork()) }

            val response = putNote(target.id, "lim", "work", "x".repeat(11))

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
            assertNull(stored(target.id, "lim"))
        }

    // ---------------------------------------------------------------------------------------------
    // S12 / S16 role validation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S12 a schema key written with the wrong role answers 400 validation_error and is not stored`(): Unit =
        testApplication {
            val target = item()
            application { configureWriteTestApp(db.repositoryProvider(), schemaService = schema("warn"), unitOfWork = db.unitOfWork()) }

            val response = putNote(target.id, "lim", "queue", "ok")

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("validation_error"), response.bodyAsText())
            assertNull(stored(target.id, "lim"))
        }

    @Test
    fun `S12 control - the schema key with WORK is accepted`(): Unit =
        testApplication {
            val target = item()
            application { configureWriteTestApp(db.repositoryProvider(), schemaService = schema("warn"), unitOfWork = db.unitOfWork()) }

            val response = putNote(target.id, "lim", "WORK", "ok")

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            assertEquals("work", stored(target.id, "lim")?.role)
        }

    @Test
    fun `S16 roles done and padded work answer 400 validation_error and nothing is stored`(): Unit =
        testApplication {
            val target = item(tags = null)
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

            for ((i, bad) in listOf("done", " work", "work ", "").withIndex()) {
                val response = putNote(target.id, "bad$i", bad, "b")
                assertEquals(HttpStatusCode.BadRequest, response.status, "role '$bad': ${response.bodyAsText()}")
                assertTrue(response.bodyAsText().contains("validation_error"), "role '$bad': ${response.bodyAsText()}")
                assertNull(stored(target.id, "bad$i"), "role '$bad' must not be stored")
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S14 byte cap
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S14 a body of 21846 euro signs is 65538 bytes in under 65536 chars and answers 413 payload_too_large`(): Unit =
        testApplication {
            val target = item(tags = null)
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }
            val body = "\u20AC".repeat(21846)
            assertTrue(body.length < 65536 && body.toByteArray(Charsets.UTF_8).size == 65538)

            val response = putNote(target.id, "big", "work", body)

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("payload_too_large"), response.bodyAsText())
            assertNull(stored(target.id, "big"))
        }

    @Test
    fun `S14 probe 65536 bytes is accepted and 65537 ASCII bytes is refused`(): Unit =
        testApplication {
            val target = item(tags = null)
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

            val atCap = putNote(target.id, "fits", "work", "\u20AC".repeat(21845) + "a")
            assertEquals(HttpStatusCode.Created, atCap.status, atCap.bodyAsText())

            val over = putNote(target.id, "over", "work", "a".repeat(65537))
            assertEquals(HttpStatusCode.PayloadTooLarge, over.status, over.bodyAsText())
            assertTrue(over.bodyAsText().contains("payload_too_large"))
            assertNull(stored(target.id, "over"))
        }

    @Test
    fun `S14 probe the cap applies on update of an existing note too and leaves the old body`(): Unit =
        testApplication {
            val target = item(tags = null)
            runBlocking {
                db.repositoryProvider().noteRepository().upsert(
                    Note(itemId = target.id, key = "k", role = "work", body = "old")
                )
            }
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

            val response = putNote(target.id, "k", "work", "a".repeat(65537))

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status, response.bodyAsText())
            assertEquals("old", stored(target.id, "k")?.body)
        }

    // ---------------------------------------------------------------------------------------------
    // probes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `probe an empty body is accepted`(): Unit =
        testApplication {
            val target = item(tags = null)
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

            val response = putNote(target.id, "empty", "work", "")

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            assertEquals("", stored(target.id, "empty")?.body)
        }

    @Test
    fun `probe If-Match is checked before the policy so a stale tag with an invalid role is 412 not 400`(): Unit =
        testApplication {
            val target = item(tags = null)
            runBlocking {
                db.repositoryProvider().noteRepository().upsert(
                    Note(itemId = target.id, key = "k", role = "work", body = "old")
                )
            }
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

            val response = putNote(target.id, "k", "done", "new", ifMatch = "\"v1-0\"")

            assertEquals(HttpStatusCode.PreconditionFailed, response.status, response.bodyAsText())
            assertEquals("old", stored(target.id, "k")?.body)
        }

    // ---------------------------------------------------------------------------------------------
    // S17 (NEW-SURFACE) config unavailable
    // ---------------------------------------------------------------------------------------------

    private class NoteTestFailableConfigStore(
        private val delegate: ProjectConfigStore
    ) : ProjectConfigStore by delegate {
        @Volatile var failFingerprint: Boolean = false

        override suspend fun getFingerprint(rootItemId: UUID) =
            if (failFingerprint) throw IllegalStateException("x") else delegate.getFingerprint(rootItemId)
    }

    private class NoteTestFailableProvider(
        private val delegate: RepositoryProvider,
        private val failable: NoteTestFailableConfigStore
    ) : RepositoryProvider by delegate {
        override fun projectConfigRepository(): ProjectConfigStore = failable
    }

    private fun Application.configureNoteApp(
        provider: RepositoryProvider,
        schemaService: WorkItemSchemaService,
        unitOfWork: UnitOfWork
    ) {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        val authConfig = makeWriteAuthConfig()
        routing {
            route("/api/v1") {
                install(ApiBearerAuth) {
                    this.authConfig = authConfig
                    tokenEntries = authConfig.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }
                }
                val toolContext =
                    ToolExecutionContext(
                        provider,
                        schemaService,
                        perRootConfigService = PerRootConfigService(provider.projectConfigRepository()),
                        unitOfWork = unitOfWork
                    )
                noteWriteRoutes(
                    provider,
                    DegradedModePolicy.ACCEPT_CACHED,
                    IdempotencyService(unitOfWork),
                    unitOfWork,
                    toolContext.noteCommandService
                )
            }
        }
    }

    @Test
    fun `S17 PUT note answers 503 config_unavailable when the per-root config cannot be read and stores nothing`(): Unit =
        testApplication {
            val sqlite = db.repositoryProvider()
            val root = runBlocking { sqlite.workItemRepository().create(WorkItem(title = "failing root")) }
            val under = runBlocking { sqlite.workItemRepository().create(WorkItem(title = "under root", rootId = root.id)) }
            val rootless = runBlocking { sqlite.workItemRepository().create(WorkItem(title = "rootless")) }
            val failable = NoteTestFailableConfigStore(sqlite.projectConfigRepository())
            failable.failFingerprint = true // armed before the first read: cold, no last-known-good
            application { configureNoteApp(NoteTestFailableProvider(sqlite, failable), schema("warn"), db.unitOfWork()) }

            val response = putNote(under.id, "k", "work", "ok")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("config_unavailable"), response.bodyAsText())
            assertNull(stored(under.id, "k"), "nothing is stored when the policy cannot be evaluated")

            // vacuity control: the same failing store does not stop a write whose item has no root
            val control = putNote(rootless.id, "k", "work", "ok")
            assertEquals(HttpStatusCode.Created, control.status, control.bodyAsText())
        }

    // ---------------------------------------------------------------------------------------------
    // S19 events (real composition)
    // ---------------------------------------------------------------------------------------------

    private val eventsYaml =
        "work_item_schemas:\n" +
            "  lim-type:\n" +
            "    notes:\n" +
            "      - key: lim\n" +
            "        role: work\n" +
            "        required: false\n" +
            "        description: \"limited\"\n" +
            "        maxLength: 10\n" +
            "note_limits:\n" +
            "  mode: reject\n"

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
            serverName = "note-write-policy",
            serverVersion = "test",
            actorAuthEnabled = composition.actorAuthEnabled,
            noteSchemaService = composition.noteSchemaService,
            toolContext = composition.toolContext,
            degradedModePolicy = composition.degradedModePolicy,
        )
    }

    @Test
    fun `S19 a rejected REST write records no note upserted event and an accepted WORK write records one with role work`(
        @TempDir dir: Path
    ): Unit =
        testApplication {
            val rig = EventLogRig.build(db.db, dir, eventsYaml)
            val host = runBlocking { rig.seed("event host", tags = "lim-type") }
            application { configureProductionApp(rig.composition) }

            val (rejected, rejectedRows) = rig.written { putNote(host.id, "lim", "work", "x".repeat(11)) }
            assertEquals(HttpStatusCode.UnprocessableEntity, rejected.status, rejected.bodyAsText())
            assertTrue(rejectedRows.none { it.type == "note.upserted" }, "a rejected write must leave no note.upserted row: $rejectedRows")
            assertNull(stored(host.id, "lim"))

            val (accepted, acceptedRows) = rig.written { putNote(host.id, "plain", "WORK", "ok") }
            assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
            val upserted = acceptedRows.filter { it.type == "note.upserted" }
            assertEquals(1, upserted.size, "an accepted write records exactly one note.upserted row: $acceptedRows")
            assertEquals("work", upserted.single().str("role"))
            assertEquals("plain", upserted.single().str("key"))
        }

    @Test
    fun `S19 an MCP upsert with role WORK records the event with the normalized role`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir, eventsYaml)
            val host = rig.seed("event host mcp", tags = "lim-type")

            val (result, rows) =
                rig.written {
                    rig.callOk(
                        ManageNotesTool(),
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("itemId", host.id.toString())
                                        put("key", "plain")
                                        put("role", "WORK")
                                        put("body", "ok")
                                    }
                                )
                            }
                    )
                }

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val upserted = rows.filter { it.type == "note.upserted" }
            assertEquals(1, upserted.size, "$rows")
            assertEquals("work", upserted.single().str("role"))
        }
}
