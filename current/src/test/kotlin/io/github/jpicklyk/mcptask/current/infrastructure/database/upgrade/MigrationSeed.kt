package io.github.jpicklyk.mcptask.current.infrastructure.database.upgrade

import io.github.jpicklyk.mcptask.current.test.sqlite.ClasspathScan
import java.sql.Connection

/**
 * Per-migration additions to the upgrade harness. A migration item adds ONE new file under
 * `infrastructure/database/upgrade/seeds/` containing a Kotlin `object` that extends this class
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
 * - [rewrites] lists `table.column` pairs the migration (or any later one) is DECLARED to change. Every other
 *   column present both before and after must keep its value for every pre-existing row.
 * - [verify] runs after the whole chain has been migrated to the latest version, on the migration's own step only,
 *   and asserts the transformation took effect.
 */
abstract class MigrationSeed(
    val version: Int
) {
    open fun seed(conn: Connection) {}

    open val rewrites: Set<String> = emptySet()

    open fun verify(conn: Connection) {}

    companion object {
        const val SEEDS_PACKAGE = "io.github.jpicklyk.mcptask.current.infrastructure.database.upgrade.seeds"

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
