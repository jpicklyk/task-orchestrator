package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.unitNow
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLeaseInterval
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.ResourceLeaseHistoryTable
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.ResourceLeasesTable
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.UtcTimestamp
import io.github.jpicklyk.mcptask.current.infrastructure.time.SystemClock
import org.jetbrains.exposed.v1.core.Op
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
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of [LeaseStore], backed by [ResourceLeasesTable].
 *
 * Storage + concurrency primitive only - no gate enforcement, no MCP tool surface (see the
 * interface KDoc). Every timestamp compared or written for lease-freshness decisions is the ambient unit instant
 * ([unitNow]) bound as a canonical UTC text parameter, and expiry (`now + ttl`) is computed in Kotlin - no database
 * clock is read and no SQL date function is used. A lease whose `expires_at <= now` is expired.
 */
class SQLiteResourceLeaseRepository(
    private val databaseManager: DatabaseManager,
    private val clock: Clock = SystemClock
) : LeaseStore {
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

    override suspend fun acquireAll(
        holderItemId: UUID,
        actorId: String?,
        requirements: List<Pair<String, Int>>
    ): LeaseAcquireResult {
        require(requirements.all { it.second > 0 }) {
            "ttlSeconds must be positive for every requirement: " +
                requirements.filter { it.second <= 0 }.joinToString { "${it.first}=${it.second}" }
        }
        // ONE attempt: the call runs inside the caller's write unit (IMMEDIATE, single writer), and
        // SQLITE_BUSY/LOCKED is retried by the unit itself. A database failure is thrown.
        return acquireAllOnce(holderItemId, actorId, requirements)
    }

    private suspend fun acquireAllOnce(
        holderItemId: UUID,
        actorId: String?,
        requirements: List<Pair<String, Int>>
    ): LeaseAcquireResult {
        // One instant for the whole call: the ambient unit's, else the store clock. Expiry is now + ttl.
        val now = clock.unitNow()
        val nowText = UtcTimestamp.format(now)
        return run {
            if (requirements.isEmpty()) {
                LeaseAcquireResult.Success(emptyList())
            } else {
                // Sort lexicographically for deterministic contention reporting across calls.
                // SQLite's single-writer model already serializes the whole transaction below, so
                // this ordering is not required for correctness here — only for predictable
                // diagnostics when the same requirement set is retried.
                val sortedRequirements = requirements.sortedBy { it.first }

                databaseManager.writeTx("LeaseStore.acquireAllOnce") {
                    val uuidType = UUIDColumnType()
                    val keyType = VarCharColumnType(255)
                    val actorType = VarCharColumnType(500)
                    val textType = VarCharColumnType(40)

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
                               AND expires_at > ?
                            """.trimIndent(),
                            args = listOf(keyType to key, uuidType to holderItemId, textType to nowText)
                        ) { rs -> if (rs.next()) activeOtherHolders = rs.getLong(1) }

                        if (activeOtherHolders >= maxHoldersPerKey) {
                            contendedKeys += key
                        }
                    }

                    if (contendedKeys.isNotEmpty()) {
                        // retryAfterMs: milliseconds until the SOONEST of the contending leases expires, across
                        // every contended key, computed in Kotlin from the bound instant; at least 1.
                        var soonest: String? = null
                        exec(
                            """
                            SELECT MIN(expires_at)
                              FROM resource_leases
                             WHERE resource_key IN (${contendedKeys.joinToString(",") { "?" }})
                               AND holder_item_id != ?
                               AND expires_at > ?
                            """.trimIndent(),
                            args = contendedKeys.map { keyType to it } + listOf(uuidType to holderItemId, textType to nowText)
                        ) { rs ->
                            if (rs.next()) soonest = rs.getString(1)
                        }
                        val retryAfterMs =
                            soonest?.let { maxOf(1L, UtcTimestamp.parse(it).toEpochMilli() - now.toEpochMilli()) } ?: 1L
                        return@writeTx LeaseAcquireResult.Contended(contendedKeys, retryAfterMs)
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
                        val expiresText = UtcTimestamp.format(now.plusSeconds(ttlSeconds.toLong()))

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
                        // Computed UNCONDITIONALLY — including on the own-row refresh/re-take path.
                        // A re-take of a key this item held before (own expired row still present)
                        // can follow an intervening holder whose expired row also lingers; guarding
                        // this on `ownRow == null` left that holder's history interval open, and a
                        // later releaseAllForItem would then close it at 'now' — making an at-T
                        // query report two simultaneous holders (found in post-merge field review
                        // of PR #262).
                        val staleOtherRows =
                            ResourceLeasesTable
                                .selectAll()
                                .where {
                                    (ResourceLeasesTable.resourceKey eq key) and (ResourceLeasesTable.holderItemId neq holderItemId)
                                }.toList()

                        exec(
                            """
                            INSERT INTO resource_leases
                                (id, resource_key, holder_item_id, acquired_by_actor_id,
                                 acquired_at, expires_at, original_acquired_at, version)
                            VALUES
                                (randomblob(16), ?, ?, ?, ?, ?, ?, 0)
                            ON CONFLICT(resource_key, holder_item_id) DO UPDATE SET
                                acquired_by_actor_id = excluded.acquired_by_actor_id,
                                acquired_at = excluded.acquired_at,
                                expires_at = excluded.expires_at,
                                version = resource_leases.version + 1
                            """.trimIndent(),
                            args =
                                listOf(
                                    keyType to key,
                                    uuidType to holderItemId,
                                    actorType to actorId,
                                    textType to nowText,
                                    textType to expiresText,
                                    textType to nowText,
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
        }
    }

    /**
     * Writes the `resource_lease_history` side of a single-key acquire, in the SAME transaction as
     * the live-row upsert `acquireAll` just performed for [key]. Three cases, distinguished by the
     * pre-write state captured before the live upsert ran:
     *
     * On EVERY branch, stale OTHER-holder rows still on the key (necessarily expired — the
     * contention pre-pass rejected any active other holder; there can be more than one, since a
     * steal never deletes the row it supersedes) first have their OPEN history intervals closed
     * with `releaseReason = "expired"` at each row's own last-known `expiresAt`. This runs on
     * refresh/re-take too, not only on steals — a re-take of a previously-held key can follow an
     * intervening expired holder whose interval must not stay open (post-merge fix, PR #262
     * field review). Then, for the acquiring holder:
     *
     * 1. **Refresh** ([ownRowExisted] true) — the OPEN history interval for `(key, holderItemId)`
     *    has its `expiresAt` extended in place. If none is open (the bootstrap gap for a lease that
     *    predates V16 — see the migration header), a new interval opens instead.
     * 2. **Fresh/steal** ([ownRowExisted] false) — a new interval opens.
     *
     * [liveRow] is the freshly-upserted `resource_leases` row, read back after the write — its
     * `acquiredAt` / `expiresAt` (both computed from the bound clock) are reused verbatim for the history row so the
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

        // Close stale OTHER holders' open intervals FIRST, on every branch — not only on steals.
        // The refresh/re-take branch can also follow an intervening expired holder (see the
        // staleOtherRows comment at the call site); each closes at its own last-known live-row
        // expiry, which is when its hold factually ended.
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
            openNewInterval()
        }
    }

    override suspend fun releaseAllForItem(holderItemId: UUID): LeaseReleaseResult {
        val nowText = UtcTimestamp.format(clock.unitNow())
        return databaseManager.writeTx("LeaseStore.releaseAllForItem") {
            val uuidType = UUIDColumnType()
            val textType = VarCharColumnType(40)
            // Close every OPEN interval this holder has, across all its keys. released_at is the bound instant,
            // clamped to the interval expiry (a hold never outlives its TTL). Reason: 'expired' when the interval
            // had already lapsed at that instant (expires_at <= now), else 'released'.
            exec(
                """
                UPDATE resource_lease_history
                   SET released_at = min(?, expires_at),
                       release_reason = CASE WHEN expires_at <= ? THEN 'expired' ELSE 'released' END
                 WHERE holder_item_id = ? AND released_at IS NULL
                """.trimIndent(),
                args = listOf(textType to nowText, textType to nowText, uuidType to holderItemId)
            )
            val count = ResourceLeasesTable.deleteWhere { ResourceLeasesTable.holderItemId eq holderItemId }
            LeaseReleaseResult.Success(count)
        }
    }

    override suspend fun releaseAllForItems(holderItemIds: Set<UUID>): LeaseReleaseResult {
        if (holderItemIds.isEmpty()) return LeaseReleaseResult.Success(0)
        val nowText = UtcTimestamp.format(clock.unitNow())
        return databaseManager.writeTx("LeaseStore.releaseAllForItems") {
            val uuidType = UUIDColumnType()
            val textType = VarCharColumnType(40)
            var total = 0
            // Chunked so the IN list never exceeds the bound-variable limit; every chunk runs in this one
            // transaction. Semantics per chunk are identical to releaseAllForItem.
            // Two more bound variables (the instant, twice) share each statement with the id list.
            for (chunk in holderItemIds.chunked(SQL_IN_CHUNK_SIZE - 2)) {
                val placeholders = chunk.joinToString(",") { "?" }
                exec(
                    """
                    UPDATE resource_lease_history
                       SET released_at = min(?, expires_at),
                           release_reason = CASE WHEN expires_at <= ? THEN 'expired' ELSE 'released' END
                     WHERE holder_item_id IN ($placeholders) AND released_at IS NULL
                    """.trimIndent(),
                    args = listOf(textType to nowText, textType to nowText) + chunk.map { uuidType to it }
                )
                total += ResourceLeasesTable.deleteWhere { ResourceLeasesTable.holderItemId inList chunk }
            }
            LeaseReleaseResult.Success(total)
        }
    }

    override suspend fun forceReleaseByKey(
        resourceKey: String,
        actorId: String?
    ): LeaseReleaseResult {
        val nowText = UtcTimestamp.format(clock.unitNow())
        return databaseManager.writeTx("LeaseStore.forceReleaseByKey") {
            val keyType = VarCharColumnType(255)
            val actorType = VarCharColumnType(500)
            val textType = VarCharColumnType(40)
            // Close every OPEN interval on this key, regardless of holder. released_by_actor_id
            // records the acting principal (the REST route threads its tokenId through).
            exec(
                """
                UPDATE resource_lease_history
                   SET released_at = min(?, expires_at),
                       release_reason = CASE WHEN expires_at <= ? THEN 'expired' ELSE 'force_released' END,
                       released_by_actor_id = ?
                 WHERE resource_key = ? AND released_at IS NULL
                """.trimIndent(),
                args = listOf(textType to nowText, textType to nowText, actorType to actorId, keyType to resourceKey)
            )
            val count = ResourceLeasesTable.deleteWhere { ResourceLeasesTable.resourceKey eq resourceKey }
            LeaseReleaseResult.Success(count)
        }
    }

    override suspend fun findLapsed(
        keys: List<String>?,
        holderItemIds: Set<UUID>?
    ): List<ResourceLease> {
        if (keys != null && keys.isEmpty()) return emptyList()
        if (holderItemIds != null && holderItemIds.isEmpty()) return emptyList()
        val now = clock.unitNow()
        return databaseManager.readTx {
            val rows = mutableListOf<ResourceLease>()
            // Chunked on the holder set so the IN list never exceeds the bound-variable limit.
            val holderChunks = holderItemIds?.chunked(SQL_IN_CHUNK_SIZE - 1) ?: listOf(null)
            for (holders in holderChunks) {
                ResourceLeasesTable
                    .selectAll()
                    .where {
                        var cond: Op<Boolean> = ResourceLeasesTable.expiresAt lessEq now
                        if (keys != null) cond = cond and (ResourceLeasesTable.resourceKey inList keys)
                        if (holders != null) cond = cond and (ResourceLeasesTable.holderItemId inList holders)
                        cond
                    }.mapTo(rows) { toLapsedLease(it) }
            }
            rows
        }
    }

    override suspend fun deleteLapsed(leaseIds: Set<UUID>): Int {
        if (leaseIds.isEmpty()) return 0
        val now = clock.unitNow()
        return databaseManager.writeTx("LeaseStore.deleteLapsed") {
            var removed = 0
            for (chunk in leaseIds.chunked(SQL_IN_CHUNK_SIZE - 1)) {
                val lapsed =
                    ResourceLeasesTable
                        .selectAll()
                        .where { (ResourceLeasesTable.id inList chunk) and (ResourceLeasesTable.expiresAt lessEq now) }
                        .toList()
                for (row in lapsed) {
                    val key = row[ResourceLeasesTable.resourceKey]
                    val holder = row[ResourceLeasesTable.holderItemId]
                    val expiresAt = row[ResourceLeasesTable.expiresAt]
                    // The hold ended at its own expiry, not at the instant the sweep noticed.
                    ResourceLeaseHistoryTable.update({
                        (ResourceLeaseHistoryTable.resourceKey eq key) and
                            (ResourceLeaseHistoryTable.holderItemId eq holder) and
                            ResourceLeaseHistoryTable.releasedAt.isNull()
                    }) {
                        it[releasedAt] = expiresAt
                        it[releaseReason] = "expired"
                    }
                    removed += ResourceLeasesTable.deleteWhere { ResourceLeasesTable.id eq row[ResourceLeasesTable.id] }
                }
            }
            removed
        }
    }

    override suspend fun findActiveByKeys(keys: List<String>): List<ResourceLease> {
        if (keys.isEmpty()) return emptyList()
        val now = clock.unitNow()
        return databaseManager.readTx {
            ResourceLeasesTable
                .selectAll()
                .where { (ResourceLeasesTable.resourceKey inList keys) and (ResourceLeasesTable.expiresAt greater now) }
                .map { toResourceLease(it) }
        }
    }

    override suspend fun findActiveForItem(holderItemId: UUID): List<ResourceLease> {
        val now = clock.unitNow()
        return databaseManager.readTx {
            ResourceLeasesTable
                .selectAll()
                .where { (ResourceLeasesTable.holderItemId eq holderItemId) and (ResourceLeasesTable.expiresAt greater now) }
                .map { toResourceLease(it) }
        }
    }

    override suspend fun findAllActive(): List<ResourceLease> {
        val now = clock.unitNow()
        return databaseManager.readTx {
            ResourceLeasesTable
                .selectAll()
                .where { ResourceLeasesTable.expiresAt greater now }
                .map { toResourceLease(it) }
        }
    }

    override suspend fun findHoldersAt(
        resourceKey: String?,
        at: Instant,
        limit: Int
    ): List<ResourceLeaseInterval> =
        databaseManager.readTx {
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
                .limit(limit)
                .map { toInterval(it) }
        }

    override suspend fun findRecentIntervals(
        resourceKey: String?,
        limit: Int
    ): List<ResourceLeaseInterval> =
        databaseManager.readTx {
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

    /**
     * A lapsed row as a [ResourceLease]. A row whose expiry was moved before its acquisition (hand-edited data; the
     * store itself never writes one) would fail the domain invariants and wedge every expiry pass, so its timestamps
     * are clamped to the expiry instead.
     */
    private fun toLapsedLease(row: ResultRow): ResourceLease {
        val expiresAt = row[ResourceLeasesTable.expiresAt]
        val acquiredAt = minOf(row[ResourceLeasesTable.acquiredAt], expiresAt)
        return ResourceLease(
            id = row[ResourceLeasesTable.id].value,
            resourceKey = row[ResourceLeasesTable.resourceKey],
            holderItemId = row[ResourceLeasesTable.holderItemId],
            acquiredByActorId = row[ResourceLeasesTable.acquiredByActorId],
            acquiredAt = acquiredAt,
            expiresAt = expiresAt,
            originalAcquiredAt = minOf(row[ResourceLeasesTable.originalAcquiredAt], acquiredAt),
            version = row[ResourceLeasesTable.version],
        )
    }

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
