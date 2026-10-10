package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f75eb22e / Q8) for the versioned REST config routes: version reads, history,
 * rollback, `cfg-v<n>` ETags with the legacy fingerprint form still accepted, DELETE as a reset version, and the
 * config-sync flow.
 *
 * Oracles: task-scope section 1.10 (route contract, status codes, ETag and If-Match / If-None-Match grammar, DTO fields),
 * 1.11 AC3-AC8 and AC13, decisions D4-D5 and D8. Fingerprints are computed through the store's pure `computeFingerprint`.
 * Header and error-code strings (`etag_mismatch`, `superseded`, `bad_request`, `validation_error`, `not_found`) are taken
 * from the plan text; no public constants exist for them.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ConfigVersionRoutesTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val readToken = TEST_TOKEN

    private val base =
        "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: true\n"

    private fun rev(n: Int) = "$base# rev $n\n"

    private fun makeRoot(
        repo: DefaultRepositoryProvider,
        title: String = "Project Root"
    ): UUID = runBlocking { repo.workItemRepository().create(WorkItem(title = title, type = "project", depth = 0)).id }

    private fun fp(yaml: String): String = db.repositoryProvider().configStore().computeFingerprint(yaml)

    private fun etagOf(version: Int) = "\"cfg-v$version\""

    private fun historySize(
        repo: DefaultRepositoryProvider,
        root: UUID
    ): Int = runBlocking { repo.configStore().history(ConfigScope.ROOT, root, 200).size }

    private suspend fun HttpClient.putConfig(
        root: UUID,
        yaml: String,
        ifMatch: String? = null,
        force: Boolean = false,
        token: String = WRITE_TOKEN
    ): HttpResponse =
        put("/api/v1/roots/$root/config" + if (force) "?force=true" else "") {
            header("Authorization", "Bearer $token")
            if (ifMatch != null) header(HttpHeaders.IfMatch, ifMatch)
            contentType(ContentType.Text.Plain)
            setBody(yaml)
        }

    private suspend fun HttpClient.getConfig(
        root: UUID,
        query: String = "",
        ifNoneMatch: String? = null,
        token: String = readToken
    ): HttpResponse =
        get("/api/v1/roots/$root/config$query") {
            header("Authorization", "Bearer $token")
            if (ifNoneMatch != null) header(HttpHeaders.IfNoneMatch, ifNoneMatch)
        }

    private suspend fun HttpClient.getHistory(
        root: UUID,
        query: String = "",
        token: String = readToken
    ): HttpResponse =
        get("/api/v1/roots/$root/config/history$query") {
            header("Authorization", "Bearer $token")
        }

    private suspend fun HttpClient.postRollback(
        root: UUID,
        body: String,
        ifMatch: String? = null,
        token: String = WRITE_TOKEN
    ): HttpResponse =
        post("/api/v1/roots/$root/config/rollback") {
            header("Authorization", "Bearer $token")
            if (ifMatch != null) header(HttpHeaders.IfMatch, ifMatch)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int

    private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.versions(): List<Int> = this["versions"]!!.jsonArray.map { it.jsonObject["version"]!!.jsonPrimitive.int }

    // ---------------------------------------------------------------------------------------------
    // S3 - PUT / GET / history
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 PUT answers with ETag cfg-v1 then cfg-v2 and the version fields`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }

            val first = client.putConfig(root, rev(1))
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(etagOf(1), first.headers[HttpHeaders.ETag])
            val firstBody = first.json()
            assertEquals(1, firstBody.intOrNull("version"))
            assertEquals(true, firstBody["appended"]!!.jsonPrimitive.boolean)
            assertEquals(fp(rev(1)), firstBody.str("fingerprint"))
            assertEquals(root.toString(), firstBody.str("rootId"))

            val second = client.putConfig(root, rev(2))
            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals(etagOf(2), second.headers[HttpHeaders.ETag])
            assertEquals(2, second.json().intOrNull("version"))
        }

    @Test
    fun `S3 GET serves the head and any version with its own ETag, version and source`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))

            val head = client.getConfig(root)
            assertEquals(HttpStatusCode.OK, head.status)
            assertEquals(etagOf(2), head.headers[HttpHeaders.ETag])
            val headBody = head.json()
            assertEquals(2, headBody.intOrNull("version"))
            assertEquals("push", headBody.str("source"))
            assertEquals(rev(2), headBody.str("configYaml"))
            assertEquals(fp(rev(2)), headBody.str("fingerprint"))

            val old = client.getConfig(root, "?version=1")
            assertEquals(HttpStatusCode.OK, old.status)
            assertEquals(etagOf(1), old.headers[HttpHeaders.ETag])
            val oldBody = old.json()
            assertEquals(1, oldBody.intOrNull("version"))
            assertEquals(rev(1), oldBody.str("configYaml"))
            assertEquals(fp(rev(1)), oldBody.str("fingerprint"))
        }

    @Test
    fun `S3 GET with an unknown, malformed or non-positive version is 404 or 400 bad_request`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            val bare = makeRoot(repo, "No config")
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))

            val missing = client.getConfig(root, "?version=99")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue(missing.bodyAsText().contains("not_found"), "404 carries the not_found code")
            assertEquals(HttpStatusCode.NotFound, client.getConfig(bare, "?version=1").status)

            for (bad in listOf("abc", "0", "-1", "1.5")) {
                val resp = client.getConfig(root, "?version=$bad")
                assertEquals(HttpStatusCode.BadRequest, resp.status, "version='$bad'")
                assertTrue(resp.bodyAsText().contains("bad_request"), "version='$bad' carries bad_request")
            }
        }

    @Test
    fun `S3 GET honours If-None-Match in the version form and the legacy fingerprint form`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))

            assertEquals(HttpStatusCode.NotModified, client.getConfig(root, ifNoneMatch = etagOf(1)).status)
            assertEquals(HttpStatusCode.NotModified, client.getConfig(root, ifNoneMatch = "\"cfg-${fp(rev(1))}\"").status)
            assertEquals(HttpStatusCode.NotModified, client.getConfig(root, "?version=1", ifNoneMatch = etagOf(1)).status)

            client.putConfig(root, rev(2))

            val staleVersion = client.getConfig(root, ifNoneMatch = etagOf(1))
            assertEquals(HttpStatusCode.OK, staleVersion.status, "a stale version ETag no longer matches the head")
            val staleLegacy = client.getConfig(root, ifNoneMatch = "\"cfg-${fp(rev(1))}\"")
            assertEquals(HttpStatusCode.OK, staleLegacy.status, "the superseded fingerprint no longer matches the head")
            assertEquals(HttpStatusCode.NotModified, client.getConfig(root, ifNoneMatch = etagOf(2)).status)
        }

    @Test
    fun `S3 history lists versions newest first with summaries and headVersion`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))
            client.putConfig(root, rev(3))

            val resp = client.getHistory(root)

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.json()
            assertEquals(root.toString(), body.str("rootId"))
            assertEquals(3, body.intOrNull("headVersion"))
            assertEquals(listOf(3, 2, 1), body.versions())
            val rows = body["versions"]!!.jsonArray.map { it.jsonObject }
            assertEquals(fp(rev(3)), rows[0].str("fingerprint"))
            assertEquals(fp(rev(1)), rows[2].str("fingerprint"))
            assertTrue(rows.all { it.str("source") == "push" })
            assertTrue(rows.all { !it.str("createdAt").isNullOrBlank() })
            assertTrue(rows.all { it["canonical"]!!.jsonPrimitive.boolean }, "pushed versions carry a canonical form")
            assertTrue(rows.all { it.intOrNull("warningsCount") == 0 })
            assertTrue(rows.all { it["yaml"] == null && it["configYaml"] == null }, "summaries do not carry the document")
        }

    @Test
    fun `S3 history honours limit and before, and validates them`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            for (n in 1..5) client.putConfig(root, rev(n))

            assertEquals(listOf(5, 4), client.getHistory(root, "?limit=2").json().versions())
            assertEquals(listOf(2, 1), client.getHistory(root, "?before=3").json().versions())
            assertEquals(listOf(3, 2), client.getHistory(root, "?limit=2&before=4").json().versions())
            assertEquals(emptyList(), client.getHistory(root, "?before=1").json().versions())
            assertEquals(listOf(5), client.getHistory(root, "?limit=1").json().versions())
            assertEquals(HttpStatusCode.OK, client.getHistory(root, "?limit=200").status)

            for (bad in listOf("0", "201", "-5")) {
                val resp = client.getHistory(root, "?limit=$bad")
                assertEquals(HttpStatusCode.BadRequest, resp.status, "limit=$bad")
                assertTrue(resp.bodyAsText().contains("validation_error"), "limit=$bad carries validation_error")
            }
            assertEquals(HttpStatusCode.BadRequest, client.getHistory(root, "?limit=abc").status)
            assertEquals(HttpStatusCode.BadRequest, client.getHistory(root, "?before=abc").status)
        }

    @Test
    fun `S3 history of an unknown root or a root without config is 200 with no versions and no headVersion`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val bare = makeRoot(repo, "No config")
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }

            for (id in listOf(bare, UUID.randomUUID())) {
                val resp = client.getHistory(id)
                assertEquals(HttpStatusCode.OK, resp.status, "root $id")
                val body = resp.json()
                assertEquals(emptyList(), body.versions())
                assertNull(body.intOrNull("headVersion"))
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S4 - identical re-PUT
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 an identical PUT answers 200 with the same ETag and appends nothing`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            val first = client.putConfig(root, base)

            val again = client.putConfig(root, base)

            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals(first.headers[HttpHeaders.ETag], again.headers[HttpHeaders.ETag])
            assertEquals(etagOf(1), again.headers[HttpHeaders.ETag])
            val body = again.json()
            assertEquals(1, body.intOrNull("version"))
            assertEquals(false, body["appended"]!!.jsonPrimitive.boolean)
            assertEquals(1, historySize(repo, root), "fixture condition: a second row would make this 2")
        }

    // ---------------------------------------------------------------------------------------------
    // S5 - If-Match grammar
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 a stale cfg-v If-Match is 412 etag_mismatch with the refreshed ETag and stores nothing`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))

            val stale = client.putConfig(root, rev(3), ifMatch = etagOf(1))

            assertEquals(HttpStatusCode.PreconditionFailed, stale.status)
            assertTrue(stale.bodyAsText().contains("etag_mismatch"))
            assertEquals(etagOf(2), stale.headers[HttpHeaders.ETag], "the 412 carries the current head ETag")
            assertEquals(2, historySize(repo, root))
        }

    @Test
    fun `S5 a matching cfg-v If-Match applies`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))

            val ok = client.putConfig(root, rev(3), ifMatch = etagOf(2))

            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(etagOf(3), ok.headers[HttpHeaders.ETag])
        }

    @Test
    fun `S5 the legacy cfg-fingerprint If-Match still works for the head and fails for anything else`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))

            val stale = client.putConfig(root, rev(3), ifMatch = "\"cfg-${fp(rev(1))}\"")
            assertEquals(HttpStatusCode.PreconditionFailed, stale.status)
            assertTrue(stale.bodyAsText().contains("etag_mismatch"))
            assertEquals(2, historySize(repo, root))

            val ok = client.putConfig(root, rev(3), ifMatch = "\"cfg-${fp(rev(2))}\"")
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(etagOf(3), ok.headers[HttpHeaders.ETag])
        }

    @Test
    fun `S5 malformed If-Match values are 412 on an existing head and never apply`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))

            val malformed = listOf("\"banana\"", "W/\"cfg-v1\"", "\"cfg-V1\"", "\"cfg-v-1\"", "*x", "\"\"")
            for (value in malformed) {
                val resp = client.putConfig(root, rev(2), ifMatch = value)
                assertEquals(HttpStatusCode.PreconditionFailed, resp.status, "If-Match: $value")
            }
            assertEquals(1, historySize(repo, root), "none of the malformed values applied")
        }

    @Test
    fun `S5 If-Match is ignored while the root has no config, whatever it says`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val rootA = makeRoot(repo, "A")
            val rootB = makeRoot(repo, "B")
            val rootC = makeRoot(repo, "C")
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }

            assertEquals(HttpStatusCode.OK, client.putConfig(rootA, base, ifMatch = "\"banana\"").status)
            assertEquals(HttpStatusCode.OK, client.putConfig(rootB, base, ifMatch = etagOf(5)).status)
            assertEquals(HttpStatusCode.OK, client.putConfig(rootC, base, ifMatch = "\"cfg-${"0".repeat(64)}\"").status)
        }

    @Test
    fun `S5 force=true never skips the If-Match check`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))

            val resp = client.putConfig(root, rev(3), ifMatch = etagOf(1), force = true)

            assertEquals(HttpStatusCode.PreconditionFailed, resp.status)
            assertEquals(2, historySize(repo, root))
        }
    // ---------------------------------------------------------------------------------------------
    // S6 - fast-forward guard over REST
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 re-PUTting superseded content is 409 superseded and force=true appends it`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))

            val rejected = client.putConfig(root, rev(1))

            assertEquals(HttpStatusCode.Conflict, rejected.status)
            assertTrue(rejected.bodyAsText().contains("superseded"))
            assertEquals(2, historySize(repo, root))

            val forced = client.putConfig(root, rev(1), force = true)
            assertEquals(HttpStatusCode.OK, forced.status)
            assertEquals(etagOf(3), forced.headers[HttpHeaders.ETag])
        }

    // ---------------------------------------------------------------------------------------------
    // S8 / S9 - DELETE is a reset version
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 DELETE resets, GET head is 404, the reset and older versions stay readable, history shows the reset`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))

            val deleted = client.delete("/api/v1/roots/$root/config") { header("Authorization", "Bearer $WRITE_TOKEN") }
            assertEquals(HttpStatusCode.NoContent, deleted.status)

            val head = client.getConfig(root)
            assertEquals(HttpStatusCode.NotFound, head.status)
            assertTrue(head.bodyAsText().contains("No project config found for root"), "the 3.x message is unchanged")

            val resetVersion = client.getConfig(root, "?version=2")
            assertEquals(HttpStatusCode.OK, resetVersion.status)
            val resetBody = resetVersion.json()
            assertEquals(2, resetBody.intOrNull("version"))
            assertEquals("reset", resetBody.str("source"))
            assertEquals("", resetBody.str("configYaml"))
            assertEquals(etagOf(2), resetVersion.headers[HttpHeaders.ETag])

            val old = client.getConfig(root, "?version=1")
            assertEquals(HttpStatusCode.OK, old.status)
            assertEquals(rev(1), old.json().str("configYaml"))

            val history = client.getHistory(root).json()
            assertEquals(listOf(2, 1), history.versions())
            val newest = history["versions"]!!.jsonArray[0].jsonObject
            assertEquals("reset", newest.str("source"))
            assertEquals(fp(""), newest.str("fingerprint"))
        }

    @Test
    fun `S8 a second DELETE, and a DELETE of a root without config, are 404`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            val bare = makeRoot(repo, "No config")
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .delete("/api/v1/roots/$root/config") {
                        header("Authorization", "Bearer $WRITE_TOKEN")
                    }.status
            )

            assertEquals(
                HttpStatusCode.NotFound,
                client.delete("/api/v1/roots/$root/config") { header("Authorization", "Bearer $WRITE_TOKEN") }.status
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.delete("/api/v1/roots/$bare/config") { header("Authorization", "Bearer $WRITE_TOKEN") }.status
            )
            assertEquals(2, historySize(repo, root), "fixture condition: a second reset row would make this 3")
        }

    @Test
    fun `S9 PUT of pre-reset content after a DELETE is accepted, not 409`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))
            client.delete("/api/v1/roots/$root/config") { header("Authorization", "Bearer $WRITE_TOKEN") }

            val again = client.putConfig(root, rev(1))

            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals(etagOf(4), again.headers[HttpHeaders.ETag])
            assertEquals(HttpStatusCode.OK, client.getConfig(root).status, "the config is visible again")
        }

    // ---------------------------------------------------------------------------------------------
    // S10 - rollback
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S10 rollback answers 200 with the new head version and ETag and no document, and history shows the rollback row`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))

            val resp = client.postRollback(root, "{\"version\":1}")

            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(etagOf(3), resp.headers[HttpHeaders.ETag])
            val body = resp.json()
            assertEquals(3, body.intOrNull("version"))
            assertEquals(fp(rev(1)), body.str("fingerprint"))
            assertEquals(root.toString(), body.str("rootId"))
            assertNull(body.str("configYaml"), "the rollback response carries no document")

            val head = client.getConfig(root).json()
            assertEquals(3, head.intOrNull("version"))
            assertEquals(rev(1), head.str("configYaml"))
            val newest =
                client
                    .getHistory(root)
                    .json()["versions"]!!
                    .jsonArray[0]
                    .jsonObject
            assertEquals("rollback", newest.str("source"))
            assertEquals(3, newest.intOrNull("version"))
        }

    @Test
    fun `S10 rollback to a version equal to the head is 200 with the head ETag and appends nothing`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))
            client.postRollback(root, "{\"version\":1}")

            val again = client.postRollback(root, "{\"version\":1}")

            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals(etagOf(3), again.headers[HttpHeaders.ETag])
            assertEquals(3, again.json().intOrNull("version"))
            assertEquals(3, historySize(repo, root), "fixture condition: an appended row would make this 4")
        }

    @Test
    fun `S10 rollback to an unknown version or on a root without config is 404 not_found`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            val bare = makeRoot(repo, "No config")
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))

            val unknown = client.postRollback(root, "{\"version\":99}")
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertTrue(unknown.bodyAsText().contains("not_found"))
            assertEquals(HttpStatusCode.NotFound, client.postRollback(bare, "{\"version\":1}").status)
            assertEquals(1, historySize(repo, root))
        }

    @Test
    fun `S10 rollback validates its JSON body with 400 bad_request`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))

            val bodies =
                listOf(
                    "",
                    "not json",
                    "{}",
                    "[1]",
                    "{\"version\":null}",
                    "{\"version\":\"abc\"}",
                    "{\"version\":0}",
                    "{\"version\":-1}",
                    "{\"version\":1.5}",
                    "{\"other\":1}"
                )
            for (body in bodies) {
                val resp = client.postRollback(root, body)
                assertEquals(HttpStatusCode.BadRequest, resp.status, "body='$body'")
                assertTrue(resp.bodyAsText().contains("bad_request"), "body='$body' carries bad_request")
            }
            assertEquals(2, historySize(repo, root), "no invalid body changed the config")
        }

    @Test
    fun `S10 rollback honours If-Match in both forms`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))

            val staleVersion = client.postRollback(root, "{\"version\":1}", ifMatch = etagOf(1))
            assertEquals(HttpStatusCode.PreconditionFailed, staleVersion.status)
            assertTrue(staleVersion.bodyAsText().contains("etag_mismatch"))
            val staleLegacy = client.postRollback(root, "{\"version\":1}", ifMatch = "\"cfg-${fp(rev(1))}\"")
            assertEquals(HttpStatusCode.PreconditionFailed, staleLegacy.status)
            assertEquals(2, historySize(repo, root))

            val ok = client.postRollback(root, "{\"version\":1}", ifMatch = etagOf(2))
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(3, ok.json().intOrNull("version"))
        }

    // ---------------------------------------------------------------------------------------------
    // S15 - authorization of the new routes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S15 history and version reads need root scope, rollback needs WRITE_CONFIG and scope`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            val other = makeRoot(repo, "Other")
            application {
                configureProjectConfigTestApp(
                    repo,
                    authConfig = makeWriteAuthConfig(scopeRootIds = setOf(other)),
                    unitOfWork = db.unitOfWork()
                )
            }

            assertEquals(HttpStatusCode.Forbidden, client.getHistory(root).status, "history outside the token's root scope")
            assertEquals(
                HttpStatusCode.Forbidden,
                client.getConfig(root, "?version=1").status,
                "version read outside the token's root scope"
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.postRollback(root, "{\"version\":1}").status,
                "rollback outside the token's root scope"
            )
            assertEquals(HttpStatusCode.OK, client.getHistory(other).status, "control: the scoped root itself is readable")
        }

    @Test
    fun `S15 a read-only token can read history but cannot roll back`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }
            client.putConfig(root, rev(1))
            client.putConfig(root, rev(2))

            assertEquals(HttpStatusCode.OK, client.getHistory(root, token = readToken).status)
            val denied = client.postRollback(root, "{\"version\":1}", token = readToken)
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            assertEquals(2, historySize(repo, root))
        }

    // ---------------------------------------------------------------------------------------------
    // AC13 - the config-sync flow (GET ?fingerprint= then PUT with the echoed ETag as If-Match)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `AC13 the config-sync flow works unchanged against versioned ETags`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }

            assertEquals(HttpStatusCode.NotFound, client.getConfig(root, "?fingerprint=${fp(rev(1))}").status, "no config yet means push")

            val firstPut = client.putConfig(root, rev(1))
            assertEquals(HttpStatusCode.OK, firstPut.status)
            val etag1 = assertNotNull(firstPut.headers[HttpHeaders.ETag])

            val probe = client.getConfig(root, "?fingerprint=${fp(rev(1))}")
            assertEquals(HttpStatusCode.OK, probe.status)
            assertEquals("current", probe.json().str("relation"))
            assertEquals(etag1, probe.headers[HttpHeaders.ETag], "the echoed ETag is the one the PUT returned")

            val secondPut = client.putConfig(root, rev(2), ifMatch = etag1)
            assertEquals(HttpStatusCode.OK, secondPut.status)
            assertNotEquals(etag1, secondPut.headers[HttpHeaders.ETag])

            val racing = client.putConfig(root, rev(3), ifMatch = etag1)
            assertEquals(HttpStatusCode.PreconditionFailed, racing.status, "a writer holding the old ETag loses")

            assertEquals("superseded", client.getConfig(root, "?fingerprint=${fp(rev(1))}").json().str("relation"))
            assertEquals("current", client.getConfig(root, "?fingerprint=${fp(rev(2))}").json().str("relation"))
            assertEquals("unknown", client.getConfig(root, "?fingerprint=${"0".repeat(64)}").json().str("relation"))
        }

    @Test
    fun `AC13 the response JSON arrays and keys of the 3x fields are still present on PUT and GET`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = makeRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }

            val put = client.putConfig(root, "$base" + "actor_authentication:\n  mode: jwks\n").json()
            assertEquals(listOf("actor_authentication"), (put["ignoredSections"] as JsonArray).map { it.jsonPrimitive.content })
            assertTrue(!put.str("updatedAt").isNullOrBlank())

            val get = client.getConfig(root).json()
            for (key in listOf("rootId", "fingerprint", "updatedAt", "configYaml")) assertNotNull(get[key], "GET keeps '$key'")
        }
}
