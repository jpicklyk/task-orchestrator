package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A minimal, guaranteed-LF YAML fixture for this file. Deliberately built from explicit `\n`
 * escapes rather than a triple-quoted block: a triple-quoted Kotlin string literal reproduces
 * whatever line-ending convention the SOURCE FILE happens to be checked out with, which would
 * silently corrupt the LF-vs-CRLF distinction the CRLF probe below depends on.
 */
private val GUARD_ORDER_VALID_YAML =
    "work_item_schemas:\n" +
        "  feature-task:\n" +
        "    notes:\n" +
        "      - key: spec\n" +
        "        role: queue\n" +
        "        required: true\n"

/**
 * REST guard-order tests for item `fab1b3ea` ("Move the project-config fingerprint compare-and-set
 * inside the upsert transaction") — the `PUT /roots/{rootId}/config` route's guard evaluation
 * order (validation before the rootId/fast-forward guards before `If-Match`), the
 * blank/absent/no-row `If-Match` matrix, and replay idempotency, none of which
 * [ProjectConfigRoutesTest] already covers.
 *
 * Test-plan scenarios covered: S6, S7, S9, S10, plus the adversarial probes from the test-plan's
 * Probes section (CRLF re-fingerprinting, unquoted/uppercase ETag values, `force=true` + stale
 * `If-Match`).
 *
 * Oracle: `current/docs/api-rest.md` §18 "PUT /roots/{rootId}/config" (the numbered guard order
 * 1-7, the `force` semantics, and the `"cfg-<fingerprint>"` ETag format), and
 * `current/docs/api-reference.md`'s "Pushing byte-identical content is naturally idempotent"
 * push description (cited for S10). All scenarios here are `EXISTING-SURFACE`: the route, its
 * status codes, and its ETag format all pre-date this item — this item only moves WHEN two
 * already-existing guards (6 and 7) are evaluated relative to each other and to the write, so a
 * plain revert of the fix still exercises the same route surface and yields behavioral red.
 */
class ProjectConfigRoutesGuardOrderTest {
    private fun createGuardOrderRoot(repo: DefaultRepositoryProvider): WorkItem =
        runBlocking {
            (
                repo.workItemRepository().create(
                    WorkItem(title = "Guard Order Root", type = "project", depth = 0),
                ) as Result.Success
            ).data
        }

    // ──────────────────────────────────────────────
    // S6 — a superseded body wins over a mismatched If-Match (guard 6 before guard 7)
    // ──────────────────────────────────────────────

    @Test
    fun `S6 a superseded body with a mismatched If-Match returns 409 superseded, not 412`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createGuardOrderRoot(repo)
            application { configureProjectConfigTestApp(repo) }
            val yamlA = GUARD_ORDER_VALID_YAML
            val yamlB = GUARD_ORDER_VALID_YAML + "\n"

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(yamlA)
            }
            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(yamlB)
            }

            // yamlA is now superseded by yamlB. Attach a deliberately WRONG If-Match too — guard 6
            // (fast-forward/superseded) must fire before guard 7 (If-Match) is ever consulted.
            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "\"cfg-${"0".repeat(64)}\"")
                    contentType(ContentType.Text.Plain)
                    setBody(yamlA)
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("superseded"))

            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertEquals(yamlB, (persisted as Result.Success).data?.configYaml, "the rejected push must not overwrite B")
        }

    // ──────────────────────────────────────────────
    // S7 — malformed YAML wins over a stale If-Match (validation before guard 7)
    // ──────────────────────────────────────────────

    @Test
    fun `S7 malformed YAML with a stale If-Match returns 422 parse_error, not 412`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createGuardOrderRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(GUARD_ORDER_VALID_YAML)
            }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "\"cfg-${"0".repeat(64)}\"")
                    contentType(ContentType.Text.Plain)
                    setBody("work_item_schemas: [this is not, a valid: map")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(response.bodyAsText().contains("parse_error"))
        }

    // ──────────────────────────────────────────────
    // S9 — blank vs. absent vs. no-row If-Match
    // ──────────────────────────────────────────────

    @Test
    fun `S9a a blank but present If-Match on an existing row is a mismatch, 412`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createGuardOrderRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(GUARD_ORDER_VALID_YAML)
            }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "")
                    contentType(ContentType.Text.Plain)
                    setBody(GUARD_ORDER_VALID_YAML + "\n")
                }

            assertEquals(HttpStatusCode.PreconditionFailed, response.status)
            assertTrue(response.bodyAsText().contains("etag_mismatch"))
        }

    @Test
    fun `S9b an absent If-Match on an existing row is not evaluated, 200`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createGuardOrderRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(GUARD_ORDER_VALID_YAML)
            }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    // Deliberately no If-Match header at all — it's optional per api-rest.md.
                    contentType(ContentType.Text.Plain)
                    setBody(GUARD_ORDER_VALID_YAML + "\n")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `S9c a blank If-Match with no existing row is ignored — first push is a create, 200`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createGuardOrderRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "")
                    contentType(ContentType.Text.Plain)
                    setBody(GUARD_ORDER_VALID_YAML)
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    // ──────────────────────────────────────────────
    // S10 — replay: identical body pushed twice with If-Match = the row's own current ETag
    // ──────────────────────────────────────────────

    @Test
    fun `S10 replaying the same body with If-Match set to its own current ETag succeeds both times with the same ETag`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createGuardOrderRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val first =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(GUARD_ORDER_VALID_YAML)
                }
            assertEquals(HttpStatusCode.OK, first.status)
            val etag = first.headers[HttpHeaders.ETag]
            assertNotNull(etag)

            val second =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Text.Plain)
                    setBody(GUARD_ORDER_VALID_YAML)
                }

            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals(
                etag,
                second.headers[HttpHeaders.ETag],
                "a byte-identical replay is naturally idempotent — same ETag both times",
            )
        }

    // ──────────────────────────────────────────────
    // Adversarial probes
    // ──────────────────────────────────────────────

    @Test
    fun `probe a CRLF copy of the current content re-fingerprints and is accepted even with If-Match naming the LF fingerprint`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createGuardOrderRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val first =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(GUARD_ORDER_VALID_YAML)
                }
            val originalEtag = first.headers[HttpHeaders.ETag]
            assertNotNull(originalEtag)

            val crlfBody = GUARD_ORDER_VALID_YAML.replace("\n", "\r\n")
            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, originalEtag)
                    contentType(ContentType.Text.Plain)
                    setBody(crlfBody)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val newEtag = response.headers[HttpHeaders.ETag]
            assertNotNull(newEtag)
            assertNotEquals(
                originalEtag,
                newEtag,
                "CRLF changes the byte sequence, so the fingerprint (SHA-256 of bytes) must change",
            )

            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertEquals(crlfBody, (persisted as Result.Success).data?.configYaml)
        }

    @Test
    fun `probe an unquoted If-Match value matching the fingerprint digits is a mismatch, 412`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createGuardOrderRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val first =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(GUARD_ORDER_VALID_YAML)
                }
            val etag = first.headers[HttpHeaders.ETag]
            assertNotNull(etag)
            val unquoted = etag.removeSurrounding("\"")

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, unquoted)
                    contentType(ContentType.Text.Plain)
                    setBody(GUARD_ORDER_VALID_YAML + "\n")
                }

            assertEquals(HttpStatusCode.PreconditionFailed, response.status)
        }

    @Test
    fun `probe an uppercase-hex If-Match value is a mismatch, 412`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createGuardOrderRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val first =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(GUARD_ORDER_VALID_YAML)
                }
            val etag = first.headers[HttpHeaders.ETag]
            assertNotNull(etag)
            val uppercased = etag.uppercase()

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, uppercased)
                    contentType(ContentType.Text.Plain)
                    setBody(GUARD_ORDER_VALID_YAML + "\n")
                }

            assertEquals(HttpStatusCode.PreconditionFailed, response.status)
        }

    @Test
    fun `probe force=true with a stale If-Match still returns 412 — force skips guards 5-6 only`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createGuardOrderRoot(repo)
            application { configureProjectConfigTestApp(repo) }
            val yamlB = GUARD_ORDER_VALID_YAML + "\n"

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(GUARD_ORDER_VALID_YAML)
            }
            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(yamlB)
            }

            val response =
                client.put("/api/v1/roots/${root.id}/config?force=true") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "\"cfg-${"0".repeat(64)}\"")
                    contentType(ContentType.Text.Plain)
                    setBody(GUARD_ORDER_VALID_YAML)
                }

            assertEquals(HttpStatusCode.PreconditionFailed, response.status)
        }
}
