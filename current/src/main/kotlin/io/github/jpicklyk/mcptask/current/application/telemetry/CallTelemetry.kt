package io.github.jpicklyk.mcptask.current.application.telemetry

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.proofWire
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Per-call telemetry carried in the coroutine context for the life of one MCP tool call or one REST request.
 *
 * The transport adapter installs one fresh instance per call ([reqId], [surface], [sessionId], [host]); code deeper in the
 * call fills the accumulators (principal, BUSY retries, replay, target versions, result counts), and the adapter
 * reads them once when it builds the `call_log` row. Every accumulator is thread-safe: a call may fan out.
 *
 * Every recording helper ([recordPrincipal], [recordRetry], ...) is a no-op when no element is installed (direct tool
 * tests), so instrumented code never needs to check.
 */
class CallTelemetry(
    val reqId: String,
    val surface: String,
    val sessionId: String?,
    val host: String? = null
) : AbstractCoroutineContextElement(Key) {
    private val principalRef = AtomicReference<Principal?>(null)
    private val retryCount = AtomicInteger(0)
    private val replayedFlag = AtomicBoolean(false)
    private val versions = ConcurrentHashMap<UUID, Long>()
    private val resultCountRef = AtomicReference<Int?>(null)
    private val eligibleCountRef = AtomicReference<Int?>(null)
    private val searchShapeRef = AtomicReference<SearchShape?>(null)
    private val relatedSignalsRef = AtomicReference<Map<String, Int>?>(null)
    private val hitSignalsRef = AtomicReference<List<HitSignals>?>(null)
    private val rootState = AtomicReference(RootState())
    private val runRef = AtomicReference<RunStamp?>(null)
    private val fieldsRef = AtomicReference<List<String>?>(null)

    private data class RootState(
        val root: UUID? = null,
        val multi: Boolean = false
    )

    /** The run and seat a call was made under; set as one atomic pair. */
    data class RunStamp(
        val runId: UUID,
        val seat: String?
    )

    /**
     * One returned hit of a `related` call and the signals that found it.
     *
     * @property id The hit's id.
     * @property kind `item` or `document`.
     * @property signals The signal wire names whose ranked list holds the hit, in signal order (edges, links, tags, text).
     */
    data class HitSignals(
        val id: UUID,
        val kind: String,
        val signals: List<String>
    )

    /** The returned page of a `related` call, hit by hit, with the signals that found each; null when none was recorded. */
    val hitSignals: List<HitSignals>? get() = hitSignalsRef.get()

    /** First recorded hit signals win. */
    fun setHitSignals(hits: List<HitSignals>) {
        hitSignalsRef.compareAndSet(null, hits.toList())
    }

    /**
     * Per signal (`edges`, `links`, `tags`, `text`), the number of hits on the returned page of a `related` call that
     * signal contributed to (zeros kept); null when the call ran no `related` read.
     */
    val relatedSignals: Map<String, Int>? get() = relatedSignalsRef.get()

    /** First recorded related-signal counts win. */
    fun setRelatedSignals(counts: Map<String, Int>) {
        relatedSignalsRef.compareAndSet(null, counts.toMap())
    }

    /**
     * The single root this call read (target-version reads) or wrote (events); null when none was recorded or when
     * the call touched more than one distinct root ([multiRoot]).
     */
    val rootId: UUID? get() = rootState.get().takeUnless { it.multi }?.root

    /** True once the call recorded two distinct roots; sticky. */
    val multiRoot: Boolean get() = rootState.get().multi

    /** First root wins; a second distinct root makes the call [multiRoot] for good. */
    fun setRoot(rootId: UUID) {
        rootState.updateAndGet { s ->
            when {
                s.multi -> s
                s.root == null -> RootState(rootId, false)
                s.root == rootId -> s
                else -> RootState(s.root, true)
            }
        }
    }

    /** The run id of this call, as first recorded; null when never called. */
    val runId: UUID? get() = runRef.get()?.runId

    /** The seat of this call, as first recorded with the run id; null when none. */
    val seat: String? get() = runRef.get()?.seat

    /** First recorded run (id and seat together) wins. */
    fun setRun(
        runId: UUID,
        seat: String?
    ) {
        runRef.compareAndSet(null, RunStamp(runId, seat))
    }

    /** Sorted distinct `fields` names an `items_read` select accepted; null when none were recorded. */
    val fields: List<String>? get() = fieldsRef.get()

    /** First recorded fields win; an empty collection records nothing. */
    fun setFields(names: Collection<String>) {
        if (names.isEmpty()) return
        fieldsRef.compareAndSet(null, names.toSortedSet().toList())
    }

    /** Who made the call, as first recorded: the claim's id, the credential's PrincipalKind wire value (human|agent|service) and the proof status wire value. */
    data class Principal(
        val id: String,
        val kind: String,
        val proofStatus: String?
    )

    /**
     * What a search call recorded about its query for `request_shape`: a hash of the normalized terms, the term
     * count and the match mode, plus the hit mix once the search has run. Never the query text.
     *
     * @property hitsByKind Hits per searched kind in the capped result list (zeros kept, one key per searched kind);
     *   null until the search ran.
     */
    data class SearchShape(
        val queryHash: String,
        val termCount: Int,
        val matchMode: String,
        val hitsByKind: Map<String, Int>? = null
    )

    val principal: Principal? get() = principalRef.get()

    /** BUSY retries across every unit of the call. */
    val retries: Int get() = retryCount.get()

    val replayed: Boolean get() = replayedFlag.get()

    /** Item id to the version the call observed, in no particular order. */
    val targetVersions: Map<UUID, Long> get() = versions.toMap()

    val resultCount: Int? get() = resultCountRef.get()
    val eligibleCount: Int? get() = eligibleCountRef.get()

    /** The search shape of this call, or null when it ran no search. */
    val searchShape: SearchShape? get() = searchShapeRef.get()

    /** First recorded principal wins. */
    fun setPrincipal(principal: Principal) {
        principalRef.compareAndSet(null, principal)
    }

    fun incrementRetry() {
        retryCount.incrementAndGet()
    }

    fun setReplayed() {
        replayedFlag.set(true)
    }

    fun setTargetVersion(
        id: UUID,
        version: Long
    ) {
        versions.putIfAbsent(id, version)
    }

    fun setResultCounts(
        result: Int?,
        eligible: Int?
    ) {
        if (result != null) resultCountRef.set(result)
        if (eligible != null) eligibleCountRef.set(eligible)
    }

    /** First recorded search shape wins. */
    fun setSearchShape(shape: SearchShape) {
        searchShapeRef.compareAndSet(null, shape)
    }

    /** Adds the per-kind hit counts to the recorded search shape; a no-op when no shape was recorded. */
    fun setHitsByKind(counts: Map<String, Int>) {
        searchShapeRef.updateAndGet { it?.copy(hitsByKind = counts) }
    }

    companion object Key : CoroutineContext.Key<CallTelemetry>
}

/** The telemetry of the call this coroutine runs in, or null outside one. */
suspend fun currentCallTelemetry(): CallTelemetry? = coroutineContext[CallTelemetry]

/** The reqId of the call this coroutine runs in, or null outside one. */
suspend fun currentReqId(): String? = coroutineContext[CallTelemetry]?.reqId

suspend fun recordCallPrincipal(
    claim: ActorClaim,
    verification: VerificationResult,
    proofStatus: String? = null,
    principalKind: String? = null
) {
    // The proof vocabulary: the transport principal's own proof when the caller has one (it alone knows
    // `verified_stale`), else the stored verification read through the one mapping.
    currentCallTelemetry()?.setPrincipal(
        CallTelemetry.Principal(
            claim.id,
            // The credential's PrincipalKind wire; the claim kind (always `external`) only for a hand-built principal.
            principalKind ?: claim.kind.toJsonString(),
            proofStatus ?: verification.proofWire()
        )
    )
}

/** Notes one BUSY retry of a unit. */
suspend fun recordCallRetry() {
    currentCallTelemetry()?.incrementRetry()
}

/** Marks the call as served (at least in part) from a stored idempotency record. */
suspend fun recordCallReplayed() {
    currentCallTelemetry()?.setReplayed()
}

suspend fun recordCallTargetVersion(
    id: UUID,
    version: Long
) {
    currentCallTelemetry()?.setTargetVersion(id, version)
}

suspend fun recordCallResultCounts(
    result: Int?,
    eligible: Int?
) {
    currentCallTelemetry()?.setResultCounts(result, eligible)
}

/** Records the root a call read or wrote; a no-op outside a call or for a null root. */
suspend fun recordCallRoot(rootId: UUID?) {
    if (rootId != null) currentCallTelemetry()?.setRoot(rootId)
}

/** Records the `fields` names an `items_read` select accepted; a no-op outside a call or for an empty collection. */
suspend fun recordCallFields(names: Collection<String>) {
    currentCallTelemetry()?.setFields(names)
}

/** Records the run and seat of a call; a no-op outside a call. */
suspend fun recordCallRun(
    runId: UUID,
    seat: String?
) {
    currentCallTelemetry()?.setRun(runId, seat)
}

/** Records the query shape of a search call (hash, term count, match mode); a no-op outside a call. */
suspend fun recordCallSearchShape(
    queryHash: String,
    termCount: Int,
    matchMode: String
) {
    currentCallTelemetry()?.setSearchShape(CallTelemetry.SearchShape(queryHash, termCount, matchMode))
}

/** Records, per related signal, how many returned-page hits it contributed to; a no-op outside a call. */
suspend fun recordCallRelatedSignals(counts: Map<String, Int>) {
    currentCallTelemetry()?.setRelatedSignals(counts)
}

/** Records the signals that found each returned hit of a `related` call, in page order; a no-op outside a call. */
suspend fun recordCallHitSignals(hits: List<CallTelemetry.HitSignals>) {
    currentCallTelemetry()?.setHitSignals(hits)
}

/** Records how many hits of each searched kind the search returned (page-invariant); a no-op outside a call. */
suspend fun recordCallSearchHitMix(hitsByKind: Map<String, Int>) {
    currentCallTelemetry()?.setHitsByKind(hitsByKind)
}
