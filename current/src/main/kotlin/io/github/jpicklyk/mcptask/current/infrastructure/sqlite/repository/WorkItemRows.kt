package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.MAX_TRAVERSAL_DEPTH
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.statements.jdbc.JdbcResult
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.util.UUID

/*
 * Shared internals of the four SQLite work-item stores: the total row mapper, the one raw-query helper, the
 * one hierarchy traversal primitive and the scope resolver built on it.
 */

private val rowLogger = LoggerFactory.getLogger("io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.WorkItemRows")

/** Total mapping from a `work_items` row to a [WorkItem]: every row is returned, violations ride in `diagnostics`. */
internal object WorkItemRows {
    fun toWorkItem(row: ResultRow): WorkItem {
        val item =
            WorkItem(
                id = row[WorkItemsTable.id].value,
                parentId = row[WorkItemsTable.parentId],
                rootId = row[WorkItemsTable.rootId],
                title = row[WorkItemsTable.title],
                description = row[WorkItemsTable.description],
                summary = row[WorkItemsTable.summary],
                role = Role.fromString(row[WorkItemsTable.role]) ?: Role.QUEUE,
                statusLabel = row[WorkItemsTable.statusLabel],
                previousRole = row[WorkItemsTable.previousRole]?.let { Role.fromString(it) },
                priority = Priority.fromString(row[WorkItemsTable.priority]) ?: Priority.MEDIUM,
                complexity = row[WorkItemsTable.complexity],
                requiresVerification = row[WorkItemsTable.requiresVerification],
                depth = row[WorkItemsTable.depth],
                metadata = row[WorkItemsTable.metadata],
                tags = row[WorkItemsTable.tags],
                type = row[WorkItemsTable.type],
                properties = row[WorkItemsTable.properties],
                createdAt = row[WorkItemsTable.createdAt],
                modifiedAt = row[WorkItemsTable.modifiedAt],
                roleChangedAt = row[WorkItemsTable.roleChangedAt],
                version = row[WorkItemsTable.version],
                claimedBy = row[WorkItemsTable.claimedBy],
                claimedAt = row[WorkItemsTable.claimedAt],
                claimExpiresAt = row[WorkItemsTable.claimExpiresAt],
                originalClaimedAt = row[WorkItemsTable.originalClaimedAt],
                diagnostics = emptyList()
            )
        val violations = item.violations()
        // A valid row carries no diagnostics, so later copy() calls validate exactly like an application-built item.
        if (violations.isEmpty()) return item.copy(diagnostics = null)
        rowLogger.warn("Stored WorkItem row {} violates domain invariants: {}", item.id, violations)
        return item.copy(diagnostics = violations)
    }

    /** Loads rows for [ids] in chunks, all inside the caller's transaction. Result order is unspecified. */
    fun loadByIds(ids: Collection<UUID>): List<WorkItem> =
        ids.chunked(SQL_IN_CHUNK_SIZE).flatMap { chunk ->
            val entityIds = chunk.map { EntityID(it, WorkItemsTable) }
            WorkItemsTable
                .selectAll()
                .where { WorkItemsTable.id inList entityIds }
                .map { toWorkItem(it) }
        }
}

/**
 * Runs a SELECT through the raw prepared-statement API and maps its result: Exposed's `exec` routes through
 * `executeUpdate` on the sqlite driver, which rejects SELECT-returning CTEs ("Query returns results"). Values are
 * bound as typed parameters, never interpolated. Must run inside an active transaction.
 */
internal fun <T> JdbcTransaction.rawQuery(
    sql: String,
    args: List<Pair<ColumnType<*>, Any?>>,
    map: (JdbcResult) -> T
): T {
    val ps = connection.prepareStatement(sql, false)
    try {
        ps.fillParameters(args)
        return map(ps.executeQuery())
    } finally {
        ps.closeIfPossible()
    }
}

/** Reads a BLOB `id` column value back into a [UUID]. */
internal fun uuidOf(raw: Any): UUID {
    @Suppress("UNCHECKED_CAST")
    return UUIDColumnType().valueFromDB(raw) as UUID
}

/** The one parent_id traversal: bounded, cycle-safe, shared by descendants and scope resolution. */
internal object HierarchyTraversal {
    /**
     * Every item reachable from [seeds] by following `parent_id` downward, [seeds] themselves included
     * when they exist. Levels count from 0 (a seed). The recursive arm stops expanding at level
     * [MAX_TRAVERSAL_DEPTH]; reaching that level means the data is cyclic or pathologically deep, which is an
     * error for every caller (these traversals feed cascade deletes, restamps and scoped queries, where a
     * silently short answer is worse than failing).
     */
    fun subtreeIds(seeds: Set<UUID>): Set<UUID> {
        if (seeds.isEmpty()) return emptySet()
        val uuidType = UUIDColumnType()
        val placeholders = seeds.joinToString(",") { "?" }
        val sql =
            """
            WITH RECURSIVE walk(id, lvl) AS (
                SELECT id, 0 FROM work_items WHERE id IN ($placeholders)
                UNION ALL
                SELECT wi.id, w.lvl + 1 FROM work_items wi
                JOIN walk w ON wi.parent_id = w.id
                WHERE w.lvl < $MAX_TRAVERSAL_DEPTH
            )
            SELECT id, lvl FROM walk
            """.trimIndent()
        var exceeded = false
        val ids = LinkedHashSet<UUID>()
        TransactionManager.current().rawQuery(sql, seeds.map { uuidType to it }) { rs ->
            while (rs.next()) {
                ids.add(uuidOf(rs.getObject("id")!!))
                if ((rs.getObject("lvl") as Number).toInt() >= MAX_TRAVERSAL_DEPTH) exceeded = true
            }
        }
        if (exceeded) {
            throw IllegalStateException(
                "Hierarchy traversal exceeded the maximum depth $MAX_TRAVERSAL_DEPTH (cyclic parent_id data?)"
            )
        }
        return ids
    }

    /** [subtreeIds] without the root itself. */
    fun descendantIds(root: UUID): Set<UUID> = subtreeIds(setOf(root)) - root
}

/**
 * The resolved WHERE-clause form of a caller-supplied root set (see [ScopeResolver.resolve]).
 *
 * [ByRoot] is the fast path: it binds ONE parameter per requested root and lets SQLite use
 * `idx_work_items_root_id`. [ByIds] is the historical path: the traversal expands the scope to every
 * descendant id and binds them all, which is O(subtree) bound variables and fails outright above
 * SQLITE_MAX_VARIABLE_NUMBER (250,000 in the bundled xerial build).
 */
internal sealed interface ResolvedScope {
    /** The condition this scope contributes to a WHERE clause. */
    fun toCondition(): Op<Boolean>

    /** Nothing is in scope. Callers short-circuit to an empty result rather than query. */
    data object Empty : ResolvedScope {
        override fun toCondition(): Op<Boolean> = Op.FALSE
    }

    /** Every requested id is a stamped depth-0 root: `root_id IN (rootIds)` is the whole scope. */
    data class ByRoot(
        val rootIds: Set<UUID>
    ) : ResolvedScope {
        override fun toCondition(): Op<Boolean> = WorkItemsTable.rootId inList rootIds
    }

    /** Fallback: the fully expanded id set (roots + all descendants). */
    data class ByIds(
        val ids: Set<UUID>
    ) : ResolvedScope {
        override fun toCondition(): Op<Boolean> = WorkItemsTable.id inList ids.map { EntityID(it, WorkItemsTable) }
    }
}

internal object ScopeResolver {
    /**
     * Resolve a caller-supplied root set into a scope filter, preferring the flat `root_id`
     * predicate over the recursive expansion whenever it is provably equivalent.
     *
     * Fast path (ALL-OR-NOTHING): taken only when EVERY id in [rootIds] resolves to a row that is
     * a stamped depth-0 root, `parent_id IS NULL AND root_id = id`. V9 stamps every item with the id of its
     * depth-0 ancestor and a depth-0 item with its own id, so for such a root R the set
     * `{x : x.root_id = R}` is exactly `subtree(R)`, R included. The scope then costs |rootIds|
     * bound variables instead of one per subtree row.
     *
     * The all-or-nothing guard is the entire safety mechanism: accepting a set containing a
     * below-root id would silently widen that id scope to its whole containing tree. One
     * non-root, unstamped, inconsistently stamped or missing id and the whole call falls back to
     * [HierarchyTraversal.subtreeIds].
     *
     * Declared behaviour delta: the fast path does not traverse, so a cyclic `parent_id` edge
     * beneath a stamped root returns rows here where the traversal raises MAX_TRAVERSAL_DEPTH.
     *
     * Must be called from within an active Exposed transaction.
     */
    fun resolve(rootIds: Set<UUID>): ResolvedScope {
        if (rootIds.isEmpty()) return ResolvedScope.Empty
        if (allAreStampedRoots(rootIds)) return ResolvedScope.ByRoot(rootIds)

        val scopeIds = HierarchyTraversal.subtreeIds(rootIds)
        return if (scopeIds.isEmpty()) ResolvedScope.Empty else ResolvedScope.ByIds(scopeIds)
    }

    /**
     * True when every id in [rootIds] exists AND is a stamped depth-0 root. A missing id fails the
     * count check, so a scope naming a non-existent root falls back to the traversal.
     */
    private fun allAreStampedRoots(rootIds: Set<UUID>): Boolean {
        val entityIds = rootIds.map { EntityID(it, WorkItemsTable) }
        var matched = 0
        WorkItemsTable
            .selectAll()
            .where { WorkItemsTable.id inList entityIds }
            .forEach { row ->
                val id = row[WorkItemsTable.id].value
                if (row[WorkItemsTable.parentId] != null || row[WorkItemsTable.rootId] != id) return false
                matched++
            }
        return matched == rootIds.size
    }
}
