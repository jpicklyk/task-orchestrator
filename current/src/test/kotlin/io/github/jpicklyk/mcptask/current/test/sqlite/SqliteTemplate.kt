package io.github.jpicklyk.mcptask.current.test.sqlite

import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one Flyway-migrated SQLite template per JVM. Built lazily by the production schema chain
 * ([FlywayDatabaseSchemaManager.updateSchema]: migrate plus startup integrity, but not the
 * startup compaction that `DatabaseManager.updateSchema` adds), then flattened with `VACUUM INTO`
 * into a single self-contained file that [SqliteTestDatabase] copies per scope.
 *
 * The Gradle test task runs one fork, so "once per JVM" is "once per test run".
 */
internal object SqliteTemplate {
    /** How many times the template was built in this JVM; the fixture test asserts it stays at 1. */
    val buildCount = AtomicInteger(0)

    private val templateFile: File by lazy { build() }

    fun file(): File = templateFile

    private fun build(): File {
        buildCount.incrementAndGet()
        val dir = Files.createTempDirectory("to-sqlite-template-")
        Runtime.getRuntime().addShutdownHook(Thread { deleteQuietly(dir) })

        val migrated = dir.resolve("migrated.db").toFile()
        val url = "jdbc:sqlite:" + migrated.absolutePath.replace(File.separatorChar, '/')
        check(FlywayDatabaseSchemaManager(url).updateSchema()) {
            "SqliteTemplate: the production Flyway chain failed to build the test template at $url"
        }

        val template = dir.resolve("template.db").toFile()
        val target = template.absolutePath.replace(File.separatorChar, '/').replace("'", "''")
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { it.execute("VACUUM INTO '$target'") }
        }
        return template
    }

    private fun deleteQuietly(dir: Path) {
        runCatching {
            Files
                .walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
