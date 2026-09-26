package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.GlobalConfigLookup
import io.github.jpicklyk.mcptask.current.application.config.LayerBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ServiceBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.GlobalConfigFile
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlWorkItemSchemaService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.EffectiveConfigDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.StatusGraphDto
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Independently authored against the frozen `task-scope`/`test-plan` notes on item `ec445109`
// (C5 — `GET /api/v1/roots/{rootId}/config/effective`). Scenarios S1-S22 per `test-plan`; oracles
// cited inline per scenario. All fixtures are LEGACY (no `schema_resolution` key), per the
// dispatch contract's instruction that C5 must not bind to any C4-owned `schema_resolution`
// behaviour.
//
// Harness: ServiceBackedGlobalLookup built directly over a temp-file-backed
// YamlWorkItemSchemaService (the SAME instance is also passed as the route's `schemaService`
// enumeration param, so global-layer drift between the two params — R1 in the test-plan's
// red-proof table — cannot occur by construction), mirroring
// EffectiveConfigResolverTest/AvailableTraitsOrderTest's proven pattern. Per-root layers use
// PerRootConfigService over a real H2-backed ProjectConfigRepository (mirroring
// ProjectConfigRoutesTest/ConfigUnavailableRoutesTest), except where a scenario needs a fixed
// or instrumented PerRootConfigSource fake, per that scenario's own note.

// ───────────────────────────── Shared fixtures ─────────────────────────────

private fun writeYamlFile(content: String): Path {
    val path = Files.createTempFile("effective-config-routes", ".yaml")
    Files.writeString(path, content)
    return path
}

/** Builds a [WorkItemSchemaService] and the [GlobalConfigLookup] wrapping the SAME instance. */
private fun globalFixture(yaml: String): Pair<WorkItemSchemaService, GlobalConfigLookup> {
    val service = YamlWorkItemSchemaService(writeYamlFile(yaml))
    return service to ServiceBackedGlobalLookup(service, NoOpStatusLabelService)
}

/**
 * A truly ABSENT global config file — the path is never written. Per the frozen C1 test-plan
 * S1 contract ("`GlobalConfigFile.layer()`: absent file -> null"), an absent file yields a null
 * fingerprint, unlike a present-but-empty file (which still hashes its zero bytes to a real
 * fingerprint). "No global" means this, not an empty document — mirrors
 * `GlobalConfigFileTest`'s `absentConfigPath()` pattern.
 */
private fun absentGlobalFixture(): Pair<WorkItemSchemaService, GlobalConfigLookup> {
    val absentPath = Files.createTempDirectory("effective-config-routes-absent").resolve("config.yaml")
    val service = YamlWorkItemSchemaService(absentPath)
    return service to ServiceBackedGlobalLookup(service, NoOpStatusLabelService)
}

private fun Application.configureEffectiveConfigTestApp(
    repositoryProvider: RepositoryProvider,
    configResolver: EffectiveConfigResolver,
    schemaService: WorkItemSchemaService,
    authConfig: ApiAuthConfig = makeTestAuthConfig(),
) {
    configureTestApp(authConfig) {
        effectiveConfigRoutes(repositoryProvider, configResolver, schemaService)
    }
}

/** Also mounts the pre-existing (global-only) `/config*` routes, for S4/S20/S22's cross-route checks. */
private fun Application.configureEffectiveAndLegacyConfigTestApp(
    repositoryProvider: RepositoryProvider,
    configResolver: EffectiveConfigResolver,
    schemaService: WorkItemSchemaService,
    authConfig: ApiAuthConfig = makeTestAuthConfig(),
) {
    configureTestApp(authConfig) {
        effectiveConfigRoutes(repositoryProvider, configResolver, schemaService)
        configRoutes(schemaService)
    }
}

private fun createRootItem(
    repo: RepositoryProvider,
    title: String = "Effective Config Root",
    tags: String? = null,
): WorkItem =
    runBlocking {
        (repo.workItemRepository().create(WorkItem(title = title, type = "project", depth = 0, tags = tags)) as Result.Success).data
    }

/** Independently-computed oracle for `effectiveConfigEtag`'s stated formula (task-scope Build §1). */
private fun expectedCompositeEtag(
    globalFingerprint: String?,
    perRootFingerprint: String?,
): String {
    val input = "global:${globalFingerprint ?: "-"}\nper-root:${perRootFingerprint ?: "-"}"
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return "\"eff-$hex\""
}

private val NULL_PER_ROOT_SOURCE =
    object : PerRootConfigSource {
        override suspend fun layer(rootId: UUID): ConfigLayer? = null
    }

private fun fixedPerRootSource(
    schemas: Map<String, WorkItemSchema> = emptyMap(),
    traits: Map<String, List<NoteSchemaEntry>> = emptyMap(),
    fingerprint: String = "fixed-per-root-fp",
): PerRootConfigSource {
    val document = ConfigDocument(workItemSchemas = schemas, traits = traits)
    return object : PerRootConfigSource {
        override suspend fun layer(rootId: UUID): ConfigLayer? = ConfigLayer(document, fingerprint, ConfigSource.PER_ROOT)
    }
}

private fun throwingPerRootSource(message: String): PerRootConfigSource =
    object : PerRootConfigSource {
        override suspend fun layer(rootId: UUID): ConfigLayer? = throw PerRootConfigUnavailableException(rootId, message)
    }

/** Wraps a delegate [PerRootConfigSource] and counts calls to [layer], for S12/S18's read-count assertions. */
private class CountingPerRootConfigSource(
    private val delegate: PerRootConfigSource,
) : PerRootConfigSource {
    var callCount = 0
        private set

    override suspend fun layer(rootId: UUID): ConfigLayer? {
        callCount++
        return delegate.layer(rootId)
    }
}

private fun decodeEffective(body: String): EffectiveConfigDto = McpJson.decodeFromString<EffectiveConfigDto>(body)

/**
 * Builds the global layer via [GlobalConfigFile]/[LayerBackedGlobalLookup] — the PRODUCTION path
 * (C3 task-scope: `LayerBackedGlobalLookup(globalConfigFile.layer())`) — rather than [globalFixture]'s
 * [ServiceBackedGlobalLookup], whose `schemaResolution` is a constructor default (`null`) and is
 * never derived from the YAML file. Only this path actually parses a top-level `schema_resolution:`
 * key out of the global document, which S15's global-level cases need.
 * The [WorkItemSchemaService] returned reads the SAME file, so the two params stay in parity.
 */
private fun globalLayerFixture(yaml: String): Pair<WorkItemSchemaService, GlobalConfigLookup> {
    val path = writeYamlFile(yaml)
    val schemaService = YamlWorkItemSchemaService(path)
    val lookup = LayerBackedGlobalLookup(GlobalConfigFile(path).layer())
    return schemaService to lookup
}

// ───────────────────────────── Happy path: S1-S6 ─────────────────────────────

class EffectiveConfigRoutesHappyPathTest {
    companion object {
        // No review-phase note anywhere in the global layer.
        private val GLOBAL_S1 =
            """
            work_item_schemas:
              bug:
                notes:
                  - key: task-scope
                    role: queue
                    required: true
              feature-task:
                notes:
                  - key: task-scope
                    role: queue
                    required: true
            """.trimIndent()

        // Per-root overrides feature-task, ADDING a review-role note (so hasReviewPhase flips true
        // per-root but the global-only /config route, unaware of the per-root row, must still say
        // "terminal" for feature-task's work.start transition — this is S4's cross-route check).
        private val PER_ROOT_S1 =
            """
            work_item_schemas:
              feature-task:
                notes:
                  - key: task-scope
                    role: queue
                    required: true
                  - key: review-checklist
                    role: review
                    required: true
            """.trimIndent()
    }

    @Test
    fun `S1 - per-root override and global-only type are both listed with correct source, fingerprint and layer echoes`() =
        testApplication {
            val (schemaService, global) = globalFixture(GLOBAL_S1)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            runBlocking { repo.projectConfigRepository().upsert(root.id, PER_ROOT_S1) }
            val rowFingerprint = runBlocking { repo.projectConfigRepository().getFingerprint(root.id).getOrNull() }
            assertNotNull(rowFingerprint, "sanity: the per-root push must persist a fingerprint")
            val globalFingerprint = schemaService.getConfigFingerprint()
            assertNotNull(globalFingerprint, "sanity: the global YAML must produce a fingerprint")

            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val dto = decodeEffective(response.bodyAsText())

            assertEquals(root.id.toString(), dto.rootId)
            assertEquals(listOf("bug", "feature-task"), dto.types.sorted())
            assertEquals(globalFingerprint, dto.globalFingerprint)
            assertEquals(rowFingerprint, dto.perRootFingerprint)

            val bugEntry = dto.schemas.first { it.type == "bug" }
            assertEquals("bug", bugEntry.matchedType)
            assertEquals("global", bugEntry.configSource)
            assertEquals(globalFingerprint, bugEntry.configFingerprint)
            assertEquals(false, bugEntry.hasReviewPhase)

            val featureEntry = dto.schemas.first { it.type == "feature-task" }
            assertEquals("feature-task", featureEntry.matchedType)
            assertEquals("per-root", featureEntry.configSource)
            assertEquals(rowFingerprint, featureEntry.configFingerprint)
            assertEquals(true, featureEntry.hasReviewPhase)
            assertTrue(featureEntry.notes.any { it.key == "review-checklist" }, "per-root note must be visible: ${featureEntry.notes}")
        }

    @Test
    fun `S2 - a per-root default schema answers a type only the global layer names, sourced per-root`() =
        testApplication {
            val globalYaml =
                """
                work_item_schemas:
                  bug:
                    notes:
                      - key: task-scope
                        role: queue
                        required: true
                """.trimIndent()
            val perRootYaml =
                """
                work_item_schemas:
                  default:
                    notes:
                      - key: default-note
                        role: queue
                        required: false
                """.trimIndent()
            val (schemaService, global) = globalFixture(globalYaml)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            runBlocking { repo.projectConfigRepository().upsert(root.id, perRootYaml) }

            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val dto = decodeEffective(response.bodyAsText())

            val bugEntry = dto.schemas.first { it.type == "bug" }
            assertEquals(
                "default",
                bugEntry.matchedType,
                "the per-root default must answer for a type it does not name (LC per-root-default precedence)"
            )
            assertEquals("per-root", bugEntry.configSource)
        }

    @Test
    fun `S3 - every entry's matchedType, configSource, configFingerprint and note keys equal a direct resolveTypeSchema call`() =
        testApplication {
            val (schemaService, global) = globalFixture(GLOBAL_S1)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            runBlocking { repo.projectConfigRepository().upsert(root.id, PER_ROOT_S1) }

            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            val dto = decodeEffective(response.bodyAsText())

            for (entry in dto.schemas) {
                val direct = runBlocking { resolver.resolveTypeSchema(entry.type, root.id) }
                assertNotNull(direct, "resolveTypeSchema(${entry.type}) must resolve directly, matching the route entry")
                assertEquals(direct.schema.type, entry.matchedType, "matchedType parity for ${entry.type}")
                val expectedSource = if (direct.source == ConfigSource.PER_ROOT) "per-root" else "global"
                assertEquals(expectedSource, entry.configSource, "configSource parity for ${entry.type}")
                assertEquals(direct.fingerprint, entry.configFingerprint, "configFingerprint parity for ${entry.type}")
                assertEquals(direct.schema.notes.map { it.key }, entry.notes.map { it.key }, "note key parity for ${entry.type}")
            }
        }

    @Test
    fun `S4 - the effective status graph reflects the per-root review note while the legacy config route stays global-only`() =
        testApplication {
            val (schemaService, global) = globalFixture(GLOBAL_S1)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            runBlocking { repo.projectConfigRepository().upsert(root.id, PER_ROOT_S1) }

            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveAndLegacyConfigTestApp(repo, resolver, schemaService) }

            val effective =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            val dto = decodeEffective(effective.bodyAsText())
            val featureType = dto.statusGraph.types.first { it.type == "feature-task" }
            assertEquals("review", featureType.transitions["work"]?.get("start"), "effective graph must reflect the per-root review note")

            val legacy =
                client.get("/api/v1/config/status-graph") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            val legacyDto = McpJson.decodeFromString<StatusGraphDto>(legacy.bodyAsText())
            val legacyFeature = legacyDto.types.first { it.type == "feature-task" }
            assertEquals(
                "terminal",
                legacyFeature.transitions["work"]?.get("start"),
                "the legacy /config/status-graph route must remain global-only (feature-task work.start->terminal)",
            )
        }

    @Test
    fun `S5 - trait order is per-root-first then global, with per-root wholesale override and per-trait dispatch-resources omission`() =
        testApplication {
            val globalYaml =
                """
                traits:
                  t1:
                    dispatch:
                      work: { agent: agent-B }
                    notes:
                      - key: n-g
                        role: work
                        required: false
                  t2:
                    notes:
                      - key: t2-note
                        role: queue
                        required: false
                """.trimIndent()
            val perRootYaml =
                """
                traits:
                  t1:
                    dispatch:
                      work: { agent: agent-A }
                    notes:
                      - key: n-pr
                        role: work
                        required: false
                """.trimIndent()
            val (schemaService, global) = globalFixture(globalYaml)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            runBlocking { repo.projectConfigRepository().upsert(root.id, perRootYaml) }

            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            val dto = decodeEffective(response.bodyAsText())

            assertEquals(listOf("t1", "t2"), dto.traits.map { it.name })
            val t1 = dto.traits.first { it.name == "t1" }
            assertEquals(listOf("n-pr"), t1.notes.map { it.key }, "t1 must be the per-root wholesale replacement")
            assertEquals("agent-A", t1.dispatch?.get("work")?.agent)
            assertNull(t1.resources, "t1 has no resources declared in either layer")

            val t2 = dto.traits.first { it.name == "t2" }
            assertEquals(listOf("t2-note"), t2.notes.map { it.key })
            assertNull(t2.dispatch, "t2 (global only, no dispatch declared) must omit dispatch, not emit an empty map")
            assertNull(t2.resources, "t2 must omit resources")
        }

    @Test
    fun `S6 - with no per-root row, the view is entirely global with no perRootFingerprint`() =
        testApplication {
            val (schemaService, global) = globalFixture(GLOBAL_S1)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            // Deliberately no upsert() — no per-root config row exists for this root.

            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val dto = decodeEffective(response.bodyAsText())

            assertEquals(listOf("bug", "feature-task"), dto.types.sorted())
            assertTrue(dto.schemas.all { it.configSource == "global" }, "every entry must be global-sourced: ${dto.schemas}")
            assertNull(dto.perRootFingerprint, "no per-root row means perRootFingerprint must be omitted/null")
            assertNotNull(dto.globalFingerprint)
        }
}

// ───────────────────────────── ETag: S7-S10 ─────────────────────────────

class EffectiveConfigRoutesEtagTest {
    private val globalYaml =
        """
        work_item_schemas:
          bug:
            notes: []
        """.trimIndent()
    private val perRootYamlA =
        """
        work_item_schemas:
          bug:
            notes:
              - key: a-note
                role: queue
                required: false
        """.trimIndent()
    private val perRootYamlB =
        """
        work_item_schemas:
          bug:
            notes:
              - key: b-note
                role: queue
                required: false
        """.trimIndent()

    @Test
    fun `S7 - repeat GET returns a stable eff- ETag, and If-None-Match round-trips to 304 with an empty body`() =
        testApplication {
            val (schemaService, global) = globalFixture(globalYaml)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            runBlocking { repo.projectConfigRepository().upsert(root.id, perRootYamlA) }
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val r1 = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            val etag1 = r1.headers[HttpHeaders.ETag]
            assertNotNull(etag1)
            assertTrue(Regex("^\"eff-[0-9a-f]{64}\"$").matches(etag1), "ETag must match the eff- shape: $etag1")

            val r2 = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(etag1, r2.headers[HttpHeaders.ETag], "ETag must be stable across identical reads")

            val r3 =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    header(HttpHeaders.IfNoneMatch, etag1)
                }
            assertEquals(HttpStatusCode.NotModified, r3.status)
            assertEquals(etag1, r3.headers[HttpHeaders.ETag], "the ETag header must still be set on a 304")
            assertEquals("", r3.bodyAsText(), "a 304 must carry an empty body")
        }

    @Test
    fun `S8 - pushing a new per-root config changes the ETag, and the old ETag no longer matches`() =
        testApplication {
            val (schemaService, global) = globalFixture(globalYaml)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            runBlocking { repo.projectConfigRepository().upsert(root.id, perRootYamlA) }
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val r1 = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            val etagBefore = r1.headers[HttpHeaders.ETag]
            assertNotNull(etagBefore)

            runBlocking { repo.projectConfigRepository().upsert(root.id, perRootYamlB) }

            val r2 = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, r2.status)
            val etagAfter = r2.headers[HttpHeaders.ETag]
            assertNotEquals(etagBefore, etagAfter, "a per-root push must change the composite ETag")

            val staleCheck =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    header(HttpHeaders.IfNoneMatch, etagBefore)
                }
            assertEquals(HttpStatusCode.OK, staleCheck.status, "the pre-push ETag must no longer match")
        }

    @Test
    fun `S9 - the same per-root row under two different global fingerprints yields two different ETags`() {
        val repo = buildH2RepositoryProvider()
        val root = createRootItem(repo)
        runBlocking { repo.projectConfigRepository().upsert(root.id, perRootYamlA) }

        var etag1: String? = null
        testApplication {
            val (schemaService, global) = globalFixture(globalYaml)
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }
            val r1 = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, r1.status)
            etag1 = r1.headers[HttpHeaders.ETag]
            assertNotNull(etag1)
        }

        val globalYamlV2 =
            """
            work_item_schemas:
              bug:
                notes: []
              feature:
                notes: []
            """.trimIndent()
        testApplication {
            val (schemaService, global) = globalFixture(globalYamlV2)
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }
            val r2 = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, r2.status)
            val etag2 = r2.headers[HttpHeaders.ETag]
            assertNotEquals(etag1, etag2, "a global-only fingerprint change must change the composite ETag")

            val staleCheck =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    header(HttpHeaders.IfNoneMatch, etag1!!)
                }
            assertEquals(HttpStatusCode.OK, staleCheck.status, "the global-v1 ETag must not match under global-v2")
        }
    }

    @Test
    fun `S10 - effectiveConfigEtag is order-sensitive per layer and independent of the legacy cfg- ETag`() =
        testApplication {
            val a = expectedCompositeEtag("a", null)
            val b = expectedCompositeEtag(null, "a")
            assertNotEquals(a, b, "swapping which layer carries the fingerprint must change the ETag")
            assertEquals(a, effectiveConfigEtag("a", null), "independent oracle: effectiveConfigEtag(a,null)")
            assertEquals(b, effectiveConfigEtag(null, "a"), "independent oracle: effectiveConfigEtag(null,a)")

            // Cross-route distinctness: the legacy /config route's "cfg-" ETag must never collide
            // with the "eff-" prefix, so an effective ETag can never be replayed as a stale
            // /config If-Match.
            val (schemaService, global) = globalFixture(globalYaml)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            runBlocking { repo.projectConfigRepository().upsert(root.id, perRootYamlA) }
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveAndLegacyConfigTestApp(repo, resolver, schemaService) }

            val effectiveEtag =
                client
                    .get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
                    .headers[HttpHeaders.ETag]
            val legacyEtag =
                client
                    .get("/api/v1/config") { header("Authorization", "Bearer $TEST_TOKEN") }
                    .headers[HttpHeaders.ETag]
            assertNotNull(effectiveEtag)
            assertNotNull(legacyEtag)
            assertTrue(effectiveEtag.startsWith("\"eff-"), "effective ETag must use the eff- prefix: $effectiveEtag")
            assertTrue(legacyEtag.startsWith("\"cfg-"), "legacy ETag must use the cfg- prefix: $legacyEtag")
            assertNotEquals(effectiveEtag, legacyEtag)
        }
}

// ───────────────────────────── Authz / failure: S11-S17 ─────────────────────────────

class EffectiveConfigRoutesAuthzTest {
    private val emptyGlobalYaml = "work_item_schemas: {}"

    private fun buildBareApp(
        repo: RepositoryProvider,
        perRoot: PerRootConfigSource = NULL_PER_ROOT_SOURCE,
        authConfig: ApiAuthConfig = makeTestAuthConfig(),
    ): Pair<EffectiveConfigResolver, WorkItemSchemaService> {
        val (schemaService, global) = globalFixture(emptyGlobalYaml)
        return EffectiveConfigResolver(global, perRoot) to schemaService
    }

    @Test
    fun `S11 - a bearer app rejects a missing Authorization header with 401, but Unauthenticated mode allows it through with 200`() {
        val repo = buildH2RepositoryProvider()
        val root = createRootItem(repo)
        val (resolver, schemaService) = buildBareApp(repo)

        testApplication {
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }
            val response = client.get("/api/v1/roots/${root.id}/config/effective")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        testApplication {
            application {
                configureEffectiveConfigTestApp(repo, resolver, schemaService, authConfig = ApiAuthConfig.Unauthenticated)
            }
            val response = client.get("/api/v1/roots/${root.id}/config/effective")
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `S12 - a token scoped to a different root is rejected 403 scope_forbidden with zero per-root reads`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            val otherRoot = createRootItem(repo, title = "Other Root")
            val counting = CountingPerRootConfigSource(NULL_PER_ROOT_SOURCE)
            val (resolver, schemaService) = buildBareApp(repo, perRoot = counting)
            application {
                configureEffectiveConfigTestApp(
                    repo,
                    resolver,
                    schemaService,
                    authConfig = makeTestAuthConfig(scopeRootIds = setOf(otherRoot.id)),
                )
            }

            val response =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("scope_forbidden"))
            assertEquals(0, counting.callCount, "a scope-denied request must never reach the per-root resolver")
        }

    @Test
    fun `S13 - a tagsInclude-scoped token is rejected for an untagged root but allowed for a tagged one`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val taggedRoot = createRootItem(repo, title = "Tagged Root", tags = "x")
            val untaggedRoot = createRootItem(repo, title = "Untagged Root")
            val (resolver, schemaService) = buildBareApp(repo)
            application {
                configureEffectiveConfigTestApp(
                    repo,
                    resolver,
                    schemaService,
                    authConfig = makeTestAuthConfig(tagsInclude = setOf("x")),
                )
            }

            val untaggedResponse =
                client.get("/api/v1/roots/${untaggedRoot.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, untaggedResponse.status)

            val taggedResponse =
                client.get("/api/v1/roots/${taggedRoot.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, taggedResponse.status)
        }

    @Test
    fun `S14 - an unknown root is 403 under a restricted scope but 404 not_found under an unrestricted one`() {
        val repo = buildH2RepositoryProvider()
        val realRoot = createRootItem(repo)
        val unknownId = UUID.randomUUID()
        val (resolver, schemaService) = buildBareApp(repo)

        testApplication {
            application {
                configureEffectiveConfigTestApp(
                    repo,
                    resolver,
                    schemaService,
                    authConfig = makeTestAuthConfig(scopeRootIds = setOf(realRoot.id)),
                )
            }

            val scopedResponse =
                client.get("/api/v1/roots/$unknownId/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, scopedResponse.status, "an unknown root under a restricted scope fails closed as 403")
        }

        testApplication {
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }
            val unrestrictedResponse =
                client.get("/api/v1/roots/$unknownId/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.NotFound, unrestrictedResponse.status)
            assertTrue(unrestrictedResponse.bodyAsText().contains("not_found"))
        }
    }

    @Test
    fun `S15 - a non-depth-0 root returns 422 validation_error naming depth-0`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val parent = createRootItem(repo, title = "Parent")
            val child =
                runBlocking {
                    (repo.workItemRepository().create(WorkItem(title = "Child", parentId = parent.id, depth = 1)) as Result.Success).data
                }
            val (resolver, schemaService) = buildBareApp(repo)
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response =
                client.get("/api/v1/roots/${child.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("validation_error"))
            assertTrue(body.contains("depth-0"), "must name the depth-0 constraint: $body")
        }

    @Test
    fun `S16 - a malformed rootId path segment returns 400 bad_request`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (resolver, schemaService) = buildBareApp(repo)
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response =
                client.get("/api/v1/roots/nope/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("bad_request"))
        }

    @Test
    fun `S17 - a PerRootConfigUnavailableException from the resolver yields 503 config_unavailable with no ETag and no Retry-After`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            val (schemaService, global) = globalFixture(emptyGlobalYaml)
            val resolver = EffectiveConfigResolver(global, throwingPerRootSource("boom"))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"error\":\"config_unavailable\""), "body: $body")
            assertTrue(body.contains("\"message\":\"boom\""), "body: $body")
            assertNull(response.headers["Retry-After"], "D6: no Retry-After on a config_unavailable 503")
            assertNull(response.headers[HttpHeaders.ETag], "a 503 must not carry an ETag")
        }
}

// ───────────────────────────── Edge cases: S18-S22 ─────────────────────────────

class EffectiveConfigRoutesEdgeTest {
    @Test
    fun `S18 - exactly one per-root read occurs on the 200 request and exactly one more on the 304 request`() =
        testApplication {
            val (schemaService, global) = globalFixture("work_item_schemas: {}")
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            runBlocking { repo.projectConfigRepository().upsert(root.id, "work_item_schemas:\n  t:\n    notes: []\n") }
            val counting = CountingPerRootConfigSource(PerRootConfigService(repo.projectConfigRepository()))
            val resolver = EffectiveConfigResolver(global, counting)
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val r1 = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, r1.status)
            assertEquals(1, counting.callCount, "exactly one per-root read for the 200 request")
            val etag = r1.headers[HttpHeaders.ETag]!!

            val r2 =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    header(HttpHeaders.IfNoneMatch, etag)
                }
            assertEquals(HttpStatusCode.NotModified, r2.status)
            assertEquals(2, counting.callCount, "exactly one MORE per-root read for the 304 request")
        }

    @Test
    fun `S19 - a per-root layer naming a type absent from the DB row is still listed, sourced per-root`() =
        testApplication {
            val (schemaService, global) = globalFixture("work_item_schemas: {}")
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            // No row is ever pushed to the DB — the fake resolver is entirely self-contained.
            val ghostSchema = WorkItemSchema(type = "ghost", notes = listOf(NoteSchemaEntry(key = "ghost-note", role = Role.QUEUE)))
            val resolver = EffectiveConfigResolver(global, fixedPerRootSource(schemas = mapOf("ghost" to ghostSchema)))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val dto = decodeEffective(response.bodyAsText())

            assertTrue(dto.types.contains("ghost"), "a type only the injected resolver knows must still be enumerated: ${dto.types}")
            val ghostEntry = dto.schemas.first { it.type == "ghost" }
            assertEquals("per-root", ghostEntry.configSource)
            assertEquals(listOf("ghost-note"), ghostEntry.notes.map { it.key })
        }

    @Test
    fun `S20 - GET config body and ETag are byte-identical before and after a per-root push`() =
        testApplication {
            val (schemaService, global) =
                globalFixture(
                    """
                    work_item_schemas:
                      bug:
                        notes: []
                    """.trimIndent(),
                )
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveAndLegacyConfigTestApp(repo, resolver, schemaService) }

            val before = client.get("/api/v1/config") { header("Authorization", "Bearer $TEST_TOKEN") }
            val beforeBody = before.bodyAsText()
            val beforeEtag = before.headers[HttpHeaders.ETag]

            runBlocking {
                repo.projectConfigRepository().upsert(
                    root.id,
                    "work_item_schemas:\n  bug:\n    notes:\n      - key: x\n        role: queue\n"
                )
            }

            val after = client.get("/api/v1/config") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(beforeBody, after.bodyAsText(), "the legacy /config body must be unaffected by a per-root push")
            assertEquals(beforeEtag, after.headers[HttpHeaders.ETag], "the legacy /config ETag must be unaffected by a per-root push")
        }

    @Test
    fun `S21 - with neither layer configured, the response is 200 with empty collections, no fingerprints, and a stable ETag`() =
        testApplication {
            val (schemaService, global) = absentGlobalFixture()
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val r1 = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, r1.status)
            val dto = decodeEffective(r1.bodyAsText())

            assertEquals(emptyList(), dto.schemas)
            assertEquals(emptyList(), dto.types)
            assertEquals(emptyList(), dto.traits)
            assertEquals(emptyList(), dto.statusGraph.types)
            assertNull(dto.globalFingerprint)
            assertNull(dto.perRootFingerprint)
            assertNull(dto.defaultSchema)

            val r2 = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(r1.headers[HttpHeaders.ETag], r2.headers[HttpHeaders.ETag], "an all-empty view must still produce a stable ETag")
        }

    /**
     * S22 goes through the REAL production entry point,
     * [io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes] — the exact function
     * [io.github.jpicklyk.mcptask.current.interfaces.mcp.CurrentMcpServer] calls in HTTP mode —
     * mirroring [McpRestAuthBypassTest]'s call-shape. A hand-built routing block (this file's
     * previous approach) registers [effectiveConfigRoutes] itself, so it cannot detect the route
     * missing from production wiring; only exercising `installRestApiRoutes` itself can.
     */
    @Test
    fun `S22 - the route is reachable through the real installRestApiRoutes production wiring`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            val (schemaService, _) = globalFixture("work_item_schemas:\n  bug:\n    notes: []\n")
            runBlocking {
                repo.projectConfigRepository().upsert(root.id, "work_item_schemas:\n  bug:\n    notes: []\n")
            }
            val authConfig = makeTestAuthConfig()
            val tokenEntries = authConfig.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }

            application {
                install(ContentNegotiation) { json(McpJson) }
                installRestApiRoutes(
                    apiConfig = authConfig,
                    eventBus = null,
                    effectiveProvider = repo,
                    apiTokenEntries = tokenEntries,
                    allowQueryToken = false,
                    serverName = "s22-test",
                    serverVersion = "1.0.0",
                    actorAuthEnabled = false,
                    noteSchemaService = schemaService,
                    toolContext =
                        ToolExecutionContext(
                            repo,
                            schemaService,
                            statusLabelService = NoOpStatusLabelService,
                            perRootConfigService = PerRootConfigService(repo.projectConfigRepository()),
                        ),
                    degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
                    idempotencyCache = IdempotencyCache(),
                )
            }

            val response =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val dto = decodeEffective(response.bodyAsText())
            assertEquals(root.id.toString(), dto.rootId)
            assertEquals(listOf("bug"), dto.types)
            assertEquals("per-root", dto.schemas.first { it.type == "bug" }.configSource)
        }
}

// ───────────────────────────── Probes ─────────────────────────────

class EffectiveConfigRoutesProbeTest {
    private val globalYaml =
        """
        work_item_schemas:
          bug:
            notes: []
        """.trimIndent()

    @Test
    fun `probe - an upper-case rootId still resolves (UUID parsing is case-insensitive)`() =
        testApplication {
            val (schemaService, global) = globalFixture(globalYaml)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response =
                client.get("/api/v1/roots/${root.id.toString().uppercase()}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status, "an upper-case rootId must still parse")
        }

    @Test
    fun `probe - a padded If-None-Match value still matches (304), a weak W- validator does not (200)`() =
        testApplication {
            val (schemaService, global) = globalFixture(globalYaml)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val etag =
                client
                    .get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
                    .headers[HttpHeaders.ETag]!!

            val padded =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    header(HttpHeaders.IfNoneMatch, "  $etag  ")
                }
            assertEquals(HttpStatusCode.NotModified, padded.status, "a trimmed If-None-Match must still match")

            val weak =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    header(HttpHeaders.IfNoneMatch, "W/$etag")
                }
            assertEquals(HttpStatusCode.OK, weak.status, "a weak validator must not be treated as a match")
        }

    @Test
    fun `probe - a type present in both layers appears exactly once, sourced per-root`() =
        testApplication {
            val (schemaService, global) = globalFixture(globalYaml)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            runBlocking {
                repo.projectConfigRepository().upsert(
                    root.id,
                    """
                    work_item_schemas:
                      bug:
                        notes:
                          - key: per-root-bug-note
                            role: queue
                            required: false
                    """.trimIndent(),
                )
            }
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response =
                client.get("/api/v1/roots/${root.id}/config/effective") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            val dto = decodeEffective(response.bodyAsText())
            val bugEntries = dto.schemas.filter { it.type == "bug" }
            assertEquals(1, bugEntries.size, "a key present in both layers must appear exactly once: $bugEntries")
            assertEquals("per-root", bugEntries.first().configSource)
        }

    @Test
    fun `probe - an explicit empty per-root traits map behaves the same as a per-root document with no traits key at all`() {
        val globalWithTrait =
            """
            traits:
              only-global:
                notes:
                  - key: g-note
                    role: queue
                    required: false
            """.trimIndent()
        val (schemaServiceA, globalA) = globalFixture(globalWithTrait)
        val repoA = buildH2RepositoryProvider()
        val rootA = createRootItem(repoA)
        runBlocking { repoA.projectConfigRepository().upsert(rootA.id, "work_item_schemas: {}\ntraits: {}\n") }
        val resolverA = EffectiveConfigResolver(globalA, PerRootConfigService(repoA.projectConfigRepository()))

        val (schemaServiceB, globalB) = globalFixture(globalWithTrait)
        val repoB = buildH2RepositoryProvider()
        val rootB = createRootItem(repoB)
        runBlocking { repoB.projectConfigRepository().upsert(rootB.id, "work_item_schemas: {}\n") }
        val resolverB = EffectiveConfigResolver(globalB, PerRootConfigService(repoB.projectConfigRepository()))

        lateinit var namesWithExplicitEmpty: List<String>
        testApplication {
            application { configureEffectiveConfigTestApp(repoA, resolverA, schemaServiceA) }
            val body =
                client
                    .get("/api/v1/roots/${rootA.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
                    .bodyAsText()
            namesWithExplicitEmpty = decodeEffective(body).traits.map { it.name }
        }

        testApplication {
            application { configureEffectiveConfigTestApp(repoB, resolverB, schemaServiceB) }
            val body =
                client
                    .get("/api/v1/roots/${rootB.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
                    .bodyAsText()
            val namesWithAbsentKey = decodeEffective(body).traits.map { it.name }
            assertEquals(
                namesWithExplicitEmpty,
                namesWithAbsentKey,
                "explicit empty traits map and an absent traits key must behave identically"
            )
            assertEquals(listOf("only-global"), namesWithAbsentKey)
        }
    }
}

// ───────────────────── C4 task-scope-addendum: S15-S19 ─────────────────────
//
// Independently authored against the frozen `task-scope`/`test-plan`/`task-scope-addendum` notes
// on item `8879f554` (C4 — AR-39 `schema_resolution` opt-in + D2 tag-probe fix), whose addendum
// extends C4 to also own the `schemaResolution` field on `EffectiveConfigDto` and two behavioural
// carry-overs on this route (A2 the DB-failure 500 mapping, A3 the ordering/ctor cleanups). All
// fixtures below are additional to S1-S22 above; no existing test in this file is modified.
//
// Surface labels (task-scope-addendum "Build additions" + Dtos.kt declarations):
//  - S15 (schemaResolution): the DTO field is additive-NEW in this item (A1). A narrowest revert
//    that keeps the field but hard-codes its population to "legacy" still compiles and reddens.
//  - S16 (ordering), S17 (defaultSchema), S18 (entry fields): all touch DTO fields that already
//    existed before this item (EffectiveSchemaDto.lifecycleMode/defaultTraits, TraitDto.resources,
//    EffectiveConfigDto.defaultSchema/types — see decl-ec445109.md) — EXISTING-SURFACE; these add
//    coverage for values the earlier S1-S22 suite never populated (S1/S6 exercised `.sorted()`
//    types and an always-null defaultSchema only).
//  - S19 (root-lookup DB failure -> 500): EXISTING-SURFACE (A2 changes existing route behaviour,
//    introduces no new type). NOT authored here — see the arbitration note below.
//
// S19 status: the addendum requires the 500 envelope to mirror GET /api/v1/roots/{rootId}/config's
// own internal-error response, "read that route's existing tests (ProjectConfigRoutesTest.kt) for
// the status/code/message it asserts; if none asserts it, stop and ask." ProjectConfigRoutesTest.kt
// asserts OK/Forbidden/UnprocessableEntity/PayloadTooLarge/PreconditionFailed/NotFound/Conflict/
// NotModified/NoContent only (grepped: no InternalServerError, no internal_error, no 500 assertion
// anywhere in that file, nor in FullApiWiringSmokeTest.kt). Per rule 4, this is a missing
// declaration, not a gap to fill from the route's general error-mapping conventions — escalated
// rather than derived. See the return line's `missing-declaration` field and `test-manifest`'s
// arbitration record.

class EffectiveConfigRoutesSchemaResolutionTest {
    @Test
    fun `S15 - schemaResolution reflects per-root, falls back to global, defaults legacy, downgrades isolated`() {
        // Case 1: a per-root schema_resolution wins outright, regardless of the (absent) global value.
        testApplication {
            val (schemaService, global) = globalFixture("work_item_schemas: {}")
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo, title = "S15 per-root layered")
            runBlocking {
                repo.projectConfigRepository().upsert(root.id, "schema_resolution: layered\nwork_item_schemas: {}\n")
            }
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "layered",
                decodeEffective(response.bodyAsText()).schemaResolution,
                "a per-root schema_resolution must win outright [task-scope effective-mode rule]"
            )
        }

        // Case 2: no schema_resolution key anywhere (no per-root row, global YAML omits it) -> legacy.
        testApplication {
            val (schemaService, global) = globalFixture("work_item_schemas: {}")
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo, title = "S15 absent everywhere")
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "legacy",
                decodeEffective(response.bodyAsText()).schemaResolution,
                "absent everywhere must default to legacy [D1: absent = legacy, no default flip]"
            )
        }

        // Case 3: global layered, per-root document present but WITHOUT the key -> inherits layered.
        testApplication {
            val (schemaService, global) = globalLayerFixture("schema_resolution: layered\nwork_item_schemas: {}\n")
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo, title = "S15 global layered, per-root silent")
            runBlocking { repo.projectConfigRepository().upsert(root.id, "work_item_schemas: {}\n") }
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "layered",
                decodeEffective(response.bodyAsText()).schemaResolution,
                "a per-root doc without the key must fall back to the global value [effective-mode rule]"
            )
        }

        // Case 4: global isolated, no per-root row at all -> downgraded to layered (isolated has no
        // meaning at the global level: nothing to isolate FROM).
        testApplication {
            val (schemaService, global) = globalLayerFixture("schema_resolution: isolated\nwork_item_schemas: {}\n")
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo, title = "S15 global isolated")
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "layered",
                decodeEffective(response.bodyAsText()).schemaResolution,
                "global isolated must be treated as layered [task-scope effective-mode rule]"
            )
        }
    }
}

class EffectiveConfigRoutesOrderingTest {
    @Test
    fun `S16 - types and schemas are listed in ascending natural key order regardless of insertion order`() =
        testApplication {
            // Global keys inserted in descending order; per-root contributes a key that sorts first.
            // If the route enumerated by insertion order (per-root-first, then global) this would
            // read ["apple", "zebra", "mango"] — not the sorted union.
            val globalYaml =
                """
                work_item_schemas:
                  zebra:
                    notes: []
                  mango:
                    notes: []
                """.trimIndent()
            val perRootYaml =
                """
                work_item_schemas:
                  apple:
                    notes: []
                """.trimIndent()
            val (schemaService, global) = globalFixture(globalYaml)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            runBlocking { repo.projectConfigRepository().upsert(root.id, perRootYaml) }
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, response.status)
            val dto = decodeEffective(response.bodyAsText())

            val expectedOrder = listOf("apple", "mango", "zebra")
            assertEquals(
                expectedOrder,
                dto.types,
                "types must be the sorted union in ascending natural order, unsorted by this assertion [C5 task-scope: keys.toSortedSet()]"
            )
            assertEquals(expectedOrder, dto.schemas.map { it.type }, "schemas[] must be listed in the same ascending order")
        }
}

class EffectiveConfigRoutesDefaultSchemaTest {
    @Test
    fun `S17 - a per-root default type populates defaultSchema sourced per-root`() =
        testApplication {
            val globalYaml = "work_item_schemas:\n  bug:\n    notes: []\n"
            val perRootYaml =
                """
                work_item_schemas:
                  default:
                    notes:
                      - key: default-note
                        role: queue
                        required: false
                """.trimIndent()
            val (schemaService, global) = globalFixture(globalYaml)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            runBlocking { repo.projectConfigRepository().upsert(root.id, perRootYaml) }
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            val dto = decodeEffective(response.bodyAsText())

            val defaultSchema = dto.defaultSchema
            assertNotNull(defaultSchema, "a per-root default type must populate defaultSchema")
            assertEquals("default", defaultSchema.type)
            assertEquals("per-root", defaultSchema.configSource)
            assertEquals(listOf("default-note"), defaultSchema.notes.map { it.key })
        }

    @Test
    fun `S17b - a global-only default type populates defaultSchema sourced global`() =
        testApplication {
            val globalYaml =
                """
                work_item_schemas:
                  default:
                    notes: []
                  bug:
                    notes: []
                """.trimIndent()
            val (schemaService, global) = globalFixture(globalYaml)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            // Deliberately no upsert() — no per-root row, so the default must come from global.
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            val dto = decodeEffective(response.bodyAsText())

            val defaultSchema = dto.defaultSchema
            assertNotNull(defaultSchema, "a global-only default type must still populate defaultSchema")
            assertEquals("default", defaultSchema.type)
            assertEquals("global", defaultSchema.configSource)
        }
}

class EffectiveConfigRoutesEntryFieldsTest {
    @Test
    fun `S18 - a manual-lifecycle schema surfaces default_traits, and a trait's resources are present or omitted per declaration`() =
        testApplication {
            val globalYaml =
                """
                work_item_schemas:
                  bug:
                    lifecycle: manual
                    default_traits:
                      - t1
                    notes: []
                traits:
                  t1:
                    resources:
                      - key: staging-db
                        mode: exclusive
                        ttlSeconds: 1800
                    notes: []
                  t2:
                    notes: []
                """.trimIndent()
            val (schemaService, global) = globalFixture(globalYaml)
            val repo = buildH2RepositoryProvider()
            val root = createRootItem(repo)
            val resolver = EffectiveConfigResolver(global, PerRootConfigService(repo.projectConfigRepository()))
            application { configureEffectiveConfigTestApp(repo, resolver, schemaService) }

            val response = client.get("/api/v1/roots/${root.id}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            val dto = decodeEffective(response.bodyAsText())

            val bugEntry = dto.schemas.first { it.type == "bug" }
            assertEquals(
                "manual",
                bugEntry.lifecycleMode,
                "lifecycle: manual must surface as lifecycleMode [config-format.md \"lifecycle\"]"
            )
            assertEquals(listOf("t1"), bugEntry.defaultTraits, "default_traits must surface verbatim [config-format.md \"default_traits\"]")

            val t1 = dto.traits.first { it.name == "t1" }
            val t1Resources = t1.resources
            assertNotNull(t1Resources, "a trait declaring resources: must populate TraitDto.resources")
            assertEquals(1, t1Resources.size)
            val resourceEntry = t1Resources.first()
            assertEquals("staging-db", resourceEntry.key)
            assertEquals("exclusive", resourceEntry.mode)
            assertEquals(1800, resourceEntry.ttlSeconds)

            val t2 = dto.traits.first { it.name == "t2" }
            assertNull(
                t2.resources,
                "a trait with no resources: declared must omit the field, not emit an empty list [api-rest.md TraitDto]"
            )
        }
}
