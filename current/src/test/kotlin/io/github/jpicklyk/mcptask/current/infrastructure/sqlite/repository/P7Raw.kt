package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Independent oracle helpers for the P7 (item beeef6f7) persistence scenarios. Deliberately does NOT use the
 * production UtcTimestamp helpers: the canonical shape (`yyyy-MM-dd HH:mm:ss.SSS`, UTC, 23 characters) is restated
 * from the frozen test-plan so a bug in the production formatter cannot validate itself.
 */
internal object P7Raw {
    private const val PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"
    private val UTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(PATTERN).withZone(ZoneOffset.UTC)
    private val LOCAL_PARSE: DateTimeFormatter = DateTimeFormatter.ofPattern(PATTERN)

    /** SQLite GLOB for the canonical shape (a '.' is a literal in GLOB). */
    const val CANONICAL_GLOB = "[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]"

    /** Canonical UTC text of [instant]. */
    fun canon(instant: Instant): String = UTC_FORMAT.format(instant)

    /** Parses canonical text as UTC. */
    fun parseCanon(text: String): Instant = LocalDateTime.parse(text, LOCAL_PARSE).toInstant(ZoneOffset.UTC)

    fun bytes(id: UUID): ByteArray =
        java.nio.ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    private fun bind(
        ps: PreparedStatement,
        args: Array<out Any?>
    ) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> ps.setNull(i + 1, java.sql.Types.NULL)
                is UUID -> ps.setBytes(i + 1, bytes(a))
                is ByteArray -> ps.setBytes(i + 1, a)
                is Int -> ps.setInt(i + 1, a)
                is Long -> ps.setLong(i + 1, a)
                else -> ps.setString(i + 1, a.toString())
            }
        }
    }

    /** Runs a SELECT on a fresh JDBC connection to [jdbcUrl] and maps every row. */
    fun <T> query(
        jdbcUrl: String,
        sql: String,
        vararg args: Any?,
        map: (ResultSet) -> T
    ): List<T> =
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps, args)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
            }
        }

    /** The single text value (or null) a one-row, one-column SELECT yields; fails if the row is missing. */
    fun text(
        jdbcUrl: String,
        sql: String,
        vararg args: Any?
    ): String? {
        val rows = query(jdbcUrl, sql, *args) { it.getString(1) }
        check(rows.size == 1) { "expected exactly one row for [$sql], got ${rows.size}" }
        return rows.single()
    }

    /** Executes an INSERT/UPDATE/DELETE on a fresh JDBC connection and returns the affected row count. */
    fun exec(
        jdbcUrl: String,
        sql: String,
        vararg args: Any?
    ): Int =
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps, args)
                ps.executeUpdate()
            }
        }
}
