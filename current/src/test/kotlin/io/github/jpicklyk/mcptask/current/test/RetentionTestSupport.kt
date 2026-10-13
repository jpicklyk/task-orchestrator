package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Raw JDBC fixtures and reads for the retention-pruner tests (item 0da29ea1). Everything here talks to the tables through
 * plain SQL, independent of every production store class, so a fixture's expected state is an input the test chose and a
 * read-back is not routed through the code under test.
 *
 * Timestamps use the stored text shape (`yyyy-MM-dd HH:mm:ss.SSS`, UTC, 23 characters, enforced by the tables' CHECKs).
 */
object RetentionRows {
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    /** The root every raw event row belongs to unless a test says otherwise. */
    val ROOT: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    fun stamp(at: Instant): String = stamp.format(at)

    fun idBytes(id: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    /** Inserts one `events` row at an explicit [seq]. */
    fun insertEvent(
        jdbcUrl: String,
        seq: Long,
        type: String,
        at: Instant,
        entityId: UUID = UUID.randomUUID(),
        rootId: UUID = ROOT
    ) {
        DriverManager.getConnection(jdbcUrl).use { c -> insertEvent(c, seq, type, at, entityId, rootId) }
    }

    /** Inserts [count] rows of one type at seq `firstSeq .. firstSeq+count-1` in JDBC batches (large-fixture path). */
    fun insertEventsBatch(
        jdbcUrl: String,
        firstSeq: Long,
        count: Int,
        type: String,
        at: Instant
    ) {
        DriverManager.getConnection(jdbcUrl).use { c ->
            c.autoCommit = false
            c
                .prepareStatement(
                    "INSERT INTO events (seq, id, occurred_at, root_id, entity_kind, entity_id, type, data) VALUES (?,?,?,?,?,?,?,?)"
                ).use { ps ->
                    val ts = stamp(at)
                    val root = idBytes(ROOT)
                    for (i in 0 until count) {
                        ps.setLong(1, firstSeq + i)
                        ps.setBytes(2, idBytes(UUID.randomUUID()))
                        ps.setString(3, ts)
                        ps.setBytes(4, root)
                        ps.setString(5, DomainEvent.KIND_ITEM)
                        ps.setBytes(6, idBytes(UUID.randomUUID()))
                        ps.setString(7, type)
                        ps.setString(8, "{}")
                        ps.addBatch()
                        if ((i + 1) % 5_000 == 0) ps.executeBatch()
                    }
                    ps.executeBatch()
                }
            c.commit()
        }
    }

    private fun insertEvent(
        c: Connection,
        seq: Long,
        type: String,
        at: Instant,
        entityId: UUID,
        rootId: UUID
    ) {
        c
            .prepareStatement(
                "INSERT INTO events (seq, id, occurred_at, root_id, entity_kind, entity_id, type, data) VALUES (?,?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setLong(1, seq)
                ps.setBytes(2, idBytes(UUID.randomUUID()))
                ps.setString(3, stamp(at))
                ps.setBytes(4, idBytes(rootId))
                ps.setString(5, DomainEvent.KIND_ITEM)
                ps.setBytes(6, idBytes(entityId))
                ps.setString(7, type)
                ps.setString(8, "{}")
                ps.executeUpdate()
            }
    }

    /** Every surviving event seq, ascending. */
    fun eventSeqs(jdbcUrl: String): List<Long> = longs(jdbcUrl, "SELECT seq FROM events ORDER BY seq")

    /** Surviving seqs of one type, ascending. */
    fun eventSeqs(
        jdbcUrl: String,
        type: String
    ): List<Long> = longs(jdbcUrl, "SELECT seq FROM events WHERE type = '$type' ORDER BY seq")

    fun eventCount(jdbcUrl: String): Int = scalar(jdbcUrl, "SELECT count(*) FROM events").toInt()

    /** Inserts one `resource_lease_history` interval; [releasedAt] null is an open interval. */
    fun insertLeaseInterval(
        jdbcUrl: String,
        resourceKey: String,
        acquiredAt: Instant,
        releasedAt: Instant?
    ) {
        DriverManager.getConnection(jdbcUrl).use { c ->
            c
                .prepareStatement(
                    "INSERT INTO resource_lease_history " +
                        "(resource_key, holder_item_id, acquired_by_actor_id, acquired_at, expires_at, released_at, release_reason) " +
                        "VALUES (?,?,?,?,?,?,?)"
                ).use { ps ->
                    ps.setString(1, resourceKey)
                    ps.setBytes(2, idBytes(UUID.randomUUID()))
                    ps.setString(3, "agent-x")
                    ps.setString(4, stamp(acquiredAt))
                    ps.setString(5, stamp(acquiredAt.plusSeconds(600)))
                    if (releasedAt == null) ps.setNull(6, java.sql.Types.VARCHAR) else ps.setString(6, stamp(releasedAt))
                    if (releasedAt == null) ps.setNull(7, java.sql.Types.VARCHAR) else ps.setString(7, "released")
                    ps.executeUpdate()
                }
        }
    }

    /** Surviving `resource_lease_history` resource keys, sorted. */
    fun leaseKeys(jdbcUrl: String): List<String> = strings(jdbcUrl, "SELECT resource_key FROM resource_lease_history ORDER BY resource_key")

    fun leaseCount(jdbcUrl: String): Int = scalar(jdbcUrl, "SELECT count(*) FROM resource_lease_history").toInt()

    /** Surviving call_log req_ids, sorted. */
    fun callLogIds(jdbcUrl: String): List<String> = strings(jdbcUrl, "SELECT req_id FROM call_log ORDER BY req_id")

    /** events + call_log + resource_lease_history row total. */
    fun prunableTotal(jdbcUrl: String): Long =
        scalar(
            jdbcUrl,
            "SELECT (SELECT count(*) FROM events) + (SELECT count(*) FROM call_log) + (SELECT count(*) FROM resource_lease_history)"
        )

    fun scalar(
        jdbcUrl: String,
        sql: String
    ): Long =
        DriverManager.getConnection(jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private fun longs(
        jdbcUrl: String,
        sql: String
    ): List<Long> =
        DriverManager.getConnection(jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add(rs.getLong(1)) }
                }
            }
        }

    private fun strings(
        jdbcUrl: String,
        sql: String
    ): List<String> =
        DriverManager.getConnection(jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
}

/**
 * Delegates to a real [UnitOfWork] and records, per `write` call, how many rows (events + call_log + resource_lease_history)
 * the unit removed, measured over a separate JDBC connection before and after the unit. [deltas] holds one entry per write
 * unit in call order; a unit that deleted nothing records 0.
 */
class RemovedRowsUnitOfWork(
    private val real: UnitOfWork,
    private val jdbcUrl: String
) : UnitOfWork by real {
    val deltas = CopyOnWriteArrayList<Long>()

    /** Units that removed at least one row, in call order. */
    val deletingUnits: List<Long> get() = deltas.filter { it > 0 }

    override suspend fun <T> write(
        op: String,
        block: suspend WriteScope.() -> Outcome<T>
    ): Outcome<T> {
        val before = RetentionRows.prunableTotal(jdbcUrl)
        val result = real.write(op, block)
        deltas += before - RetentionRows.prunableTotal(jdbcUrl)
        return result
    }
}
