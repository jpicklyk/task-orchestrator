package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the `dispatch` / `resources` / `maxLength` additions to
 * `GET /api/v1/config/traits` (B1, dispatch trait dimension, S13, P9). Mirrors [ConfigRoutesTest]'s
 * `FakeSchemaService` + `configureTestApp` conventions and its own preference for substring
 * assertions over structural JSON parsing on this endpoint (its envelope shape isn't asserted on
 * structurally anywhere in that file either) — that file already covers the base `/config/traits`
 * shape (name, notes, skill, guidance); this file is scoped to the three new fields.
 *
 * Independent test authorship per the `needs-test-author` trait: oracles come from the pinned
 * contract and P9 in the item's `task-scope` note — never from reading ConfigRoutes's source.
 */
class ConfigRoutesDispatchTest {
    /** In-memory [WorkItemSchemaService] carrying dispatch/resources per trait, for these tests only. */
    class FakeDispatchSchemaService(
        private val traits: Map<String, List<NoteSchemaEntry>>,
        private val traitDispatch: Map<String, Map<Role, DispatchProfile>>,
        private val traitResources: Map<String, List<ResourceRequirement>>
    ) : WorkItemSchemaService {
        override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null

        override fun getAllSchemas(): Map<String, WorkItemSchema> = emptyMap()

        override fun getAllTraits(): Map<String, List<NoteSchemaEntry>> = traits

        override fun getConfigFingerprint(): String? = "dispatch-test-fp"

        override fun getTraitDispatch(traitName: String): Map<Role, DispatchProfile> = traitDispatch[traitName] ?: emptyMap()

        override fun getTraitResources(traitName: String): List<ResourceRequirement> = traitResources[traitName] ?: emptyList()
    }

    // ──────────────────────────────────────────────
    // S13 — dispatch for work and review, omitting queue; resources present
    // ──────────────────────────────────────────────

    @Test
    fun `S13 GET config traits includes dispatch for work and review, omitting queue, plus resources`(): Unit =
        testApplication {
            val service =
                FakeDispatchSchemaService(
                    traits =
                        mapOf(
                            "delegated" to
                                listOf(NoteSchemaEntry(key = "trait-note", role = Role.REVIEW, required = false, description = "note"))
                        ),
                    traitDispatch =
                        mapOf(
                            "delegated" to
                                mapOf(
                                    Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"),
                                    Role.REVIEW to DispatchProfile(agent = "task-orchestrator:reviewer", effort = "high")
                                )
                        ),
                    traitResources = mapOf("delegated" to listOf(ResourceRequirement(key = "db")))
                )
            application { configureTestApp { configRoutes(service) } }
            val response = client.get("/api/v1/config/traits") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()

            assertTrue(body.contains("\"dispatch\""), "Expected dispatch key: $body")
            assertTrue(body.contains("\"work\""), "Expected work phase key: $body")
            assertTrue(body.contains("\"review\""), "Expected review phase key: $body")
            assertTrue(body.contains("\"agent\":\"task-orchestrator:implementer\""), "Expected implementer agent: $body")
            assertTrue(body.contains("\"agent\":\"task-orchestrator:reviewer\""), "Expected reviewer agent: $body")
            assertTrue(body.contains("\"effort\":\"high\""), "Expected effort high on review profile: $body")
            assertFalse(body.contains("\"queue\""), "queue is not a valid dispatch phase per the pinned contract: $body")

            assertTrue(body.contains("\"resources\""), "Expected resources key: $body")
            assertTrue(body.contains("\"key\":\"db\""), "Expected db resource key: $body")
            assertTrue(body.contains("\"mode\":\"exclusive\""), "Expected exclusive mode: $body")
        }

    @Test
    fun `S13 GET config traits omits dispatch and resources when a trait declares neither`(): Unit =
        testApplication {
            val service =
                FakeDispatchSchemaService(
                    traits =
                        mapOf(
                            "plain-trait" to
                                listOf(NoteSchemaEntry(key = "plain-note", role = Role.WORK, required = false, description = "note"))
                        ),
                    traitDispatch = emptyMap(),
                    traitResources = emptyMap()
                )
            application { configureTestApp { configRoutes(service) } }
            val response = client.get("/api/v1/config/traits") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()

            assertTrue(body.contains("plain-trait"), "Expected plain-trait in body: $body")
            assertFalse(body.contains("\"dispatch\""), "dispatch key must be absent when the trait declares none: $body")
            assertFalse(body.contains("\"resources\""), "resources key must be absent when the trait declares none: $body")
        }

    // ──────────────────────────────────────────────
    // S13 — note maxLength present when configured, absent otherwise
    // ──────────────────────────────────────────────

    @Test
    fun `S13 GET config traits note maxLength is present when configured and absent when not`(): Unit =
        testApplication {
            val service =
                FakeDispatchSchemaService(
                    traits =
                        mapOf(
                            "limited-trait" to
                                listOf(
                                    NoteSchemaEntry(
                                        key = "limited-note",
                                        role = Role.WORK,
                                        required = false,
                                        description = "note",
                                        maxLength = 500
                                    ),
                                    NoteSchemaEntry(
                                        key = "unlimited-note",
                                        role = Role.WORK,
                                        required = false,
                                        description = "note"
                                    )
                                )
                        ),
                    traitDispatch = emptyMap(),
                    traitResources = emptyMap()
                )
            application { configureTestApp { configRoutes(service) } }
            val response = client.get("/api/v1/config/traits") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()

            assertTrue(body.contains("\"maxLength\":500"), "Expected maxLength:500 on limited-note: $body")
            // Only the configured note should carry the field at all -- the unlimited-note object
            // must not carry a null/omitted-but-present maxLength anywhere in the response.
            val maxLengthOccurrences = Regex("\"maxLength\"").findAll(body).count()
            assertEquals(1, maxLengthOccurrences, "Only the configured note should carry maxLength: $body")
        }
}
