package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management

import java.io.File

/**
 * The real migration chain, read from the migration folder, so tests never hard-code its length or
 * version list (every migration-adding item would otherwise have to edit them).
 */
object MigrationChain {
    private val FILE = Regex("^V([0-9]+)__.+[.]sql$")

    /** Migration folder relative to the `current` module directory (Gradle's test working dir). */
    val folder: File = File("src/main/resources/db/migration/sqlite")

    /** Every migration file name in the folder, sorted. */
    fun fileNames(): List<String> = folder.list()!!.sorted()

    /** Migration versions in ascending order. */
    fun versions(): List<Int> =
        fileNames()
            .map {
                FILE
                    .matchEntire(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: error("unexpected file $it")
            }.sorted()

    /** Highest migration version. */
    fun max(): Int = versions().last()

    /** Number of migrations (equals [max] while the chain is contiguous). */
    fun count(): Int = versions().size
}
