package io.github.jpicklyk.mcptask.current.platform.port

import java.time.Instant

/** The stores the platform itself reads and writes: idempotent results and the domain-event log. */
interface PlatformStoreProvider {
    fun idempotencyStore(): IdempotencyStore

    /**
     * The append-only domain-event log (`events`). Write services append to it through the unit's
     * `WriteScope.events` sink, never directly.
     */
    fun eventStore(): EventStore
}

/**
 * The stores the retention pruner deletes from: the platform stores plus the two slice tables it prunes by age. Each
 * prune is a write that runs inside the caller's unit and deletes at most `batch` rows older than `cutoff`, oldest
 * first, returning how many it deleted.
 */
interface RetentionStores : PlatformStoreProvider {
    /** Deletes closed resource-lease history intervals released before [cutoff]. */
    suspend fun pruneLeaseHistory(
        cutoff: Instant,
        batch: Int
    ): Int

    /** Deletes `call_log` rows recorded before [cutoff]; the caller bounds [cutoff] by the `usage_daily` fold watermark. */
    suspend fun pruneCallLog(
        cutoff: Instant,
        batch: Int
    ): Int
}
