package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.AssigneeHolder
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.InFlightSeat
import io.github.jpicklyk.mcptask.current.application.port.RunSeatStore
import io.github.jpicklyk.mcptask.current.application.port.RunUsageTotals
import io.github.jpicklyk.mcptask.current.application.port.unitNow
import io.github.jpicklyk.mcptask.current.domain.model.RunSeat
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatLink
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatOutcome
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatStatus
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.RunSeatsTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of [RunSeatStore] over [RunSeatsTable]. Every method joins the ambient unit; outside one, a
 * write is subject to the outside-unit write policy. `links` and `hints` are stored as JSON (empty as NULL).
 */
class SqliteRunSeatStore(
    private val databaseManager: DatabaseManager,
    private val clock: Clock
) : RunSeatStore {
    override suspend fun create(seat: RunSeat): RunSeat =
        databaseManager.writeTx("RunSeatStore.create") {
            RunSeatsTable.insert {
                it[RunSeatsTable.id] = seat.id
                it[RunSeatsTable.runId] = seat.runId
                it[RunSeatsTable.itemId] = seat.itemId
                it[RunSeatsTable.seat] = seat.seat
                it[RunSeatsTable.attempt] = seat.attempt
                it[RunSeatsTable.principalId] = seat.principalId
                it[RunSeatsTable.tokenHash] = seat.tokenHash
                it[RunSeatsTable.status] = seat.status.wire
                it[RunSeatsTable.outcome] = seat.outcome?.wire
                it[RunSeatsTable.summary] = seat.summary
                it[RunSeatsTable.links] = encodeLinks(seat.links)
                it[RunSeatsTable.hints] = encodeHints(seat.hints)
                it[RunSeatsTable.assignedAt] = seat.assignedAt
                it[RunSeatsTable.endedAt] = seat.endedAt
                it[RunSeatsTable.lastSeenAt] = seat.lastSeenAt
                it[RunSeatsTable.usageTokens] = seat.usageTokens
                it[RunSeatsTable.usageCostMicro] = seat.usageCostMicro
            }
            readById(seat.id) ?: error("Seat assignment ${seat.id} vanished after insert")
        }

    override suspend fun getById(id: UUID): RunSeat? = databaseManager.readTx { readById(id) }

    override suspend fun findByTokenHash(tokenHash: String): RunSeat? =
        databaseManager.readTx {
            RunSeatsTable
                .selectAll()
                .where { RunSeatsTable.tokenHash eq tokenHash }
                .singleOrNull()
                ?.let(::toSeat)
        }

    override suspend fun findActive(
        runId: UUID,
        itemId: UUID,
        seat: String
    ): RunSeat? =
        databaseManager.readTx {
            RunSeatsTable
                .selectAll()
                .where {
                    (RunSeatsTable.runId eq runId) and (RunSeatsTable.itemId eq itemId) and (RunSeatsTable.seat eq seat) and
                        (RunSeatsTable.status eq RunSeatStatus.ACTIVE.wire)
                }.singleOrNull()
                ?.let(::toSeat)
        }

    override suspend fun maxAttempt(
        runId: UUID,
        itemId: UUID,
        seat: String
    ): Int =
        databaseManager.readTx {
            val top = RunSeatsTable.attempt.max()
            RunSeatsTable
                .select(top)
                .where { (RunSeatsTable.runId eq runId) and (RunSeatsTable.itemId eq itemId) and (RunSeatsTable.seat eq seat) }
                .single()[top] ?: 0
        }

    override suspend fun end(
        id: UUID,
        outcome: RunSeatOutcome,
        endedAt: Instant,
        summary: String?,
        links: List<RunSeatLink>
    ): RunSeat? =
        databaseManager.writeTx("RunSeatStore.end") {
            val updated =
                RunSeatsTable.update({ (RunSeatsTable.id eq id) and (RunSeatsTable.status eq RunSeatStatus.ACTIVE.wire) }) {
                    it[RunSeatsTable.status] = RunSeatStatus.ENDED.wire
                    it[RunSeatsTable.outcome] = outcome.wire
                    it[RunSeatsTable.endedAt] = endedAt
                    if (summary != null) it[RunSeatsTable.summary] = summary
                    if (links.isNotEmpty()) it[RunSeatsTable.links] = encodeLinks(links)
                }
            if (updated == 0) null else readById(id)
        }

    override suspend fun touch(
        id: UUID,
        at: Instant
    ) {
        databaseManager.writeTx("RunSeatStore.touch") {
            RunSeatsTable.update({ RunSeatsTable.id eq id }) { it[RunSeatsTable.lastSeenAt] = at }
        }
    }

    override suspend fun recordUsage(
        id: UUID,
        tokens: Long?,
        costMicro: Long?
    ): RunSeat? =
        databaseManager.writeTx("RunSeatStore.recordUsage") {
            val current = readById(id) ?: return@writeTx null
            val newTokens = if (tokens != null) maxOf(current.usageTokens, tokens) else current.usageTokens
            val newCost = if (costMicro != null) maxOf(current.usageCostMicro, costMicro) else current.usageCostMicro
            if (newTokens != current.usageTokens || newCost != current.usageCostMicro) {
                RunSeatsTable.update({ RunSeatsTable.id eq id }) {
                    it[RunSeatsTable.usageTokens] = newTokens
                    it[RunSeatsTable.usageCostMicro] = newCost
                }
            }
            readById(id)
        }

    override suspend fun list(
        runId: UUID,
        itemId: UUID?,
        includeEnded: Boolean
    ): List<RunSeat> =
        databaseManager.readTx {
            RunSeatsTable
                .selectAll()
                .where {
                    var cond = RunSeatsTable.runId eq runId
                    if (itemId != null) cond = cond and (RunSeatsTable.itemId eq itemId)
                    if (!includeEnded) cond = cond and (RunSeatsTable.status eq RunSeatStatus.ACTIVE.wire)
                    cond
                }.orderBy(RunSeatsTable.assignedAt to SortOrder.ASC, RunSeatsTable.id to SortOrder.ASC)
                .map(::toSeat)
        }

    // ---- RunSeatPort ----------------------------------------------------------------------------------------

    override suspend fun usageTotals(runId: UUID): RunUsageTotals =
        databaseManager.readTx {
            val tokens = RunSeatsTable.usageTokens.sum()
            val cost = RunSeatsTable.usageCostMicro.sum()
            val row =
                RunSeatsTable
                    .select(tokens, cost)
                    .where { RunSeatsTable.runId eq runId }
                    .single()
            RunUsageTotals(row[tokens] ?: 0L, row[cost] ?: 0L)
        }

    override suspend fun activeCount(runId: UUID): Int =
        databaseManager.readTx {
            val n = RunSeatsTable.id.count()
            RunSeatsTable
                .select(n)
                .where {
                    (RunSeatsTable.runId eq runId) and (RunSeatsTable.status eq RunSeatStatus.ACTIVE.wire) and
                        (RunSeatsTable.seat neq RunSeat.COORDINATOR)
                }.single()[n]
                .toInt()
        }

    override suspend fun assigneeHolders(runId: UUID): Set<AssigneeHolder> =
        databaseManager.readTx {
            RunSeatsTable
                .select(RunSeatsTable.id, RunSeatsTable.principalId)
                .where { (RunSeatsTable.runId eq runId) and (RunSeatsTable.status eq RunSeatStatus.ACTIVE.wire) }
                .mapTo(LinkedHashSet()) { AssigneeHolder(it[RunSeatsTable.principalId], it[RunSeatsTable.id]) }
        }

    override suspend fun endActive(
        runId: UUID,
        outcome: String
    ): List<InFlightSeat> {
        val ended = RunSeatOutcome.fromWire(outcome) ?: throw IllegalArgumentException("Unknown seat outcome '$outcome'")
        val now = clock.unitNow()
        return databaseManager.writeTx("RunSeatStore.endActive") {
            val active =
                RunSeatsTable
                    .selectAll()
                    .where { (RunSeatsTable.runId eq runId) and (RunSeatsTable.status eq RunSeatStatus.ACTIVE.wire) }
                    .orderBy(RunSeatsTable.assignedAt to SortOrder.ASC, RunSeatsTable.id to SortOrder.ASC)
                    .map(::toSeat)
            if (active.isNotEmpty()) {
                RunSeatsTable.update({ (RunSeatsTable.runId eq runId) and (RunSeatsTable.status eq RunSeatStatus.ACTIVE.wire) }) {
                    it[RunSeatsTable.status] = RunSeatStatus.ENDED.wire
                    it[RunSeatsTable.outcome] = ended.wire
                    it[RunSeatsTable.endedAt] = now
                }
            }
            active.mapNotNull { seat -> seat.itemId?.let { InFlightSeat(it, seat.seat) } }
        }
    }

    // ---- mapping --------------------------------------------------------------------------------------------

    private fun readById(id: UUID): RunSeat? =
        RunSeatsTable
            .selectAll()
            .where { RunSeatsTable.id eq id }
            .singleOrNull()
            ?.let(::toSeat)

    private fun toSeat(row: ResultRow): RunSeat =
        RunSeat(
            id = row[RunSeatsTable.id],
            runId = row[RunSeatsTable.runId],
            itemId = row[RunSeatsTable.itemId],
            seat = row[RunSeatsTable.seat],
            attempt = row[RunSeatsTable.attempt],
            principalId = row[RunSeatsTable.principalId],
            tokenHash = row[RunSeatsTable.tokenHash],
            status = RunSeatStatus.fromWire(row[RunSeatsTable.status]) ?: error("Unknown seat status '${row[RunSeatsTable.status]}'"),
            outcome = row[RunSeatsTable.outcome]?.let { RunSeatOutcome.fromWire(it) ?: error("Unknown seat outcome '$it'") },
            summary = row[RunSeatsTable.summary],
            links = decodeLinks(row[RunSeatsTable.links]),
            hints = decodeHints(row[RunSeatsTable.hints]),
            assignedAt = row[RunSeatsTable.assignedAt],
            endedAt = row[RunSeatsTable.endedAt],
            lastSeenAt = row[RunSeatsTable.lastSeenAt],
            usageTokens = row[RunSeatsTable.usageTokens],
            usageCostMicro = row[RunSeatsTable.usageCostMicro]
        )

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encodeLinks(links: List<RunSeatLink>): String? {
            if (links.isEmpty()) return null
            return JsonArray(
                links.map { JsonObject(mapOf("kind" to JsonPrimitive(it.kind), "ref" to JsonPrimitive(it.ref))) }
            ).toString()
        }

        fun decodeLinks(text: String?): List<RunSeatLink> {
            if (text.isNullOrBlank()) return emptyList()
            val array = json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val ref = (obj["ref"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                RunSeatLink(kind, ref)
            }
        }

        fun encodeHints(hints: Map<String, String>): String? =
            hints.takeIf { it.isNotEmpty() }?.let { h -> JsonObject(h.mapValues { (_, v) -> JsonPrimitive(v) }).toString() }

        fun decodeHints(text: String?): Map<String, String> {
            if (text.isNullOrBlank()) return emptyMap()
            return json
                .parseToJsonElement(text)
                .jsonObject
                .mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }
        }
    }
}
