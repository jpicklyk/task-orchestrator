package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.EventCommitListener
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ActorClaimDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The SSE stream at `GET /api/v1/events`, as a projection of the `events` table (plan section 3.7).
 *
 * ## Design
 *
 * - **Ids are seqs.** Every data event's [ApiEvent.id] is the `seq` of the row it projects: monotonic, durable
 *   across restarts, and shared by every process writing the same database. The `/mcp` channel's `EventStore`
 *   is a separate namespace; clients must not mix `Last-Event-ID` values across the two.
 * - **Tail.** One tail cursor (`tailSeq`) per bus. A local commit ([committed], the [EventCommitListener] the
 *   recorder signals once a unit commits) fans its rows out synchronously when they follow the tail directly;
 *   otherwise (another process wrote in between) the tail is re-read from [source]. A 1 s poll ([startTailer])
 *   picks up other processes' writes while any subscriber is connected.
 * - **Projection.** Only the 3.x event types stream ([project]); every other row (rejections, leases, config,
 *   plan documents) stays table-only.
 * - **Per-root fan-out.** A subscriber with root ids receives only rows whose `root_id` is in its set; an empty
 *   set receives everything. Every row has a root, so nothing is ever broadcast by accident; only control events
 *   (`sync.lost`, `auth.expired`) published with an empty root set reach every subscriber.
 * - **Replay.** A resuming subscriber replays rows `seq > Last-Event-ID` from the table (root-filtered), then
 *   streams live, deduplicated by seq. See [replayGapSentinel] for the gap rules.
 * - **Backpressure.** Each subscriber has a bounded per-connection [Channel]. When full, the oldest event is
 *   dropped and a [ApiEventType.SYNC_LOST] ([SyncLostReason.QUEUE_OVERFLOW]) sentinel is queued.
 *
 * @param bufferSize The replay window (`API_SSE_BUFFER_SIZE`, default 1000): a resume further than this many
 *   seqs behind the newest row gets [SyncLostReason.BUFFER_EVICTED] and replays only the window. `0` means no
 *   replay: every resume behind the newest row is evicted. Negative values are treated as 0.
 * @param connectionQueueSize Per-connection bounded queue capacity (default 256).
 * @param source The event log this bus projects. Null only for a bus that is never subscribed to.
 */
class ApiEventBus(
    private val bufferSize: Int = System.getenv("API_SSE_BUFFER_SIZE")?.toIntOrNull() ?: 1000,
    private val connectionQueueSize: Int = 256,
    source: EventStore? = null,
) : EventCommitListener {
    private val logger = LoggerFactory.getLogger(ApiEventBus::class.java)

    /** The event log this bus projects; settable once for wiring that builds the bus before the store. */
    @Volatile
    internal var source: EventStore? = source

    /** Replay window in seqs; 0 = no replay. */
    private val replayWindow: Long = bufferSize.coerceAtLeast(0).toLong()

    /** Serializes tail advancement, fan-out and the replay snapshot. */
    private val tailMutex = Mutex()

    /** Highest seq fanned out (or skipped with no subscriber connected); null until first known. */
    @Volatile
    private var tailSeq: Long? = null

    /** True while a [startTailer] poll is running: [committed] may then hand a busy or gapped tail to it. */
    @Volatile
    private var tailerRunning = false

    /** Wakes the tailer at once (conflated): sent by [committed] when it defers the tail to the poll. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Active subscribers: subscriber-id to Subscriber. */
    private val subscribers = ConcurrentHashMap<String, Subscriber>()

    private data class Subscriber(
        val id: String,
        /** Root UUIDs this subscriber is interested in. Empty = interested in ALL roots. */
        val rootIds: Set<UUID>,
        val channel: Channel<ApiEvent>,
    )

    // -------------------------------------------------------------------------
    // Publish (live fan-out)
    // -------------------------------------------------------------------------

    /**
     * Fans [event] out to every subscriber whose root filter admits [affectedRoots]: a subscriber with no root
     * filter receives everything; a root-scoped one receives the event when its set intersects [affectedRoots].
     * An empty [affectedRoots] is a bus-level broadcast and is reserved for control events
     * ([ApiEventType.SYNC_LOST], [ApiEventType.AUTH_EXPIRED]); a projected row always carries its root.
     *
     * This is the live delivery step only: it does not store anything, so nothing published here is replayable.
     */
    fun publish(
        event: ApiEvent,
        affectedRoots: Set<UUID> = emptySet(),
    ) {
        for (sub in subscribers.values) {
            val interested =
                sub.rootIds.isEmpty() ||
                    affectedRoots.isEmpty() ||
                    sub.rootIds.intersect(affectedRoots).isNotEmpty()
            if (!interested) continue
            deliver(sub, event)
        }
    }

    private fun deliver(
        sub: Subscriber,
        event: ApiEvent,
    ) {
        if (sub.channel.trySend(event).isSuccess) return
        // Queue full: drop the oldest queued event and tell the client. The sentinel's id sits just below the
        // dropped event, so a reconnect with it as Last-Event-ID replays from the hole.
        logger.debug("Subscriber {} queue full, dropping oldest event", sub.id)
        val dropped = sub.channel.tryReceive().getOrNull()
        val sentinelId = dropped?.id?.minus(1) ?: (event.id - 1)
        sub.channel.trySend(ApiEvent(id = sentinelId, event = ApiEventType.SYNC_LOST, reason = SyncLostReason.QUEUE_OVERFLOW))
        sub.channel.trySend(event)
    }

    /**
     * Builds a CONTROL event (`auth.expired`, `sync.lost`). It carries the current tail seq as its id, so a client
     * that reconnects with it loses nothing that was already delivered. Data events are never built here: their
     * id is the seq of the row they project.
     */
    fun buildEvent(
        eventType: String,
        itemId: UUID? = null,
        modifiedAt: Instant? = null,
        newRole: String? = null,
        reason: String? = null,
        actor: ActorClaim? = null,
        rootId: UUID? = null,
    ): ApiEvent =
        ApiEvent(
            id = tailSeq ?: EventStore.SEQ_FLOOR,
            event = eventType,
            itemId = itemId?.toString(),
            modifiedAt = modifiedAt?.toString(),
            newRole = newRole,
            reason = reason,
            // Only id/kind/parent leave the process; proof is never copied into an event.
            actor = actor?.let { ActorClaimDto(id = it.id, kind = it.kind.toJsonString(), parent = it.parent) },
            rootId = rootId?.toString(),
        )

    // -------------------------------------------------------------------------
    // Tail
    // -------------------------------------------------------------------------

    /**
     * The local commit signal: [records] were appended by a unit that has now committed. Rows that directly follow
     * the tail are fanned out as given; a gap (another process committed in between) re-reads the tail from
     * [source], so nothing is skipped or delivered twice.
     *
     * This runs in the writer's afterCommit hook, so with a tailer running ([startTailer]) it never waits on the
     * tail lock nor reads the table: when the lock is busy (a replay snapshot is being read) or the rows do not
     * follow the tail, it wakes the tailer, which re-reads the tail from [source] (the rows are durable, so
     * deferring loses nothing). Without a tailer (tests) it takes the lock and catches up inline.
     *
     * Never throws: a fan-out failure is logged at WARN and swallowed, so it can never reach the write that
     * committed; the poll re-reads the tail from [source] (the table is the source of truth).
     */
    override suspend fun committed(records: List<EventRecord>) {
        try {
            committedUnguarded(records)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("SSE fan-out of {} committed event row(s) failed; the poll will retry: {}", records.size, e.message)
        }
    }

    private suspend fun committedUnguarded(records: List<EventRecord>) {
        if (records.isEmpty()) return
        if (tailerRunning && source != null && tailSeq != null) {
            if (!tailMutex.tryLock()) {
                wake.trySend(Unit)
                return
            }
            try {
                val tail = tailSeq
                val first = records.first().seq
                val last = records.last().seq
                when {
                    tail == null -> wake.trySend(Unit)
                    subscribers.isEmpty() -> tailSeq = maxOf(tail, last)
                    first == tail + 1 -> {
                        fanOut(records)
                        tailSeq = last
                    }
                    last <= tail -> Unit
                    else -> wake.trySend(Unit)
                }
            } finally {
                tailMutex.unlock()
            }
            return
        }
        tailMutex.withLock {
            val first = records.first().seq
            val last = records.last().seq
            val tail = tailSeq
            when {
                subscribers.isEmpty() -> tailSeq = maxOf(tail ?: last, last)
                tail == null || first == tail + 1 -> {
                    fanOut(records)
                    tailSeq = last
                }
                last <= tail -> Unit
                else -> {
                    val src = source
                    if (src != null) {
                        pumpLocked(src, tail)
                    } else {
                        fanOut(records.filter { it.seq > tail })
                        tailSeq = last
                    }
                }
            }
        }
    }

    /** Reads every row after the tail from [source] and fans it out (the poll path). */
    suspend fun pump() {
        val src = source ?: return
        tailMutex.withLock {
            val tail = tailSeq
            if (tail == null) tailSeq = src.maxSeq() else pumpLocked(src, tail)
        }
    }

    private suspend fun pumpLocked(
        src: EventStore,
        from: Long,
    ) {
        var cursor = from
        while (true) {
            val page = src.readAfter(cursor, null, EventStore.DEFAULT_PAGE)
            if (page.isEmpty()) break
            if (subscribers.isNotEmpty()) fanOut(page)
            cursor = page.last().seq
            tailSeq = cursor
            if (page.size < EventStore.DEFAULT_PAGE) break
        }
    }

    private fun fanOut(records: List<EventRecord>) {
        for (record in records) {
            val event = project(record) ?: continue
            publish(event, setOf(record.rootId))
        }
    }

    /**
     * Launches the cross-process poll in [scope]: every [pollInterval], while at least one subscriber is
     * connected, rows committed by other processes are read and fanned out. Cancel the returned job (or the
     * scope) to stop it.
     */
    fun startTailer(
        scope: CoroutineScope,
        pollInterval: Duration = 1.seconds,
    ): Job =
        scope
            .launch {
                tailerRunning = true
                while (isActive) {
                    // A deferred commit ([committed]) wakes the poll at once; otherwise it ticks every pollInterval.
                    withTimeoutOrNull(pollInterval) { wake.receive() }
                    if (subscribers.isEmpty()) continue
                    try {
                        pump()
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        logger.warn("SSE tail poll failed; retrying on the next tick: {}", e.message)
                    }
                }
            }.also { job -> job.invokeOnCompletion { tailerRunning = false } }

    // -------------------------------------------------------------------------
    // Subscribe
    // -------------------------------------------------------------------------

    /**
     * Subscribe to events for the given [rootIds].
     *
     * The subscriber is registered at call time, so every row committed after this call reaches it, even before
     * the returned [Flow] is collected. The caller must call [unsubscribe] in a `finally` block when the SSE
     * connection closes (collecting to completion also unsubscribes).
     *
     * When a resume was requested, the flow first emits the [ApiEventType.SYNC_LOST] sentinel when the cursor is
     * not replayable ([replayGapSentinel]), then replays the projected rows after the cursor (or after the
     * sentinel's id) from the table, root-filtered, then streams live events. Replay and live are deduplicated by
     * seq: no row is emitted twice and none is skipped.
     *
     * @param subscriberId Stable identifier for this connection (used for cleanup).
     * @param rootIds Root UUIDs to subscribe to. Empty = subscribe to all events, i.e. an UNRESTRICTED
     *   subscription; callers must never pass an empty set for a root-scoped principal whose effective root set
     *   came out empty (that is a denial, not a wildcard); `GET /api/v1/events` rejects that case with 403 first.
     * @param lastEventId The resume cursor (a seq), or null.
     * @param resumeRequested Whether the client actually asked to resume. Defaults to `lastEventId != null`.
     *   Transports pass `true` with a null [lastEventId] when a resume cursor was present but unparsable, so the
     *   gap is reported as [SyncLostReason.UNKNOWN_EVENT_ID] rather than treated as a fresh connection.
     */
    fun subscribe(
        subscriberId: String,
        rootIds: Set<UUID>,
        lastEventId: Long? = null,
        resumeRequested: Boolean = lastEventId != null,
    ): Flow<ApiEvent> = stream(register(subscriberId, rootIds), lastEventId, resumeRequested, startSeq = null)

    /**
     * [subscribe] for a connection that starts NOW (what `GET /api/v1/events` uses). Under the tail lock it first
     * catches the tail up to the newest committed row WITHOUT handing the backlog to the new subscriber (rows other
     * processes committed while nobody listened go to the existing subscribers only, or nowhere), then registers
     * it. So a fresh connection never receives rows committed before it connected, nor a `queue_overflow` caused
     * by them; a resume replays them from the table instead. Without a [source] it is [subscribe].
     */
    suspend fun connect(
        subscriberId: String,
        rootIds: Set<UUID>,
        lastEventId: Long? = null,
        resumeRequested: Boolean = lastEventId != null,
    ): Flow<ApiEvent> {
        val src = source ?: return subscribe(subscriberId, rootIds, lastEventId, resumeRequested)
        val (sub, start) =
            tailMutex.withLock {
                val tail = tailSeq
                // With subscribers connected the tail advances only to what the pump READ and fanned out: a separate
                // newest-seq read could jump over a row committed between the two reads, losing it for them.
                // With none (or no known tail) nobody can miss a row, so the newest committed seq is the start.
                val caughtUp =
                    if (tail != null && subscribers.isNotEmpty()) {
                        pumpLocked(src, tail)
                        tailSeq ?: tail
                    } else {
                        maxOf(tail ?: Long.MIN_VALUE, src.maxSeq())
                    }
                tailSeq = caughtUp
                register(subscriberId, rootIds) to caughtUp
            }
        return stream(sub, lastEventId, resumeRequested, startSeq = start)
    }

    private fun register(
        subscriberId: String,
        rootIds: Set<UUID>,
    ): Subscriber {
        val channel = Channel<ApiEvent>(capacity = connectionQueueSize)
        val sub = Subscriber(id = subscriberId, rootIds = rootIds, channel = channel)
        subscribers[subscriberId] = sub
        return sub
    }

    /**
     * The flow of one registered subscriber. [startSeq], when known, is the tail at registration: a fresh
     * connection skips rows at or below it, and a `queue_overflow` sentinel whose dropped event lies at or below
     * what was already covered (replayed, or [startSeq]) lost nothing new and is not emitted.
     */
    private fun stream(
        sub: Subscriber,
        lastEventId: Long?,
        resumeRequested: Boolean,
        startSeq: Long?,
    ): Flow<ApiEvent> {
        val subscriberId = sub.id
        val rootIds = sub.rootIds
        val channel = sub.channel
        return flow {
            try {
                var lastEmitted = Long.MIN_VALUE
                // Rows at or below this seq were already covered (replayed, or committed before a fresh connect).
                var covered = startSeq ?: Long.MIN_VALUE
                if (resumeRequested) {
                    val (sentinel, from, replay) = replaySnapshot(rootIds, lastEventId)
                    // Nothing at or below the replay start may follow it (a lagging live delivery would).
                    lastEmitted = from
                    if (sentinel != null) {
                        logger.debug(
                            "Subscriber {} resumed with unreplayable Last-Event-ID {} (reason={})",
                            subscriberId,
                            lastEventId,
                            sentinel.reason,
                        )
                        emit(sentinel)
                    }
                    for (event in replay) {
                        emit(event)
                        lastEmitted = event.id
                    }
                    covered = maxOf(covered, lastEmitted)
                } else {
                    if (startSeq != null) lastEmitted = startSeq
                    val src = source
                    if (src != null && tailSeq == null) {
                        tailMutex.withLock { if (tailSeq == null) tailSeq = src.maxSeq() }
                    }
                }

                for (evt in channel) {
                    if (evt.event in ApiEventType.CONTROL_EVENTS) {
                        // The sentinel's id is one below the dropped event: a drop inside the covered range was a
                        // duplicate the dedupe below would have discarded anyway, so nothing new was lost.
                        val duplicateDrop =
                            evt.event == ApiEventType.SYNC_LOST && evt.reason == SyncLostReason.QUEUE_OVERFLOW && evt.id < covered
                        if (!duplicateDrop) emit(evt)
                    } else if (evt.id > lastEmitted) {
                        emit(evt)
                        lastEmitted = evt.id
                    }
                }
            } finally {
                unsubscribe(subscriberId)
            }
        }
    }

    /**
     * Under the tail lock: catches the tail up, decides the gap sentinel against the newest row, and reads the
     * replay (rows after the cursor, or after the sentinel's id, up to the tail). Rows after the tail reach the
     * already-registered subscriber live.
     */
    private suspend fun replaySnapshot(
        rootIds: Set<UUID>,
        lastEventId: Long?,
    ): Triple<ApiEvent?, Long, List<ApiEvent>> {
        val src = source
        return tailMutex.withLock {
            if (src == null) {
                val maxSeq = tailSeq ?: EventStore.SEQ_FLOOR
                val sentinel = replayGapSentinel(lastEventId, true, maxSeq)
                return@withLock Triple(sentinel, sentinel?.id ?: lastEventId ?: maxSeq, emptyList())
            }
            val tail = tailSeq
            if (tail == null) tailSeq = src.maxSeq() else pumpLocked(src, tail)
            val maxSeq = tailSeq ?: EventStore.SEQ_FLOOR
            val sentinel = replayGapSentinel(lastEventId, true, maxSeq)
            val from = sentinel?.id ?: lastEventId ?: maxSeq
            Triple(sentinel, from, readProjected(src, from, maxSeq, rootIds.takeIf { it.isNotEmpty() }))
        }
    }

    private suspend fun readProjected(
        src: EventStore,
        afterSeq: Long,
        upToSeq: Long,
        rootIds: Set<UUID>?,
    ): List<ApiEvent> {
        val out = mutableListOf<ApiEvent>()
        var cursor = afterSeq
        while (cursor < upToSeq) {
            val page = src.readAfter(cursor, rootIds, EventStore.DEFAULT_PAGE)
            if (page.isEmpty()) break
            for (record in page) {
                if (record.seq > upToSeq) return out
                project(record)?.let { out += it }
            }
            cursor = page.last().seq
            if (page.size < EventStore.DEFAULT_PAGE) break
        }
        return out
    }

    /**
     * Returns the [ApiEventType.SYNC_LOST] sentinel a resuming subscriber must be sent before its replay, or null
     * when the cursor is replayable (or no resume was requested).
     *
     * ## Gap rules (against [maxSeq], the newest committed seq; floor = [EventStore.SEQ_FLOOR])
     * - [resumeRequested] false: null.
     * - cursor unparsable (null), above [maxSeq], or below the floor: [SyncLostReason.UNKNOWN_EVENT_ID]. Below the
     *   floor covers every id the pre-table ring buffer issued; above [maxSeq] covers a cursor from another
     *   database.
     * - cursor more than the replay window behind [maxSeq]: [SyncLostReason.BUFFER_EVICTED].
     *
     * ## Id contract
     * `sentinel.id = max(floor, maxSeq - window)`: below every event replayed or streamed after it, so a client
     * that reconnects with the sentinel's id gets no sentinel and the whole window. The sentinel is never
     * published to other subscribers. The floor itself is a valid cursor ("from the start"), which is what makes
     * the sentinel id reconnectable on a short table.
     */
    internal fun replayGapSentinel(
        lastEventId: Long?,
        resumeRequested: Boolean,
        maxSeq: Long,
    ): ApiEvent? {
        if (!resumeRequested) return null
        val floor = EventStore.SEQ_FLOOR
        val reason =
            when {
                lastEventId == null -> SyncLostReason.UNKNOWN_EVENT_ID
                lastEventId > maxSeq -> SyncLostReason.UNKNOWN_EVENT_ID
                lastEventId < floor -> SyncLostReason.UNKNOWN_EVENT_ID
                maxSeq - lastEventId > replayWindow -> SyncLostReason.BUFFER_EVICTED
                else -> null
            } ?: return null
        return ApiEvent(
            id = maxOf(floor, maxSeq - replayWindow),
            event = ApiEventType.SYNC_LOST,
            reason = reason,
        )
    }

    /**
     * Remove the subscriber with [subscriberId] and close its channel.
     * Safe to call multiple times (idempotent).
     */
    fun unsubscribe(subscriberId: String) {
        val sub = subscribers.remove(subscriberId)
        sub?.channel?.close()
    }

    /** Returns the current number of active subscribers (for testing/monitoring). */
    fun subscriberCount(): Int = subscribers.size

    /**
     * Every COMMITTED row after [afterSeq] projected exactly as the stream would carry it (table-only types left
     * out), in seq order, optionally root-filtered. Read on a fresh coroutine, outside any ambient unit of work, so
     * a caller inside a unit sees what other connections see, never its own uncommitted rows. For tests and
     * diagnostics; reads the whole log, so not for request paths.
     */
    suspend fun projectedEvents(
        afterSeq: Long = 0,
        rootIds: Set<UUID>? = null,
    ): List<ApiEvent> {
        val src = source ?: return emptyList()
        return CoroutineScope(Dispatchers.IO).async { readProjected(src, afterSeq, Long.MAX_VALUE, rootIds) }.await()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The SSE projection of one row, or null for a table-only type. 3.x names stream unchanged;
         * `item.transitioned` streams as `item.advanced` (newRole = toRole), `item.reparented` as `scope.left` /
         * `scope.entered` by side, `claim.acquired` / `claim.released` as `item.updated`.
         */
        fun project(record: EventRecord): ApiEvent? {
            val data = parseData(record.data)
            val str = { key: String -> data?.get(key)?.jsonPrimitive?.contentOrNull }
            var newRole: String? = null
            var itemId: String = record.entityId.toString()
            val type =
                when (record.type) {
                    DomainEvent.ITEM_CREATED -> ApiEventType.ITEM_CREATED
                    DomainEvent.ITEM_UPDATED -> ApiEventType.ITEM_UPDATED
                    DomainEvent.ITEM_DELETED -> ApiEventType.ITEM_DELETED
                    DomainEvent.ITEM_TRANSITIONED -> {
                        newRole = str("toRole")
                        ApiEventType.ITEM_ADVANCED
                    }
                    DomainEvent.ITEM_REPARENTED ->
                        if (str("side") == "left") ApiEventType.SCOPE_LEFT else ApiEventType.SCOPE_ENTERED
                    DomainEvent.CLAIM_ACQUIRED, DomainEvent.CLAIM_RELEASED -> ApiEventType.ITEM_UPDATED
                    DomainEvent.NOTE_UPSERTED, DomainEvent.NOTE_DELETED -> {
                        itemId = str("itemId") ?: itemId
                        record.type
                    }
                    DomainEvent.DEPENDENCY_ADDED, DomainEvent.DEPENDENCY_REMOVED -> {
                        itemId = str("fromItemId") ?: itemId
                        record.type
                    }
                    else -> return null
                }
            val actor =
                record.principalId?.let { id ->
                    ActorClaimDto(id = id, kind = record.principalKind ?: "external", parent = str("actorParent"))
                }
            return ApiEvent(
                id = record.seq,
                event = type,
                itemId = itemId,
                modifiedAt = record.occurredAt.toString(),
                newRole = newRole,
                actor = actor,
                rootId = record.rootId.toString(),
            )
        }

        private fun parseData(data: String): JsonObject? =
            try {
                json.parseToJsonElement(data).jsonObject
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                null
            }
    }
}
