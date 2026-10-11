package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.test.sqlite.ClasspathScan
import java.sql.Connection

/**
 * Per-migration additions to the upgrade harness. A migration item adds ONE new file under
 * `infrastructure/sqlite/upgrade/seeds/` containing a Kotlin `object` that extends this class
 * (for example `object SeedV18 : MigrationSeed(18)`). Seeds are discovered by scanning that package:
 * there is no registry, list or ServiceLoader file, so concurrent migration items never edit a shared file.
 *
 * Contract:
 * - [version] is the Flyway version of the migration the seed belongs to; it must name an existing migration.
 * - The harness fails when any migration of version 18 or later has no seed, even when the seed does nothing.
 *   Migrations up to 17 are covered by [BaselineDataset] alone unless they rewrite baseline data.
 * - [seed] runs on a raw JDBC connection at version-1, after [BaselineDataset.seed] and before the migration.
 *   Insert only rows the migration is meant to transform, using [BaselineDataset.insert] so columns that do
 *   not exist at version-1 are skipped.
 * - [expected] is the EXPECTED-TRANSFORM contract. For a pre-existing row (a dump taken before the migration chain
 *   runs) it returns the column values this migration changes, as a column-to-new-value map; an empty map means
 *   "unchanged". The harness applies it only to steps whose chain includes this migration (steps N <= [version]),
 *   in version order after the earlier seeds' transforms, and then compares EVERY common column exactly. There is no
 *   column-wide exclusion: a step below this migration still verifies every column it does not transform, and the
 *   transformed columns are verified against the exact expected value rather than skipped. Return overrides only
 *   for columns present in the row (guard with `"col" in row`): earlier steps dump older schemas.
 * - [expectedAfterDataSteps] is the same contract for a check that ALSO ran the production data steps after the chain
 *   (a [UpgradeHarness.checkUpgrade] call given a data-step report or registered steps). It defaults to [expected];
 *   a seed whose rows a data step (not the migration) rewrites keeps [expected] for the bare chain and declares the
 *   step's effect here.
 * - [verify] runs after the whole chain has been migrated to the latest version, on the migration's own step only,
 *   and asserts the transformation took effect.
 * - [relocated] is the DROPPED-TABLE contract. When a table present before the chain is missing after it, the harness
 *   asks the applicable seeds (in version order) where each of its rows went; the first non-null [Relocated] names the
 *   new table, the row's `id` key there (lowercase hex for a BLOB id, as the dump keys it) and the exact expected values
 *   of the columns to compare. The row is passed AFTER every seed's [expected] transform. A missing table whose rows no
 *   seed relocates is still reported as "table X disappeared".
 */
abstract class MigrationSeed(
    val version: Int
) {
    open fun seed(conn: Connection) {}

    /** The expected post-migration values of the columns this migration rewrites for [row] of [table]. */
    open fun expected(
        table: String,
        row: Map<String, Any?>
    ): Map<String, Any?> = emptyMap()

    /** Like [expected], for a check that ran the production data steps after the chain; defaults to [expected]. */
    open fun expectedAfterDataSteps(
        table: String,
        row: Map<String, Any?>
    ): Map<String, Any?> = expected(table, row)

    open fun verify(conn: Connection) {}

    /** Where [row] of [table] lives after this migration dropped [table], or null when this seed does not relocate it. */
    open fun relocated(
        table: String,
        row: Map<String, Any?>
    ): Relocated? = null

    /**
     * A relocated row: it now lives in [table] under the dump key [id], and every column in [expected] must hold exactly
     * that value there (columns the new table lacks are skipped, as for an in-place row).
     */
    data class Relocated(
        val table: String,
        val id: String,
        val expected: Map<String, Any?>
    )

    companion object {
        val SEEDS_PACKAGE: String = MigrationSeed::class.java.packageName + ".seeds"

        /** First migration version that REQUIRES a seed; earlier ones are covered by the baseline dataset. */
        const val FIRST_SEED_REQUIRED = 18

        /** Every seed object found in the seeds package, ordered by version. */
        fun discover(): List<MigrationSeed> =
            ClasspathScan
                .classesUnder(MigrationSeed::class.java, SEEDS_PACKAGE.replace('.', '/') + "/")
                .filter { MigrationSeed::class.java.isAssignableFrom(it) }
                .mapNotNull { runCatching { it.getField("INSTANCE").get(null) as? MigrationSeed }.getOrNull() }
                .sortedBy { it.version }

        /** Problems with seed coverage: a missing seed for version >= 18, a seed for a missing migration, or duplicates. */
        fun coverageProblems(
            migrationVersions: List<Int>,
            seeds: List<MigrationSeed>
        ): List<String> {
            val problems = mutableListOf<String>()
            val seedVersions = seeds.map { it.version }
            migrationVersions.filter { it >= FIRST_SEED_REQUIRED && it !in seedVersions }.forEach {
                problems += "migration V$it has no MigrationSeed (add upgrade/seeds/SeedV$it.kt)"
            }
            seeds.filter { it.version !in migrationVersions }.forEach {
                problems += "MigrationSeed ${it::class.simpleName} names version ${it.version}, which has no migration"
            }
            seedVersions.groupBy { it }.filter { it.value.size > 1 }.keys.forEach {
                problems += "more than one MigrationSeed for version $it"
            }
            return problems
        }
    }
}
