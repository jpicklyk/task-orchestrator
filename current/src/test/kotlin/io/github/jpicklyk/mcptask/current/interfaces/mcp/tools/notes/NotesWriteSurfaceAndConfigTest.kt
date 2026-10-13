package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.notes

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.notes.NoteHandlers
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.application.port.BodySource
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
import io.github.jpicklyk.mcptask.current.test.callTool
import io.github.jpicklyk.mcptask.current.test.putNote
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review fix round r2 of item 0b2d5199 (implementer-authored, next to the blind Q11 tests):
 *
 * - C1: `bodyFromFile` is MCP-only. Over `POST /api/v1/ops/notes_write` an element that sets it fails alone with
 *   `invalid_request` on `/bodyFromFile`, the body source is never consulted and nothing is stored for it, while a sibling
 *   element of the same non-atomic call is stored. Over MCP the file is still read.
 * - C2: a per-root config that cannot be read fails that element alone with `unavailable` (`retryAfterMs` 1000) and a
 *   sibling element of the same non-atomic call is still stored.
 * - U22 for `notes_read list`: the last page of a cursor walk has `truncated: false` and no `next`.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class NotesWriteSurfaceAndConfigTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val fileText = "FILE-CONTENT-INSIDE-ROOT"

    /** A [BodySource] that records every path it is asked for and answers [text]. */
    private class RecordingBodySource(
        private val text: String
    ) : BodySource {
        val calls = mutableListOf<String>()

        override fun read(path: String): Outcome<String> {
            calls += path
            return Outcome.Ok(text)
        }
    }

    private object UnreadablePerRoot : PerRootConfigSource {
        override suspend fun layer(rootId: UUID): ConfigLayer? =
            throw PerRootConfigUnavailableException(rootId, "per-root config unreadable")
    }

    private fun upsert(
        item: UUID,
        key: String,
        body: String? = null,
        file: String? = null
    ): JsonObject =
        buildJsonObject {
            put("op", "upsert")
            put("itemId", item.toString())
            put("key", key)
            put("role", "work")
            body?.let { put("body", it) }
            file?.let { put("bodyFromFile", it) }
        }

    private fun opsArgs(vararg ops: JsonObject): JsonObject = buildJsonObject { put("ops", JsonArray(ops.toList())) }

    private fun JsonObject.results(): List<JsonObject> =
        getValue("data")
            .jsonObject
            .getValue("results")
            .jsonArray
            .map { it.jsonObject }

    private fun JsonObject.isOk(): Boolean = getValue("ok").jsonPrimitive.content.toBoolean()

    private fun JsonObject.errorCode(): String =
        getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private suspend fun dispatch(
        ctx: ToolExecutionContext,
        surface: String,
        arguments: JsonObject
    ): JsonObject {
        val spec = checkNotNull(ToolRegistry(buildToolSpecs())["notes_write"])
        return run {
            buildToolDispatcher(ctx).dispatch(spec, ToolProfile.ORCHESTRATION, arguments, "r2req000", surface)
        }.envelope.body
    }

    private fun recordingContext(source: RecordingBodySource): ToolExecutionContext =
        ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork(), bodySource = source)

    private suspend fun seed(
        title: String,
        parent: WorkItem? = null
    ): WorkItem =
        db.repositoryProvider().workItemRepository().create(
            WorkItem(
                title = title,
                parentId = parent?.id,
                depth = if (parent == null) 0 else parent.depth + 1,
                rootId = parent?.let { it.rootId ?: it.id }
            )
        )

    // ------------------------------------------------------------------ C1 REST leg, real route + production FileBodySource

    @Test
    fun `C1 REST notes_write refuses bodyFromFile for a file inside the root, stores nothing for it, and the sibling is stored`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val agentDir = dir.resolve("agent")
            Files.createDirectories(agentDir)
            Files.write(agentDir.resolve("inside.txt"), fileText.toByteArray(Charsets.UTF_8))
            val rig = EventLogRig.build(db.db, agentDir)
            val item = rig.seed("owner")
            val token = ParityToken("r2-rest", setOf(ApiCapability.READ, ApiCapability.WRITE_NOTES))
            val registry = ToolRegistry(buildToolSpecs())

            var status = 0
            var text = ""
            testApplication {
                application { installParityRestApp(rig, parityAuthConfig(token), registry) }
                val response =
                    client.post("/api/v1/ops/notes_write") {
                        header(HttpHeaders.Authorization, "Bearer ${token.raw}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            opsArgs(
                                upsert(item.id, "from-file", file = "inside.txt"),
                                upsert(item.id, "sibling", body = "inline")
                            ).toString()
                        )
                    }
                status = response.status.value
                text = response.bodyAsText()
            }

            assertEquals(207, status, text)
            assertFalse(text.contains(fileText), "the file content never reaches the response: $text")
            val results = Json.parseToJsonElement(text).jsonObject.results()
            assertFalse(results[0].isOk(), text)
            assertEquals("invalid_request", results[0].errorCode())
            val error = results[0].getValue("error").toString()
            assertTrue("/bodyFromFile" in error, "the error names the field: $error")
            assertTrue(NoteHandlers.BODY_FROM_FILE_MCP_ONLY in error, "the error names the inline body alternative: $error")
            assertTrue(results[1].isOk(), text)
            val notes = rig.raw.noteRepository()
            assertNull(notes.findByItemIdAndKey(item.id, "from-file"), "nothing stored for the refused element")
            assertEquals("inline", assertNotNull(notes.findByItemIdAndKey(item.id, "sibling")).body)
        }

    @Test
    fun `C1 MCP notes_write still reads bodyFromFile through the production FileBodySource`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val agentDir = dir.resolve("agent")
            Files.createDirectories(agentDir)
            Files.write(agentDir.resolve("inside.txt"), fileText.toByteArray(Charsets.UTF_8))
            val rig = EventLogRig.build(db.db, agentDir)
            val item = rig.seed("owner")

            val answer = rig.ctx.callTool("notes_write", opsArgs(upsert(item.id, "from-file", file = "inside.txt")))

            assertTrue(answer.results().single().isOk(), "$answer")
            assertEquals(fileText, assertNotNull(rig.raw.noteRepository().findByItemIdAndKey(item.id, "from-file")).body)
        }

    // ------------------------------------------------------------------ C1 the body source is never consulted off MCP

    @Test
    fun `C1 on the rest surface the body source is never consulted`(): Unit =
        runBlocking {
            val source = RecordingBodySource(fileText)
            val ctx = recordingContext(source)
            val item = seed("owner")

            val answer =
                dispatch(ctx, "rest", opsArgs(upsert(item.id, "from-file", file = "any.txt"), upsert(item.id, "sibling", body = "inline")))

            assertEquals(emptyList(), source.calls, "no file read for a REST caller")
            val results = answer.results()
            assertEquals("invalid_request", results[0].errorCode(), "$answer")
            assertTrue(results[1].isOk(), "$answer")
            val notes = db.repositoryProvider().noteRepository()
            assertNull(notes.findByItemIdAndKey(item.id, "from-file"))
            assertNotNull(notes.findByItemIdAndKey(item.id, "sibling"))
        }

    @Test
    fun `C1 on the mcp surface the body source is consulted once and its text is stored`(): Unit =
        runBlocking {
            val source = RecordingBodySource(fileText)
            val ctx = recordingContext(source)
            val item = seed("owner")

            val answer = dispatch(ctx, "mcp", opsArgs(upsert(item.id, "from-file", file = "any.txt")))

            assertTrue(answer.results().single().isOk(), "$answer")
            assertEquals(listOf("any.txt"), source.calls)
            assertEquals(fileText, assertNotNull(db.repositoryProvider().noteRepository().findByItemIdAndKey(item.id, "from-file")).body)
        }

    // ------------------------------------------------------------------ C2 per-root config unavailable

    @Test
    fun `C2 an unreadable per-root config fails that element as unavailable with retryAfterMs 1000 and the sibling is stored`(): Unit =
        runBlocking {
            val root = seed("failing root")
            val underRoot = seed("under the failing root", parent = root)
            val rootless = seed("no root")
            val ctx =
                ToolExecutionContext(
                    db.repositoryProvider(),
                    perRootConfigService = UnreadablePerRoot,
                    unitOfWork = db.unitOfWork()
                )

            val answer =
                ctx.callTool(
                    "notes_write",
                    opsArgs(upsert(underRoot.id, "blocked", body = "a"), upsert(rootless.id, "stored", body = "b"))
                )

            val results = answer.results()
            assertFalse(results[0].isOk(), "$answer")
            assertEquals("unavailable", results[0].errorCode(), "$answer")
            val detail =
                results[0]
                    .getValue("error")
                    .jsonObject
                    .getValue("detail")
                    .jsonObject
            assertEquals(1000L, detail.getValue("retryAfterMs").jsonPrimitive.long, "$answer")
            assertTrue(results[1].isOk(), "the sibling of a non-atomic call still runs: $answer")
            val notes = db.repositoryProvider().noteRepository()
            assertNull(notes.findByItemIdAndKey(underRoot.id, "blocked"), "nothing stored for the unavailable element")
            assertEquals("b", assertNotNull(notes.findByItemIdAndKey(rootless.id, "stored")).body)
        }

    // ------------------------------------------------------------------ U22 notes_read list: the last page is not truncated

    @Test
    fun `U22 notes_read list 3 notes limit 2 - page 1 truncated with next, page 2 holds the rest untruncated with no next`(): Unit =
        runBlocking {
            val ctx = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())
            val item = seed("owner")
            for (key in listOf("a", "b", "c")) ctx.putNote(item.id, key, body = "body-$key")

            suspend fun list(cursor: String?): JsonObject =
                ctx.callTool(
                    "notes_read",
                    buildJsonObject {
                        put("select", "list")
                        put("itemId", item.id.toString())
                        put("limit", 2)
                        cursor?.let { put("cursor", it) }
                    }
                )

            fun JsonObject.page(): JsonObject = getValue("data").jsonObject.getValue("page").jsonObject

            fun JsonObject.itemCount(): Int =
                getValue("data")
                    .jsonObject
                    .getValue("items")
                    .jsonArray.size

            val first = list(null)
            assertEquals(2, first.itemCount(), "$first")
            assertEquals(
                3L,
                first
                    .page()
                    .getValue("total")
                    .jsonPrimitive.long
            )
            assertEquals(
                "true",
                first
                    .page()
                    .getValue("truncated")
                    .jsonPrimitive.content
            )
            val next = assertNotNull(first.page()["next"], "a truncated page carries next: ${first.page()}").jsonPrimitive.content

            val second = list(next)
            assertEquals(1, second.itemCount(), "$second")
            assertEquals(
                3L,
                second
                    .page()
                    .getValue("total")
                    .jsonPrimitive.long
            )
            assertEquals(
                "false",
                second
                    .page()
                    .getValue("truncated")
                    .jsonPrimitive.content,
                "the last page is not truncated: ${second.page()}"
            )
            assertFalse("next" in second.page(), "the last page has no next: ${second.page()}")
        }
}
