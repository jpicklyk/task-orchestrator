package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.streams.toList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f75eb22e / Q8) that run the checked-in golden 3.16 database through the
 * versioned-config upgrade: S14 (the golden copy plus the production data steps: `config-canonicalize` applies once,
 * fills the canonical form, and a second boot skips it), S17 / AC1 / AC12 (the migrated root keeps its id, yaml,
 * fingerprint and timestamp, its head and its known fingerprints, and its per-root layer fingerprint does not move) and
 * AC11 (no `project_config.*` event vocabulary is left in the production source).
 *
 * Oracle: task-scope AC1, AC10-AC12 and section 1.7 ("ConfigLayer.fingerprint for a migrated root equals the pre-upgrade
 * project_config.fingerprint"); the golden's own contents are the literals of BaselineDataset (a root "root" whose
 * project_config row has yaml `project:\n  name: synthetic-baseline\n`, fingerprint `baseline-fingerprint-0001`, history
 * `["baseline-fingerprint-0000"]`, updated_at `2026-03-01 10:15:30.123`). Those literals are read from the golden before the
 * upgrade, not hard-coded in the assertions except where stated.
 */
class ConfigVersionsGoldenTest {
    @TempDir
    lateinit var dir: File

    private val rootName = "root"

    private fun scalarString(
        conn: Connection,
        sql: String
    ): String? =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private data class OldRow(
        val id: String,
        val yaml: String,
        val fingerprint: String,
        val updatedAt: String,
        val history: String?
    )

    private fun oldProjectConfig(url: String): OldRow =
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT hex(id), config_yaml, fingerprint, updated_at, fingerprint_history FROM project_config").use { rs ->
                    check(rs.next()) { "the golden database must carry one project_config row" }
                    OldRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))
                }
            }
        }

    private fun upgradedGolden(name: String): String {
        val golden = GoldenV17.copyTo(File(dir, "$name.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        UpgradeHarness.migrate(url)
        return url
    }

    // ---------------------------------------------------------------------------------------------
    // S14 - the data step on the golden database
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S14 golden - the migration leaves canonical JSON NULL and config-canonicalize fills it once`() {
        val golden = GoldenV17.copyTo(File(dir, "s14.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        val old = oldProjectConfig(url)
        UpgradeHarness.migrate(url)

        DriverManager.getConnection(url).use { conn ->
            assertNull(
                scalarString(conn, "SELECT canonical_json FROM config_versions"),
                "the SQL migration alone never fills the canonical form"
            )
            assertNull(scalarString(conn, "SELECT model_version FROM config_versions"))
        }

        val (first, plan) = UpgradeHarness.runProductionDataStepsWithPlan(url, File(dir, "run-1").also { it.mkdirs() })

        assertTrue("config-canonicalize" in plan.map { it.name }, "the production composition registers the step")
        assertTrue("config-canonicalize" in first.applied, "first boot applies it: $first")
        DriverManager.getConnection(url).use { conn ->
            assertEquals(
                "{\"project\":{\"name\":\"synthetic-baseline\"}}",
                scalarString(conn, "SELECT canonical_json FROM config_versions")
            )
            assertEquals("1", scalarString(conn, "SELECT model_version FROM config_versions"))
            assertEquals(
                "1",
                scalarString(conn, "SELECT rows_affected FROM data_steps WHERE name = 'config-canonicalize'"),
                "rows_affected is the filled-row count"
            )
            assertEquals(old.yaml, scalarString(conn, "SELECT yaml FROM config_versions"), "the step does not touch the stored document")
        }

        val (second, _) = UpgradeHarness.runProductionDataStepsWithPlan(url, File(dir, "run-2").also { it.mkdirs() })

        assertTrue("config-canonicalize" in second.skipped, "second boot skips the ONCE step: $second")
        assertTrue("config-canonicalize" !in second.applied, "second boot does not re-apply it: $second")
    }

    // ---------------------------------------------------------------------------------------------
    // S17 / AC1 / AC12 - the migrated root
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S17 golden - the migrated root is version 1 with the old id yaml fingerprint and timestamp, and a head`() {
        val golden = GoldenV17.copyTo(File(dir, "ac1.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        val old = oldProjectConfig(url)
        UpgradeHarness.migrate(url)
        UpgradeHarness.runProductionDataSteps(url, File(dir, "run-ac1").also { it.mkdirs() })

        withProvider(url) { provider ->
            val root = BaselineDataset.uuid(rootName)
            val store = provider.configStore()

            val v1 = assertNotNull(store.versionAt(ConfigScope.ROOT, root, 1))
            assertEquals(old.id.lowercase(), BaselineDataset.bytes(v1.id).joinToString("") { "%02x".format(it) })
            assertEquals(BaselineDataset.uuid("project-config"), v1.id)
            assertEquals(old.yaml, v1.yaml)
            assertEquals(old.fingerprint, v1.fingerprint)
            assertEquals(Instant.parse(old.updatedAt.replace(' ', 'T') + "Z"), v1.createdAt, "created_at is the old updated_at")
            assertEquals(ConfigVersionSource.MIGRATION, v1.source)
            assertNull(v1.principalId)
            assertNull(v1.reqId)
            assertEquals(emptyList(), v1.warnings)

            val head = assertNotNull(store.head(ConfigScope.ROOT, root))
            assertEquals(v1.id, head.versionId)
            assertEquals(1, head.version)
            assertEquals(old.fingerprint, head.fingerprint)

            assertEquals(FingerprintRelation.CURRENT, store.classifyFingerprint(root, old.fingerprint))
            assertEquals(
                FingerprintRelation.SUPERSEDED,
                store.classifyFingerprint(root, "baseline-fingerprint-0000"),
                "the V11 history entry became a known fingerprint"
            )
            assertEquals(FingerprintRelation.UNKNOWN, store.classifyFingerprint(root, "never-seen-fingerprint"))
            assertEquals(1, store.history(ConfigScope.ROOT, root, 50).size)
        }
    }

    @Test
    fun `AC12 golden - the per-root layer fingerprint after the upgrade equals the pre-upgrade project_config fingerprint`() {
        val golden = GoldenV17.copyTo(File(dir, "ac12.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        val before = oldProjectConfig(url).fingerprint
        UpgradeHarness.migrate(url)
        UpgradeHarness.runProductionDataSteps(url, File(dir, "run-ac12").also { it.mkdirs() })

        withProvider(url) { provider ->
            val root = BaselineDataset.uuid(rootName)
            val service = PerRootConfigService(provider.configStore())

            val layer = assertNotNull(service.layer(root))

            assertEquals(
                before,
                layer.fingerprint,
                "query_items(schema).configFingerprint is built from this value and must not move at upgrade"
            )
            assertEquals(ConfigSource.PER_ROOT, layer.source)
            assertEquals(before, service.getFingerprint(root))
        }
    }

    @Test
    fun `AC9 golden - a migrated root whose canonical form was never filled fails closed instead of falling through`() {
        val url = upgradedGolden("ac9")

        withProvider(url) { provider ->
            val root = BaselineDataset.uuid(rootName)
            val service = PerRootConfigService(provider.configStore())

            val invalid = assertFailsWith<PerRootConfigInvalidException> { service.layer(root) }

            assertEquals(root, invalid.rootId)
            assertEquals(1, invalid.version)
        }
    }

    private fun withProvider(
        url: String,
        block: suspend (DefaultRepositoryProvider) -> Unit
    ) {
        val manager = DatabaseManager(appConfig = AppConfig.fromEnv { null })
        try {
            assertTrue(manager.initialize(url), "initialize must accept the upgraded golden database")
            runBlocking { block(DefaultRepositoryProvider(manager)) }
        } finally {
            manager.shutdown()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // AC11 - the old event vocabulary is gone
    // ---------------------------------------------------------------------------------------------

    private fun mainSourceRoot(): Path =
        listOf(Paths.get("src/main/kotlin"), Paths.get("current/src/main/kotlin"))
            .firstOrNull { Files.isDirectory(it) }
            ?: error("production source root not found from ${Paths.get("").toAbsolutePath()}")

    @Test
    fun `AC11 no production source mentions the retired project_config event vocabulary`() {
        val root = mainSourceRoot()
        val files = Files.walk(root).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
        assertTrue(files.size > 100, "vacuity control: the scan really covers the production tree (${files.size} files)")
        assertTrue(files.any { it.fileName.toString() == "DomainEvent.kt" }, "vacuity control: the event definitions file is scanned")
        assertTrue(
            files.any { f -> Files.readAllLines(f, Charsets.UTF_8).any { it.contains("config.version_created") } },
            "vacuity control: the replacement event type is defined somewhere in the scanned tree"
        )

        val retired =
            Regex(
                "project_config\\.(upserted|deleted)|\\b(ProjectConfigUpserted|ProjectConfigDeleted|KIND_PROJECT_CONFIG|PROJECT_CONFIG_UPSERTED|PROJECT_CONFIG_DELETED)\\b"
            )
        val hits =
            files.flatMap { file ->
                Files.readAllLines(file, Charsets.UTF_8).withIndex().mapNotNull { (i, line) ->
                    retired.find(line)?.let { "${root.relativize(file)}:${i + 1}: ${it.value}" }
                }
            }

        assertEquals(emptyList(), hits, "retired project_config event vocabulary still present in production source")
    }
}
