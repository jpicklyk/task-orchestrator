package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DeleteCause
import io.github.jpicklyk.mcptask.current.domain.graph.CycleDetector
import io.github.jpicklyk.mcptask.current.domain.graph.DependencyEdges
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import java.util.UUID

/**
 * The single owner of the dependency write policy, shared by `manage_dependencies`, the REST dependency
 * routes and `create_work_tree`. JSON-free and transport-free: callers map the returned [DomainError]
 * codes to their own wire shapes.
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
 */
class DependencyCommandService(
    private val repositoryProvider: RepositoryProvider,
    private val unitOfWork: UnitOfWork
) {
    /** Applies the write policy to [deps] and stores them in one write unit, returning the stored rows in request order. */
    suspend fun create(deps: List<Dependency>): Outcome<List<Dependency>> =
        unitOfWork.write("DependencyCommandService.create") { createBody(deps) }

    /**
     * The same policy as [create], for a caller that already holds a write unit (an atomic composite). It
     * joins the caller's unit: the caller's unit decides commit, and an [Outcome.Err] returned from the
     * caller's block rolls it back.
     */
    suspend fun createInUnit(deps: List<Dependency>): Outcome<List<Dependency>> =
        unitOfWork.write("DependencyCommandService.createInUnit") { createBody(deps) }

    private suspend fun WriteScope.createBody(deps: List<Dependency>): Outcome<List<Dependency>> {
        if (deps.isEmpty()) return Outcome.Ok(emptyList())
        val normalized =
            when (val checked = checkRequest(deps)) {
                is Outcome.Ok -> checked.value
                is Outcome.Err -> return checked
            }
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
        type: DependencyType?
    ): Outcome<Int> =
        unitOfWork.write("DependencyCommandService.deleteByRelationship") {
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

    /** Deletes the dependency [id] in one write unit; `Ok(false)` when it does not exist. */
    suspend fun deleteById(id: UUID): Outcome<Boolean> =
        unitOfWork.write("DependencyCommandService.deleteById") {
            val depRepo = repositoryProvider.dependencyRepository()
            val dep = depRepo.findById(id)
            val deleted = depRepo.delete(id)
            if (deleted && dep != null) events.record(dependencyRemovedEvent(dep, rootOfItem(dep.fromItemId), DeleteCause.EXPLICIT))
            Outcome.Ok(deleted)
        }

    /** Deletes every dependency touching [itemId] (either end) in one write unit, returning how many were removed. */
    suspend fun deleteByItemId(itemId: UUID): Outcome<Int> =
        unitOfWork.write("DependencyCommandService.deleteByItemId") {
            val depRepo = repositoryProvider.dependencyRepository()
            val edges = depRepo.findByItemId(itemId)
            val count = depRepo.deleteByItemId(itemId)
            if (count > 0) events.record(edges.map { dependencyRemovedEvent(it, rootOfItem(it.fromItemId), DeleteCause.EXPLICIT) })
            Outcome.Ok(count)
        }

    /** The root of the item [itemId] as it is NOW ([eventRootOf]; its own id when it cannot be resolved). */
    private suspend fun rootOfItem(itemId: UUID): UUID {
        val repo = repositoryProvider.workItemRepository()
        val item = repo.getById(itemId) ?: return eventRootOf(itemId, repo)
        return eventRootOf(item, repo)
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
}
