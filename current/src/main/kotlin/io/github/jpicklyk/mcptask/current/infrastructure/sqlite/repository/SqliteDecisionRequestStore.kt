package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.DecisionRequestStore
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestAnswer
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestAnswerers
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestOption
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatLink
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.DecisionRequestsTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * SQLite implementation of [DecisionRequestStore] over [DecisionRequestsTable]. Every method joins the ambient unit;
 * outside one, a write is subject to the outside-unit write policy. `options` and `links` are stored as JSON arrays
 * (no links as NULL) and parsed here, never with SQL JSON functions.
 */
class SqliteDecisionRequestStore(
    private val databaseManager: DatabaseManager
) : DecisionRequestStore {
    override suspend fun create(decision: DecisionRequest): DecisionRequest =
        databaseManager.writeTx("DecisionRequestStore.create") {
            DecisionRequestsTable.insert {
                it[DecisionRequestsTable.id] = decision.id
                it[DecisionRequestsTable.rootId] = decision.rootId
                it[DecisionRequestsTable.runId] = decision.runId
                it[DecisionRequestsTable.itemId] = decision.itemId
                it[DecisionRequestsTable.decisionClass] = decision.decisionClass
                it[DecisionRequestsTable.answerers] = decision.answerers.wire
                it[DecisionRequestsTable.severity] = decision.severity.wire
                it[DecisionRequestsTable.dedupeKey] = decision.dedupeKey
                it[DecisionRequestsTable.scopeKey] = decision.scopeKey
                it[DecisionRequestsTable.question] = decision.question
                it[DecisionRequestsTable.options] = encodeOptions(decision.options)
                it[DecisionRequestsTable.recommended] = decision.recommended
                it[DecisionRequestsTable.links] = encodeLinks(decision.links)
                it[DecisionRequestsTable.context] = decision.context
                it[DecisionRequestsTable.raisedById] = decision.raisedById
                it[DecisionRequestsTable.raisedByKind] = decision.raisedByKind
                it[DecisionRequestsTable.raisedBySeat] = decision.raisedBySeat
                it[DecisionRequestsTable.raisedAt] = decision.raisedAt
                it[DecisionRequestsTable.expiresAt] = decision.expiresAt
                it[DecisionRequestsTable.onExpiry] = decision.onExpiry
                it[DecisionRequestsTable.version] = decision.version
                writeMutable(it, decision)
            }
            readById(decision.id) ?: error("Decision request ${decision.id} vanished after insert")
        }

    override suspend fun getById(id: UUID): DecisionRequest? = databaseManager.readTx { readById(id) }

    override suspend fun findByKey(
        rootId: UUID,
        scopeKey: String,
        dedupeKey: String
    ): DecisionRequest? =
        databaseManager.readTx {
            DecisionRequestsTable
                .selectAll()
                .where {
                    (DecisionRequestsTable.rootId eq rootId) and (DecisionRequestsTable.scopeKey eq scopeKey) and
                        (DecisionRequestsTable.dedupeKey eq dedupeKey) and
                        (DecisionRequestsTable.status neq DecisionRequestStatus.WITHDRAWN.wire)
                }.singleOrNull()
                ?.let(::toDecision)
        }

    override suspend fun update(
        decision: DecisionRequest,
        expectedVersion: Long
    ): DecisionRequest? =
        databaseManager.writeTx("DecisionRequestStore.update") {
            val updated =
                DecisionRequestsTable.update({
                    (DecisionRequestsTable.id eq decision.id) and (DecisionRequestsTable.version eq expectedVersion)
                }) {
                    it[DecisionRequestsTable.version] = expectedVersion + 1
                    writeMutable(it, decision)
                }
            if (updated == 0) null else readById(decision.id)
        }

    override suspend fun list(
        rootIds: Set<UUID>?,
        runId: UUID?,
        itemId: UUID?,
        statuses: Set<DecisionRequestStatus>?
    ): List<DecisionRequest> {
        if (rootIds != null && rootIds.isEmpty()) return emptyList()
        if (statuses != null && statuses.isEmpty()) return emptyList()
        return databaseManager.readTx {
            val chunks = rootIds?.chunked(SQL_IN_CHUNK_SIZE) ?: listOf(null)
            val rows = ArrayList<DecisionRequest>()
            for (chunk in chunks) {
                DecisionRequestsTable
                    .selectAll()
                    .where {
                        var cond: Op<Boolean> = Op.TRUE
                        if (chunk != null) cond = cond and (DecisionRequestsTable.rootId inList chunk)
                        if (runId != null) cond = cond and (DecisionRequestsTable.runId eq runId)
                        if (itemId != null) cond = cond and (DecisionRequestsTable.itemId eq itemId)
                        if (statuses != null) cond = cond and (DecisionRequestsTable.status inList statuses.map { it.wire })
                        cond
                    }.mapTo(rows) { toDecision(it) }
            }
            rows.sortedWith(compareByDescending<DecisionRequest> { it.raisedAt }.thenBy { it.id })
        }
    }

    override suspend fun openCount(runId: UUID): Int =
        databaseManager.readTx {
            val n = DecisionRequestsTable.id.count()
            DecisionRequestsTable
                .select(n)
                .where {
                    (DecisionRequestsTable.runId eq runId) and
                        (DecisionRequestsTable.status inList PENDING.map { it.wire })
                }.single()[n]
                .toInt()
        }

    override suspend fun findIdsByPrefix(
        prefix: String,
        limit: Int
    ): List<UUID> {
        val bounds = IdPrefixRange.of(prefix) ?: return emptyList()
        return databaseManager.readTx {
            val lower = DecisionRequestsTable.id greaterEq bounds.lower
            val condition = bounds.upper?.let { lower and (DecisionRequestsTable.id less it) } ?: lower
            DecisionRequestsTable
                .select(DecisionRequestsTable.id)
                .where { condition }
                .orderBy(DecisionRequestsTable.id, SortOrder.ASC)
                .limit(limit)
                .map { it[DecisionRequestsTable.id] }
        }
    }

    // ---- mapping --------------------------------------------------------------------------------------------

    /** Writes every column a request may change after it is raised: status, acknowledgement and answer. */
    private fun writeMutable(
        it: UpdateBuilder<*>,
        decision: DecisionRequest
    ) {
        val answer = decision.answer
        it[DecisionRequestsTable.status] = decision.status.wire
        it[DecisionRequestsTable.ackById] = decision.ackById
        it[DecisionRequestsTable.ackByKind] = decision.ackByKind
        it[DecisionRequestsTable.ackAt] = decision.ackAt
        it[DecisionRequestsTable.answerOption] = answer?.option
        it[DecisionRequestsTable.answerText] = answer?.text
        it[DecisionRequestsTable.answerRationale] = answer?.rationale
        it[DecisionRequestsTable.answeredById] = answer?.answeredById
        it[DecisionRequestsTable.answeredByKind] = answer?.answeredByKind
        it[DecisionRequestsTable.answeredByProof] = answer?.answeredByProof
        it[DecisionRequestsTable.answeredHost] = answer?.host
        it[DecisionRequestsTable.answeredSessionId] = answer?.sessionId
        it[DecisionRequestsTable.answeredRunId] = answer?.runId
        it[DecisionRequestsTable.answeredSeat] = answer?.seat
        it[DecisionRequestsTable.answeredProfile] = answer?.profile
        it[DecisionRequestsTable.answeredAt] = answer?.answeredAt
        it[DecisionRequestsTable.afterExpiry] = answer?.afterExpiry ?: false
    }

    private fun readById(id: UUID): DecisionRequest? =
        DecisionRequestsTable
            .selectAll()
            .where { DecisionRequestsTable.id eq id }
            .singleOrNull()
            ?.let(::toDecision)

    private fun toDecision(row: ResultRow): DecisionRequest {
        val status = enumOf(row[DecisionRequestsTable.status], "status", DecisionRequestStatus::fromWire)
        val answeredAt = row[DecisionRequestsTable.answeredAt]
        val answeredById = row[DecisionRequestsTable.answeredById]
        val answer =
            if (status == DecisionRequestStatus.ANSWERED && answeredAt != null && answeredById != null) {
                DecisionRequestAnswer(
                    option = row[DecisionRequestsTable.answerOption],
                    text = row[DecisionRequestsTable.answerText],
                    rationale = row[DecisionRequestsTable.answerRationale],
                    answeredById = answeredById,
                    answeredByKind = row[DecisionRequestsTable.answeredByKind].orEmpty(),
                    answeredByProof = row[DecisionRequestsTable.answeredByProof].orEmpty(),
                    host = row[DecisionRequestsTable.answeredHost],
                    sessionId = row[DecisionRequestsTable.answeredSessionId],
                    runId = row[DecisionRequestsTable.answeredRunId],
                    seat = row[DecisionRequestsTable.answeredSeat],
                    profile = row[DecisionRequestsTable.answeredProfile],
                    answeredAt = answeredAt,
                    afterExpiry = row[DecisionRequestsTable.afterExpiry]
                )
            } else {
                null
            }
        return DecisionRequest(
            id = row[DecisionRequestsTable.id],
            rootId = row[DecisionRequestsTable.rootId],
            runId = row[DecisionRequestsTable.runId],
            itemId = row[DecisionRequestsTable.itemId],
            decisionClass = row[DecisionRequestsTable.decisionClass],
            answerers = enumOf(row[DecisionRequestsTable.answerers], "answerers", DecisionRequestAnswerers::fromWire),
            severity = enumOf(row[DecisionRequestsTable.severity], "severity", DecisionRequestSeverity::fromWire),
            status = status,
            dedupeKey = row[DecisionRequestsTable.dedupeKey],
            scopeKey = row[DecisionRequestsTable.scopeKey],
            question = row[DecisionRequestsTable.question],
            options = decodeOptions(row[DecisionRequestsTable.options]),
            recommended = row[DecisionRequestsTable.recommended],
            links = decodeLinks(row[DecisionRequestsTable.links]),
            context = row[DecisionRequestsTable.context],
            raisedById = row[DecisionRequestsTable.raisedById],
            raisedByKind = row[DecisionRequestsTable.raisedByKind],
            raisedBySeat = row[DecisionRequestsTable.raisedBySeat],
            raisedAt = row[DecisionRequestsTable.raisedAt],
            expiresAt = row[DecisionRequestsTable.expiresAt],
            onExpiry = row[DecisionRequestsTable.onExpiry],
            ackById = row[DecisionRequestsTable.ackById],
            ackByKind = row[DecisionRequestsTable.ackByKind],
            ackAt = row[DecisionRequestsTable.ackAt],
            answer = answer,
            version = row[DecisionRequestsTable.version]
        )
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The statuses that still wait for an answer. */
        val PENDING: List<DecisionRequestStatus> = DecisionRequestStatus.entries.filter { it.isPending }

        fun <E> enumOf(
            value: String,
            column: String,
            parse: (String) -> E?
        ): E = parse(value) ?: error("Unknown decision $column '$value'")

        fun encodeOptions(options: List<DecisionRequestOption>): String =
            JsonArray(
                options.map { option ->
                    val members = LinkedHashMap<String, JsonPrimitive>()
                    members["key"] = JsonPrimitive(option.key)
                    members["label"] = JsonPrimitive(option.label)
                    option.consequence?.let { members["consequence"] = JsonPrimitive(it) }
                    JsonObject(members)
                }
            ).toString()

        fun decodeOptions(text: String): List<DecisionRequestOption> {
            val array = json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val key = (obj["key"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val label = (obj["label"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                DecisionRequestOption(key, label, (obj["consequence"] as? JsonPrimitive)?.contentOrNull)
            }
        }

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
    }
}
