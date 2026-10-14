package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.RunStore
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunBudget
import io.github.jpicklyk.mcptask.current.domain.model.RunPauseReason
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.RunItemsTable
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.RunsTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of [RunStore] over [RunsTable] and [RunItemsTable]. Every method joins the ambient unit;
 * outside one, a write is subject to the outside-unit write policy. `budget`, `hints` and `marks` are stored as JSON
 * objects (an empty map or an unlimited budget as NULL).
 */
class SqliteRunStore(
    private val databaseManager: DatabaseManager
) : RunStore {
    override suspend fun create(run: Run): Run =
        databaseManager.writeTx("RunStore.create") {
            RunsTable.insert {
                it[RunsTable.id] = run.id
                it[RunsTable.rootId] = run.rootId
                it[RunsTable.openedAt] = run.openedAt
                it[RunsTable.openedBy] = run.openedBy
                it[RunsTable.openedByKind] = run.openedByKind
                it[RunsTable.version] = run.version
                writeMutable(it, run)
            }
            RunsTable
                .selectAll()
                .where { RunsTable.id eq run.id }
                .single()
                .let(::toRun)
        }

    override suspend fun getById(id: UUID): Run? =
        databaseManager.readTx {
            RunsTable
                .selectAll()
                .where { RunsTable.id eq id }
                .singleOrNull()
                ?.let(::toRun)
        }

    override suspend fun update(
        run: Run,
        expectedVersion: Long
    ): Run? =
        databaseManager.writeTx("RunStore.update") {
            val updated =
                RunsTable.update({ (RunsTable.id eq run.id) and (RunsTable.version eq expectedVersion) }) {
                    it[RunsTable.version] = expectedVersion + 1
                    writeMutable(it, run)
                }
            if (updated == 0) {
                null
            } else {
                RunsTable
                    .selectAll()
                    .where { RunsTable.id eq run.id }
                    .single()
                    .let(::toRun)
            }
        }

    override suspend fun members(runId: UUID): List<UUID> =
        databaseManager.readTx {
            RunItemsTable
                .select(RunItemsTable.itemId)
                .where { RunItemsTable.runId eq runId }
                .orderBy(RunItemsTable.addedAt to SortOrder.ASC, RunItemsTable.itemId to SortOrder.ASC)
                .map { it[RunItemsTable.itemId] }
        }

    override suspend fun addMembers(
        runId: UUID,
        itemIds: Collection<UUID>,
        addedAt: Instant
    ): Int {
        val wanted = itemIds.toCollection(LinkedHashSet())
        if (wanted.isEmpty()) return 0
        return databaseManager.writeTx("RunStore.addMembers") {
            val existing = HashSet<UUID>()
            for (chunk in wanted.chunked(SQL_IN_CHUNK_SIZE)) {
                RunItemsTable
                    .select(RunItemsTable.itemId)
                    .where { (RunItemsTable.runId eq runId) and (RunItemsTable.itemId inList chunk) }
                    .forEach { existing += it[RunItemsTable.itemId] }
            }
            var added = 0
            for (itemId in wanted) {
                if (itemId in existing) continue
                RunItemsTable.insert {
                    it[RunItemsTable.runId] = runId
                    it[RunItemsTable.itemId] = itemId
                    it[RunItemsTable.addedAt] = addedAt
                }
                added++
            }
            added
        }
    }

    override suspend fun removeMembers(
        runId: UUID,
        itemIds: Collection<UUID>
    ): Int {
        val ids = itemIds.distinct()
        if (ids.isEmpty()) return 0
        return databaseManager.writeTx("RunStore.removeMembers") {
            ids.chunked(SQL_IN_CHUNK_SIZE).sumOf { chunk ->
                RunItemsTable.deleteWhere { (RunItemsTable.runId eq runId) and (RunItemsTable.itemId inList chunk) }
            }
        }
    }

    override suspend fun openRunByItem(itemIds: Set<UUID>): Map<UUID, UUID> {
        if (itemIds.isEmpty()) return emptyMap()
        return databaseManager.readTx {
            val memberships = ArrayList<Pair<UUID, UUID>>()
            for (chunk in itemIds.chunked(SQL_IN_CHUNK_SIZE)) {
                RunItemsTable
                    .select(RunItemsTable.itemId, RunItemsTable.runId)
                    .where { RunItemsTable.itemId inList chunk }
                    .forEach { memberships += it[RunItemsTable.itemId] to it[RunItemsTable.runId] }
            }
            val runIds = memberships.mapTo(LinkedHashSet()) { it.second }
            val notClosed = HashSet<UUID>()
            for (chunk in runIds.chunked(SQL_IN_CHUNK_SIZE)) {
                RunsTable
                    .select(RunsTable.id)
                    .where { (RunsTable.id inList chunk) and (RunsTable.status neq RunStatus.CLOSED.wire) }
                    .forEach { notClosed += it[RunsTable.id] }
            }
            val out = HashMap<UUID, UUID>()
            for ((itemId, runId) in memberships) {
                if (runId in notClosed) out.putIfAbsent(itemId, runId)
            }
            out
        }
    }

    override suspend fun countNotClosedByRoot(rootId: UUID): Int =
        databaseManager.readTx {
            val n = RunsTable.id.count()
            RunsTable
                .select(n)
                .where { (RunsTable.rootId eq rootId) and (RunsTable.status neq RunStatus.CLOSED.wire) }
                .single()[n]
                .toInt()
        }

    override suspend fun list(
        rootIds: Set<UUID>?,
        status: RunStatus?,
        closedSince: Instant?
    ): List<Run> {
        if (rootIds != null && rootIds.isEmpty()) return emptyList()
        return databaseManager.readTx {
            val chunks = rootIds?.chunked(SQL_IN_CHUNK_SIZE) ?: listOf(null)
            val rows = ArrayList<Run>()
            for (chunk in chunks) {
                RunsTable
                    .selectAll()
                    .where {
                        var cond: Op<Boolean> = Op.TRUE
                        if (chunk != null) cond = cond and (RunsTable.rootId inList chunk)
                        if (status != null) cond = cond and (RunsTable.status eq status.wire)
                        if (closedSince != null) cond = cond and (RunsTable.closedAt greaterEq closedSince)
                        cond
                    }.mapTo(rows) { toRun(it) }
            }
            rows.sortedWith(compareByDescending<Run> { it.openedAt }.thenBy { it.id })
        }
    }

    override suspend fun findIdsByPrefix(
        prefix: String,
        limit: Int
    ): List<UUID> {
        val bounds = IdPrefixRange.of(prefix) ?: return emptyList()
        return databaseManager.readTx {
            val lower = RunsTable.id greaterEq bounds.lower
            val condition = bounds.upper?.let { lower and (RunsTable.id less it) } ?: lower
            RunsTable
                .select(RunsTable.id)
                .where { condition }
                .orderBy(RunsTable.id, SortOrder.ASC)
                .limit(limit)
                .map { it[RunsTable.id] }
        }
    }

    /** Writes every column a run may change after it is opened. */
    private fun writeMutable(
        it: UpdateBuilder<*>,
        run: Run
    ) {
        it[RunsTable.scopeId] = run.scopeId
        it[RunsTable.label] = run.label
        it[RunsTable.title] = run.title
        it[RunsTable.status] = run.status.wire
        it[RunsTable.pauseReason] = run.pauseReason?.wire
        it[RunsTable.pauseDecisionId] = run.pauseDecisionId
        it[RunsTable.terminalReason] = run.terminalReason?.wire
        it[RunsTable.closedBy] = run.closedBy
        it[RunsTable.closedAt] = run.closedAt
        it[RunsTable.coordinatorSeenAt] = run.coordinatorSeenAt
        it[RunsTable.stallAfterSeconds] = run.stallAfterSeconds
        it[RunsTable.budget] = encodeBudget(run.budget)
        it[RunsTable.reportedTokens] = run.reportedTokens
        it[RunsTable.reportedCostMicro] = run.reportedCostMicroUsd
        it[RunsTable.observedTokens] = run.observedTokens
        it[RunsTable.hints] =
            run.hints.takeIf { h -> h.isNotEmpty() }?.let { h -> JsonObject(h.mapValues { (_, v) -> JsonPrimitive(v) }).toString() }
        it[RunsTable.marks] =
            run.marks
                .takeIf { m ->
                    m.isNotEmpty()
                }?.let { m -> JsonObject(m.mapValues { (_, v) -> JsonPrimitive(v.toString()) }).toString() }
    }

    private fun toRun(row: ResultRow): Run =
        Run(
            id = row[RunsTable.id],
            rootId = row[RunsTable.rootId],
            scopeId = row[RunsTable.scopeId],
            label = row[RunsTable.label],
            title = row[RunsTable.title],
            status = RunStatus.fromWire(row[RunsTable.status]) ?: error("Unknown run status '${row[RunsTable.status]}'"),
            pauseReason = row[RunsTable.pauseReason]?.let { RunPauseReason.fromWire(it) ?: error("Unknown pause reason '$it'") },
            pauseDecisionId = row[RunsTable.pauseDecisionId],
            terminalReason =
                row[RunsTable.terminalReason]?.let {
                    RunTerminalReason.fromWire(
                        it
                    ) ?: error("Unknown terminal reason '$it'")
                },
            openedBy = row[RunsTable.openedBy],
            openedByKind = row[RunsTable.openedByKind],
            openedAt = row[RunsTable.openedAt],
            closedBy = row[RunsTable.closedBy],
            closedAt = row[RunsTable.closedAt],
            coordinatorSeenAt = row[RunsTable.coordinatorSeenAt],
            stallAfterSeconds = row[RunsTable.stallAfterSeconds],
            budget = decodeBudget(row[RunsTable.budget]),
            reportedTokens = row[RunsTable.reportedTokens],
            reportedCostMicroUsd = row[RunsTable.reportedCostMicro],
            observedTokens = row[RunsTable.observedTokens],
            hints = decodeObject(row[RunsTable.hints]).mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() },
            marks =
                decodeObject(row[RunsTable.marks])
                    .mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { text ->
                            runCatchingNonCancellation { k to Instant.parse(text) }.getOrNull()
                        }
                    }.toMap(),
            version = row[RunsTable.version]
        )

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encodeBudget(budget: RunBudget?): String? {
            if (budget == null || budget.isUnlimited) return null
            return buildJsonObject {
                budget.tokens?.let { put("tokens", it) }
                budget.costMicroUsd?.let { put("costMicroUsd", it) }
                budget.attemptsPerSeat?.let { put("attemptsPerSeat", it) }
                budget.maxActiveSeats?.let { put("maxActiveSeats", it) }
            }.toString()
        }

        fun decodeBudget(text: String?): RunBudget? {
            val obj = decodeObject(text)
            if (obj.isEmpty()) return null

            fun long(key: String): Long? = (obj[key] as? JsonPrimitive)?.longOrNull

            fun int(key: String): Int? = (obj[key] as? JsonPrimitive)?.intOrNull
            return RunBudget(long("tokens"), long("costMicroUsd"), int("attemptsPerSeat"), int("maxActiveSeats"))
        }

        fun decodeObject(text: String?): JsonObject {
            if (text.isNullOrBlank()) return JsonObject(emptyMap())
            return runCatchingNonCancellation { json.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        }
    }
}
