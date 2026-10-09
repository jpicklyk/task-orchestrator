package io.github.jpicklyk.mcptask.current.application.telemetry

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
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
 * The transport adapter installs one fresh instance per call ([reqId], [surface], [sessionId]); code deeper in the
 * call fills the accumulators (principal, BUSY retries, replay, target versions, result counts), and the adapter
 * reads them once when it builds the `call_log` row. Every accumulator is thread-safe: a call may fan out.
 *
 * Every recording helper ([recordPrincipal], [recordRetry], ...) is a no-op when no element is installed (direct tool
 * tests), so instrumented code never needs to check.
 */
class CallTelemetry(
    val reqId: String,
    val surface: String,
    val sessionId: String?
) : AbstractCoroutineContextElement(Key) {
    private val principalRef = AtomicReference<Principal?>(null)
    private val retryCount = AtomicInteger(0)
    private val replayedFlag = AtomicBoolean(false)
    private val versions = ConcurrentHashMap<UUID, Long>()
    private val resultCountRef = AtomicReference<Int?>(null)
    private val eligibleCountRef = AtomicReference<Int?>(null)

    /** Who made the call, as first recorded: the claim's id and kind and the verification status wire value. */
    data class Principal(
        val id: String,
        val kind: String,
        val proofStatus: String?
    )

    val principal: Principal? get() = principalRef.get()

    /** BUSY retries across every unit of the call. */
    val retries: Int get() = retryCount.get()

    val replayed: Boolean get() = replayedFlag.get()

    /** Item id to the version the call observed, in no particular order. */
    val targetVersions: Map<UUID, Long> get() = versions.toMap()

    val resultCount: Int? get() = resultCountRef.get()
    val eligibleCount: Int? get() = eligibleCountRef.get()

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

    companion object Key : CoroutineContext.Key<CallTelemetry>
}

/** The telemetry of the call this coroutine runs in, or null outside one. */
suspend fun currentCallTelemetry(): CallTelemetry? = coroutineContext[CallTelemetry]

/** The reqId of the call this coroutine runs in, or null outside one. */
suspend fun currentReqId(): String? = coroutineContext[CallTelemetry]?.reqId

suspend fun recordCallPrincipal(
    claim: ActorClaim,
    verification: VerificationResult
) {
    currentCallTelemetry()?.setPrincipal(
        CallTelemetry.Principal(claim.id, claim.kind.toJsonString(), verification.status.toJsonString())
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
