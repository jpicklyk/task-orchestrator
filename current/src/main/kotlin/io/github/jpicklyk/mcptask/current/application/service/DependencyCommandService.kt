@file:OptIn(StoreWrite::class)

package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.StoreWrite
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.support.eventRootOf
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DeleteCause
import io.github.jpicklyk.mcptask.current.domain.graph.CycleDetector
import io.github.jpicklyk.mcptask.current.domain.graph.DependencyEdges
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import java.util.UUID

/**
 * The single owner of the dependency write policy, shared by `deps_write` and `items_write` `create_tree`.
 * JSON-free and transport-free: callers map the returned [DomainError] codes to their own wire shapes.
 *
 * Policy, in this order: normalization ([Dependency.normalized]: IS_BLOCKED_BY is stored as BLOCKS with its
 * ends swapped), duplicates within the request, duplicates against stored rows, then cycles over the
 * normalized blocking edges. Two edges are duplicates when they share the same normalized
 * (from, to, type), whatever their `unblockAt`; a restatement (BLOCKS b to a plus IS_BLOCKED_BY a to b)
 * is therefore a duplicate, never a cycle. A duplicate or a cycle rejects the whole request and stores
 * nothing. RELATES_TO never forms a cycle.
 *
 * Cycle detection loads the stored blocking edges reachable downstream of the request's blocked items
 * (a frontier walk over `findByItemIds`), inside the same write unit as the insert, then adds the
 * request's own earlier edges as it goes. A write called inside an ambient unit joins it.
 *
 * Every write records its own `events` rows through the unit's sink, in the same unit: `dependency.added` per stored
 * row and `dependency.removed` (cause `explicit`) per deleted row, under the from item's root ([eventRootOf]).
 *
 * Every write is fenced ([FenceGuard]) for its `caller`, first thing in its unit: an item the write names that carries
 * an active claim another holder holds is `not_claim_holder` and nothing is written. The named items are the endpoints
 * of every requested edge (create), both ends (delete by relationship), the stored edge's two endpoints (delete by
 * id: naming the edge names its items), and the item itself, never its counterparties (delete by item).
 */
class DependencyCommandService(
    private val repositoryProvider: RepositoryProvider,
    private val unitOfWork: UnitOfWork
) {
    /** Applies the write policy to [deps] and stores them in one write unit, returning the stored rows in request order. */
    suspend fun create(
        deps: List<Dependency>,
        caller: ClaimHolder?
    ): Outcome<List<Dependency>> = unitOfWork.write("DependencyCommandService.create") { createBody(deps, caller) }

    /**
     * The same policy as [create], for a caller that already holds a write unit (an atomic composite). It
     * joins the caller's unit: the caller's unit decides commit, and an [Outcome.Err] returned from the
     * caller's block rolls it back.
     */
    suspend fun createInUnit(
        deps: List<Dependency>,
        caller: ClaimHolder?
    ): Outcome<List<Dependency>> = unitOfWork.write("DependencyCommandService.createInUnit") { createBody(deps, caller) }

    private suspend fun WriteScope.createBody(
        deps: List<Dependency>,
        caller: ClaimHolder?
    ): Outcome<List<Dependency>> {
        if (deps.isEmpty()) return Outcome.Ok(emptyList())
        fence(deps.flatMap { listOf(it.fromItemId, it.toItemId) }, caller)?.let { return Outcome.Err(it) }
        val normalized =
            when (val checked = checkRequest(deps)) {
                is Outcome.Ok -> checked.value
                is Outcome.Err -> return checked
            }
        lifecycleFreeEndpoint(normalized)?.let { return Outcome.Err(it) }
        val depRepo = repositoryProvider.dependencyRepository()

        val stored = depRepo.findByItemIds(normalized.flatMapTo(LinkedHashSet()) { listOf(it.fromItemId, it.toItemId) })
        val storedKeys = HashMap<Triple<UUID, UUID, DependencyType>, Dependency>()
        for (row in stored.values.flatten()) {
            val n = row.normalized()
            storedKeys.putIfAbsent(Triple(n.fromItemId, n.toItemId, n.type), row)
        }
        for (dep in normalized) {
            val existing = storedKeys[Triple(dep.fromItemId, dep.toItemId, dep.type)] ?: continue
            return Outcome.Err(
                duplicate(
                    "A dependency of type ${dep.type} already exists between items ${dep.fromItemId} and ${dep.toItemId}",
                    existing.id.toString()
                )
            )
        }

        val blockingCandidates = normalized.filter { it.type == DependencyType.BLOCKS }
        if (blockingCandidates.isNotEmpty()) {
            val existingEdges =
                DependencyEdges
                    .normalize(loadDownstream(blockingCandidates.map { it.toItemId }.toSet()))
                    .blocking
                    .toMutableList()
            for (dep in blockingCandidates) {
                val candidate = DependencyEdges.toBlockingEdge(dep) ?: continue
                val path = CycleDetector.cycleIfAdded(existingEdges, candidate)
                if (path != null) {
                    return Outcome.Err(cycle("Creating these dependencies would result in a circular dependency chain", path))
                }
                existingEdges += candidate
            }
        }

        val created = depRepo.createBatch(normalized)
        events.record(created.map { dependencyAddedEvent(it, rootOfItem(it.fromItemId)) })
        return Outcome.Ok(created)
    }

    /**
     * Pure, store-free check for a set of edges between items that are not stored yet (a work tree):
     * normalizes, rejects duplicates within the set, then rejects a cycle among the blocking edges. Returns the
     * normalized edges in request order.
     */
    fun validateTreeEdges(deps: List<Dependency>): Outcome<List<Dependency>> {
        val normalized =
            when (val checked = checkRequest(deps)) {
                is Outcome.Ok -> checked.value
                is Outcome.Err -> return checked
            }
        val path = CycleDetector.findCycle(DependencyEdges.normalize(normalized).blocking)
        if (path != null) return Outcome.Err(cycle("Circular dependency detected", path))
        return Outcome.Ok(normalized)
    }

    /**
     * Deletes the dependencies from [fromItemId] to [toItemId], in one write unit, returning how many were removed.
     * [type] IS_BLOCKED_BY deletes the stored BLOCKS row from [toItemId] to [fromItemId]; another type deletes the
     * stored rows of that type from [fromItemId] to [toItemId]; null matches stored rows from [fromItemId] to
     * [toItemId] of any type only (a former IS_BLOCKED_BY stored the other way round is not matched).
     */
    suspend fun deleteByRelationship(
        fromItemId: UUID,
        toItemId: UUID,
        type: DependencyType?,
        caller: ClaimHolder?
    ): Outcome<Int> =
        unitOfWork.write("DependencyCommandService.deleteByRelationship") {
            fence(listOf(fromItemId, toItemId), caller)?.let { return@write Outcome.Err(it) }
            val depRepo = repositoryProvider.dependencyRepository()
            val matches =
                if (type == DependencyType.IS_BLOCKED_BY) {
                    depRepo.findByFromItemId(toItemId).filter { it.toItemId == fromItemId && it.type == DependencyType.BLOCKS }
                } else {
                    depRepo.findByFromItemId(fromItemId).filter { it.toItemId == toItemId && (type == null || it.type == type) }
                }
            var deleted = 0
            for (dep in matches) {
                if (depRepo.delete(dep.id)) {
                    deleted++
                    events.record(dependencyRemovedEvent(dep, rootOfItem(dep.fromItemId), DeleteCause.EXPLICIT))
                }
            }
            Outcome.Ok(deleted)
        }

    /** Deletes the dependency [id] in one write unit; `Ok(false)` when it does not exist. Fences its two endpoints. */
    suspend fun deleteById(
        id: UUID,
        caller: ClaimHolder?
    ): Outcome<Boolean> =
        unitOfWork.write("DependencyCommandService.deleteById") {
            val depRepo = repositoryProvider.dependencyRepository()
            val dep = depRepo.findById(id)
            if (dep != null) fence(listOf(dep.fromItemId, dep.toItemId), caller)?.let { return@write Outcome.Err(it) }
            val deleted = depRepo.delete(id)
            if (deleted && dep != null) events.record(dependencyRemovedEvent(dep, rootOfItem(dep.fromItemId), DeleteCause.EXPLICIT))
            Outcome.Ok(deleted)
        }

    /**
     * Deletes every dependency touching [itemId] (either end) in one write unit, returning how many were removed. Fences
     * [itemId] only: the counterparties of its edges are not named by the request.
     */
    suspend fun deleteByItemId(
        itemId: UUID,
        caller: ClaimHolder?
    ): Outcome<Int> =
        unitOfWork.write("DependencyCommandService.deleteByItemId") {
            fence(listOf(itemId), caller)?.let { return@write Outcome.Err(it) }
            val depRepo = repositoryProvider.dependencyRepository()
            val edges = depRepo.findByItemId(itemId)
            val count = depRepo.deleteByItemId(itemId)
            if (count > 0) events.record(edges.map { dependencyRemovedEvent(it, rootOfItem(it.fromItemId), DeleteCause.EXPLICIT) })
            Outcome.Ok(count)
        }

    /**
     * The `not_claim_holder` of the first of [itemIds] (in the given order, each id once) whose row carries an active
     * claim [caller] does not hold, or null. An id with no row is not judged here (the write's own policy answers it).
     */
    private suspend fun WriteScope.fence(
        itemIds: List<UUID>,
        caller: ClaimHolder?
    ): DomainError? {
        val repo = repositoryProvider.workItemRepository()
        for (id in LinkedHashSet(itemIds)) {
            val item = repo.getById(id) ?: continue
            val checked = FenceGuard.check(item, caller, now)
            if (checked is Outcome.Err) return checked.error
        }
        return null
    }

    /** The root of the item [itemId] as it is NOW ([eventRootOf]; its own id when it cannot be resolved). */
    private suspend fun rootOfItem(itemId: UUID): UUID {
        val repo = repositoryProvider.workItemRepository()
        val item = repo.getById(itemId) ?: return eventRootOf(itemId, repo)
        return eventRootOf(item, repo)
    }

    /**
     * The `invalid_request` for the first BLOCKS edge of [normalized] (storage form: an IS_BLOCKED_BY input is already
     * a BLOCKS edge) with a lifecycle-free endpoint, or null. A lifecycle-free item has no role a blocker could be
     * satisfied by, so it can neither block nor be blocked; RELATES_TO edges are unaffected. The violation names the
     * endpoint field and carries the endpoint's id. An id with no row is left to the store (not judged here).
     */
    private suspend fun lifecycleFreeEndpoint(normalized: List<Dependency>): DomainError? {
        val blocking = normalized.filter { it.type == DependencyType.BLOCKS }
        if (blocking.isEmpty()) return null
        val roles = repositoryProvider.workItemRepository().rolesOf(blocking.flatMapTo(HashSet()) { listOf(it.fromItemId, it.toItemId) })

        fun lifecycleFree(id: UUID): Boolean = roles.containsKey(id) && roles[id] == null
        for (dep in blocking) {
            val (field, id) =
                when {
                    lifecycleFree(dep.fromItemId) -> FIELD_FROM to dep.fromItemId
                    lifecycleFree(dep.toItemId) -> FIELD_TO to dep.toItemId
                    else -> continue
                }
            val reason = Dependency.LIFECYCLE_FREE_ENDPOINT_REASON
            return DomainError(
                code = ErrorCode.INVALID_REQUEST,
                message = "Item $id is lifecycle-free and cannot be an endpoint of a BLOCKS dependency",
                detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(field, reason, id.toString())))
            )
        }
        return null
    }

    /** Normalizes [deps] and rejects a duplicate within the request. */
    private fun checkRequest(deps: List<Dependency>): Outcome<List<Dependency>> {
        val normalized = deps.map { it.normalized() }
        val seen = HashSet<Triple<UUID, UUID, DependencyType>>()
        for (dep in normalized) {
            if (!seen.add(Triple(dep.fromItemId, dep.toItemId, dep.type))) {
                return Outcome.Err(
                    duplicate("Duplicate dependency within batch: ${dep.fromItemId} -> ${dep.toItemId} (${dep.type})", null)
                )
            }
        }
        return Outcome.Ok(normalized)
    }

    /** The stored blocking rows reachable downstream (blocker to blocked) of [starts]. */
    private suspend fun loadDownstream(starts: Set<UUID>): List<Dependency> {
        val depRepo = repositoryProvider.dependencyRepository()
        val seen = HashSet<UUID>()
        val rows = LinkedHashMap<UUID, Dependency>()
        var frontier: Set<UUID> = starts
        while (frontier.isNotEmpty()) {
            seen += frontier
            val next = LinkedHashSet<UUID>()
            for ((node, deps) in depRepo.findByItemIds(frontier)) {
                for (dep in deps) {
                    val n = dep.normalized()
                    if (n.type != DependencyType.BLOCKS || n.fromItemId != node) continue
                    rows[dep.id] = dep
                    if (n.toItemId !in seen) next += n.toItemId
                }
            }
            frontier = next
        }
        return rows.values.toList()
    }

    private fun duplicate(
        message: String,
        existingId: String?
    ): DomainError =
        DomainError(
            code = ErrorCode.DUPLICATE,
            message = message,
            detail = ErrorDetail.Duplicate(kind = EntityKind.DEPENDENCY, id = null, existingId = existingId),
            fixArgs = mapOf("kind" to "dependency", "existingId" to (existingId ?: "entry earlier in this request"))
        )

    private fun cycle(
        message: String,
        path: List<UUID>
    ): DomainError = DomainError(code = ErrorCode.CYCLE_DETECTED, message = message, detail = ErrorDetail.CycleDetected(path))

    companion object {
        private const val FIELD_FROM = "fromItemId"
        private const val FIELD_TO = "toItemId"
    }
}
