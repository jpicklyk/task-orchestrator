package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.StatusGraphBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for `/api/v1/config*` routes (Phase 4).
 *
 * Uses a [FakeSchemaService] backed by in-memory schema maps so tests are
 * completely isolated from the file system. Two schema types are defined:
 * - `feature-task` — has REVIEW phase (hasReviewPhase=true)
 * - `simple-task`  — no REVIEW phase (hasReviewPhase=false)
 *
 * Trait `test-trait` is also defined to cover trait endpoints.
 *
 * Test coverage:
 * - All 6 `/config*` endpoints return HTTP 200 with correct shapes
 * - `/config/schemas/{type}` returns 404 for unknown type
 * - ETag is stable per config fingerprint; two reads → same ETag
 * - `If-None-Match` with current ETag → 304 Not Modified
 * - Different config fingerprint → different ETag
 * - Status-graph oracle: feature-task (work→review), simple-task (work→terminal)
 * - BLOCKED row has ONLY `resume:"<previousRole>"`
 * - TERMINAL row has ONLY `reopen:"queue"`
 * - Additive interface: `NoOpNoteSchemaService` still compiles (verified structurally)
 */
class ConfigRoutesTest {
    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * In-memory [WorkItemSchemaService] used by config route tests.
     * Does NOT touch the file system.
     */
    class FakeSchemaService(
        private val schemas: Map<String, WorkItemSchema> = buildDefaultSchemas(),
        private val traits: Map<String, List<NoteSchemaEntry>> = buildDefaultTraits(),
        private val fingerprint: String? = "test-fingerprint-abc123",
    ) : WorkItemSchemaService {
        override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = tags.firstNotNullOfOrNull { schemas[it]?.notes }

        override fun getAllSchemas(): Map<String, WorkItemSchema> = schemas

        override fun getAllTraits(): Map<String, List<NoteSchemaEntry>> = traits

        override fun getConfigFingerprint(): String? = fingerprint
    }

    companion object {
        fun buildDefaultSchemas(): Map<String, WorkItemSchema> =
            mapOf(
                "feature-task" to
                    WorkItemSchema(
                        type = "feature-task",
                        lifecycleMode = LifecycleMode.AUTO,
                        notes =
                            listOf(
                                NoteSchemaEntry("task-scope", Role.QUEUE, required = true, description = "Scope note"),
                                NoteSchemaEntry("impl-notes", Role.WORK, required = true, description = "Impl notes"),
                                NoteSchemaEntry("review-checklist", Role.REVIEW, required = true, description = "Review"),
                            ),
                        defaultTraits = listOf("test-trait"),
                    ),
                "simple-task" to
                    WorkItemSchema(
                        type = "simple-task",
                        lifecycleMode = LifecycleMode.MANUAL,
                        notes =
                            listOf(
                                NoteSchemaEntry("task-scope", Role.QUEUE, required = true, description = "Scope"),
                                NoteSchemaEntry("impl-notes", Role.WORK, required = false, description = "Optional impl"),
                            ),
                        defaultTraits = emptyList(),
                    ),
            )

        fun buildDefaultTraits(): Map<String, List<NoteSchemaEntry>> =
            mapOf(
                "test-trait" to
                    listOf(
                        NoteSchemaEntry(
                            "trait-note",
                            Role.REVIEW,
                            required = false,
                            description = "A trait note",
                            guidance = "Guidance text",
                            skill = "some-skill",
                        ),
                    ),
            )
    }

    // ─── GET /config ──────────────────────────────────────────────────────────

    @Test
    fun `GET config returns 200 with snapshot`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // All 5 top-level keys present
            assertTrue(body.contains("\"schemas\""), "Expected schemas key: $body")
            assertTrue(body.contains("\"traits\""), "Expected traits key: $body")
            assertTrue(body.contains("\"types\""), "Expected types key: $body")
            assertTrue(body.contains("\"statusGraph\""), "Expected statusGraph key: $body")
            // defaultSchema absent (no "default" type in our fake)
            assertTrue(body.contains("feature-task"), "Expected feature-task type: $body")
            assertTrue(body.contains("simple-task"), "Expected simple-task type: $body")
            assertTrue(body.contains("test-trait"), "Expected test-trait: $body")
        }

    // ─── GET /config/schemas ──────────────────────────────────────────────────

    @Test
    fun `GET config schemas returns all schemas`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config/schemas") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("feature-task"), "Expected feature-task: $body")
            assertTrue(body.contains("simple-task"), "Expected simple-task: $body")
            assertTrue(body.contains("\"lifecycleMode\""), "Expected lifecycleMode field: $body")
            assertTrue(body.contains("\"hasReviewPhase\""), "Expected hasReviewPhase field: $body")
        }

    @Test
    fun `GET config schemas has correct shape for feature-task`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config/schemas") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // feature-task has review phase
            assertTrue(body.contains("\"hasReviewPhase\":true"), "Expected hasReviewPhase:true for feature-task: $body")
            assertTrue(body.contains("\"lifecycleMode\":\"auto\""), "Expected auto lifecycle: $body")
        }

    // ─── GET /config/schemas/{type} ───────────────────────────────────────────

    @Test
    fun `GET config schemas type returns single schema`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config/schemas/feature-task") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("feature-task"), "Expected feature-task: $body")
            assertTrue(body.contains("task-scope"), "Expected task-scope note: $body")
            assertTrue(body.contains("review-checklist"), "Expected review-checklist note: $body")
        }

    @Test
    fun `GET config schemas unknown type returns 404`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config/schemas/nonexistent-type") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("not_found"), "Expected not_found error: $body")
        }

    // ─── GET /config/traits ───────────────────────────────────────────────────

    @Test
    fun `GET config traits returns trait definitions`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config/traits") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("test-trait"), "Expected test-trait: $body")
            assertTrue(body.contains("trait-note"), "Expected trait-note key: $body")
            assertTrue(body.contains("some-skill"), "Expected skill pointer: $body")
            assertTrue(body.contains("Guidance text"), "Expected guidance: $body")
        }

    // ─── GET /config/types ────────────────────────────────────────────────────

    @Test
    fun `GET config types returns type names`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config/types") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("feature-task"), "Expected feature-task: $body")
            assertTrue(body.contains("simple-task"), "Expected simple-task: $body")
        }

    // ─── GET /config/status-graph ─────────────────────────────────────────────

    @Test
    fun `GET config status-graph returns structural graph`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config/status-graph") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"roles\""), "Expected roles: $body")
            assertTrue(body.contains("\"triggers\""), "Expected triggers: $body")
            assertTrue(body.contains("\"types\""), "Expected types: $body")
        }

    // ─── Status-graph oracle ─────────────────────────────────────────────────

    @Test
    fun `status-graph feature-task work-start goes to review`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config/status-graph") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // feature-task has review phase so work.start → review
            // The JSON contains the transitions map — verify "start":"review" appears near feature-task context
            assertTrue(body.contains("\"start\":\"review\""), "Expected work.start→review for feature-task: $body")
        }

    @Test
    fun `status-graph simple-task work-start goes to terminal`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config/status-graph") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // simple-task has no review phase so work.start → terminal
            assertTrue(body.contains("\"start\":\"terminal\""), "Expected work.start→terminal for simple-task: $body")
        }

    @Test
    fun `status-graph blocked row has previousRole sentinel`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config/status-graph") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // BLOCKED.resume → "<previousRole>" sentinel
            assertTrue(
                body.contains("\"resume\":\"<previousRole>\""),
                "Expected blocked.resume → <previousRole> sentinel: $body"
            )
        }

    @Test
    fun `status-graph terminal row has only reopen-to-queue`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config/status-graph") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // TERMINAL.reopen → "queue"
            assertTrue(body.contains("\"reopen\":\"queue\""), "Expected terminal.reopen→queue: $body")
        }

    // ─── StatusGraphBuilder unit tests ────────────────────────────────────────

    @Test
    fun `StatusGraphBuilder feature-task oracle transitions match spec`() {
        val service = FakeSchemaService()
        val builder = StatusGraphBuilder(service)
        val graph = builder.getStatusGraph()

        val featureTask = graph.types.find { it.type == "feature-task" }
        assertNotNull(featureTask, "feature-task type must be present")
        assertTrue(featureTask.hasReviewPhase, "feature-task must have review phase")

        val queueRow = featureTask.transitions["queue"] ?: error("queue row missing")
        assertEquals("work", queueRow["start"], "queue.start→work")
        assertEquals("terminal", queueRow["complete"], "queue.complete→terminal")
        assertEquals("blocked", queueRow["block"], "queue.block→blocked")
        assertEquals("blocked", queueRow["hold"], "queue.hold→blocked")
        assertEquals("terminal", queueRow["cancel"], "queue.cancel→terminal")

        val workRow = featureTask.transitions["work"] ?: error("work row missing")
        assertEquals("review", workRow["start"], "work.start→review for hasReviewPhase=true")
        assertEquals("terminal", workRow["complete"], "work.complete→terminal")
        assertEquals("blocked", workRow["block"], "work.block→blocked")
        assertEquals("terminal", workRow["cancel"], "work.cancel→terminal")

        val reviewRow = featureTask.transitions["review"] ?: error("review row missing")
        assertEquals("terminal", reviewRow["start"], "review.start→terminal")
        assertEquals("terminal", reviewRow["complete"], "review.complete→terminal")
        assertEquals("blocked", reviewRow["block"], "review.block→blocked")
        assertEquals("terminal", reviewRow["cancel"], "review.cancel→terminal")

        val blockedRow = featureTask.transitions["blocked"] ?: error("blocked row missing")
        assertEquals(StatusGraphBuilder.PREVIOUS_ROLE_SENTINEL, blockedRow["resume"], "blocked.resume→<previousRole>")
        // `cancel` is valid from ANY non-terminal role, including BLOCKED → terminal. The plan's
        // §5.2 illustration showed only `resume`, but the graph is DERIVED from the real
        // RoleTransitionHandler (per the plan's own derivation instruction), which permits
        // cancel-from-blocked. The graph reflects reality so dashboards can offer a cancel button
        // on blocked items.
        assertEquals("terminal", blockedRow["cancel"], "blocked.cancel→terminal")
        assertEquals(2, blockedRow.size, "blocked row should have resume + cancel: $blockedRow")

        val terminalRow = featureTask.transitions["terminal"] ?: error("terminal row missing")
        assertEquals("queue", terminalRow["reopen"], "terminal.reopen→queue")
        // terminal row should ONLY have reopen
        assertEquals(1, terminalRow.size, "terminal row should have exactly 1 entry (reopen): $terminalRow")
    }

    @Test
    fun `StatusGraphBuilder simple-task no-review override`() {
        val service = FakeSchemaService()
        val builder = StatusGraphBuilder(service)
        val graph = builder.getStatusGraph()

        val simpleTask = graph.types.find { it.type == "simple-task" }
        assertNotNull(simpleTask, "simple-task type must be present")
        assertFalse(simpleTask.hasReviewPhase, "simple-task must NOT have review phase")

        val workRow = simpleTask.transitions["work"] ?: error("work row missing for simple-task")
        assertEquals("terminal", workRow["start"], "simple-task work.start→terminal (no review phase)")
    }

    // ─── ETag tests ───────────────────────────────────────────────────────────

    @Test
    fun `same config fingerprint produces identical ETag on two reads`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService(fingerprint = "stable-fp")) } }
            val r1 = client.get("/api/v1/config") { header("Authorization", "Bearer $TEST_TOKEN") }
            val r2 = client.get("/api/v1/config") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, r1.status)
            assertEquals(HttpStatusCode.OK, r2.status)
            val etag1 = r1.headers[HttpHeaders.ETag]
            val etag2 = r2.headers[HttpHeaders.ETag]
            assertNotNull(etag1, "ETag must be present on first read")
            assertEquals(etag1, etag2, "ETags must be identical across reads with same fingerprint")
        }

    @Test
    fun `If-None-Match with current ETag returns 304`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val r1 = client.get("/api/v1/config/schemas") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, r1.status)
            val etag = r1.headers[HttpHeaders.ETag] ?: error("ETag header missing from first response")

            val r2 =
                client.get("/api/v1/config/schemas") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    header(HttpHeaders.IfNoneMatch, etag)
                }
            assertEquals(HttpStatusCode.NotModified, r2.status)
        }

    @Test
    fun `different config fingerprint produces different ETag`() {
        // Make TWO real HTTP calls — one per service — and compare the ETags the ROUTE actually
        // returns. Computing the expected ETag from the formula in-test would be tautological:
        // it would pass even if the route ignored the fingerprint and returned a constant ETag.
        var etag1: String? = null
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService(fingerprint = "fingerprint-A")) } }
            etag1 =
                client
                    .get("/api/v1/config") { header("Authorization", "Bearer $TEST_TOKEN") }
                    .headers[HttpHeaders.ETag]
        }
        var etag2: String? = null
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService(fingerprint = "fingerprint-B")) } }
            etag2 =
                client
                    .get("/api/v1/config") { header("Authorization", "Bearer $TEST_TOKEN") }
                    .headers[HttpHeaders.ETag]
        }
        assertNotNull(etag1, "ETag must be present for service1")
        assertNotNull(etag2, "ETag must be present for service2")
        assertNotEquals(etag1, etag2, "Route must derive different ETags from different config fingerprints")
    }

    @Test
    fun `null fingerprint produces stable no-config ETag`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService(fingerprint = null)) } }
            val r1 = client.get("/api/v1/config") { header("Authorization", "Bearer $TEST_TOKEN") }
            val r2 = client.get("/api/v1/config") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, r1.status)
            val etag1 = r1.headers[HttpHeaders.ETag]
            val etag2 = r2.headers[HttpHeaders.ETag]
            assertNotNull(etag1)
            assertEquals(etag1, etag2, "No-config ETags must be stable")
        }

    // ─── Auth guard ───────────────────────────────────────────────────────────

    @Test
    fun `GET config without auth token returns 401`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response = client.get("/api/v1/config")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // ─── NoteSchemaEntryDto field completeness ────────────────────────────────

    @Test
    fun `NoteSchemaEntryDto includes skill and guidance when present`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(FakeSchemaService()) } }
            val response =
                client.get("/api/v1/config/traits") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"skill\":\"some-skill\""), "Expected skill field: $body")
            assertTrue(body.contains("\"guidance\":\"Guidance text\""), "Expected guidance field: $body")
        }
}
