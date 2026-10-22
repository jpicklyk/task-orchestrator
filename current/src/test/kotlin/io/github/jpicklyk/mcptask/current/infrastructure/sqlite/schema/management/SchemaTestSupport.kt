package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** One captured log record: level plus the formatted message and any throwable message chain. */
data class CapturedLog(
    val level: Level,
    val text: String
)

/** Shared helpers for the independent Flyway-only schema tests (file-backed SQLite only). */
object SchemaTestSupport {
    fun dbFile(
        dir: Path,
        name: String = "db-${System.nanoTime()}.sqlite"
    ): File = dir.resolve(name).toFile()

    fun urlFor(file: File): String = "jdbc:sqlite:" + file.absolutePath.replace(File.separatorChar, '/')

    fun newUrl(dir: Path): String = urlFor(dbFile(dir))

    fun <T> withConn(
        url: String,
        block: (Connection) -> T
    ): T = DriverManager.getConnection(url).use(block)

    fun exec(
        url: String,
        vararg sql: String
    ) = withConn(url) { c -> c.createStatement().use { st -> sql.forEach { st.execute(it) } } }

    fun <T> query(
        url: String,
        sql: String,
        mapper: (java.sql.ResultSet) -> T
    ): List<T> =
        withConn(url) { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val out = mutableListOf<T>()
                    while (rs.next()) out += mapper(rs)
                    out
                }
            }
        }

    fun scalarInt(
        url: String,
        sql: String
    ): Int = query(url, sql) { it.getInt(1) }.single()

    fun tableExists(
        url: String,
        name: String
    ): Boolean = scalarInt(url, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$name'") > 0

    fun objectNames(
        url: String,
        type: String
    ): Set<String> = query(url, "SELECT name FROM sqlite_master WHERE type='$type'") { it.getString(1) }.toSet()

    fun userTableCount(url: String): Int =
        scalarInt(url, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")

    fun uuidBytes(id: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    /** Inserts a work item through raw JDBC; every NOT NULL column without a default is supplied. */
    fun insertItem(
        url: String,
        id: UUID,
        parent: UUID? = null,
        depth: Int = 0,
        rootId: UUID? = id,
        title: String = "item"
    ) = withConn(url) { c ->
        c
            .prepareStatement(
                "INSERT INTO work_items (id, parent_id, title, depth, root_id, created_at, modified_at, role_changed_at) " +
                    "VALUES (?, ?, ?, ?, ?, '2026-01-01 00:00:00', '2026-01-01 00:00:00', '2026-01-01 00:00:00')"
            ).use { ps ->
                ps.setBytes(1, uuidBytes(id))
                ps.setBytes(2, parent?.let(::uuidBytes))
                ps.setString(3, title)
                ps.setInt(4, depth)
                ps.setBytes(5, rootId?.let(::uuidBytes))
                ps.executeUpdate()
            }
    }

    fun insertDependency(
        url: String,
        from: UUID,
        to: UUID,
        type: String
    ) = withConn(url) { c ->
        c
            .prepareStatement(
                "INSERT INTO dependencies (id, from_item_id, to_item_id, type, created_at) VALUES (?, ?, ?, ?, '2026-01-01 00:00:00')"
            ).use { ps ->
                ps.setBytes(1, uuidBytes(UUID.randomUUID()))
                ps.setBytes(2, uuidBytes(from))
                ps.setBytes(3, uuidBytes(to))
                ps.setString(4, type)
                ps.executeUpdate()
            }
    }

    fun insertNote(
        url: String,
        itemId: UUID,
        key: String,
        body: String
    ) = withConn(url) { c ->
        c
            .prepareStatement(
                "INSERT INTO notes (id, work_item_id, key, role, body, created_at, modified_at) " +
                    "VALUES (?, ?, ?, 'queue', ?, '2026-01-01 00:00:00', '2026-01-01 00:00:00')"
            ).use { ps ->
                ps.setBytes(1, uuidBytes(UUID.randomUUID()))
                ps.setBytes(2, uuidBytes(itemId))
                ps.setString(3, key)
                ps.setString(4, body)
                ps.executeUpdate()
            }
    }

    /** Brings a fresh file-backed database to the current schema through the production manager. */
    fun migrated(dir: Path): String {
        val url = newUrl(dir)
        check(FlywayDatabaseSchemaManager(url, repair = false).updateSchema()) { "fixture migration failed" }
        return url
    }

    /** Captures every log record emitted anywhere (root logger) during [block]. */
    fun captureLogs(block: () -> Unit): List<CapturedLog> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                root.addAppender(it)
            }
        try {
            block()
            return appender.list.map { e ->
                val chain = generateSequence(e.throwableProxy) { it.cause }.joinToString(" | ") { it.message ?: "" }
                CapturedLog(e.level, e.formattedMessage + " " + chain)
            }
        } finally {
            root.detachAppender(appender)
        }
    }

    fun List<CapturedLog>.at(level: Level): List<String> = filter { it.level == level }.map { it.text }
}
