package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The published `detail` shape of each error code, as `describe` lists it: member name to a short type
 * text. The member set is the envelope design's catalog (section 4) as `EnvelopeEncoder.detail` emits
 * it: `config_invalid` is `{errors}` (Q1a naming).
 * A code with no detail (`invalid_cursor`, `unauthenticated`, `partial_failure`, `internal`) has none.
 */
object ErrorDetailDocs {
    private const val ID = "string (UUID)"
    private const val ENTITY_KIND = "string: item | note | dependency | document | run | root | lease"

    private val docs: Map<ErrorCode, LinkedHashMap<String, String>> =
        mapOf(
            ErrorCode.INVALID_REQUEST to
                linkedMapOf("fields" to "array of {field: JSON pointer, reason: string, received?: string}"),
            ErrorCode.UNKNOWN_PARAMETER to linkedMapOf("parameters" to "array of JSON pointers"),
            ErrorCode.NOT_FOUND to linkedMapOf("kind" to ENTITY_KIND, "id" to "string, optional"),
            ErrorCode.AMBIGUOUS_ID to linkedMapOf("prefix" to "string", "candidates" to "array of $ID"),
            ErrorCode.VERSION_CONFLICT to
                linkedMapOf("kind" to ENTITY_KIND, "id" to "string", "expected" to "integer", "actual" to "integer"),
            ErrorCode.DUPLICATE to
                linkedMapOf("kind" to ENTITY_KIND, "id" to "string, optional", "existingId" to "string, optional"),
            ErrorCode.IDEMPOTENCY_MISMATCH to linkedMapOf("idempotencyKey" to "string"),
            ErrorCode.INVALID_TRANSITION to
                linkedMapOf("itemId" to ID, "fromRole" to "string", "trigger" to "string", "allowed" to "array of string"),
            ErrorCode.GATE_BLOCKED to
                linkedMapOf(
                    "itemId" to ID,
                    "role" to "string",
                    "missing" to "array of {key: string, role: string, seat?: string}, empty when only violations block",
                    "violations" to "array of {gate: string, reason: string}, optional"
                ),
            ErrorCode.DEPENDENCY_UNMET to linkedMapOf("itemId" to ID, "blockers" to "array of {id: $ID, role: string}"),
            ErrorCode.CYCLE_DETECTED to linkedMapOf("path" to "array of $ID"),
            ErrorCode.CLAIM_HELD to
                linkedMapOf("itemId" to ID, "expiresAt" to "string (ISO-8601 instant)", "retryAfterMs" to "integer"),
            ErrorCode.NOT_CLAIM_HOLDER to linkedMapOf("itemId" to ID),
            ErrorCode.SEAT_FORBIDDEN to
                linkedMapOf("itemId" to ID, "seat" to "string", "action" to "string", "allowedSeats" to "array of string"),
            ErrorCode.NOTE_OWNED_BY_OTHER to linkedMapOf("itemId" to ID, "key" to "string", "ownerSeat" to "string, optional"),
            ErrorCode.NOTE_TOO_LONG to linkedMapOf("key" to "string", "max" to "integer", "actual" to "integer"),
            ErrorCode.RESOURCE_UNAVAILABLE to
                linkedMapOf(
                    "itemId" to ID,
                    "resources" to "array of {name: string, mode: string}",
                    "retryAfterMs" to "integer, optional"
                ),
            ErrorCode.RUN_CLOSED to linkedMapOf("runId" to ID, "terminalReason" to "string"),
            ErrorCode.RUN_PAUSED to
                linkedMapOf("runId" to ID, "pauseReason" to "string: budget | attempts | manual", "pauseDecisionId" to "$ID, optional"),
            ErrorCode.INVALID_RUN_STATE to
                linkedMapOf("runId" to ID, "status" to "string: open | paused | closed", "allowed" to "array of string"),
            ErrorCode.SCHEMA_VIOLATION to
                linkedMapOf(
                    "type" to "string, optional",
                    "reason" to "string",
                    "availableTypes" to "array of string, optional",
                    "availableTraits" to "array of string, optional"
                ),
            ErrorCode.SCHEMA_PINNED_CONFLICT to
                linkedMapOf("itemId" to ID, "pinnedVersion" to "string", "currentVersion" to "string"),
            ErrorCode.CONFIG_INVALID to linkedMapOf("errors" to "array of {path: string, reason: string}"),
            ErrorCode.PAYLOAD_TOO_LARGE to linkedMapOf("max" to "integer", "actual" to "integer"),
            ErrorCode.FORBIDDEN to linkedMapOf("scope" to "string: root | tag | capability", "required" to "string"),
            ErrorCode.UNAVAILABLE to linkedMapOf("retryAfterMs" to "integer")
        )

    init {
        // Exactly the codes that define a detail are documented.
        val withDetail = ErrorCode.entries.filter { it.detailClass != null }.toSet()
        check(docs.keys == withDetail) { "ErrorDetailDocs must cover exactly ${withDetail.map { it.wire }}" }
    }

    /** The `detail` shape of [code] as `{member: type text}`, or null when the code has no detail. */
    fun of(code: ErrorCode): JsonObject? = docs[code]?.let { members -> JsonObject(members.mapValues { JsonPrimitive(it.value) }) }
}
