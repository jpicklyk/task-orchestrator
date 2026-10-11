package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.ConfigAppend
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.telemetry.CallTelemetry
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepPhase
import io.github.jpicklyk.mcptask.current.application.upgrade.steps.ConfigCanonicalizeStep
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.ConfigAppendOutcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersion
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f75eb22e / Q8) for the versioning behaviour of [ConfigService] over a real
 * SQLite database: push, identical re-push, both compare-and-set guards, the fast-forward guard, get / history, rollback,
 * reset, the canonical backfill, and the `config.version_created` event.
 *
 * Oracles: task-scope section 1.5 (service contract), 1.6 (event shape), 1.11 AC2-AC11 and decisions D5-D8, D15. Expected
 * canonical JSON is written out by hand from the codec rules of section 1.4; expected version numbers are counted from
 * the sequence of calls; fingerprints are derived through the store's pure `computeFingerprint`.
 *
 * The file is named ConfigServiceVersioningTest because ConfigServiceTest.kt already exists (the renamed push-pipeline
 * tests of the old service) and is not this seat's to overwrite.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ConfigServiceVersioningTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val provider get() = sqlite.repositoryProvider()
    private val store: ConfigStore get() = provider.configStore()
    private lateinit var service: ConfigService

    private val base =
        "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: true\n"

    private val baseCanonical =
        "{\"work_item_schemas\":{\"feature-task\":{\"notes\":[{\"key\":\"spec\",\"required\":true,\"role\":\"queue\"}]}}}"

    /** [base] plus a seat-aware schema with a required note no seat owns: one advisory warning, no error. */
    private val withAdvisoryWarning =
        base + "  seated:\n    seats:\n      - { name: impl, phase: work, enters: true }\n" +
            "    notes:\n      - key: unowned\n        role: work\n        required: true\n"

    /** Revision [n] of the base document: same data, a distinct trailing comment, therefore a distinct fingerprint. */
    private fun rev(n: Int) = "$base# rev $n\n"

    @BeforeEach
    fun setUp() {
        service = ConfigService(provider, YamlConfigDocumentParser, sqlite.unitOfWork())
    }

    private suspend fun newRoot(title: String = "Root"): UUID =
        provider.workItemRepository().create(WorkItem(title = title, type = "project", depth = 0)).id

    private suspend fun pushOk(
        root: UUID,
        yaml: String
    ): ConfigPushResult.Success = assertIs<ConfigPushResult.Success>(service.push(root, yaml), "push of '${yaml.take(40)}'")

    private suspend fun <T> written(block: suspend () -> T): Pair<T, List<EventRecord>> {
        val mark = provider.eventStore().maxSeq()
        val result = block()
        return result to provider.eventStore().readAfter(mark, null, 100_000)
    }

    private fun List<EventRecord>.configEvents() = filter { it.type == "config.version_created" }

    private suspend fun head(root: UUID) = assertNotNull(store.head(ConfigScope.ROOT, root), "root $root has a head")

    private suspend fun versionAt(
        root: UUID,
        n: Int
    ): ConfigVersion = assertNotNull(store.versionAt(ConfigScope.ROOT, root, n), "version $n of $root")

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

    private fun <T> Outcome<T>.value(): T = getOrNull() ?: error("expected Outcome.Ok, got $this")

    // ---------------------------------------------------------------------------------------------
    // S2 - first push
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 the first push is version 1, stores every field with the ambient actor and request id, and records one event`(): Unit =
        runBlocking {
            val root = newRoot()
            val actor = ActorClaim(id = "agent-cfg", kind = ActorKind.SUBAGENT)
            val telemetry = CallTelemetry(reqId = "k7f3q9ab", surface = "mcp", sessionId = null)

            val (result, rows) = written { withEventActor(actor) { withContext(telemetry) { service.push(root, base) } } }

            val success = assertIs<ConfigPushResult.Success>(result)
            assertEquals(root, success.rootItemId)
            assertEquals(1, success.version)
            assertTrue(success.appended)
            assertEquals(store.computeFingerprint(base), success.fingerprint)

            val v = versionAt(root, 1)
            assertEquals(base, v.yaml)
            assertEquals(store.computeFingerprint(base), v.fingerprint)
            assertEquals(baseCanonical, v.canonicalJson)
            assertEquals(1, v.modelVersion)
            assertEquals(ConfigVersionSource.PUSH, v.source)
            assertEquals("agent-cfg", v.principalId)
            assertEquals("k7f3q9ab", v.reqId)
            assertEquals(success.schemaWarnings, v.warnings, "the stored warnings are the push's schema warnings")
            assertEquals(success.updatedAt, v.createdAt, "updatedAt of the result is the new version's creation time")
            assertEquals(v.id, head(root).versionId)

            assertEquals(listOf("config.version_created"), rows.map { it.type }, "exactly one event, of the new type")
            val event = rows.single()
            assertEquals(v.id, event.entityId)
            assertEquals(root, event.rootId)
            assertEquals("config", event.entityKind)
            assertEquals("root", event.str("scope"))
            assertEquals(root.toString(), event.str("rootId"))
            assertEquals(
                1,
                event
                    .payload()["version"]!!
                    .jsonPrimitive.content
                    .toInt()
            )
            assertEquals(v.fingerprint, event.str("fingerprint"))
            assertEquals("push", event.str("source"))
            assertEquals(
                v.warnings.size,
                event
                    .payload()["warningsCount"]!!
                    .jsonPrimitive.content
                    .toInt()
            )
        }

    @Test
    fun `S2 a push outside any actor or telemetry context stores null principal and request id`(): Unit =
        runBlocking {
            val root = newRoot()

            pushOk(root, base)

            val v = versionAt(root, 1)
            assertNull(v.principalId)
            assertNull(v.reqId)
        }

    @Test
    fun `S2 schema warnings of a push are stored on the version and counted in the event`(): Unit =
        runBlocking {
            val root = newRoot()

            val (success, rows) = written { pushOk(root, withAdvisoryWarning) }

            assertTrue(success.schemaWarnings.isNotEmpty(), "fixture: a required note no seat owns must warn")
            val v = versionAt(root, 1)
            assertEquals(success.schemaWarnings, v.warnings)
            assertEquals(
                success.schemaWarnings.size,
                rows
                    .configEvents()
                    .single()
                    .payload()["warningsCount"]!!
                    .jsonPrimitive.content
                    .toInt()
            )
        }

    @Test
    fun `S2 CRLF line endings are stored byte for byte, and fingerprint like their LF form as documented`(): Unit =
        runBlocking {
            val root = newRoot()
            val crlf = base.replace("\n", "\r\n")

            val success = pushOk(root, crlf)

            val v = versionAt(root, 1)
            assertEquals(crlf, v.yaml, "the stored text is exactly what was pushed")
            assertEquals(
                store.computeFingerprint(base),
                success.fingerprint,
                "api-reference: CRLF is normalized to LF before fingerprinting"
            )
            assertEquals(baseCanonical, v.canonicalJson, "the canonical form does not depend on line endings")

            val lf = pushOk(root, base)
            assertFalse(lf.appended, "the LF form of the same document is an identical re-push (same fingerprint)")
            assertEquals(1, lf.version)
            assertEquals(crlf, versionAt(root, 1).yaml, "the first writer's text is kept")
            assertEquals(1, store.history(ConfigScope.ROOT, root, 50).size)
        }

    @Test
    fun `S2 a failed push writes no version and no event`(): Unit =
        runBlocking {
            val root = newRoot()

            val (result, rows) = written { service.push(root, "work_item_schemas: [\ninvalid yaml: :\n  - broken") }

            assertIs<ConfigPushResult.ParseError>(result)
            assertNull(store.head(ConfigScope.ROOT, root))
            assertEquals(emptyList(), store.history(ConfigScope.ROOT, root, 50))
            assertEquals(emptyList(), rows.configEvents())
        }

    @Test
    fun `S2 the size cap boundary - exactly the maximum is accepted and one byte more is TooLarge`(): Unit =
        runBlocking {
            val root = newRoot()
            val max = ConfigService.MAX_CONFIG_YAML_BYTES
            val head = "note_limits:\n  mode: warn\n#"
            val atCap = head + "x".repeat(max - head.length)
            assertEquals(max, atCap.toByteArray(Charsets.UTF_8).size, "fixture: exactly at the cap")

            assertIs<ConfigPushResult.Success>(service.push(root, atCap))

            val other = newRoot("Other")
            val tooLarge = assertIs<ConfigPushResult.TooLarge>(service.push(other, atCap + "x"))
            assertEquals(max + 1, tooLarge.sizeBytes)
            assertEquals(max, tooLarge.maxBytes)
            assertNull(store.head(ConfigScope.ROOT, other))
        }

    // ---------------------------------------------------------------------------------------------
    // S3 - changed push, get, history
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 a changed push is version 2, get serves the head and a named version, history lists both`(): Unit =
        runBlocking {
            val root = newRoot()
            val first = pushOk(root, rev(1))
            val v1 = versionAt(root, 1)

            val (second, rows) = written { pushOk(root, rev(2)) }

            assertEquals(2, second.version)
            assertTrue(second.appended)
            assertNotEquals(first.fingerprint, second.fingerprint)
            assertEquals(listOf("config.version_created"), rows.map { it.type })
            assertEquals(v1, versionAt(root, 1), "version 1 is immutable")

            val headRow = assertNotNull(service.get(root))
            assertEquals(2, headRow.version)
            assertEquals(rev(2), headRow.yaml)
            assertEquals(rev(1), assertNotNull(service.get(root, 1)).yaml)
            assertEquals(rev(2), assertNotNull(service.get(root, 2)).yaml)
            assertNull(service.get(root, 3))
            assertEquals(2, service.headVersion(root))
            assertEquals(listOf(2, 1), service.history(root).map { it.version })
        }

    @Test
    fun `S3 get history and headVersion of a root without config are empty`(): Unit =
        runBlocking {
            val root = newRoot()

            assertNull(service.get(root))
            assertNull(service.get(root, 1))
            assertNull(service.headVersion(root))
            assertEquals(emptyList(), service.history(root))
        }

    @Test
    fun `S3 history honours limit and before`(): Unit =
        runBlocking {
            val root = newRoot()
            for (n in 1..5) pushOk(root, rev(n))

            assertEquals(listOf(5, 4, 3, 2, 1), service.history(root).map { it.version })
            assertEquals(listOf(5, 4), service.history(root, limit = 2).map { it.version })
            assertEquals(listOf(2, 1), service.history(root, before = 3).map { it.version })
            assertEquals(listOf(3), service.history(root, limit = 1, before = 4).map { it.version })
        }

    // ---------------------------------------------------------------------------------------------
    // S4 - identical re-push
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 an identical re-push appends nothing, records no event, and reports the head`(): Unit =
        runBlocking {
            val root = newRoot()
            val first = pushOk(root, base)
            val v1 = versionAt(root, 1)

            val (again, rows) = written { pushOk(root, base) }

            assertEquals(1, again.version)
            assertFalse(again.appended)
            assertEquals(first.fingerprint, again.fingerprint)
            assertEquals(v1.createdAt, again.updatedAt, "updatedAt is the head version's creation time, unchanged")
            assertEquals(1, store.history(ConfigScope.ROOT, root, 50).size, "fixture condition: a second row would make this 2")
            assertEquals(emptyList(), rows.configEvents(), "no config event for an idempotent re-push")
            assertEquals(v1, versionAt(root, 1))
        }

    @Test
    fun `S4 a whitespace-only change is a different fingerprint and does append`(): Unit =
        runBlocking {
            val root = newRoot()
            pushOk(root, base)

            val changed = pushOk(root, "$base\n")

            assertEquals(2, changed.version)
            assertTrue(changed.appended)
        }

    // ---------------------------------------------------------------------------------------------
    // S5 - compare-and-set
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 a stale expectedVersion is PreconditionFailed with the head, even under force, and writes nothing`(): Unit =
        runBlocking {
            val root = newRoot()
            pushOk(root, rev(1))
            val second = pushOk(root, rev(2))

            val (stale, rows) = written { service.push(root, rev(3), expectedVersion = 1) }

            val failed = assertIs<ConfigPushResult.PreconditionFailed>(stale)
            assertEquals(root, failed.rootItemId)
            assertEquals(2, failed.currentVersion)
            assertEquals(second.fingerprint, failed.currentFingerprint)

            val forced = assertIs<ConfigPushResult.PreconditionFailed>(service.push(root, rev(3), force = true, expectedVersion = 1))
            assertEquals(2, forced.currentVersion)

            assertEquals(2, store.history(ConfigScope.ROOT, root, 50).size)
            assertEquals(emptyList(), rows.configEvents())
        }

    @Test
    fun `S5 a matching expectedVersion applies`(): Unit =
        runBlocking {
            val root = newRoot()
            pushOk(root, rev(1))
            pushOk(root, rev(2))

            val ok = service.push(root, rev(3), expectedVersion = 2)

            assertEquals(3, assertIs<ConfigPushResult.Success>(ok).version)
        }

    @Test
    fun `S5 the legacy expectedFingerprint behaves the same and force does not skip it`(): Unit =
        runBlocking {
            val root = newRoot()
            val first = pushOk(root, rev(1))
            val second = pushOk(root, rev(2))

            val stale = assertIs<ConfigPushResult.PreconditionFailed>(service.push(root, rev(3), expectedFingerprint = first.fingerprint))
            assertEquals(2, stale.currentVersion)
            assertEquals(second.fingerprint, stale.currentFingerprint)
            assertIs<ConfigPushResult.PreconditionFailed>(service.push(root, rev(3), force = true, expectedFingerprint = first.fingerprint))
            assertEquals(2, store.history(ConfigScope.ROOT, root, 50).size)

            val fresh = service.push(root, rev(3), expectedFingerprint = second.fingerprint)
            assertEquals(3, assertIs<ConfigPushResult.Success>(fresh).version)
        }

    @Test
    fun `S5 both guards are ignored while the root has no config`(): Unit =
        runBlocking {
            val rootA = newRoot("A")
            val rootB = newRoot("B")

            assertEquals(1, assertIs<ConfigPushResult.Success>(service.push(rootA, base, expectedVersion = 9)).version)
            assertEquals(1, assertIs<ConfigPushResult.Success>(service.push(rootB, base, expectedFingerprint = "0".repeat(64))).version)
        }
    // ---------------------------------------------------------------------------------------------
    // S6 - fast-forward guard
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 re-pushing content an earlier version held is Superseded and writes nothing`(): Unit =
        runBlocking {
            val root = newRoot()
            pushOk(root, rev(1))
            pushOk(root, rev(2))
            val v2 = versionAt(root, 2)

            val (rejected, rows) = written { service.push(root, rev(1)) }

            val superseded = assertIs<ConfigPushResult.Superseded>(rejected)
            assertEquals(root, superseded.rootItemId)
            assertEquals(v2.createdAt, superseded.currentUpdatedAt)
            assertEquals(2, store.history(ConfigScope.ROOT, root, 50).size)
            assertEquals(emptyList(), rows.configEvents())
        }

    @Test
    fun `S6 force alone accepts superseded content`(): Unit =
        runBlocking {
            val root = newRoot()
            pushOk(root, rev(1))
            pushOk(root, rev(2))

            val forced = assertIs<ConfigPushResult.Success>(service.push(root, rev(1), force = true))
            assertEquals(3, forced.version)
            assertTrue(forced.appended)
        }

    @Test
    fun `S6 classifyRelation reports current, superseded and unknown`(): Unit =
        runBlocking {
            val root = newRoot()
            val first = pushOk(root, rev(1))
            val second = pushOk(root, rev(2))

            assertEquals("current", service.classifyRelation(root, second.fingerprint))
            assertEquals("superseded", service.classifyRelation(root, first.fingerprint))
            assertEquals("unknown", service.classifyRelation(root, store.computeFingerprint("other: doc\n")))
        }

    // ---------------------------------------------------------------------------------------------
    // S8 / S9 - reset
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 reset appends a reset version, hides the head, keeps history, and records an event`(): Unit =
        runBlocking {
            val root = newRoot()
            pushOk(root, rev(1))
            val v1 = versionAt(root, 1)

            val (outcome, rows) = written { service.reset(root) }

            assertEquals(true, outcome.value())
            assertNull(service.get(root), "a reset head is absence for get without a version")
            val reset = assertNotNull(service.get(root, 2), "the reset version is addressable by number")
            assertEquals(ConfigVersionSource.RESET, reset.source)
            assertEquals("", reset.yaml)
            assertEquals("{}", reset.canonicalJson)
            assertEquals(1, reset.modelVersion)
            assertEquals(store.computeFingerprint(""), reset.fingerprint)
            assertEquals(v1, assertNotNull(service.get(root, 1)), "pre-reset versions stay readable")
            assertEquals(listOf(2, 1), service.history(root).map { it.version })
            assertEquals(ConfigVersionSource.RESET, service.history(root).first().source)

            val event = rows.configEvents().single()
            assertEquals(reset.id, event.entityId)
            assertEquals("reset", event.str("source"))
            assertEquals(
                2,
                event
                    .payload()["version"]!!
                    .jsonPrimitive.content
                    .toInt()
            )
            assertEquals(reset.fingerprint, event.str("fingerprint"))
            assertEquals(
                0,
                event
                    .payload()["warningsCount"]!!
                    .jsonPrimitive.content
                    .toInt()
            )
        }

    @Test
    fun `S8 a second reset, and a reset of a never-configured root, write nothing`(): Unit =
        runBlocking {
            val root = newRoot()
            val never = newRoot("Never")
            pushOk(root, base)
            assertEquals(true, service.reset(root).value())

            val (second, rows) = written { service.reset(root) }
            val (neverConfigured, neverRows) = written { service.reset(never) }

            assertEquals(false, second.value())
            assertEquals(false, neverConfigured.value())
            assertEquals(2, store.history(ConfigScope.ROOT, root, 50).size, "fixture condition: a second reset row would make this 3")
            assertNull(store.head(ConfigScope.ROOT, never))
            assertEquals(emptyList(), rows.configEvents())
            assertEquals(emptyList(), neverRows.configEvents())
        }

    @Test
    fun `S9 after a reset the pre-reset content is accepted as new, not rejected as superseded`(): Unit =
        runBlocking {
            val root = newRoot()
            val first = pushOk(root, rev(1))
            pushOk(root, rev(2))
            service.reset(root).value()

            assertEquals("unknown", service.classifyRelation(root, first.fingerprint))

            val again = service.push(root, rev(1))
            val success = assertIs<ConfigPushResult.Success>(again, "pre-reset content must not be Superseded after a reset")
            assertEquals(4, success.version)
            assertTrue(success.appended)
            assertEquals(rev(1), assertNotNull(service.get(root)).yaml, "the config is visible again")
        }

    // ---------------------------------------------------------------------------------------------
    // S10 - rollback
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S10 rollback copies the target as a new head with source rollback and records an event`(): Unit =
        runBlocking {
            val root = newRoot()
            pushOk(
                root,
                "${rev(
                    1
                ).removeSuffix(
                    "# rev 1\n"
                )}  seated:\n    seats:\n      - { name: impl, phase: work, enters: true }\n    notes:\n      - key: unowned\n        role: work\n        required: true\n# rev 1\n"
            )
            pushOk(root, rev(2))
            val v1 = versionAt(root, 1)
            assertTrue(v1.warnings.isNotEmpty(), "fixture: the target carries warnings that must be copied")
            val actor = ActorClaim(id = "agent-rb", kind = ActorKind.SUBAGENT)

            val (result, rows) = written { withEventActor(actor) { service.rollback(root, 1) } }

            val success = assertIs<ConfigRollbackResult.Success>(result)
            assertTrue(success.appended)
            val v = success.version
            assertEquals(3, v.version)
            assertEquals(ConfigVersionSource.ROLLBACK, v.source)
            assertEquals(v1.yaml, v.yaml)
            assertEquals(v1.fingerprint, v.fingerprint)
            assertEquals(v1.canonicalJson, v.canonicalJson)
            assertEquals(v1.modelVersion, v.modelVersion)
            assertEquals(v1.warnings, v.warnings)
            assertEquals("agent-rb", v.principalId)
            assertNotEquals(v1.id, v.id, "a rollback is a new row, not a re-pointed old one")
            assertEquals(v.id, head(root).versionId)
            assertEquals(3, head(root).version)
            assertEquals(listOf(3, 2, 1), service.history(root).map { it.version })
            assertEquals(v1, versionAt(root, 1), "the target is untouched")

            val event = rows.configEvents().single()
            assertEquals(v.id, event.entityId)
            assertEquals("rollback", event.str("source"))
            assertEquals(
                3,
                event
                    .payload()["version"]!!
                    .jsonPrimitive.content
                    .toInt()
            )
            assertEquals(v1.fingerprint, event.str("fingerprint"))
            assertEquals(
                v1.warnings.size,
                event
                    .payload()["warningsCount"]!!
                    .jsonPrimitive.content
                    .toInt()
            )
        }

    @Test
    fun `S10 rollback to a target with the head's fingerprint writes nothing`(): Unit =
        runBlocking {
            val root = newRoot()
            pushOk(root, rev(1))
            pushOk(root, rev(2))
            service.rollback(root, 1)
            val headBefore = head(root)
            assertEquals(3, headBefore.version)

            val (again, rows) = written { service.rollback(root, 1) }

            val success = assertIs<ConfigRollbackResult.Success>(again)
            assertFalse(success.appended)
            assertEquals(3, success.version.version)
            assertEquals(headBefore.fingerprint, success.version.fingerprint)
            assertEquals(3, store.history(ConfigScope.ROOT, root, 50).size, "fixture condition: an appended row would make this 4")
            assertEquals(emptyList(), rows.configEvents())
        }

    @Test
    fun `S10 rollback to the head version itself is also a no-op`(): Unit =
        runBlocking {
            val root = newRoot()
            pushOk(root, rev(1))
            pushOk(root, rev(2))

            val result = assertIs<ConfigRollbackResult.Success>(service.rollback(root, 2))

            assertFalse(result.appended)
            assertEquals(2, result.version.version)
            assertEquals(2, store.history(ConfigScope.ROOT, root, 50).size)
        }

    @Test
    fun `S10 rollback of an unknown version or of a root without config is NotFound`(): Unit =
        runBlocking {
            val root = newRoot()
            val empty = newRoot("Empty")
            pushOk(root, rev(1))
            pushOk(root, rev(2))

            val unknown = assertIs<ConfigRollbackResult.NotFound>(service.rollback(root, 99))
            assertEquals(root, unknown.rootItemId)
            assertEquals(99, unknown.toVersion)
            val none = assertIs<ConfigRollbackResult.NotFound>(service.rollback(empty, 1))
            assertEquals(empty, none.rootItemId)
            assertEquals(1, none.toVersion)
            assertEquals(2, store.history(ConfigScope.ROOT, root, 50).size)
        }

    @Test
    fun `S10 rollback honours both compare-and-set guards`(): Unit =
        runBlocking {
            val root = newRoot()
            val first = pushOk(root, rev(1))
            val second = pushOk(root, rev(2))

            val staleVersion = assertIs<ConfigRollbackResult.PreconditionFailed>(service.rollback(root, 1, expectedVersion = 1))
            assertEquals(2, staleVersion.currentVersion)
            assertEquals(second.fingerprint, staleVersion.currentFingerprint)
            val staleFingerprint =
                assertIs<ConfigRollbackResult.PreconditionFailed>(service.rollback(root, 1, expectedFingerprint = first.fingerprint))
            assertEquals(2, staleFingerprint.currentVersion)
            assertEquals(2, store.history(ConfigScope.ROOT, root, 50).size)

            val ok = assertIs<ConfigRollbackResult.Success>(service.rollback(root, 1, expectedVersion = 2))
            assertEquals(3, ok.version.version)
        }

    @Test
    fun `S10 rolling back to a version whose canonical JSON is NULL canonicalizes the new head`(): Unit =
        runBlocking {
            val root = newRoot()
            val migrated = seedNullCanonical(root, base)
            assertNull(migrated.canonicalJson, "fixture: the migrated version has no canonical form yet")
            pushOk(root, rev(2))

            val result = assertIs<ConfigRollbackResult.Success>(service.rollback(root, 1))

            assertTrue(result.appended)
            assertEquals(baseCanonical, result.version.canonicalJson)
            assertEquals(1, result.version.modelVersion)
            assertEquals(baseCanonical, store.version(head(root).versionId)?.canonicalJson)
        }

    @Test
    fun `S9 after a rollback the rolled-back content is current, and re-pushing it is a no-op`(): Unit =
        runBlocking {
            val root = newRoot()
            val first = pushOk(root, rev(1))
            val second = pushOk(root, rev(2))
            service.rollback(root, 1)

            assertEquals("current", service.classifyRelation(root, first.fingerprint))
            assertEquals("superseded", service.classifyRelation(root, second.fingerprint))

            val (again, rows) = written { pushOk(root, rev(1)) }
            assertFalse(again.appended)
            assertEquals(3, again.version)
            assertEquals(3, store.history(ConfigScope.ROOT, root, 50).size)
            assertEquals(emptyList(), rows.configEvents())
        }

    // ---------------------------------------------------------------------------------------------
    // S14 - canonical backfill
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S14 canonicalizeMissing fills every parseable NULL row, leaves an unparseable one NULL, and a second call fills none`(): Unit =
        runBlocking {
            val rootA = newRoot("A")
            val rootB = newRoot("B")
            val rootBad = newRoot("Bad")
            val rootDone = newRoot("Done")
            val a1 = seedNullCanonical(rootA, "project:\n  name: alpha\n")
            val a2 = seedNullCanonical(rootA, "project:\n  name: alpha2\n", Instant.parse("2026-03-01T10:00:01.000Z"))
            val b = seedNullCanonical(rootB, "z: 1\na: [2, 3]\n")
            val bad = seedNullCanonical(rootBad, "work_item_schemas: [\ninvalid yaml: :\n  - broken")
            pushOk(rootDone, base)
            val done = versionAt(rootDone, 1)

            val filled = service.canonicalizeMissing()

            assertEquals(3, filled, "alpha, alpha2 and b are parseable; the broken row and the canonical row are not counted")
            assertEquals("{\"project\":{\"name\":\"alpha\"}}", store.version(a1.id)?.canonicalJson)
            assertEquals("{\"project\":{\"name\":\"alpha2\"}}", store.version(a2.id)?.canonicalJson)
            assertEquals("{\"a\":[2,3],\"z\":1}", store.version(b.id)?.canonicalJson)
            assertEquals(1, store.version(a1.id)?.modelVersion)
            assertEquals(1, store.version(b.id)?.modelVersion)
            assertNull(store.version(bad.id)?.canonicalJson, "an unparseable row stays NULL")
            assertNull(store.version(bad.id)?.modelVersion)
            assertEquals(done, store.version(done.id), "an already canonical row is untouched")
            assertEquals(listOf(bad.id), store.versionsMissingCanonical().map { it.id })

            assertEquals(0, service.canonicalizeMissing(), "nothing left to fill")
        }

    @Test
    fun `S14 canonicalizeMissing fills past one page of 500 rows`(): Unit =
        runBlocking {
            val root = newRoot()
            val total = 505
            for (n in 1..total) seedNullCanonical(root, "n: $n\n", Instant.parse("2026-03-01T10:00:00.000Z").plusMillis(n.toLong()))
            assertEquals(total, store.versionsMissingCanonical(limit = 1000).size, "fixture: every row is missing its canonical form")

            val filled = service.canonicalizeMissing()

            assertEquals(total, filled)
            assertEquals(emptyList(), store.versionsMissingCanonical(limit = 1000))
            assertEquals("{\"n\":505}", store.versionAt(ConfigScope.ROOT, root, total)?.canonicalJson)
            assertEquals("{\"n\":1}", store.versionAt(ConfigScope.ROOT, root, 1)?.canonicalJson)
        }

    @Test
    fun `S14 canonicalizeMissing records no config event`(): Unit =
        runBlocking {
            val root = newRoot()
            seedNullCanonical(root, base)

            val (filled, rows) = written { service.canonicalizeMissing() }

            assertEquals(1, filled)
            assertEquals(emptyList(), rows.configEvents(), "filling a canonical form creates no new version")
        }

    @Test
    fun `S14 the config-canonicalize step is a ONCE step in the canonicalize phase and runs canonicalizeMissing`(): Unit =
        runBlocking {
            val root = newRoot()
            seedNullCanonical(root, base)
            seedNullCanonical(root, rev(2), Instant.parse("2026-03-01T10:00:01.000Z"))
            val step = ConfigCanonicalizeStep(service)

            assertEquals("config-canonicalize", step.name)
            assertEquals(DataStepPhase.CONFIG_CANONICALIZE, step.phase)
            assertEquals(DataStepKind.ONCE, step.kind)

            var rowsAffected = -1
            val outcome =
                sqlite.unitOfWork().write("ConfigServiceVersioningTest.step") {
                    rowsAffected = step.run(this)
                    Outcome.Ok(Unit)
                }

            assertIs<Outcome.Ok<Unit>>(outcome)
            assertEquals(2, rowsAffected)
            assertEquals(emptyList(), store.versionsMissingCanonical())
        }
}
