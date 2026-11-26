package io.github.jpicklyk.mcptask.current.lifecycle

import io.github.jpicklyk.mcptask.current.claim.ClaimStore
import io.github.jpicklyk.mcptask.current.claim.LeaseStore
import io.github.jpicklyk.mcptask.current.dependency.DependencyStoreProvider
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequest
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.model.run.Run
import io.github.jpicklyk.mcptask.current.note.NoteStoreProvider
import java.util.UUID

/**
 * The stores the lifecycle slice reads: work items, dependencies, notes, claims and resource leases, plus the read-only
 * views of decisions and runs a transition and the item context check. [RepositoryProvider] implements it.
 */
interface LifecycleStoreProvider :
    DependencyStoreProvider,
    NoteStoreProvider {
    fun resourceLeaseRepository(): LeaseStore

    fun claimStore(): ClaimStore

    fun decisionReads(): DecisionReads

    fun runReads(): RunReads
}

/** The reads of decision requests the lifecycle slice makes; the run slice's [DecisionRequestStore] implements them. */
interface DecisionReads {
    /** The decision request [id], or null when there is none. */
    suspend fun getById(id: UUID): DecisionRequest?

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

    /** The requests whose id is in [ids] (one batched read; ids with no row are absent), in no particular order. */
    suspend fun findByIds(ids: Set<UUID>): List<DecisionRequest>

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

/** The reads of runs the lifecycle slice makes; the run slice's [RunStore] implements them. */
interface RunReads {
    /** The run [id], or null when there is none. */
    suspend fun getById(id: UUID): Run?
}
