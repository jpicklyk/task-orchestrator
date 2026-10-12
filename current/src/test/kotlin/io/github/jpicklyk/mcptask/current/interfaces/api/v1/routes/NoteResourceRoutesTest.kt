package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.ProofClaims
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsertNoteVia
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 0b2d5199) for the note resource routes `GET /api/v1/items/{id}/notes` and
 * `GET /api/v1/items/{id}/notes/{key}` and the removal of `PUT` / `DELETE /items/{id}/notes/{key}`: scenario S19, the REST
 * halves of S16 (scope) and S18 (attribution redaction) of the frozen test-plan (all NEW-SURFACE).
 *
 * Oracles: task-scope 1.7 (the GETs are thin translations to `notes_read list/get` under the bearer's access and answer the
 * envelope, status from `ErrorCode.httpStatus`; `get` sets `ETag: "v{version}"` and an equal `If-None-Match` answers 304 with an
 * empty body; query parameters map 1:1 - `role`, `keys` as CSV, `limit`, `cursor`; an unknown parameter is `invalid_request`;
 * `PUT` / `DELETE` note routes are deleted and writes go through `POST /api/v1/ops/notes_write`), 1.3 (scope check ->
 * `forbidden` 403), 1.6 (a non-admin caller sees no `actor` / `verification` under the default redaction, an admin does,
 * proof evidence never appears), plan 3.2 / envelope section 3 (REST body is byte-identical to MCP / `/ops` apart from
 * `meta.reqId`, and `X-Req-Id`).
 *
 * Harness: the production REST wiring ([installParityRestApp] over the real composition [EventLogRig]) with bearer tokens.
 * The weak-validator and wildcard forms of `If-None-Match` are not asserted: task-scope 1.7 states equality only.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class NoteResourceRoutesTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private lateinit var rig: EventLogRig

    @BeforeEach
    fun setUp(
        @TempDir dir: Path
    ) {
        rig = EventLogRig.build(db.db, dir)
    }

    private val reader = ParityToken("nrr-reader", setOf(ApiCapability.READ))
    private val admin = ParityToken("nrr-admin", setOf(ApiCapability.ADMIN))
    private val writer = ParityToken("nrr-writer", setOf(ApiCapability.READ, ApiCapability.WRITE_NOTES))

    // ------------------------------------------------------------------ helpers

    private fun authFor(tokens: List<ParityToken>) =
        ApiAuthConfig.Bearer(
            tokens =
                tokens.associate {
                    HashBytes(sha256(it.raw)) to
                        it.principal()
                }
        )

    private fun runApp(
        tokens: List<ParityToken>,
        block: suspend ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        application { installParityRestApp(rig, authFor(tokens), ToolRegistry(buildToolSpecs())) }
        block()
    }

    private suspend fun ApplicationTestBuilder.getAs(
        token: ParityToken,
        path: String,
        vararg headers: Pair<String, String>
    ): HttpResponse =
        client.get(path) {
            header(HttpHeaders.Authorization, "Bearer ${token.raw}")
            for ((name, value) in headers) header(name, value)
        }

    private suspend fun ApplicationTestBuilder.opsAs(
        token: ParityToken,
        tool: String,
        json: String
    ): HttpResponse =
        client.post("/api/v1/ops/$tool") {
            header(HttpHeaders.Authorization, "Bearer ${token.raw}")
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    private fun normalized(body: String): String = body.replace(Regex("\"reqId\":\"[^\"]*\""), "\"reqId\":\"<REQ>\"")

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun keysOf(text: String): List<String> =
        obj(text)
            .getValue("data")
            .jsonObject
            .getValue("items")
            .jsonArray
            .map {
                it.jsonObject
                    .getValue("key")
                    .jsonPrimitive.content
            }

    private fun errorCode(text: String): String =
        obj(text)
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private suspend fun seedNote(
        item: UUID,
        key: String,
        body: String,
        role: String = "work"
    ) {
        val answer = rig.ctx.upsertNoteVia(item, key, role = role, body = body)
        assertTrue(answer.toString().contains("\"ok\":true"), "fixture write through notes_write must succeed: $answer")
    }

    private suspend fun newItem(title: String = "owner"): WorkItem = rig.seed(title)

    // ------------------------------------------------------------------ S19 get

    @Test
    fun `S19 GET note answers the envelope equal to the ops notes_read get body, with ETag v2 after two writes`() {
        val item =
            runBlocking {
                val owner = newItem()
                seedNote(owner.id, "spec", "first")
                seedNote(owner.id, "spec", "second")
                owner
            }
        runApp(listOf(reader)) {
            val rest = getAs(reader, "/api/v1/items/${item.id}/notes/spec")
            val restBody = rest.bodyAsText()
            assertEquals(200, rest.status.value, restBody)
            assertEquals("\"v2\"", rest.headers[HttpHeaders.ETag], "ETag is v{version} in quotes")
            assertEquals(
                2L,
                obj(restBody)
                    .getValue("data")
                    .jsonObject
                    .getValue("version")
                    .jsonPrimitive.content
                    .toLong()
            )
            assertEquals(
                "second",
                obj(restBody)
                    .getValue("data")
                    .jsonObject
                    .getValue("body")
                    .jsonPrimitive.content
            )

            val ops = opsAs(reader, "notes_read", """{"select":"get","itemId":"${item.id}","key":"spec"}""")
            assertEquals(200, ops.status.value)
            assertEquals(normalized(ops.bodyAsText()), normalized(restBody), "REST GET and /ops are the same envelope minus meta.reqId")
        }
    }

    @Test
    fun `S19 If-None-Match equal to the current ETag answers 304 with an empty body, a different one answers the note`() {
        val item =
            runBlocking {
                val owner = newItem()
                seedNote(owner.id, "spec", "first")
                seedNote(owner.id, "spec", "second")
                owner
            }
        runApp(listOf(reader, writer)) {
            val path = "/api/v1/items/${item.id}/notes/spec"

            val notModified = getAs(reader, path, HttpHeaders.IfNoneMatch to "\"v2\"")
            assertEquals(304, notModified.status.value)
            assertEquals("", notModified.bodyAsText(), "a 304 has no body")

            val stale = getAs(reader, path, HttpHeaders.IfNoneMatch to "\"v1\"")
            assertEquals(200, stale.status.value)
            assertEquals("\"v2\"", stale.headers[HttpHeaders.ETag])
            assertTrue("second" in stale.bodyAsText(), "a stale validator gets the note")

            val longer = getAs(reader, path, HttpHeaders.IfNoneMatch to "\"v22\"")
            assertEquals(200, longer.status.value, "a validator that merely starts with v2 is not equal")

            val write =
                opsAs(
                    writer,
                    "notes_write",
                    """{"ops":[{"op":"upsert","itemId":"${item.id}","key":"spec","role":"work","body":"third"}]}"""
                )
            assertEquals(200, write.status.value, write.bodyAsText())
            val afterWrite = getAs(reader, path, HttpHeaders.IfNoneMatch to "\"v2\"")
            assertEquals(200, afterWrite.status.value, "the note moved on to v3, so v2 no longer matches")
            assertEquals("\"v3\"", afterWrite.headers[HttpHeaders.ETag])
            assertTrue("third" in afterWrite.bodyAsText())
        }
    }

    @Test
    fun `S19 GET of a missing note is a 404 not_found envelope without an ETag, a missing item likewise`() {
        val item =
            runBlocking {
                val owner = newItem()
                seedNote(owner.id, "present", "x")
                owner
            }
        runApp(listOf(reader)) {
            val missingKey = getAs(reader, "/api/v1/items/${item.id}/notes/absent")
            val keyBody = missingKey.bodyAsText()
            assertEquals(404, missingKey.status.value, keyBody)
            assertEquals("not_found", errorCode(keyBody))
            assertNull(missingKey.headers[HttpHeaders.ETag], "an error carries no ETag")
            assertTrue("\"kind\":\"note\"" in keyBody && "${item.id}/absent" in keyBody, keyBody)

            val missingItem = getAs(reader, "/api/v1/items/${UUID.randomUUID()}/notes/present")
            assertEquals(404, missingItem.status.value)
            assertEquals("not_found", errorCode(missingItem.bodyAsText()))
        }
    }

    // ------------------------------------------------------------------ S19 list

    @Test
    fun `S19 GET notes list equals the ops notes_read list body and carries no bodies`() {
        val item =
            runBlocking {
                val owner = newItem()
                seedNote(owner.id, "b", "BODY-B")
                seedNote(owner.id, "a", "BODY-A")
                owner
            }
        runApp(listOf(reader)) {
            val rest = getAs(reader, "/api/v1/items/${item.id}/notes")
            val restBody = rest.bodyAsText()
            assertEquals(200, rest.status.value, restBody)
            assertEquals(listOf("a", "b"), keysOf(restBody))
            assertFalse("BODY-" in restBody, "a list never carries a body: $restBody")

            val ops = opsAs(reader, "notes_read", """{"select":"list","itemId":"${item.id}"}""")
            assertEquals(normalized(ops.bodyAsText()), normalized(restBody), "REST GET and /ops are the same envelope minus meta.reqId")

            val byPrefix = getAs(reader, "/api/v1/items/${item.id.toString().take(8)}/notes")
            assertEquals(listOf("a", "b"), keysOf(byPrefix.bodyAsText()), "an 8-hex item prefix resolves")
        }
    }

    @Test
    fun `S19 the list query parameters role, keys, limit and cursor map to the select, an unknown one is invalid_request`() {
        val item =
            runBlocking {
                val owner = newItem()
                seedNote(owner.id, "a", "1", role = "queue")
                seedNote(owner.id, "b", "2", role = "work")
                seedNote(owner.id, "c", "3", role = "queue")
                owner
            }
        runApp(listOf(reader)) {
            val base = "/api/v1/items/${item.id}/notes"

            assertEquals(listOf("a", "c"), keysOf(getAs(reader, "$base?role=queue").bodyAsText()))
            assertEquals(listOf("a", "c"), keysOf(getAs(reader, "$base?keys=c,a").bodyAsText()), "keys is a CSV")

            val firstPage = obj(getAs(reader, "$base?limit=1").bodyAsText())
            val page =
                firstPage
                    .getValue("data")
                    .jsonObject
                    .getValue("page")
                    .jsonObject
            assertEquals("true", page.getValue("truncated").jsonPrimitive.content)
            val cursor = page.getValue("next").jsonPrimitive.content
            assertEquals(listOf("b"), keysOf(getAs(reader, "$base?limit=1&cursor=$cursor").bodyAsText()), "the cursor continues the walk")

            val unknown = getAs(reader, "$base?bogus=1")
            val unknownBody = unknown.bodyAsText()
            assertEquals(400, unknown.status.value, unknownBody)
            assertEquals("invalid_request", errorCode(unknownBody))

            val badCursor = getAs(reader, "$base?cursor=%25%25bad")
            assertEquals(400, badCursor.status.value)
            assertEquals("invalid_cursor", errorCode(badCursor.bodyAsText()))
        }
    }

    // ------------------------------------------------------------------ S19 removed write routes

    @Test
    fun `S19 PUT and DELETE on a note path are gone - the note is untouched - while ops notes_write still writes`() {
        val item =
            runBlocking {
                val owner = newItem()
                seedNote(owner.id, "spec", "ORIGINAL")
                owner
            }
        runApp(listOf(writer, reader)) {
            val path = "/api/v1/items/${item.id}/notes/spec"

            val put =
                client.put(path) {
                    header(HttpHeaders.Authorization, "Bearer ${writer.raw}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"work","body":"OVERWRITTEN"}""")
                }
            assertTrue(put.status.value in setOf(404, 405), "PUT note route is deleted but was ${put.status.value}")
            val del =
                client.delete(path) {
                    header(HttpHeaders.Authorization, "Bearer ${writer.raw}")
                }
            assertTrue(del.status.value in setOf(404, 405), "DELETE note route is deleted but was ${del.status.value}")

            val read = getAs(reader, path)
            assertEquals(200, read.status.value, "the note survived both calls")
            assertTrue("ORIGINAL" in read.bodyAsText())
            assertEquals("\"v1\"", read.headers[HttpHeaders.ETag], "and was never rewritten")

            val viaOps =
                opsAs(
                    writer,
                    "notes_write",
                    """{"ops":[{"op":"upsert","itemId":"${item.id}","key":"spec","role":"work","body":"VIA-OPS"}]}"""
                )
            assertEquals(200, viaOps.status.value, viaOps.bodyAsText())
            assertTrue("VIA-OPS" in getAs(reader, path).bodyAsText(), "control: the same token writes through /ops")
        }
    }

    // ------------------------------------------------------------------ S16 scope (REST half)

    @Test
    fun `S16 a root-scoped bearer reads notes of its tree and gets 403 forbidden outside it`() {
        val r1 = runBlocking { newItem("alpha root") }
        val c1 = runBlocking { rig.seed("alpha child", parent = r1) }
        val r2 = runBlocking { newItem("beta root") }
        runBlocking {
            seedNote(c1.id, "spec", "ALPHA-BODY")
            seedNote(r2.id, "spec", "BETA-BODY")
        }
        val scoped = ParityToken("nrr-root", setOf(ApiCapability.READ), rootIds = setOf(r1.id))
        runApp(listOf(scoped)) {
            val allowed = getAs(scoped, "/api/v1/items/${c1.id}/notes/spec")
            assertEquals(200, allowed.status.value, "control: an in-scope read succeeds")
            assertTrue("ALPHA-BODY" in allowed.bodyAsText())

            for (path in listOf("/api/v1/items/${r2.id}/notes/spec", "/api/v1/items/${r2.id}/notes")) {
                val denied = getAs(scoped, path)
                val body = denied.bodyAsText()
                assertEquals(403, denied.status.value, "$path: $body")
                assertEquals("forbidden", errorCode(body))
                assertTrue("\"scope\":\"root\"" in body && "\"required\":\"${r1.id}\"" in body, body)
                assertFalse("BETA-BODY" in body, "$path: an out-of-scope note text never leaks: $body")
                assertNull(denied.headers[HttpHeaders.ETag])
            }
        }
    }

    // ------------------------------------------------------------------ S18 attribution (REST half)

    private val sha = "cafebabe".repeat(8)

    private suspend fun seedEvidenceNote(item: WorkItem) {
        val claims =
            ProofClaims(
                iss = "https://issuer-rest.example",
                sub = "agent-rest",
                aud = listOf("aud-rest"),
                jti = "jti-rest",
                iat = 1000L,
                exp = 2000L,
                kid = "kid-rest",
                alg = "RS256"
            )
        assertNotNull(
            rig.raw.noteRepository().upsert(
                Note(
                    itemId = item.id,
                    key = "evidence",
                    role = "work",
                    body = "Evidence body",
                    actorClaim = ActorClaim(id = "agent-rest", kind = ActorKind.ORCHESTRATOR),
                    verification =
                        VerificationResult(
                            status = VerificationStatus.VERIFIED,
                            verifier = "jwks",
                            proofSha256 = sha,
                            proofClaims = claims
                        )
                )
            )
        )
    }

    private fun assertNoProofEvidence(
        label: String,
        body: String
    ) {
        for (evidence in listOf(
            sha,
            "jti-rest",
            "kid-rest",
            "RS256",
            "issuer-rest",
            "aud-rest",
            "\"sha256\"",
            "\"proof\"",
            "proofClaims"
        )) {
            assertFalse(evidence in body, "$label: proof evidence ($evidence) is never emitted: $body")
        }
    }

    @Test
    fun `S18 a non-admin bearer sees no actor or verification, an admin does, and nobody sees proof evidence`() {
        val item =
            runBlocking {
                val owner = newItem()
                seedEvidenceNote(owner)
                owner
            }
        runApp(listOf(reader, admin)) {
            for (path in listOf("/api/v1/items/${item.id}/notes/evidence", "/api/v1/items/${item.id}/notes")) {
                val asReader = getAs(reader, path).bodyAsText()
                assertFalse("\"actor\"" in asReader, "$path non-admin: no actor: $asReader")
                assertFalse("\"verification\"" in asReader, "$path non-admin: no verification: $asReader")
                assertFalse("agent-rest" in asReader, "$path non-admin: the actor id never appears: $asReader")
                assertTrue("evidence" in asReader, "$path control: the note is returned: $asReader")
                assertNoProofEvidence("$path non-admin", asReader)

                val asAdmin = getAs(admin, path).bodyAsText()
                assertTrue("\"actor\":{\"id\":\"agent-rest\"" in asAdmin, "$path admin: the actor is visible: $asAdmin")
                assertTrue(
                    "\"verification\":{\"status\":\"verified\"" in asAdmin,
                    "$path admin: the verification status is visible: $asAdmin"
                )
                assertNoProofEvidence("$path admin", asAdmin)
            }
        }
    }

    @Test
    fun `S18 the note routes honour redactNoteAttribution false for a non-admin bearer`() {
        val item =
            runBlocking {
                val owner = newItem()
                seedEvidenceNote(owner)
                owner
            }
        val toolContext = rig.ctx
        testApplication {
            application {
                configureTestApp {
                    noteResourceRoutes(ToolRegistry(buildToolSpecs()), buildToolDispatcher(toolContext), redactNoteAttribution = false)
                }
            }
            val response =
                client.get("/api/v1/items/${item.id}/notes/evidence") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            val body = response.bodyAsText()
            assertEquals(200, response.status.value, body)
            assertTrue("\"actor\":{\"id\":\"agent-rest\"" in body, "redaction off: a read-only bearer sees the actor: $body")
            assertNoProofEvidence("redaction off", body)
        }
    }

    @Test
    fun `S18 the note routes with redactNoteAttribution true hide attribution from a read-only bearer and show it to an admin`() {
        val item =
            runBlocking {
                val owner = newItem()
                seedEvidenceNote(owner)
                owner
            }
        val toolContext = rig.ctx
        testApplication {
            application {
                configureTestApp {
                    noteResourceRoutes(ToolRegistry(buildToolSpecs()), buildToolDispatcher(toolContext), redactNoteAttribution = true)
                }
            }
            val asReader =
                client
                    .get("/api/v1/items/${item.id}/notes/evidence") {
                        header("Authorization", "Bearer $TEST_TOKEN")
                    }.bodyAsText()
            assertTrue("Evidence body" in asReader, "control: the note is returned: $asReader")
            assertFalse("agent-rest" in asReader, "redaction on: a read-only bearer sees no actor: $asReader")
            assertFalse("\"verification\"" in asReader, "redaction on: no verification: $asReader")
            assertNoProofEvidence("redaction on", asReader)

            val asAdmin =
                client
                    .get("/api/v1/items/${item.id}/notes/evidence") {
                        header("Authorization", "Bearer $ADMIN_TOKEN")
                    }.bodyAsText()
            assertTrue("\"actor\":{\"id\":\"agent-rest\"" in asAdmin, "redaction on: an admin still sees the actor: $asAdmin")
            assertNoProofEvidence("redaction on, admin", asAdmin)
        }
    }
}
