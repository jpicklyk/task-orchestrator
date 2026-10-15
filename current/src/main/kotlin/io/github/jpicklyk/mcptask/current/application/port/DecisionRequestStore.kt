package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestStatus
import java.util.UUID

/**
 * Persistence of decision requests (`decision_requests`). Every method joins the caller's unit; the writes belong to
 * `DecisionService` only. A database failure is thrown.
 */
interface DecisionRequestStore {
    /** Inserts [decision] and returns it as stored. */
    suspend fun create(decision: DecisionRequest): DecisionRequest

    /** The decision request [id], or null when there is none. */
    suspend fun getById(id: UUID): DecisionRequest?

    /**
     * The request that is NOT withdrawn with this (root, scope key, dedupe key), or null. The partial unique index
     * guarantees there is at most one; a withdrawn request never matches.
     */
    suspend fun findByKey(
        rootId: UUID,
        scopeKey: String,
        dedupeKey: String
    ): DecisionRequest?

    /**
     * Writes the mutable fields of [decision] (status, acknowledgement, answer) when the stored version is still
     * [expectedVersion], bumping it to `expectedVersion + 1`. Returns the request as stored, or null when it is gone or
     * its version moved.
     */
    suspend fun update(
        decision: DecisionRequest,
        expectedVersion: Long
    ): DecisionRequest?

    /**
     * The requests matching every given filter, newest first (`raisedAt` descending, then `id` ascending): on a root in
     * [rootIds] (null means any root; an empty set matches nothing), of run [runId], about item [itemId], and in one of
     * [statuses] (null means any status).
     */
    suspend fun list(
        rootIds: Set<UUID>? = null,
        runId: UUID? = null,
        itemId: UUID? = null,
        statuses: Set<DecisionRequestStatus>? = null
    ): List<DecisionRequest>

    /** The number of requests of run [runId] still waiting for an answer (open or acknowledged). */
    suspend fun openCount(runId: UUID): Int

    /**
     * The ids of the requests whose id, as 32 lower-case hex digits without dashes, starts with [prefix] (compared
     * ignoring case), in ascending id order, at most [limit] of them. A prefix that is not hex, or longer than 32
     * digits, matches nothing.
     */
    suspend fun findIdsByPrefix(
        prefix: String,
        limit: Int = 2
    ): List<UUID>
}

/**
 * What the run service needs to know about a run's decision requests: how many still wait for an answer when the run
 * closes (`run.ended` `openDecisions`). [NONE] answers 0 for every run (a run service built without decisions, in tests).
 */
fun interface RunDecisionPort {
    /** The number of decision requests of [runId] that are open or acknowledged. Joins the caller's unit. */
    suspend fun openCount(runId: UUID): Int

    companion object {
        /** No decision requests exist. */
        val NONE: RunDecisionPort = RunDecisionPort { 0 }
    }
}
