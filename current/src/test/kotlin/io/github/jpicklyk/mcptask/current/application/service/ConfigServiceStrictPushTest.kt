package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.GlobalConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.application.port.ConfigAppend
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.domain.model.ConfigAppendOutcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersion
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item edae92cb / Q16) for [ConfigService.push] as an admission point, over a real
 * SQLite database and the production validator wiring (JSON Schema structural pass plus the semantic pass).
 *
 * Scenarios: S2 (extensions stored byte-equal), S3 (a plugin key at the top level is rejected, history unchanged), S4
 * (one rejection per former soft skip), S5-S7 (scope and server-layer semantic rules), S10 (stored 3.x versions with
 * plugin keys and soft-invalid content still canonicalize and load), S16 (syntax errors), the size-cap boundary and the
 * re-push idempotency probe.
 *
 * Oracles: P = v4 plan s2/s3.6/s4.4/s9/U11; B = W3 brief s3.4; task-scope build items 5 and 7 and decisions D1, D6.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ConfigServiceStrictPushTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val provider get() = sqlite.repositoryProvider()
    private val store: ConfigStore get() = provider.configStore()
    private lateinit var service: ConfigService

    @BeforeEach
    fun setUp() {
        service = serviceWith(null)
    }

    private fun serviceWith(server: ConfigLayer?) =
        ConfigService(
            provider,
            YamlConfigDocumentParser,
            sqlite.unitOfWork(),
            ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { server })
        )

    private fun serverLayer(yaml: String): ConfigLayer {
        val parsed = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)
        check(parsed is ConfigDocumentParser.Outcome.Parsed) { "server fixture must parse: $parsed" }
        return ConfigLayer(parsed.document, "server-fp", ConfigSource.GLOBAL)
    }

    private suspend fun newRoot(title: String = "Root"): UUID =
        provider.workItemRepository().create(WorkItem(title = title, type = "project", depth = 0)).id

    private suspend fun historySize(root: UUID) = store.history(ConfigScope.ROOT, root, 100).size

    private suspend fun seedNullCanonical(
        root: UUID,
        yaml: String,
        now: Instant = Instant.parse("2026-03-01T10:00:00.000Z")
    ): ConfigVersion {
        val outcome =
            store.append(
                scope = ConfigScope.ROOT,
                rootId = root,
                append =
                    ConfigAppend(
                        yaml = yaml,
                        fingerprint = store.computeFingerprint(yaml),
                        canonicalJson = null,
                        modelVersion = null,
                        source = ConfigVersionSource.MIGRATION,
                        principalId = null,
                        reqId = null,
                        warnings = emptyList()
                    ),
                now = now
            )
        return assertIs<ConfigAppendOutcome.Applied>(outcome).version
    }

    private fun ConfigPushResult.Invalid.paths() = errors.map { it.path }

    // ---------------------------------------------------------------------------------------------
    // S2 - extensions are stored verbatim
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 a push with extensions is stored and the stored yaml is byte-equal to the pushed yaml`(): Unit =
        runBlocking {
            val root = newRoot()
            val yaml = "extensions:\n  claude-code:\n    orchestration:\n      mode: workflow\n" + StrictConfigCases.VALID_ROOT_YAML

            val result = service.push(root, yaml)

            val success = assertIs<ConfigPushResult.Success>(result, "extensions must be admitted: $result")
            assertEquals(1, success.version)
            val stored = assertNotNull(store.versionAt(ConfigScope.ROOT, root, 1))
            assertEquals(yaml, stored.yaml, "the stored document is the pushed document, byte for byte")
        }

    // ---------------------------------------------------------------------------------------------
    // S3 - a plugin key at the top level is rejected; history unchanged
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 a top-level orchestration key is rejected at that key and nothing is stored`(): Unit =
        runBlocking {
            val root = newRoot()

            val result = service.push(root, "orchestration:\n  mode: workflow\n" + StrictConfigCases.VALID_ROOT_YAML)

            val invalid = assertIs<ConfigPushResult.Invalid>(result, "unexpected: $result")
            assertTrue("/orchestration" in invalid.paths(), "errors: ${invalid.errors}")
            assertNull(store.head(ConfigScope.ROOT, root), "a rejected first push leaves no head")
            assertEquals(0, historySize(root))
        }

    @Test
    fun `S3 a rejected push after a valid one leaves the history and the head unchanged`(): Unit =
        runBlocking {
            val root = newRoot()
            val first = assertIs<ConfigPushResult.Success>(service.push(root, StrictConfigCases.VALID_ROOT_YAML))
            val headBefore = assertNotNull(store.head(ConfigScope.ROOT, root))

            val result = service.push(root, "orchestration:\n  mode: workflow\n" + StrictConfigCases.VALID_ROOT_YAML)

            assertIs<ConfigPushResult.Invalid>(result)
            assertEquals(1, historySize(root))
            assertEquals(headBefore, store.head(ConfigScope.ROOT, root))
            assertEquals(first.fingerprint, store.head(ConfigScope.ROOT, root)?.fingerprint)
        }

    // ---------------------------------------------------------------------------------------------
    // S4 - every former soft skip rejects the push at its pointer
    // ---------------------------------------------------------------------------------------------

    @TestFactory
    fun `S4 each defect rejects the push at its pointer and stores nothing, the repaired twin is admitted`(): List<DynamicTest> =
        StrictConfigCases.ROOT_CASES.map { case ->
            dynamicTest(case.name) {
                runBlocking {
                    val root = newRoot("Root ${case.name}")

                    val broken = service.push(root, case.yaml)

                    val invalid = assertIs<ConfigPushResult.Invalid>(broken, "must be rejected: ${case.yaml} -> $broken")
                    assertTrue(
                        invalid.paths().any { it == case.errorPath || (case.withinEntry && it.startsWith(case.errorPath + "/")) },
                        "expected ${case.errorPath}, got ${invalid.errors}"
                    )
                    assertNull(store.head(ConfigScope.ROOT, root), "a rejected push stores nothing")

                    val fixed = service.push(root, case.fixedYaml)
                    assertIs<ConfigPushResult.Success>(fixed, "the repaired twin must be admitted: ${case.fixedYaml} -> $fixed")
                }
            }
        }

    @Test
    fun `S4 force does not bypass validation`(): Unit =
        runBlocking {
            val root = newRoot()

            val result = service.push(root, "orchestration:\n  mode: workflow\n" + StrictConfigCases.VALID_ROOT_YAML, force = true)

            assertIs<ConfigPushResult.Invalid>(result, "force bypasses the fingerprint and rootId guards, not admission: $result")
            assertNull(store.head(ConfigScope.ROOT, root))
        }

    // ---------------------------------------------------------------------------------------------
    // S5 - ROOT scope rejects server-only sections
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 a push carrying resources or actor_authentication is rejected at those keys`(): Unit =
        runBlocking {
            val root = newRoot()
            val yaml =
                "resources:\n  db-cred:\n    description: \"x\"\nactor_authentication:\n  mode: jwks\n" + StrictConfigCases.VALID_ROOT_YAML

            val result = service.push(root, yaml)

            val invalid = assertIs<ConfigPushResult.Invalid>(result)
            assertTrue("/resources" in invalid.paths(), "errors: ${invalid.errors}")
            assertTrue("/actor_authentication" in invalid.paths(), "errors: ${invalid.errors}")
            assertNull(store.head(ConfigScope.ROOT, root))
        }

    // ---------------------------------------------------------------------------------------------
    // S6 / S7 - the server layer participates in semantic checks
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 default_traits naming a trait that only the server layer defines is admitted, an unknown one is rejected`(): Unit =
        runBlocking {
            val withServer = serviceWith(serverLayer("traits:\n  ghost:\n    notes: []\n"))
            val schema = "work_item_schemas:\n  x:\n    default_traits: [ghost]\n    notes: []\n"
            val rootA = newRoot("A")
            val rootB = newRoot("B")

            val admitted = withServer.push(rootA, schema)
            val rejected = service.push(rootB, schema)

            assertIs<ConfigPushResult.Success>(admitted, "server-layer trait must resolve: $admitted")
            val invalid = assertIs<ConfigPushResult.Invalid>(rejected)
            assertTrue("/work_item_schemas/x/default_traits/0" in invalid.paths(), "errors: ${invalid.errors}")
        }

    @Test
    fun `S7 a trait resource key is admitted only when the server registry declares it`(): Unit =
        runBlocking {
            val withRegistry = serviceWith(serverLayer("resources:\n  db-cred:\n    description: \"x\"\n"))
            val yaml = "traits:\n  needs-db:\n    resources: [db-cred]\n"
            val rootA = newRoot("A")
            val rootB = newRoot("B")

            val admitted = withRegistry.push(rootA, yaml)
            val rejected = service.push(rootB, yaml)

            assertIs<ConfigPushResult.Success>(admitted, "declared key must be admitted: $admitted")
            val invalid = assertIs<ConfigPushResult.Invalid>(rejected)
            assertTrue(
                invalid.paths().any { it == "/traits/needs-db/resources/0" || it.startsWith("/traits/needs-db/resources/0/") },
                "errors: ${invalid.errors}"
            )
        }

    // ---------------------------------------------------------------------------------------------
    // S8 - advisory findings do not reject
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 a required note no seat owns succeeds with a schema warning`(): Unit =
        runBlocking {
            val root = newRoot()
            val yaml =
                "work_item_schemas:\n  feature-task:\n    seats:\n      - { name: impl, phase: work, enters: true }\n" +
                    "    notes:\n      - key: spec\n        role: work\n        required: true\n"

            val result = service.push(root, yaml)

            val success = assertIs<ConfigPushResult.Success>(result)
            assertTrue(
                success.schemaWarnings.any { it.contains("feature-task") && it.contains("spec") },
                "warnings: ${success.schemaWarnings}"
            )
            assertEquals(yaml, store.versionAt(ConfigScope.ROOT, root, 1)?.yaml)
        }

    // ---------------------------------------------------------------------------------------------
    // S10 - stored 3.x versions keep loading
    // ---------------------------------------------------------------------------------------------

    private val legacy3xYaml =
        "orchestration:\n  mode: workflow\nretrospective:\n  mode: dispatch\nactor_attribution:\n  required: true\n" +
            "work_item_schemas:\n  feature-task:\n    lifecycle: bogus\n    notes:\n      - key: spec\n        role: queue\n" +
            "        required: true\n      - key: other\n        role: Work\n"

    @Test
    fun `S10 canonicalizeMissing fills a migrated 3_x row carrying plugin keys and soft-invalid content`(): Unit =
        runBlocking {
            val root = newRoot()
            val migrated = seedNullCanonical(root, legacy3xYaml)
            assertNull(migrated.canonicalJson, "fixture: the migrated row has no canonical form yet")

            val filled = service.canonicalizeMissing()

            assertEquals(1, filled, "a 3.x row must not be left unparseable by strictness")
            val after = assertNotNull(store.version(migrated.id))
            assertNotNull(after.canonicalJson, "canonical_json is filled")
            assertEquals(1, after.modelVersion)
            assertEquals(emptyList(), store.versionsMissingCanonical())
        }

    @Test
    fun `S10 after canonicalization the root layer loads and serves the schemas, with no invalid-config failure`(): Unit =
        runBlocking {
            val root = newRoot()
            seedNullCanonical(root, legacy3xYaml)
            service.canonicalizeMissing()

            val layer = assertNotNull(PerRootConfigService(store).layer(root), "the root must have a config layer")

            assertEquals(setOf("feature-task"), layer.document.workItemSchemas.keys)
            assertEquals(emptyList(), layer.document.errors, "reading a stored version is not an admission point")
        }

    @Test
    fun `S10 rolling back to a migrated 3_x version with no canonical form fills it without re-validating`(): Unit =
        runBlocking {
            val root = newRoot()
            val migrated = seedNullCanonical(root, legacy3xYaml)
            assertNull(migrated.canonicalJson)
            assertIs<ConfigPushResult.Success>(service.push(root, StrictConfigCases.VALID_ROOT_YAML))

            val result = service.rollback(root, 1)

            val success =
                assertIs<ConfigRollbackResult.Success>(
                    result,
                    "a restore of an admitted-at-the-time version is never re-validated: $result"
                )
            assertNotNull(success.version.canonicalJson, "the restored head carries a canonical form")
            assertEquals(legacy3xYaml, success.version.yaml)
        }

    // ---------------------------------------------------------------------------------------------
    // S16 - parse failures
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S16 a YAML syntax error and a non-mapping root are parse errors and store nothing`(): Unit =
        runBlocking {
            val root = newRoot()

            val syntax = service.push(root, "work_item_schemas: [this is not, a valid: map")
            val nonMap = service.push(root, "- a\n- b\n")

            assertIs<ConfigPushResult.ParseError>(syntax)
            assertIs<ConfigPushResult.ParseError>(nonMap)
            assertNull(store.head(ConfigScope.ROOT, root))
        }

    // ---------------------------------------------------------------------------------------------
    // S17 and probes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S17 empty, comment-only and empty-mapping documents are admitted`(): Unit =
        runBlocking {
            for ((index, yaml) in listOf("", "# nothing yet\n", "{}\n").withIndex()) {
                val root = newRoot("Root $index")
                val result = service.push(root, yaml)
                assertIs<ConfigPushResult.Success>(result, "document #$index ${yaml.take(20)}: $result")
            }
        }

    @Test
    fun `probe the size cap is unchanged - exactly 131072 bytes is admitted, one more is TooLarge`(): Unit =
        runBlocking {
            val root = newRoot()
            val prefix = "# "
            val atCap = prefix + "a".repeat(131_072 - prefix.length - 1) + "\n"
            val overCap = prefix + "a".repeat(131_072 - prefix.length) + "\n"
            assertEquals(131_072, atCap.toByteArray(Charsets.UTF_8).size, "fixture: exactly at the cap")
            assertEquals(131_073, overCap.toByteArray(Charsets.UTF_8).size, "fixture: one byte over")

            val over = service.push(root, overCap)
            val at = service.push(root, atCap)

            val tooLarge = assertIs<ConfigPushResult.TooLarge>(over)
            assertEquals(131_073, tooLarge.sizeBytes)
            assertEquals(131_072, tooLarge.maxBytes)
            assertIs<ConfigPushResult.Success>(at, "a document of exactly the cap is admitted: $at")
        }

    @Test
    fun `probe re-pushing an identical valid document is a no-op that appends nothing`(): Unit =
        runBlocking {
            val root = newRoot()
            val first = assertIs<ConfigPushResult.Success>(service.push(root, StrictConfigCases.VALID_ROOT_YAML))

            val second = assertIs<ConfigPushResult.Success>(service.push(root, StrictConfigCases.VALID_ROOT_YAML))

            assertTrue(first.appended)
            assertEquals(false, second.appended)
            assertEquals(first.version, second.version)
            assertEquals(1, historySize(root))
        }
}
