package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.ResourceLeasesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * SQLite implementation of [ResourceLeaseRepository], backed by [ResourceLeasesTable].
 *
 * Storage + concurrency primitive only — no gate enforcement, no MCP tool surface (see the
 * interface KDoc). Mirrors [SQLiteWorkItemRepository.claim]'s transaction/DB-clock discipline:
 * every timestamp compared or written for lease-freshness decisions is DB-side (`datetime('now')`
 * in raw SQL, or an [Instant] read from the DB clock via [dbNow] for typed Exposed comparisons) —
 * never [Instant.now].
 */
class SQLiteResourceLeaseRepository(
    private val databaseManager: DatabaseManager
) : ResourceLeaseRepository {
    private val logger = LoggerFactory.getLogger(SQLiteResourceLeaseRepository::class.java)

    /**
     * v1 hard cap on concurrent active holders per resource key. Matches
     * [io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition.maxHolders]'s own
     * config-load-time hard cap (a config value > 1 is rejected as a load ERROR — see that
     * property's KDoc). This repository does not read config; a future fan-in ("semaphore")
     * enforcement mode would resolve the caller's actual `maxHolders` and compare against that
     * instead of this constant — the table's `(resource_key, holder_item_id)` unique index (see
     * `V15__Resource_Leases.sql`) is already shaped to support that without a schema change.
     */
    private val maxHoldersPerKey = 1

    /**
     * Return the database server's current wall-clock time as an [Instant].
     *
     * Self-contained twin of [SQLiteWorkItemRepository.dbNow] — see that method's KDoc for the
     * full string-parsing rationale (SQLite's `CURRENT_TIMESTAMP` has no timezone suffix and is in
     * UTC; naive `rs.getTimestamp()` would apply the JVM's local zone offset and drift). Used only
     * to obtain a DB-clock [Instant] for typed Exposed `expiresAt greater now` comparisons in the
     * `findActive*` read paths — mutating SQL in this class uses `datetime('now')` directly instead.
     */
    private suspend fun dbNow(): Instant =
        try {
            suspendTransaction(db = databaseManager.getDatabase()) {
                exec("SELECT CURRENT_TIMESTAMP") { rs ->
                    if (rs.next()) rs.getString(1)?.let { parseDbTimestamp(it) } else null
                }
            } ?: Instant.now()
        } catch (e: Exception) {
            logger.warn("Failed to fetch DB-side current time, falling back to JVM clock: ${e.message}")
            Instant.now()
        }

    private fun parseDbTimestamp(raw: String): Instant {
        val isoCandidate = raw.replace(" ", "T")
        val tzPattern = Regex("([+-]\\d{2}(:\\d{2})?|Z)$")
        val tzMatch = tzPattern.find(isoCandidate)
        return if (tzMatch != null) {
            val tz = tzMatch.value
            val normalized =
                if (tz.startsWith("Z") || tz.contains(":")) {
                    isoCandidate
                } else {
                    isoCandidate.dropLast(tz.length) + tz + ":00"
                }
            OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } else {
            LocalDateTime
                .parse(isoCandidate, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC)
        }
    }

    override suspend fun acquireAll(
        holderItemId: UUID,
        actorId: String?,
        requirements: List<Pair<String, Int>>
    ): LeaseAcquireResult {
        require(requirements.all { it.second > 0 }) {
            "ttlSeconds must be positive for every requirement: " +
                requirements.filter { it.second <= 0 }.joinToString { "${it.first}=${it.second}" }
        }
        return try {
            if (requirements.isEmpty()) {
                LeaseAcquireResult.Success(emptyList())
            } else {
                // Sort lexicographically for deterministic contention reporting across calls.
                // SQLite's single-writer model already serializes the whole transaction below, so
                // this ordering is not required for correctness here — only for predictable
                // diagnostics when the same requirement set is retried.
                val sortedRequirements = requirements.sortedBy { it.first }

                suspendTransaction(db = databaseManager.getDatabase()) {
                    val uuidType = UUIDColumnType()
                    val keyType = VarCharColumnType(255)
                    val actorType = VarCharColumnType(500)
                    val ttlOffsetType = VarCharColumnType(50)

                    // Pre-pass: check EVERY key for contention before writing anything. This pre-pass
                    // and the writes below run in the SAME transaction — SQLite's single-writer model
                    // means no other transaction can insert a competing row in between, so there is no
                    // TOCTOU window despite the check-then-write shape.
                    val contendedKeys = mutableListOf<String>()
                    for ((key, _) in sortedRequirements) {
                        var activeOtherHolders = 0L
                        exec(
                            """
                            SELECT COUNT(*) FROM resource_leases
                             WHERE resource_key = ?
                               AND holder_item_id != ?
                               AND expires_at > datetime('now')
                            """.trimIndent(),
                            args = listOf(keyType to key, uuidType to holderItemId)
                        ) { rs -> if (rs.next()) activeOtherHolders = rs.getLong(1) }

                        if (activeOtherHolders >= maxHoldersPerKey) {
                            contendedKeys += key
                        }
                    }

                    if (contendedKeys.isNotEmpty()) {
                        // retryAfterMs: milliseconds (computed entirely DB-side via julianday
                        // arithmetic — never the JVM clock) until the SOONEST of the contending
                        // leases expires, across every contended key.
                        var retryAfterMs = 0L
                        exec(
                            """
                            SELECT CAST(ROUND((julianday(MIN(expires_at)) - julianday('now')) * 86400000) AS INTEGER)
                              FROM resource_leases
                             WHERE resource_key IN (${contendedKeys.joinToString(",") { "?" }})
                               AND holder_item_id != ?
                               AND expires_at > datetime('now')
                            """.trimIndent(),
                            args = contendedKeys.map { keyType to it } + listOf(uuidType to holderItemId)
                        ) { rs ->
                            if (rs.next()) {
                                val value = rs.getLong(1)
                                if (!rs.wasNull() && value > 0) retryAfterMs = value
                            }
                        }
                        return@suspendTransaction LeaseAcquireResult.Contended(contendedKeys, retryAfterMs)
                    }

                    // No contention for any key — upsert every requested key's own row.
                    // Same-holder re-acquire (a row already exists for this (resource_key,
                    // holder_item_id) pair, active OR expired) refreshes acquired_at/expires_at and
                    // preserves original_acquired_at by simply omitting it from the DO UPDATE SET —
                    // untouched columns keep their prior value under SQLite upsert semantics. A fresh
                    // acquire (no existing row for this pair — including the case where a DIFFERENT
                    // holder's now-expired row exists for the same key) inserts all three timestamps
                    // together.
                    val leases = mutableListOf<ResourceLease>()
                    for ((key, ttlSeconds) in sortedRequirements) {
                        val ttlOffset = "+$ttlSeconds"
                        exec(
                            """
                            INSERT INTO resource_leases
                                (id, resource_key, holder_item_id, acquired_by_actor_id,
                                 acquired_at, expires_at, original_acquired_at, version)
                            VALUES
                                (randomblob(16), ?, ?, ?, datetime('now'), datetime('now', ? || ' seconds'), datetime('now'), 0)
                            ON CONFLICT(resource_key, holder_item_id) DO UPDATE SET
                                acquired_by_actor_id = excluded.acquired_by_actor_id,
                                acquired_at = datetime('now'),
                                expires_at = datetime('now', ? || ' seconds'),
                                version = resource_leases.version + 1
                            """.trimIndent(),
                            args =
                                listOf(
                                    keyType to key,
                                    uuidType to holderItemId,
                                    actorType to actorId,
                                    ttlOffsetType to ttlOffset,
                                    ttlOffsetType to ttlOffset,
                                )
                        )

                        val row =
                            ResourceLeasesTable
                                .selectAll()
                                .where { (ResourceLeasesTable.resourceKey eq key) and (ResourceLeasesTable.holderItemId eq holderItemId) }
                                .single()
                        leases += toResourceLease(row)
                    }

                    LeaseAcquireResult.Success(leases)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to acquire resource leases for holder $holderItemId: ${e.message}", e)
            LeaseAcquireResult.DBError(e)
        }
    }

    override suspend fun releaseAllForItem(holderItemId: UUID): LeaseReleaseResult =
        try {
            suspendTransaction(db = databaseManager.getDatabase()) {
                val count = ResourceLeasesTable.deleteWhere { ResourceLeasesTable.holderItemId eq holderItemId }
                LeaseReleaseResult.Success(count)
            }
        } catch (e: Exception) {
            logger.error("Failed to release resource leases for holder $holderItemId: ${e.message}", e)
            LeaseReleaseResult.DBError(e)
        }

    override suspend fun forceReleaseByKey(resourceKey: String): LeaseReleaseResult =
        try {
            suspendTransaction(db = databaseManager.getDatabase()) {
                val count = ResourceLeasesTable.deleteWhere { ResourceLeasesTable.resourceKey eq resourceKey }
                LeaseReleaseResult.Success(count)
            }
        } catch (e: Exception) {
            logger.error("Failed to force-release resource leases for key $resourceKey: ${e.message}", e)
            LeaseReleaseResult.DBError(e)
        }

    override suspend fun findActiveByKeys(keys: List<String>): List<ResourceLease> {
        if (keys.isEmpty()) return emptyList()
        val now = dbNow()
        return suspendTransaction(db = databaseManager.getDatabase()) {
            ResourceLeasesTable
                .selectAll()
                .where { (ResourceLeasesTable.resourceKey inList keys) and (ResourceLeasesTable.expiresAt greater now) }
                .map { toResourceLease(it) }
        }
    }

    override suspend fun findActiveForItem(holderItemId: UUID): List<ResourceLease> {
        val now = dbNow()
        return suspendTransaction(db = databaseManager.getDatabase()) {
            ResourceLeasesTable
                .selectAll()
                .where { (ResourceLeasesTable.holderItemId eq holderItemId) and (ResourceLeasesTable.expiresAt greater now) }
                .map { toResourceLease(it) }
        }
    }

    override suspend fun findAllActive(): List<ResourceLease> {
        val now = dbNow()
        return suspendTransaction(db = databaseManager.getDatabase()) {
            ResourceLeasesTable
                .selectAll()
                .where { ResourceLeasesTable.expiresAt greater now }
                .map { toResourceLease(it) }
        }
    }

    private fun toResourceLease(row: ResultRow): ResourceLease =
        ResourceLease(
            id = row[ResourceLeasesTable.id].value,
            resourceKey = row[ResourceLeasesTable.resourceKey],
            holderItemId = row[ResourceLeasesTable.holderItemId],
            acquiredByActorId = row[ResourceLeasesTable.acquiredByActorId],
            acquiredAt = row[ResourceLeasesTable.acquiredAt],
            expiresAt = row[ResourceLeasesTable.expiresAt],
            originalAcquiredAt = row[ResourceLeasesTable.originalAcquiredAt],
            version = row[ResourceLeasesTable.version],
        )
}
