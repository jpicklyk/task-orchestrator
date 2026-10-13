package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallResultCounts
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.time.Instant

/**
 * `items_read` select `history` (orchestration profile): the transition audit trail, read from the `item.transitioned`
 * rows of the event log, newest first, with an exact `page.total`.
 *
 * With `itemId` the target is resolved and scope-checked like `get` (`forbidden` / `not_found` / `ambiguous_id`); without
 * it the rows of every item the caller may see are listed (a root scope keeps its roots, a tag scope the tagged items; a
 * row whose item is gone is visible to an unrestricted or root-scoped caller only). `since`/`until` bound `occurredAt`
 * inclusively; there is no default window. Rows backfilled from the retired `role_transitions` table carry
 * `backfilled: true`. `actor` and `verification` appear only when the call may see attribution
 * ([io.github.jpicklyk.mcptask.current.application.api.CallContext.attributionVisible]); the proof hash and claims are
 * never rendered.
 */
object ItemsReadHistory {
    private const val MAX_LIMIT = 200

    private val TYPES = setOf(DomainEvent.ITEM_TRANSITIONED)

    private val json = Json

    @Serializable
    data class Request(
        @Doc(ItemsReadDocs.ITEM_ID) val itemId: IdRef? = null,
        @Doc("Only transitions at or after this time (ISO 8601).") @Contextual val since: Instant? = null,
        @Doc("Only transitions at or before this time (ISO 8601).") @Contextual val until: Instant? = null,
        @Doc(ItemsReadDocs.LIMIT) val limit: Int? = null,
        @Doc(ItemsReadDocs.CURSOR) val cursor: String? = null,
    )

    val select =
        SelectSpec.Single<Request, JsonObject>(
            name = "history",
            doc =
                "The transition audit trail, newest first (default limit 50, max 200): itemId limits it to one item, " +
                    "otherwise every transition the caller may see; since/until bound the time. Each entry has seq, id, " +
                    "itemId, occurredAt, trigger, fromRole, toRole, status labels, origin, summary, consumedCredentials, " +
                    "backfilled (a pre-4.0 row) and, when attribution is visible, actor and verification.",
            profiles = orchestration(),
            request = serializer<Request>(),
            errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.INVALID_CURSOR),
            handler = handler@{ env, ctx, req ->
                val page =
                    when (val p = pageRequest(req.limit, req.cursor, DEFAULT_LIMIT, MAX_LIMIT)) {
                        is Outcome.Err -> return@handler p
                        is Outcome.Ok -> p.value
                    }
                if (req.since != null && req.until != null && req.since.isAfter(req.until)) {
                    return@handler Outcome.Err(invalidField(pointer("since"), "must not be after until", req.since.toString()))
                }
                val store = env.repositoryProvider.eventStore()
                val result =
                    if (req.itemId != null) {
                        val itemId =
                            when (val r = resolveTarget(env, ctx, req.itemId, "itemId")) {
                                is Outcome.Err -> return@handler r
                                is Outcome.Ok -> r.value
                            }
                        // D10: the per-item read of a gone item is not_found (a restricted scope already answered forbidden).
                        if (env.workItemRepository().getById(itemId) == null) return@handler Outcome.Err(itemNotFound(itemId))
                        store.findByEntity(itemId, TYPES, page, req.since, req.until)
                    } else {
                        store.findByTypes(TYPES, req.since, req.until, ctx.scope, page)
                    }
                recordCallResultCounts(result.total, null)
                val listed = pageOf(result.items, result.total, page)
                Outcome.Ok(buildJsonObject { putPage(listed) { record -> entryOf(record, ctx.attributionVisible) } })
            },
            data = { it }
        )

    private const val DEFAULT_LIMIT = 50

    /** The wire entry of one `item.transitioned` row; `actor` and `verification` only when [attribution] is visible. */
    private fun entryOf(
        record: EventRecord,
        attribution: Boolean
    ): JsonElement {
        val data = parse(record.data)
        return buildJsonObject {
            put("seq", record.seq)
            put("id", record.id.toString())
            put("itemId", record.entityId.toString())
            put("occurredAt", record.occurredAt.toString())
            data.str("trigger")?.let { put("trigger", it) }
            data.str("fromRole")?.let { put("fromRole", it) }
            data.str("toRole")?.let { put("toRole", it) }
            data.str("fromStatusLabel")?.let { put("fromStatusLabel", it) }
            data.str("toStatusLabel")?.let { put("toStatusLabel", it) }
            data.str("origin")?.let { put("origin", it) }
            data.str("summary")?.let { put("summary", it) }
            val credentials = (data["consumedCredentials"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            if (credentials.isNotEmpty()) put("consumedCredentials", JsonArray(credentials.map { JsonPrimitive(it) }))
            if ((data["backfilled"] as? JsonPrimitive)?.booleanOrNull == true) put("backfilled", true)
            if (attribution) {
                record.principalId?.let { id ->
                    put(
                        "actor",
                        buildJsonObject {
                            put("id", id)
                            put("kind", record.principalKind ?: "external")
                            data.str("actorParent")?.let { put("parent", it) }
                        }
                    )
                }
                val verifier = data.str("verifier")
                val reason = data.str("verificationReason")
                val status = record.proofStatus
                if (status != null) {
                    put(
                        "verification",
                        buildJsonObject {
                            put("status", status)
                            verifier?.let { put("verifier", it) }
                            reason?.let { put("reason", it) }
                        }
                    )
                }
            }
        }
    }

    private fun parse(text: String): JsonObject =
        try {
            json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: kotlinx.serialization.SerializationException) {
            JsonObject(emptyMap())
        }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
}
