package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.AdvanceFailure
import io.github.jpicklyk.mcptask.current.application.service.buildMissingBySeatJson
import io.github.jpicklyk.mcptask.current.application.tools.workflow.NoteSchemaJsonHelpers
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorKind
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.ToolError
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The closed set of 3.x MCP error codes, each bound to the catalog [ErrorCode] that classifies it. [wire] is the
 * unchanged 3.x string; [catalog] supplies the `kind` (and is the default classification). A tool site names one of
 * these instead of a free string, so every MCP error is classified exactly once, here.
 */
enum class LegacyMcpCode(
    val wire: String,
    val catalog: ErrorCode
) {
    // The [ErrorCodes] top-level constants.
    VALIDATION_ERROR(ErrorCodes.VALIDATION_ERROR, ErrorCode.INVALID_REQUEST),
    RESOURCE_NOT_FOUND(ErrorCodes.RESOURCE_NOT_FOUND, ErrorCode.NOT_FOUND),
    DATABASE_ERROR(ErrorCodes.DATABASE_ERROR, ErrorCode.INTERNAL),
    CONFLICT_ERROR(ErrorCodes.CONFLICT_ERROR, ErrorCode.DUPLICATE),
    INTERNAL_ERROR(ErrorCodes.INTERNAL_ERROR, ErrorCode.INTERNAL),
    OPERATION_FAILED(ErrorCodes.OPERATION_FAILED, ErrorCode.INVALID_TRANSITION),

    // Tool-local codes (advance_item / complete_tree / manage_* / claim_item).
    ITEM_NOT_FOUND("item_not_found", ErrorCode.NOT_FOUND),
    INVALID_TRIGGER("invalid_trigger", ErrorCode.INVALID_REQUEST),
    INVALID_ACTOR("invalid_actor", ErrorCode.INVALID_REQUEST),
    GATE_BLOCKED("gate_blocked", ErrorCode.GATE_BLOCKED),
    DEPENDENCY_BLOCKED("dependency_blocked", ErrorCode.DEPENDENCY_UNMET),
    VALIDATION_FAILED("validation_failed", ErrorCode.INVALID_REQUEST),
    INVALID_TRANSITION("invalid_transition", ErrorCode.INVALID_TRANSITION),
    APPLY_FAILED("apply_failed", ErrorCode.INTERNAL),
    NOT_CLAIM_HOLDER("not_claim_holder", ErrorCode.NOT_CLAIM_HOLDER),
    REJECTED_BY_POLICY("rejected_by_policy", ErrorCode.UNAUTHENTICATED),
    RESOURCE_UNAVAILABLE("resource_unavailable", ErrorCode.RESOURCE_UNAVAILABLE),
    CONFIG_UNAVAILABLE(PerRootConfigUnavailableException.CODE, ErrorCode.INTERNAL),
    NOTE_BODY_TOO_LONG("NOTE_BODY_TOO_LONG", ErrorCode.NOTE_TOO_LONG),
    NOTE_BODY_TOO_LARGE("NOTE_BODY_TOO_LARGE", ErrorCode.PAYLOAD_TOO_LARGE),
    IDEMPOTENCY_MISMATCH("idempotency_mismatch", ErrorCode.IDEMPOTENCY_MISMATCH),

    // claim_item outcomes that carry a kind/code.
    ALREADY_CLAIMED("already_claimed", ErrorCode.CLAIM_HELD),
    NOT_FOUND("not_found", ErrorCode.NOT_FOUND),
    TERMINAL_ITEM("terminal_item", ErrorCode.INVALID_TRANSITION),
    NOT_CLAIMED_BY_YOU("not_claimed_by_you", ErrorCode.NOT_CLAIM_HOLDER),
    QUEUE_EMPTY("queue_empty", ErrorCode.NOT_FOUND),
    NONE_ELIGIBLE("none_eligible", ErrorCode.CLAIM_HELD),
    DB_ERROR("db_error", ErrorCode.INTERNAL)
}

/**
 * The only builder of MCP error JSON: top-level envelopes (`{success:false, error:{code, message, kind, ...}}`),
 * per-element failure fields (`errorCode`/`errorKind`) and the `advance_item` / `complete_tree` per-transition
 * failure shapes. The 3.x wire codes and messages are unchanged; `kind` is always the catalog code's kind
 * (the [DomainError]'s own when the failure carries one).
 */
object LegacyMcpErrorMapper {
    /** The kind of [code], or of [cause] when the failure carries a catalog error. */
    fun kindOf(
        code: LegacyMcpCode,
        cause: DomainError? = null
    ): ErrorKind = cause?.kind ?: code.catalog.kind

    /** The wire record of a failure: [code]'s 3.x string, [message], and the catalog kind. */
    fun toolError(
        code: LegacyMcpCode,
        message: String,
        cause: DomainError? = null,
        details: String? = null,
        retryAfterMs: Long? = null,
        contendedItemId: UUID? = null
    ): ToolError =
        ToolError(
            kind = kindOf(code, cause),
            code = code.wire,
            message = message,
            retryAfterMs = retryAfterMs,
            contendedItemId = contendedItemId,
            details = details
        )

    /** A top-level error envelope for [code]. */
    fun envelope(
        message: String,
        code: LegacyMcpCode = LegacyMcpCode.VALIDATION_ERROR,
        details: String? = null,
        additionalData: JsonElement? = null,
        cause: DomainError? = null
    ): JsonObject = ResponseUtil.createErrorResponse(toolError(code, message, cause, details), additionalData)

    /** A top-level error envelope for an already-built [toolError]. */
    fun envelope(
        toolError: ToolError,
        additionalData: JsonElement? = null
    ): JsonObject = ResponseUtil.createErrorResponse(toolError, additionalData)

    // ──────────────────────────────────────────────
    // advance_item / complete_tree classification
    // ──────────────────────────────────────────────

    /** The ONE classification of an [AdvanceFailure]: its 3.x wire code. Its kind is `failure.error.kind`. */
    fun codeOf(failure: AdvanceFailure): LegacyMcpCode =
        when (failure) {
            is AdvanceFailure.OwnershipRejected -> LegacyMcpCode.NOT_CLAIM_HOLDER
            is AdvanceFailure.PolicyRejected -> LegacyMcpCode.REJECTED_BY_POLICY
            is AdvanceFailure.ResourceLeaseUnavailable -> LegacyMcpCode.RESOURCE_UNAVAILABLE
            is AdvanceFailure.ResolutionFailed -> LegacyMcpCode.INVALID_TRANSITION
            is AdvanceFailure.ApplyFailed -> LegacyMcpCode.APPLY_FAILED
            is AdvanceFailure.ValidationFailed ->
                if (failure.blockers.isNotEmpty()) LegacyMcpCode.DEPENDENCY_BLOCKED else LegacyMcpCode.VALIDATION_FAILED
            is AdvanceFailure.GateBlocked -> LegacyMcpCode.GATE_BLOCKED
        }

    /** The kind of an [AdvanceFailure]: its catalog error's kind. */
    fun kindOf(failure: AdvanceFailure): ErrorKind = failure.error.kind

    // ──────────────────────────────────────────────
    // advance_item per-transition shapes
    // ──────────────────────────────────────────────

    /**
     * An `applied:false` per-transition failure outside the service (item resolution, trigger parse, actor parse,
     * idempotency mismatch): `itemId`, `trigger`?, `applied`, `error`, `errorCode`, `errorKind`.
     */
    fun transitionFailure(
        itemId: String,
        trigger: String?,
        message: String,
        code: LegacyMcpCode
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(itemId))
            if (trigger != null) put("trigger", JsonPrimitive(trigger))
            put("applied", JsonPrimitive(false))
            put("error", JsonPrimitive(message))
            put("errorCode", JsonPrimitive(code.wire))
            put("errorKind", JsonPrimitive(kindOf(code).toJsonString()))
        }

    /** The `advance_item` failure of a transition whose per-root config could not be read (`config_unavailable`, transient). */
    fun configUnavailable(
        itemId: UUID,
        trigger: String,
        message: String
    ): JsonObject = structuredTransitionFailure(itemId, trigger, toolError(LegacyMcpCode.CONFIG_UNAVAILABLE, message))

    /** The `advance_item` per-transition failure of a structured [AdvanceFailure]. */
    fun advanceFailure(
        itemId: UUID,
        trigger: String,
        failure: AdvanceFailure
    ): JsonObject {
        val code = codeOf(failure)
        val kind = kindOf(failure)
        return when (failure) {
            is AdvanceFailure.OwnershipRejected ->
                structuredTransitionFailure(
                    itemId,
                    trigger,
                    ToolError(kind, code.wire, failure.message, contendedItemId = itemId)
                )
            is AdvanceFailure.PolicyRejected ->
                structuredTransitionFailure(itemId, trigger, ToolError(kind, code.wire, failure.reason))
            is AdvanceFailure.ResourceLeaseUnavailable ->
                structuredTransitionFailure(
                    itemId,
                    trigger,
                    ToolError(kind, code.wire, failure.message, retryAfterMs = failure.retryAfterMs),
                    contendedResources = failure.contendedResources
                )
            is AdvanceFailure.ResolutionFailed -> codedTransitionFailure(itemId, trigger, failure.message, code, kind)
            is AdvanceFailure.ApplyFailed -> codedTransitionFailure(itemId, trigger, failure.message, code, kind)
            is AdvanceFailure.ValidationFailed ->
                codedTransitionFailure(
                    itemId,
                    trigger,
                    failure.message,
                    code,
                    kind,
                    blockers =
                        if (failure.blockers.isNotEmpty()) NoteSchemaJsonHelpers.buildBlockersArray(failure.blockers) else null
                )
            is AdvanceFailure.GateBlocked ->
                codedTransitionFailure(
                    itemId,
                    trigger,
                    failure.message,
                    code,
                    kind,
                    missingNotes = NoteSchemaJsonHelpers.buildMissingNotesArray(failure.missingNotes),
                    missingBySeat = buildMissingBySeatJson(failure.missingBySeat),
                    previousRole = failure.previousRole.toJsonString(),
                    targetRole = failure.targetRole.toJsonString(),
                    violations = NoteSchemaJsonHelpers.buildViolationsArrayNonEmpty(failure.violations)
                )
        }
    }

    private fun codedTransitionFailure(
        itemId: UUID,
        trigger: String,
        message: String,
        code: LegacyMcpCode,
        kind: ErrorKind,
        blockers: JsonArray? = null,
        missingNotes: JsonArray? = null,
        missingBySeat: JsonObject? = null,
        previousRole: String? = null,
        targetRole: String? = null,
        violations: JsonArray? = null
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("trigger", JsonPrimitive(trigger))
            put("applied", JsonPrimitive(false))
            put("error", JsonPrimitive(message))
            put("errorCode", JsonPrimitive(code.wire))
            put("errorKind", JsonPrimitive(kind.toJsonString()))
            if (blockers != null) put("blockers", blockers)
            if (missingNotes != null) put("missingNotes", missingNotes)
            if (missingBySeat != null) put("missingBySeat", missingBySeat)
            previousRole?.let { put("previousRole", JsonPrimitive(it)) }
            targetRole?.let { put("targetRole", JsonPrimitive(it)) }
            if (violations != null) put("violations", violations)
        }

    private fun structuredTransitionFailure(
        itemId: UUID,
        trigger: String,
        toolError: ToolError,
        contendedResources: List<String> = emptyList()
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("trigger", JsonPrimitive(trigger))
            put("applied", JsonPrimitive(false))
            put("error", JsonPrimitive(toolError.message))
            put("errorKind", JsonPrimitive(toolError.kind.toJsonString()))
            put("errorCode", JsonPrimitive(toolError.code))
            toolError.retryAfterMs?.let { put("retryAfterMs", JsonPrimitive(it)) }
            toolError.contendedItemId?.let { put("contendedItemId", JsonPrimitive(it.toString())) }
            if (contendedResources.isNotEmpty()) {
                put("contendedResources", JsonArray(contendedResources.map { JsonPrimitive(it) }))
            }
        }

    // ──────────────────────────────────────────────
    // complete_tree per-item shapes
    // ──────────────────────────────────────────────

    /**
     * The `complete_tree` per-item failure of a structured [AdvanceFailure] (same classification as `advance_item`):
     * a gate block keeps `gateErrors` + `missingNotes`; a dependency/credential validation failure keeps `error` +
     * `blockers` (and is NOT a skip); every other variant is `skipped` + `skippedReason`. Every variant carries
     * `errorCode` + `errorKind`.
     */
    fun completeTreeFailure(
        item: WorkItem,
        failure: AdvanceFailure
    ): JsonObject {
        val code = codeOf(failure)
        val kind = kindOf(failure)
        return buildJsonObject {
            put("itemId", JsonPrimitive(item.id.toString()))
            put("title", JsonPrimitive(item.title))
            put("applied", JsonPrimitive(false))
            when (failure) {
                is AdvanceFailure.GateBlocked -> {
                    put("gateErrors", JsonArray(failure.missingNotes.map { JsonPrimitive("missing: ${it.key}") }))
                    put("error", JsonPrimitive(failure.message))
                    putCodeAndKind(code, kind)
                    put("missingNotes", NoteSchemaJsonHelpers.buildMissingNotesArray(failure.missingNotes))
                    put("previousRole", JsonPrimitive(failure.previousRole.toJsonString()))
                    put("targetRole", JsonPrimitive(failure.targetRole.toJsonString()))
                    NoteSchemaJsonHelpers.buildViolationsArrayNonEmpty(failure.violations)?.let { put("violations", it) }
                }
                is AdvanceFailure.OwnershipRejected -> {
                    putSkipped(failure.message)
                    putStructured(ToolError(kind, code.wire, failure.message, contendedItemId = item.id))
                }
                is AdvanceFailure.PolicyRejected -> {
                    putSkipped(failure.reason)
                    putStructured(ToolError(kind, code.wire, failure.reason))
                }
                is AdvanceFailure.ResourceLeaseUnavailable -> {
                    putSkipped(failure.message)
                    putStructured(ToolError(kind, code.wire, failure.message, retryAfterMs = failure.retryAfterMs))
                    put("contendedResources", JsonArray(failure.contendedResources.map { JsonPrimitive(it) }))
                }
                is AdvanceFailure.ValidationFailed -> {
                    // NOT a skip: the item failed its OWN dependency validation (see advance_item).
                    put("error", JsonPrimitive(failure.message))
                    putCodeAndKind(code, kind)
                    if (failure.blockers.isNotEmpty()) put("blockers", NoteSchemaJsonHelpers.buildBlockersArray(failure.blockers))
                }
                is AdvanceFailure.ResolutionFailed -> {
                    putSkipped(failure.message)
                    putStructured(ToolError(kind, code.wire, failure.message))
                }
                is AdvanceFailure.ApplyFailed -> {
                    putSkipped(failure.message)
                    putStructured(ToolError(kind, code.wire, failure.message))
                }
            }
        }
    }

    /** A `complete_tree` per-item skip that never reached the service (a skipped dependent, an already-terminal item). */
    fun completeTreeSkipped(
        item: WorkItem,
        reason: String,
        code: ErrorCode
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(item.id.toString()))
            put("title", JsonPrimitive(item.title))
            put("applied", JsonPrimitive(false))
            put("skipped", JsonPrimitive(true))
            put("skippedReason", JsonPrimitive(reason))
            putElementError(code)
        }

    /** The `complete_tree` per-item failure of an item whose per-root config could not be read (`skipped`, transient). */
    fun completeTreeConfigUnavailable(
        item: WorkItem,
        message: String
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(item.id.toString()))
            put("title", JsonPrimitive(item.title))
            put("applied", JsonPrimitive(false))
            putSkipped(message)
            put("errorKind", JsonPrimitive(kindOf(LegacyMcpCode.CONFIG_UNAVAILABLE).toJsonString()))
            put("errorCode", JsonPrimitive(LegacyMcpCode.CONFIG_UNAVAILABLE.wire))
        }

    private fun JsonObjectBuilder.putSkipped(reason: String) {
        put("skipped", JsonPrimitive(true))
        put("skippedReason", JsonPrimitive(reason))
        put("error", JsonPrimitive(reason))
    }

    private fun JsonObjectBuilder.putCodeAndKind(
        code: LegacyMcpCode,
        kind: ErrorKind
    ) {
        put("errorCode", JsonPrimitive(code.wire))
        put("errorKind", JsonPrimitive(kind.toJsonString()))
    }

    private fun JsonObjectBuilder.putStructured(toolError: ToolError) {
        put("errorKind", JsonPrimitive(toolError.kind.toJsonString()))
        put("errorCode", JsonPrimitive(toolError.code))
        toolError.retryAfterMs?.let { put("retryAfterMs", JsonPrimitive(it)) }
        toolError.contendedItemId?.let { put("contendedItemId", JsonPrimitive(it.toString())) }
    }
}

// ──────────────────────────────────────────────
// Per-element failure fields
// ──────────────────────────────────────────────

/** Adds `errorCode` (the catalog wire code) and `errorKind` for an element failure classified as [code]. */
fun JsonObjectBuilder.putElementError(code: ErrorCode) {
    put("errorCode", JsonPrimitive(code.wire))
    put("errorKind", JsonPrimitive(code.kind.toJsonString()))
}

/** Adds `errorCode`/`errorKind` for an element failure that carries a catalog [error]. */
fun JsonObjectBuilder.putElementError(error: DomainError) = putElementError(error.code)

/** Adds `errorCode` (the 3.x wire string) and `errorKind` for an element failure classified as [code]. */
fun JsonObjectBuilder.putElementCode(
    code: LegacyMcpCode,
    cause: DomainError? = null
) {
    put("errorCode", JsonPrimitive(code.wire))
    put("errorKind", JsonPrimitive(LegacyMcpErrorMapper.kindOf(code, cause).toJsonString()))
}

/** Adds the claim result's `kind` and `code` (= the outcome's wire value) for a failed [outcome]. */
fun JsonObjectBuilder.putOutcomeKindAndCode(outcome: LegacyMcpCode) {
    put("kind", JsonPrimitive(outcome.catalog.kind.toJsonString()))
    put("code", JsonPrimitive(outcome.wire))
}
