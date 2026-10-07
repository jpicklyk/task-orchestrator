package io.github.jpicklyk.mcptask.current.test.sqlite

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A real, file-backed SQLite database for tests: a copy of the once-per-JVM Flyway template
 * ([SqliteTemplate]) opened through the production [DatabaseManager], so the production PRAGMAs
 * (WAL, foreign_keys=ON, busy_timeout) and transaction mode apply.
 *
 * Three ways to use it:
 * ```
 * // 1. Explicit lifecycle (a function-style call site, e.g. a test-helper builder)
 * SqliteTestDatabase.open().use { db -> db.repositoryProvider().workItemRepository() }
 *
 * // 2. One database per test class (opt in on a companion/static field)
 * companion object { @JvmField @RegisterExtension val db = SqliteTestDatabase.perClass() }
 *
 * // 3. A fresh database per test method (an instance field)
 * @RegisterExtension val db = SqliteTestDatabase.perMethod()
 * ```
 * Always pass [database] (not a global default) to `transaction(db = ...)`.
 */
class SqliteTestDatabase private constructor(
    private val directory: Path
) : AutoCloseable {
    /** The database file: `<directory>/test.db` (a copy of the template). */
    val file: File = directory.resolve("test.db").toFile()

    /** `jdbc:sqlite:` URL for [file]. */
    val jdbcUrl: String = "jdbc:sqlite:" + file.absolutePath.replace(File.separatorChar, '/')

    /** The production manager, already initialized against [file]. Only its public API is used. */
    val databaseManager: DatabaseManager =
        DatabaseManager(appConfig = AppConfig.fromEnv { null }).also {
            check(it.initialize(jdbcUrl)) { "SqliteTestDatabase: DatabaseManager.initialize failed for $jdbcUrl" }
        }

    /** The Exposed database; pass it to every `transaction(db = database)`. */
    val database: Database get() = databaseManager.getDatabase()

    private var provider: DefaultRepositoryProvider? = null

    /** The production repositories over this database (created once per instance). */
    fun repositoryProvider(): DefaultRepositoryProvider = provider ?: DefaultRepositoryProvider(databaseManager).also { provider = it }

    /** Shuts the manager down and deletes the database files; fails loudly if a connection leaked. */
    override fun close() {
        runCatching { databaseManager.shutdown() }
        deleteWithRetry(directory)
    }

    companion object {
        /** Opens a fresh copy of the template. The caller owns [close]. */
        fun open(): SqliteTestDatabase {
            val dir = Files.createTempDirectory("to-sqlite-test-")
            try {
                Files.copy(SqliteTemplate.file().toPath(), dir.resolve("test.db"), StandardCopyOption.REPLACE_EXISTING)
                return SqliteTestDatabase(dir)
            } catch (e: Throwable) {
                runCatching { deleteWithRetry(dir) }
                throw e
            }
        }

        /** One database shared by every test of the class; declare on a static (companion) field. */
        fun perClass(): SqliteTestDatabaseExtension = SqliteTestDatabaseExtension(perMethod = false)

        /** A fresh database for every test method; declare on an instance field. */
        fun perMethod(): SqliteTestDatabaseExtension = SqliteTestDatabaseExtension(perMethod = true)

        private const val DELETE_ATTEMPTS = 5
        private const val DELETE_BACKOFF_MS = 200L

        private fun deleteWithRetry(dir: Path) {
            var lastFailure: Pair<Path, Throwable>? = null
            repeat(DELETE_ATTEMPTS) { attempt ->
                lastFailure = null
                if (!Files.exists(dir)) return
                Files.walk(dir).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { p ->
                        try {
                            Files.deleteIfExists(p)
                        } catch (e: Exception) {
                            // Keep the FIRST failure: files are visited before their directory, so this names the leaked file.
                            if (lastFailure == null) lastFailure = p to e
                        }
                    }
                }
                if (lastFailure == null) return
                if (attempt < DELETE_ATTEMPTS - 1) Thread.sleep(DELETE_BACKOFF_MS)
            }
            val (path, cause) = lastFailure ?: return
            throw IllegalStateException(
                "SqliteTestDatabase could not delete $path after $DELETE_ATTEMPTS attempts; a test leaked a " +
                    "connection to the database (close every DriverManager connection it opens).",
                cause
            )
        }
    }
}

/**
 * JUnit 5 extension behind [SqliteTestDatabase.perClass] / [SqliteTestDatabase.perMethod]. The
 * database is held in the extension store as a CloseableResource, so JUnit closes it (and fails
 * the run loudly on a leaked connection) when the owning context ends.
 */
class SqliteTestDatabaseExtension internal constructor(
    private val perMethod: Boolean
) : BeforeAllCallback,
    BeforeEachCallback {
    private class Holder(
        val db: SqliteTestDatabase
    ) : ExtensionContext.Store.CloseableResource {
        override fun close() = db.close()
    }

    @Volatile
    private var current: SqliteTestDatabase? = null

    /** The live database for the current scope. */
    val db: SqliteTestDatabase
        get() = current ?: error("SqliteTestDatabase extension used before its scope started (no beforeAll/beforeEach yet)")

    val file: File get() = db.file
    val jdbcUrl: String get() = db.jdbcUrl
    val databaseManager: DatabaseManager get() = db.databaseManager
    val database: Database get() = db.database

    fun repositoryProvider(): DefaultRepositoryProvider = db.repositoryProvider()

    override fun beforeAll(context: ExtensionContext) {
        if (!perMethod) start(context)
    }

    override fun beforeEach(context: ExtensionContext) {
        if (perMethod) {
            start(context)
        } else if (current == null) {
            start(context.parent.orElse(context))
        }
    }

    private fun start(context: ExtensionContext) {
        val holder = Holder(SqliteTestDatabase.open())
        context.getStore(NAMESPACE).put(holder, holder)
        current = holder.db
    }

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(SqliteTestDatabaseExtension::class.java)
    }
}
