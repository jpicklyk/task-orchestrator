package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LiteralOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Sum
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import java.sql.ResultSet
import java.util.UUID

/**
 * The SQL fragments of the read model (`WorkViewQueries` and the `next` eligibility): dependency blocking,
 * ancestor-claim shielding and the "terminal but still open below" rule, each as one predicate evaluated by
 * SQLite inside the query that pages or counts, so no row window or per-row round trip decides eligibility.
 *
 * Every fragment refers to the OUTER `work_items` row through [WorkItemsTable.id] / [WorkItemsTable.role] and
 * aliases every inner table, so it composes into any `SELECT ... FROM work_items` built by the stores.
 */
internal object WorkViewSql {
    /**
     * Progression rank of a blocker role: queue 0, work 1, review 2, terminal 3. BLOCKED, an unknown value and
     * NULL (the blocker row is missing) rank -1, below every threshold: they never satisfy an edge
     * (`Role.isAtOrBeyond`, `BlockerEvaluator.isSatisfied`, fail-closed).
     */
    private const val BLOCKER_RANK =
        "CASE b.role WHEN 'queue' THEN 0 WHEN 'work' THEN 1 WHEN 'review' THEN 2 WHEN 'terminal' THEN 3 ELSE -1 END"

    /** Rank of an edge threshold (`unblock_at`, TERMINAL when null). An unknown threshold ranks 99: never satisfied. */
    private const val THRESHOLD_RANK =
        "CASE lower(coalesce(d.unblock_at, 'terminal')) " +
            "WHEN 'queue' THEN 0 WHEN 'work' THEN 1 WHEN 'review' THEN 2 WHEN 'terminal' THEN 3 ELSE 99 END"

    /**
     * True when the outer item has at least one unsatisfied blocking dependency: a stored BLOCKS row into it
     * (`to_item_id` = the item; IS_BLOCKED_BY is never stored since V22) whose blocker is not at or beyond the
     * threshold. Served by the `to_item_id` index and the work_items primary key. RELATES_TO rows never match.
     */
    fun dependencyBlocked(): Op<Boolean> =
        SqlOp {
            append("EXISTS (SELECT 1 FROM dependencies d LEFT JOIN work_items b ON b.id = d.from_item_id WHERE d.to_item_id = ")
            append(WorkItemsTable.id)
            append(" AND d.type = 'BLOCKS' AND (")
            append(BLOCKER_RANK)
            append(") < (")
            append(THRESHOLD_RANK)
            append("))")
        }

    /**
     * True when the outer item lies strictly below an item returned by [claimedSeed] (a `SELECT work_items.id`
     * of the items whose claim shields their subtree). The subtree is walked DOWNWARD once from the few claimed
     * items (one recursive CTE over the `parent_id` index, `UNION` so cyclic data terminates), not upward per
     * candidate. The claimed items themselves are not in the set.
     */
    fun shieldedByAncestorClaim(claimedSeed: Query): Op<Boolean> =
        SqlOp {
            append(WorkItemsTable.id)
            append(" IN (WITH RECURSIVE shielded(id) AS (SELECT c.id FROM work_items c WHERE c.parent_id IN (")
            claimedSeed.prepareSQL(this)
            append(") UNION SELECT x.id FROM work_items x JOIN shielded s ON x.parent_id = s.id) SELECT id FROM shielded)")
        }

    /**
     * The `excludeTerminal` visibility rule for the children of [parentId] (the parentless roots when null): the
     * outer item is kept when it is not TERMINAL, or when it is a TERMINAL child of that parent with at least one
     * non-terminal descendant at any depth (bug 18fd99a7). The walk starts only from the terminal children.
     */
    fun notTerminalOrOpenBelow(parentId: UUID?): Op<Boolean> =
        SqlOp {
            append("(")
            append(WorkItemsTable.role)
            append(" <> 'terminal' OR ")
            append(WorkItemsTable.id)
            append(" IN (WITH RECURSIVE walk(top, id) AS (SELECT c.id, c.id FROM work_items c WHERE c.role = 'terminal' AND ")
            if (parentId == null) {
                append("c.parent_id IS NULL")
            } else {
                append("c.parent_id = ")
                registerArgument(WorkItemsTable.parentId, parentId)
            }
            append(" UNION SELECT w.top, x.id FROM work_items x JOIN walk w ON x.parent_id = w.id)")
            append(" SELECT walk.top FROM walk JOIN work_items y ON y.id = walk.id WHERE y.role <> 'terminal'))")
        }

    /** Role rank for list ordering: queue 0, work 1, review 2, blocked 3, terminal 4 (anything else 99). */
    fun roleRank(): Expression<Int> =
        Case()
            .When(WorkItemsTable.role eq "queue", int(0))
            .When(WorkItemsTable.role eq "work", int(1))
            .When(WorkItemsTable.role eq "review", int(2))
            .When(WorkItemsTable.role eq "blocked", int(3))
            .When(WorkItemsTable.role eq "terminal", int(4))
            .Else(int(99))

    /** Role rank for the blocked list: BLOCKED first, then queue, work, review (anything else 99). */
    fun blockedListRank(): Expression<Int> =
        Case()
            .When(WorkItemsTable.role eq "blocked", int(0))
            .When(WorkItemsTable.role eq "queue", int(1))
            .When(WorkItemsTable.role eq "work", int(2))
            .When(WorkItemsTable.role eq "review", int(3))
            .Else(int(99))

    /** `SUM(CASE WHEN condition THEN 1 ELSE 0 END)`: one bucket of a single-statement breakdown. */
    fun countWhen(condition: Op<Boolean>): Sum<Int> = Sum(Case().When(condition, int(1)).Else(int(0)), IntegerColumnType())

    /**
     * `SELECT` of the 16-byte ids of an id set from ONE bound JSON-array parameter (`json_each` + `unhex`), so an
     * id set of any size is a single statement with a single bound variable: no `IN` list to chunk, and a view's
     * statement count stays independent of how many ids it batches. Use it inside `IN (...)` and bind
     * [idSetArgument] at its `?`.
     */
    const val ID_SET_SELECT = "SELECT unhex(value) FROM json_each(?)"

    /** The bound value for [ID_SET_SELECT]: a JSON array of the ids as 32-digit hex strings. */
    fun idSetArgument(ids: Collection<UUID>): Pair<IColumnType<*>, Any?> =
        TextColumnType() to ids.joinToString(",", "[", "]") { "\"" + it.toString().replace("-", "") + "\"" }

    /** `column IN (<ids>)` for an id set of any size, as one statement with one bound parameter ([ID_SET_SELECT]). */
    fun inIdSet(
        column: Expression<*>,
        ids: Collection<UUID>,
    ): Op<Boolean> =
        SqlOp {
            val (type, value) = idSetArgument(ids)
            append(column)
            append(" IN (SELECT unhex(value) FROM json_each(")
            registerArgument(type, value)
            append("))")
        }

    /** [role] as stored (lower case). */
    fun stored(role: Role): String = role.name.lowercase()

    private fun int(value: Int): Expression<Int> = LiteralOp(IntegerColumnType(), value)

    /** A WHERE fragment rendered by [render]; arguments it registers are bound as typed parameters. */
    private class SqlOp(
        private val render: QueryBuilder.() -> Unit
    ) : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.render()
        }
    }
}

/**
 * Runs a SELECT (including a recursive CTE) through Exposed's statement pipeline with an explicit SELECT type,
 * so it uses `executeQuery` and is seen by statement interceptors like every DSL query. Values are bound as typed
 * parameters, never interpolated. Must run inside an active transaction.
 */
internal fun <T : Any> JdbcTransaction.selectSql(
    sql: String,
    args: List<Pair<IColumnType<*>, Any?>>,
    map: (ResultSet) -> T
): T = exec(sql, args, StatementType.SELECT) { rs -> map(rs) } ?: error("SELECT returned no result set: $sql")
