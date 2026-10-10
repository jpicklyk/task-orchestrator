package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.application.service.ConfigRollbackResult
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.pushConfig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f75eb22e / Q8) for [PerRootConfigService] over the versioned store:
 * scenarios S11 (the head poll and the immutable version cache), S12 (fail closed on a head without canonical form) and
 * the layer half of S8 (a reset head is absence).
 *
 * Oracles: task-scope section 1.7 (`layer(rootId)` steps 1-4: head read, RESET is null, version read once per versionId,
 * NULL or undecodable canonical JSON throws PerRootConfigInvalidException and is never absence; the read-fault policy is
 * unchanged), AC6, AC9, AC12. Fingerprints are derived through the store's pure `computeFingerprint`.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PerRootConfigVersionedTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val provider get() = sqlite.repositoryProvider()
    private val store: ConfigStore get() = provider.configStore()

    private fun yamlWith(noteKey: String) =
        "work_item_schemas:\n  bug-fix:\n    notes:\n      - key: $noteKey\n        role: queue\n        required: true\n"

    private suspend fun newRoot(title: String = "Root"): UUID =
        provider.workItemRepository().create(WorkItem(title = title, type = "project", depth = 0)).id

    private suspend fun push(
        root: UUID,
        yaml: String
    ) = pushConfig(provider, sqlite.unitOfWork(), root, yaml)

    private fun noteKeys(layer: io.github.jpicklyk.mcptask.current.application.config.ConfigLayer?): List<String>? =
        layer
            ?.document
            ?.workItemSchemas
            ?.get("bug-fix")
            ?.notes
            ?.map { it.key }

    /** A store that counts version(id) reads per version id and can fail head reads. */
    private class CountingStore(
        private val delegate: ConfigStore
    ) : ConfigStore by delegate {
        val versionReads = ConcurrentHashMap<UUID, AtomicInteger>()

        @Volatile
        var failHead = false

        override suspend fun version(id: UUID) =
            delegate.version(id).also {
                versionReads.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
            }

        override suspend fun head(
            scope: ConfigScope,
            rootId: UUID?
        ) = if (failHead) throw IllegalStateException("injected head read fault") else delegate.head(scope, rootId)
    }

    // ---------------------------------------------------------------------------------------------
    // S11 - head poll and immutable cache
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 layer returns the pushed document with the head fingerprint and PER_ROOT source`(): Unit =
        runBlocking {
            val root = newRoot()
            val yaml = yamlWith("spec-a")
            push(root, yaml)
            val service = PerRootConfigService(store)

            val layer = assertNotNull(service.layer(root))

            assertEquals(ConfigSource.PER_ROOT, layer.source)
            assertEquals(store.computeFingerprint(yaml), layer.fingerprint)
            assertEquals(listOf("spec-a"), noteKeys(layer))
            assertEquals(store.computeFingerprint(yaml), service.getFingerprint(root))
        }

    @Test
    fun `S11 a later push is visible on the very next read and the older version is not re-read`(): Unit =
        runBlocking {
            val root = newRoot()
            val counting = CountingStore(store)
            val service = PerRootConfigService(counting)
            push(root, yamlWith("one"))
            val firstId = assertNotNull(store.head(ConfigScope.ROOT, root)).versionId
            repeat(5) { assertEquals(listOf("one"), noteKeys(service.layer(root))) }

            push(root, yamlWith("two"))
            val secondId = assertNotNull(store.head(ConfigScope.ROOT, root)).versionId
            repeat(5) { assertEquals(listOf("two"), noteKeys(service.layer(root))) }

            assertNotEquals(firstId, secondId)
            assertEquals(1, counting.versionReads[firstId]?.get(), "version 1 row is read exactly once for five layer calls")
            assertEquals(1, counting.versionReads[secondId]?.get(), "version 2 row is read exactly once for five layer calls")
        }

    @Test
    fun `S11 alternating between two heads through rollback reads each version row once`(): Unit =
        runBlocking {
            val root = newRoot()
            val counting = CountingStore(store)
            val service = PerRootConfigService(counting)
            val configService = ConfigService(provider, YamlConfigDocumentParser, sqlite.unitOfWork())
            push(root, yamlWith("one"))
            push(root, yamlWith("two"))
            assertEquals(listOf("two"), noteKeys(service.layer(root)))

            val rolled = configService.rollback(root, 1)

            assertIs<ConfigRollbackResult.Success>(rolled)
            repeat(
                3
            ) { assertEquals(listOf("one"), noteKeys(service.layer(root)), "after a rollback the layer is the rolled-back document") }
            val headId = assertNotNull(store.head(ConfigScope.ROOT, root)).versionId
            assertEquals(1, counting.versionReads[headId]?.get(), "the rollback row is its own versionId and is read once")
            assertTrue(counting.versionReads.values.all { it.get() == 1 }, "no version id is ever read twice: ${counting.versionReads}")
        }

    @Test
    fun `S11 roots are cached independently`(): Unit =
        runBlocking {
            val a = newRoot("A")
            val b = newRoot("B")
            push(a, yamlWith("a-note"))
            push(b, yamlWith("b-note"))
            val service = PerRootConfigService(store)

            assertEquals(listOf("a-note"), noteKeys(service.layer(a)))
            assertEquals(listOf("b-note"), noteKeys(service.layer(b)))
            assertEquals(listOf("a-note"), noteKeys(service.layer(a)))
        }

    @Test
    fun `S11 a root without a head has no layer`(): Unit =
        runBlocking {
            val root = newRoot()
            val service = PerRootConfigService(store)

            assertNull(service.layer(root))
            assertNull(service.getSchemas(root))
            assertNull(service.getFingerprint(root))
        }

    // ---------------------------------------------------------------------------------------------
    // S8 (layer half) - a reset head is absence
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 a reset head makes layer and every facet absent, and a later push restores them`(): Unit =
        runBlocking {
            val root = newRoot()
            val configService = ConfigService(provider, YamlConfigDocumentParser, sqlite.unitOfWork())
            val service = PerRootConfigService(store)
            val yaml = yamlWith("spec")
            push(root, yaml)
            assertNotNull(service.layer(root), "control: the layer is present before the reset (and cached)")

            assertEquals(true, configService.reset(root).getOrNull())

            assertNull(service.layer(root), "a reset head is absence, so schema resolution falls through to the global layer")
            assertNull(service.getSchemas(root))
            assertNull(service.getFingerprint(root))
            assertNull(service.getNoteLimitsMode(root))
            assertNull(service.getStatusLabels(root))

            push(root, yaml)
            val back = assertNotNull(service.layer(root), "pushing the same content after the reset serves it again")
            assertEquals(listOf("spec"), noteKeys(back))
            assertEquals(store.computeFingerprint(yaml), back.fingerprint)
        }

    // ---------------------------------------------------------------------------------------------
    // S12 - fail closed
    // ---------------------------------------------------------------------------------------------

    private fun updateCanonical(sql: String) {
        DriverManager.getConnection(sqlite.jdbcUrl).use { c -> c.createStatement().use { it.executeUpdate(sql) } }
    }

    @Test
    fun `S12 a head whose canonical JSON is NULL throws PerRootConfigInvalidException naming root and version`(): Unit =
        runBlocking {
            val root = newRoot()
            push(root, yamlWith("one"))
            push(root, yamlWith("two"))
            updateCanonical("UPDATE config_versions SET canonical_json = NULL, model_version = NULL WHERE version = 2")

            val e = assertFailsWith<PerRootConfigInvalidException> { PerRootConfigService(store).layer(root) }

            assertEquals(root, e.rootId)
            assertEquals(2, e.version)
            assertTrue(
                (e as Throwable) !is PerRootConfigUnavailableException,
                "invalid config is a distinct failure from an unavailable read"
            )
        }

    @Test
    fun `S12 valid YAML text with a NULL canonical form still fails closed instead of being re-parsed or ignored`(): Unit =
        runBlocking {
            val root = newRoot()
            push(root, yamlWith("would-be-served"))
            updateCanonical("UPDATE config_versions SET canonical_json = NULL, model_version = NULL")
            val service = PerRootConfigService(store)

            assertFailsWith<PerRootConfigInvalidException> { service.layer(root) }
            assertFailsWith<PerRootConfigInvalidException> { service.getSchemas(root) }
            assertFailsWith<PerRootConfigInvalidException> { service.getFingerprint(root) }
        }

    @Test
    fun `S12 an undecodable canonical JSON fails closed`(): Unit =
        runBlocking {
            val root = newRoot()
            push(root, yamlWith("one"))
            updateCanonical("UPDATE config_versions SET canonical_json = 'not json {'")

            assertFailsWith<PerRootConfigInvalidException> { PerRootConfigService(store).layer(root) }
        }

    @Test
    fun `S12 only the broken root fails, a healthy root is still served`(): Unit =
        runBlocking {
            val good = newRoot("Good")
            val broken = newRoot("Broken")
            push(good, yamlWith("good-note"))
            push(broken, yamlWith("broken-note"))
            val brokenHead = assertNotNull(store.head(ConfigScope.ROOT, broken))
            val brokenHex =
                brokenHead.versionId
                    .toString()
                    .replace("-", "")
                    .uppercase()
            updateCanonical("UPDATE config_versions SET canonical_json = NULL, model_version = NULL WHERE hex(id) = '$brokenHex'")
            val service = PerRootConfigService(store)

            assertFailsWith<PerRootConfigInvalidException> { service.layer(broken) }
            assertEquals(listOf("good-note"), noteKeys(service.layer(good)))
        }

    @Test
    fun `S12 a head read fault outside a unit serves the last known layer, inside a unit it throws Unavailable`(): Unit =
        runBlocking {
            val root = newRoot()
            push(root, yamlWith("lkg"))
            val counting = CountingStore(store)
            val service = PerRootConfigService(counting)
            val warm = assertNotNull(service.layer(root))
            counting.failHead = true

            val served = assertNotNull(service.layer(root), "outside a unit the last head and document are served")
            assertEquals(warm.fingerprint, served.fingerprint)
            assertEquals(listOf("lkg"), noteKeys(served))

            val e = assertFailsWith<PerRootConfigUnavailableException> { sqlite.unitOfWork().read { service.layer(root) } }
            assertEquals(root, e.rootId)
        }

    @Test
    fun `S12 a cold head read fault with nothing cached throws Unavailable outside a unit`(): Unit =
        runBlocking {
            val root = newRoot()
            push(root, yamlWith("never-read"))
            val counting = CountingStore(store)
            counting.failHead = true
            val service = PerRootConfigService(counting)

            val e = assertFailsWith<PerRootConfigUnavailableException> { service.layer(root) }

            assertEquals(root, e.rootId)
        }

    @Test
    fun `S12 control - a healthy read inside a unit is served`(): Unit =
        runBlocking {
            val root = newRoot()
            push(root, yamlWith("in-unit"))
            val service = PerRootConfigService(store)

            val layer = sqlite.unitOfWork().read { service.layer(root) }

            assertEquals(listOf("in-unit"), noteKeys(layer))
        }
}
