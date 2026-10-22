package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 9e5707b3 / Q31b) for the `pin-backfill` data step over a base schema whose
 * parsed lifecycle is `none`: scenario S16 of the frozen test-plan and its control.
 *
 * Oracles (frozen task-scope Build 11 and the V26 relation CHECK `(role IS NULL) = (lifecycle = 'none')`): every backfilled row
 * is an existing, role-bearing row, so the backfill must never write lifecycle `none` for it (a row with a role and lifecycle
 * `none` violates the CHECK and the boot would fail); a row whose LEGACY base schema is lifecycle-free is pinned `auto`
 * instead, with the same schema key and layer it would have got, and its role is kept. The step still applies, still pins
 * every row, and no row ends with a NULL role or a `none` lifecycle. The pin key and layer follow the existing LEGACY
 * resolution documented for the step (per-root default shadows a server schema of the exact type; an exact per-root type
 * wins; with no per-root layer the server schema decides). The control runs the same fixtures with `manual`, which is copied
 * unchanged, so the `auto` above can only come from the none mapping.
 *
 * Fixtures are the checked-in golden V17 database copies used by the sibling backfill tests; the expected key and layer
 * values are the ones those tests state for the same YAML shapes.
 */
class PinBackfillNoneTest {
    @TempDir
    lateinit var dir: File

    private val stepName = "pin-backfill"

    /** The golden root's document: no `feature-task`, a lifecycle-free `default`. LEGACY gives every golden row the root default. */
    private val rootNoneDefault =
        "work_item_schemas:\n" +
            "  task-a:\n" +
            "    lifecycle: manual\n" +
            "    notes: []\n" +
            "  default:\n" +
            "    lifecycle: none\n" +
            "    notes: []\n"

    /** Same shape with `manual` in place of `none`, for the control. */
    private val rootManualDefault = rootNoneDefault.replace("lifecycle: none", "lifecycle: manual")

    /** The golden root's document declares `feature-task` itself as lifecycle-free. */
    private val rootNoneExact =
        "work_item_schemas:\n" +
            "  feature-task:\n" +
            "    lifecycle: none\n" +
            "    notes: []\n" +
            "  default:\n" +
            "    lifecycle: manual\n" +
            "    notes: []\n"

    /** A server file whose `feature-task` is lifecycle-free. */
    private val serverNone =
        "work_item_schemas:\n" +
            "  feature-task:\n" +
            "    lifecycle: none\n" +
            "    notes: []\n"

    private val serverManual = serverNone.replace("lifecycle: none", "lifecycle: manual")

    private val goldenNames = BaselineDataset.ITEM_NAMES

    private fun rolesOf(url: String): Map<UUID?, String?> =
        sqlRows(url, "SELECT id, role FROM work_items") {
            uuidOf(it.getBytes(1)) to
                it.getString(2)
        }.toMap()

    private fun assertEveryRowPinned(
        url: String,
        key: String,
        layer: String,
        lifecycle: String
    ) {
        val server = serverHeadId(url)
        val rootHead = rootHeadId(url, "root")
        for (name in goldenNames) {
            val row = pinRow(url, name)
            assertEquals(key, row.key, "pinned_schema_key of $name")
            assertEquals(layer, row.layer, "pinned_schema_layer of $name")
            assertEquals(lifecycle, row.lifecycle, "lifecycle of $name")
            assertEquals(server, row.serverCfg, "pinned_server_cfg of $name")
            if (layer == "root") assertEquals(rootHead, row.rootCfg, "pinned_root_cfg of $name")
            assertNotNull(row.pinnedAt, "pinned_at of $name")
        }
        assertEquals(0, scalarInt(url, "SELECT COUNT(*) FROM work_items WHERE pinned_at IS NULL"), "every row is pinned")
    }

    private fun assertNoLifecycleFreeRow(url: String) {
        assertEquals(0, scalarInt(url, "SELECT COUNT(*) FROM work_items WHERE lifecycle = 'none'"), "no row carries lifecycle none")
        assertEquals(0, scalarInt(url, "SELECT COUNT(*) FROM work_items WHERE role IS NULL"), "no row lost its role")
    }

    @Test
    fun `S16 a per-root default that is lifecycle-free pins the in-flight rows auto and keeps their roles`() {
        val url = migratedGolden(dir, "none-default", rootNoneDefault)
        val rolesBefore = rolesOf(url)
        assertTrue(rolesBefore.values.toSet().size > 1, "fixture: the golden rows are in several roles, not all queue: $rolesBefore")

        val (_, report, _) = bootProductionSteps(url, dir, "none-default", PIN_SERVER_YAML)

        assertTrue(stepName in report.applied, "the boot succeeds and applies the step: $report")
        assertEveryRowPinned(url, "default", "root", "auto")
        assertNoLifecycleFreeRow(url)
        assertEquals(rolesBefore, rolesOf(url), "no role changed")
    }

    @Test
    fun `S16 control - the same fixture with a manual default is pinned manual, so the auto above comes from the none mapping`() {
        val url = migratedGolden(dir, "manual-default", rootManualDefault)

        val (_, report, _) = bootProductionSteps(url, dir, "manual-default", PIN_SERVER_YAML)

        assertTrue(stepName in report.applied, "$report")
        assertEveryRowPinned(url, "default", "root", "manual")
    }

    @Test
    fun `S16 a per-root document that declares the exact type lifecycle-free pins it auto under that key`() {
        val url = migratedGolden(dir, "none-exact", rootNoneExact)
        val rolesBefore = rolesOf(url)

        val (_, report, _) = bootProductionSteps(url, dir, "none-exact", PIN_SERVER_YAML)

        assertTrue(stepName in report.applied, "$report")
        assertEveryRowPinned(url, "feature-task", "root", "auto")
        assertNoLifecycleFreeRow(url)
        assertEquals(rolesBefore, rolesOf(url), "no role changed")
    }

    @Test
    fun `S16 a server schema that is lifecycle-free pins auto with the server layer, and manual stays manual`() {
        val noneUrl = migratedGolden(dir, "server-none", null)
        val manualUrl = migratedGolden(dir, "server-manual", null)
        val rolesBefore = rolesOf(noneUrl)

        val (_, noneReport, _) = bootProductionSteps(noneUrl, dir, "server-none", serverNone)
        val (_, manualReport, _) = bootProductionSteps(manualUrl, dir, "server-manual", serverManual)

        assertTrue(stepName in noneReport.applied, "$noneReport")
        assertTrue(stepName in manualReport.applied, "$manualReport")
        assertEveryRowPinned(noneUrl, "feature-task", "server", "auto")
        assertNoLifecycleFreeRow(noneUrl)
        assertEquals(rolesBefore, rolesOf(noneUrl), "no role changed")
        assertEveryRowPinned(manualUrl, "feature-task", "server", "manual")
    }

    @Test
    fun `S16 a second boot after a lifecycle-free backfill changes nothing`() {
        val url = migratedGolden(dir, "none-second", rootNoneDefault)
        bootProductionSteps(url, dir, "none-second", PIN_SERVER_YAML)
        val rolesAfterFirst = rolesOf(url)
        val pinsAfterFirst = goldenNames.associateWith { pinRow(url, it) }

        val (_, second, _) = bootProductionSteps(url, dir, "none-second", PIN_SERVER_YAML)

        assertTrue(stepName in second.skipped, "the step is skipped on the second boot: $second")
        assertEquals(rolesAfterFirst, rolesOf(url))
        assertEquals(pinsAfterFirst, goldenNames.associateWith { pinRow(url, it) })
    }
}
