package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLeaseInterval
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.ResourceLeaseHistoryTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.ResourceLeasesTable
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
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

    private companion object {
        /** Bounded retries for SQLITE_BUSY / SQLITE_LOCKED contention on [acquireAll]. */
        const val ACQUIRE_LOCK_RETRIES = 5
        const val ACQUIRE_LOCK_RETRY_DELAY_MS = 40L
    }

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
        // Bounded retry on SQLite lock contention (SQLITE_BUSY on file databases,
        // SQLITE_LOCKED_SHAREDCACHE on shared-cache connections): the losing writer of a genuine
        // cross-connection race gets a clean rollback from suspendTransaction, so retrying is safe
        // and lets it re-evaluate — typically landing on Contended (with retryAfterMs) instead of
        // surfacing a raw DBError. Exhausting the attempts falls through to the DBError path,
        // which callers already treat as transient per the interface KDoc.
        var lastLockException: Exception? = null
        repeat(ACQUIRE_LOCK_RETRIES) { attempt ->
            try {
                return acquireAllOnce(holderItemId, actorId, requirements)
            } catch (e: Exception) {
                if (!isSqliteLockContention(e)) throw e
                lastLockException = e
                logger.debug("acquireAll lock contention (attempt ${attempt + 1}/$ACQUIRE_LOCK_RETRIES), retrying")
                delay(ACQUIRE_LOCK_RETRY_DELAY_MS)
            }
        }
        logger.error("Failed to acquire resource leases for holder $holderItemId after $ACQUIRE_LOCK_RETRIES lock-contention retries")
        return LeaseAcquireResult.DBError(lastLockException ?: IllegalStateException("lock contention"))
    }

    /** Walks the cause chain for a SQLite busy/locked result code — the only retryable failures. */
    private fun isSqliteLockContention(e: Throwable?): Boolean {
        var cause = e
        while (cause != null) {
            val msg = cause.message ?: ""
            if (msg.contains("SQLITE_BUSY") || msg.contains("SQLITE_LOCKED")) return true
            cause = cause.cause
        }
        return false
    }

    private suspend fun acquireAllOnce(
        holderItemId: UUID,
        actorId: String?,
        requirements: List<Pair<String, Int>>
    ): LeaseAcquireResult {
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

                        // History bookkeeping happens on the SAME row lookups the live upsert below
                        // is about to overwrite — read the pre-write state now, before it changes.
                        // See recordHistoryOnAcquire's KDoc for the three cases this distinguishes.
                        val ownRow =
                            ResourceLeasesTable
                                .selectAll()
                                .where { (ResourceLeasesTable.resourceKey eq key) and (ResourceLeasesTable.holderItemId eq holderItemId) }
                                .singleOrNull()
                        // Not .singleOrNull(): the live table's uniqueness is scoped to (resource_key,
                        // holder_item_id), so a fresh acquire/steal never deletes a prior holder's
                        // now-stale row — a key can accumulate more than one expired other-holder row
                        // across repeated steals. All of them are guaranteed expired here (the
                        // contention pre-pass above already rejected any ACTIVE other holder).
                        val staleOtherRows =
                            if (ownRow == null) {
                                ResourceLeasesTable
                                    .selectAll()
                                    .where {
                                        (ResourceLeasesTable.resourceKey eq key) and (ResourceLeasesTable.holderItemId neq holderItemId)
                                    }.toList()
                            } else {
                                emptyList()
                            }

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

                        recordHistoryOnAcquire(
                            key = key,
                            holderItemId = holderItemId,
                            actorId = actorId,
                            ownRowExisted = ownRow != null,
                            staleOtherRows = staleOtherRows,
                            liveRow = row,
                        )
                    }

                    LeaseAcquireResult.Success(leases)
                }
            }
        } catch (e: Exception) {
            // Lock contention propagates to acquireAll's bounded retry; everything else is terminal.
            if (isSqliteLockContention(e)) throw e
            logger.error("Failed to acquire resource leases for holder $holderItemId: ${e.message}", e)
            LeaseAcquireResult.DBError(e)
        }
    }

    /**
     * Writes the `resource_lease_history` side of a single-key acquire, in the SAME transaction as
     * the live-row upsert `acquireAll` just performed for [key]. Three cases, distinguished by the
     * pre-write state captured before the live upsert ran:
     *
     * 1. **Refresh** ([ownRowExisted] true) — the OPEN history interval for `(key, holderItemId)`
     *    has its `expiresAt` extended in place. If none is open (the bootstrap gap for a lease that
     *    predates V16 — see the migration header), a new interval opens instead.
     * 2. **Steal** ([ownRowExisted] false, [staleOtherRows] non-empty) — every OTHER holder row
     *    still on this key (necessarily expired, or `acquireAll`'s contention pre-pass would have
     *    rejected this call — there can be more than one, since a steal never deletes the row it
     *    supersedes) has its OPEN history interval closed with `releaseReason = "expired"` at its
     *    own last-known `expiresAt` (read from the live row BEFORE it was overwritten —
     *    authoritative regardless of whether history was already in sync; a no-op for any stale row
     *    with no open interval to close). A new interval then opens for the new holder.
     * 3. **Fresh** ([ownRowExisted] false, [staleOtherRows] empty) — a never-before-seen (or
     *    previously fully released) key: a new interval simply opens.
     *
     * [liveRow] is the freshly-upserted `resource_leases` row, read back after the write — its
     * `acquiredAt` / `expiresAt` (both DB-computed) are reused verbatim for the history row so the
     * two tables can never disagree on the interval's timestamps.
     */
    private fun recordHistoryOnAcquire(
        key: String,
        holderItemId: UUID,
        actorId: String?,
        ownRowExisted: Boolean,
        staleOtherRows: List<ResultRow>,
        liveRow: ResultRow,
    ) {
        val liveAcquiredAt = liveRow[ResourceLeasesTable.acquiredAt]
        val liveExpiresAt = liveRow[ResourceLeasesTable.expiresAt]

        fun openNewInterval() {
            ResourceLeaseHistoryTable.insert {
                it[id] = UUID.randomUUID()
                it[resourceKey] = key
                it[ResourceLeaseHistoryTable.holderItemId] = holderItemId
                it[acquiredByActorId] = actorId
                it[acquiredAt] = liveAcquiredAt
                it[expiresAt] = liveExpiresAt
            }
        }

        if (ownRowExisted) {
            val updated =
                ResourceLeaseHistoryTable.update({
                    (ResourceLeaseHistoryTable.resourceKey eq key) and
                        (ResourceLeaseHistoryTable.holderItemId eq holderItemId) and
                        ResourceLeaseHistoryTable.releasedAt.isNull()
                }) {
                    it[expiresAt] = liveExpiresAt
                }
            if (updated == 0) openNewInterval()
        } else {
            staleOtherRows.forEach { stale ->
                val staleHolderId = stale[ResourceLeasesTable.holderItemId]
                val staleExpiresAt = stale[ResourceLeasesTable.expiresAt]
                ResourceLeaseHistoryTable.update({
                    (ResourceLeaseHistoryTable.resourceKey eq key) and
                        (ResourceLeaseHistoryTable.holderItemId eq staleHolderId) and
                        ResourceLeaseHistoryTable.releasedAt.isNull()
                }) {
                    it[releasedAt] = staleExpiresAt
                    it[releaseReason] = "expired"
                }
            }
            openNewInterval()
        }
    }

    override suspend fun releaseAllForItem(holderItemId: UUID): LeaseReleaseResult =
        try {
            suspendTransaction(db = databaseManager.getDatabase()) {
                val uuidType = UUIDColumnType()
                // Close every OPEN interval this holder has, across all its keys. releasedAt is
                // stamped DB-side (datetime('now')) — never the JVM clock, matching every other
                // write in this class.
                exec(
                    """
                    UPDATE resource_lease_history
                       SET released_at = datetime('now'), release_reason = 'released'
                     WHERE holder_item_id = ? AND released_at IS NULL
                    """.trimIndent(),
                    args = listOf(uuidType to holderItemId)
                )
                val count = ResourceLeasesTable.deleteWhere { ResourceLeasesTable.holderItemId eq holderItemId }
                LeaseReleaseResult.Success(count)
            }
        } catch (e: Exception) {
            logger.error("Failed to release resource leases for holder $holderItemId: ${e.message}", e)
            LeaseReleaseResult.DBError(e)
        }

    override suspend fun forceReleaseByKey(
        resourceKey: String,
        actorId: String?
    ): LeaseReleaseResult =
        try {
            suspendTransaction(db = databaseManager.getDatabase()) {
                val keyType = VarCharColumnType(255)
                val actorType = VarCharColumnType(500)
                // Close every OPEN interval on this key, regardless of holder. released_by_actor_id
                // records the acting principal (the REST route threads its tokenId through).
                exec(
                    """
                    UPDATE resource_lease_history
                       SET released_at = datetime('now'), release_reason = 'force_released', released_by_actor_id = ?
                     WHERE resource_key = ? AND released_at IS NULL
                    """.trimIndent(),
                    args = listOf(actorType to actorId, keyType to resourceKey)
                )
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

    override suspend fun findHoldersAt(
        resourceKey: String?,
        at: Instant
    ): List<ResourceLeaseInterval> =
        suspendTransaction(db = databaseManager.getDatabase()) {
            // Held at `at` iff acquiredAt <= at < coalesce(releasedAt, expiresAt) — expressed as an
            // OR over the open/closed shapes since Exposed has no direct coalesce() comparison here.
            val heldAt =
                (ResourceLeaseHistoryTable.acquiredAt lessEq at) and
                    (
                        (ResourceLeaseHistoryTable.releasedAt.isNull() and (ResourceLeaseHistoryTable.expiresAt greater at)) or
                            (ResourceLeaseHistoryTable.releasedAt.isNotNull() and (ResourceLeaseHistoryTable.releasedAt greater at))
                    )
            val conditions = mutableListOf(heldAt)
            if (resourceKey != null) conditions.add(ResourceLeaseHistoryTable.resourceKey eq resourceKey)

            ResourceLeaseHistoryTable
                .selectAll()
                .where { conditions.reduce { acc, op -> acc and op } }
                .orderBy(ResourceLeaseHistoryTable.acquiredAt, SortOrder.DESC)
                .map { toInterval(it) }
        }

    override suspend fun findRecentIntervals(
        resourceKey: String?,
        limit: Int
    ): List<ResourceLeaseInterval> =
        suspendTransaction(db = databaseManager.getDatabase()) {
            var query = ResourceLeaseHistoryTable.selectAll()
            if (resourceKey != null) query = query.andWhere { ResourceLeaseHistoryTable.resourceKey eq resourceKey }
            query
                .orderBy(ResourceLeaseHistoryTable.acquiredAt, SortOrder.DESC)
                .limit(limit)
                .map { toInterval(it) }
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

    private fun toInterval(row: ResultRow): ResourceLeaseInterval =
        ResourceLeaseInterval(
            id = row[ResourceLeaseHistoryTable.id].value,
            resourceKey = row[ResourceLeaseHistoryTable.resourceKey],
            holderItemId = row[ResourceLeaseHistoryTable.holderItemId],
            acquiredByActorId = row[ResourceLeaseHistoryTable.acquiredByActorId],
            acquiredAt = row[ResourceLeaseHistoryTable.acquiredAt],
            expiresAt = row[ResourceLeaseHistoryTable.expiresAt],
            releasedAt = row[ResourceLeaseHistoryTable.releasedAt],
            releaseReason = row[ResourceLeaseHistoryTable.releaseReason],
            releasedByActorId = row[ResourceLeaseHistoryTable.releasedByActorId],
        )
}
