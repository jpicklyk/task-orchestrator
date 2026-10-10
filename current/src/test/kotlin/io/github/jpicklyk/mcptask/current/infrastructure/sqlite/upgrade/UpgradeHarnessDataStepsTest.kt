package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStep
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepPhase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests for item 6b998895 (Q2a): the upgrade-harness data-step hook.
 *
 * Oracle: task-scope AC9 (`runStep` runs the production data steps after the migration and before `checkUpgrade`;
 * `checkUpgrade` adds a failure for every registered ONCE step whose name is not in `data_steps`; with zero production
 * steps the hook is vacuous by construction, so the stand-in-step scenarios prove it CAN fail: test-plan S12, S13) and
 * test-plan S15 (golden V17 database: migrate, run the production data steps, `data_steps` present, nothing lost).
 *
 * The negative scenarios register a stand-in ONCE step that has no row: that fixture is what makes the failure
 * assertion fail if the hook were removed. Their controls (a row present, an EVERY_BOOT step, no steps) show the hook
 * does not fail for a healthy database.
 */
class UpgradeHarnessDataStepsTest {
    @TempDir
    lateinit var dir: File

    private val seeds = MigrationSeed.discover()

    private class Stand(
        override val name: String,
        override val kind: DataStepKind = DataStepKind.ONCE,
        private val rows: Int = 0
    ) : DataStep {
        override val phase: DataStepPhase = DataStepPhase.ITEM_BACKFILL

        override suspend fun run(scope: WriteScope): Int = rows
    }

    /** A database migrated to latest with the baseline dataset, plus the dump the invariants are checked against. */
    private class Fixture(
        val url: String,
        val before: UpgradeHarness.Dump
    )

    private fun migratedBaseline(name: String): Fixture {
        val url = UpgradeHarness.urlFor(File(dir, "$name.db"))
        UpgradeHarness.migrate(url)
        DriverManager.getConnection(url).use { BaselineDataset.seed(it) }
        return Fixture(url, DriverManager.getConnection(url).use { UpgradeHarness.dump(it) })
    }

    private fun failuresFor(
        fixture: Fixture,
        registered: List<DataStep>
    ): List<String> =
        DriverManager.getConnection(fixture.url).use { conn ->
            UpgradeHarness.checkUpgrade(conn, fixture.before, emptyList(), null, null, registered)
        }

    private fun insertRow(
        conn: Connection,
        name: String
    ) {
        conn.createStatement().use {
            it.executeUpdate(
                "INSERT INTO data_steps (name, applied_at, rows_affected, binary_version) VALUES ('$name', '2026-03-01 10:15:30.123', 0, 't')"
            )
        }
    }

    // ---- S12: the real chain passes with the production step set ----

    @Test
    fun `S12 the harness step for the data_steps migration passes with the production step set`() {
        val result = UpgradeHarness.runStep(23, dir, seeds)
        assertTrue(result.failures.isEmpty(), "step 23 failures:\n" + result.failures.joinToString("\n"))
    }

    // ---- S13: the hook is not vacuous ----

    @Test
    fun `S13 control - no registered steps and a clean database report no failure`() {
        val failures = failuresFor(migratedBaseline("control-empty"), emptyList())
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `S13 a registered ONCE step without a data_steps row is a failure naming it`() {
        val failures = failuresFor(migratedBaseline("missing-row"), listOf(Stand("missing-once")))

        assertTrue(failures.any { "missing-once" in it }, "the unapplied ONCE step must be reported by name: $failures")
    }

    @Test
    fun `S13 every unapplied ONCE step is named, and the ones with a row are not`() {
        val fixture = migratedBaseline("two-missing")
        DriverManager.getConnection(fixture.url).use { insertRow(it, "has-a-row") }

        val failures = failuresFor(fixture, listOf(Stand("first-missing"), Stand("has-a-row"), Stand("second-missing")))

        assertTrue(failures.any { "first-missing" in it }, "first-missing must be named: $failures")
        assertTrue(failures.any { "second-missing" in it }, "second-missing must be named: $failures")
        assertTrue(failures.none { "has-a-row" in it }, "a step with a row must not be reported: $failures")
    }

    @Test
    fun `S13 control - a registered ONCE step with its row is not a failure`() {
        val fixture = migratedBaseline("row-present")
        DriverManager.getConnection(fixture.url).use { insertRow(it, "applied-once") }

        assertEquals(emptyList(), failuresFor(fixture, listOf(Stand("applied-once"))))
    }

    @Test
    fun `S13 an EVERY_BOOT step is never required to have a row`() {
        val failures = failuresFor(migratedBaseline("every-boot"), listOf(Stand("boot-only", DataStepKind.EVERY_BOOT)))

        assertEquals(emptyList(), failures)
    }

    // ---- S15: the golden V17 database through the production data-step composition ----

    @Test
    fun `S15 the golden V17 database upgrades, runs the production data steps and loses nothing`() {
        val golden = GoldenV17.copyTo(File(dir, "golden.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        val before = DriverManager.getConnection(url).use { UpgradeHarness.dump(it) }

        val probe = UpgradeHarness.migrate(url)
        val (report, plan) = UpgradeHarness.runProductionDataStepsWithPlan(url, File(dir, "golden-run").also { it.mkdirs() })

        val onceNames = plan.filter { it.kind == DataStepKind.ONCE }.map { it.name }
        assertEquals(onceNames, report.applied, "every registered ONCE step is applied on its first run")
        assertEquals(emptyList(), report.skipped)
        DriverManager.getConnection(url).use { conn ->
            val ledger =
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT name FROM data_steps ORDER BY name").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            assertEquals(onceNames.sorted(), ledger, "the data_steps table exists on the upgraded golden and holds exactly the ONCE steps")
            val failures = UpgradeHarness.checkUpgrade(conn, before, seeds.filter { it.version > 17 }, probe, report, plan)
            assertTrue(failures.isEmpty(), "golden upgrade failures:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun `S15 a stand-in step registered through the composition is applied once on the golden and recorded`() {
        val golden = GoldenV17.copyTo(File(dir, "golden-extra.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        UpgradeHarness.migrate(url)
        val run1 = File(dir, "extra-run-1").also { it.mkdirs() }
        val run2 = File(dir, "extra-run-2").also { it.mkdirs() }

        val (first, plan) = UpgradeHarness.runProductionDataStepsWithPlan(url, run1, listOf(Stand("golden-extra", rows = 3)))
        val (second, _) = UpgradeHarness.runProductionDataStepsWithPlan(url, run2, listOf(Stand("golden-extra", rows = 3)))

        assertTrue("golden-extra" in plan.map { it.name }, "the extra step is part of the composed plan")
        assertTrue("golden-extra" in first.applied, "first run applies it: $first")
        assertTrue("golden-extra" in second.skipped, "second run skips it: $second")
        assertTrue("golden-extra" !in second.applied)
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT rows_affected, binary_version FROM data_steps WHERE name = 'golden-extra'").use { rs ->
                    assertTrue(rs.next(), "the stand-in step must have a data_steps row")
                    assertEquals(3, rs.getInt(1))
                    assertTrue(rs.getString(2).isNotBlank(), "binary_version is recorded")
                }
            }
        }
    }
}
