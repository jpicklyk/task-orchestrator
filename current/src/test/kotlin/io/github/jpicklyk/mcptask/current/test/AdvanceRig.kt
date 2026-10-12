package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.service.AdvanceFailure
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceResult
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Fixtures for tests that drive the production `advance` tool (transition, claim, release, heartbeat) over the real
 * dispatcher, modelled on [itemsWrite]. The removed test-only 3.x `advance_item` / `claim_item` drivers were their
 * previous entry point. Pure plumbing: no assertion logic lives here, and every oracle stays in the test itself.
 */
internal suspend fun ToolExecutionContext.advanceTool(
    vararg ops: JsonObject,
    atomic: Boolean = false,
    actor: JsonObject? = null,
    reqId: String = "a7r1g0aa",
    access: CallAccess = CallAccess.UNRESTRICTED
): JsonObject {
    val spec = checkNotNull(ToolRegistry(buildToolSpecs())["advance"]) { "advance is not registered" }
    val arguments =
        buildJsonObject {
            actor?.let { put("actor", it) }
            if (atomic) put("atomic", true)
            put("ops", JsonArray(ops.toList()))
        }
    return buildToolDispatcher(this)
        .dispatch(spec, ToolProfile.ORCHESTRATION, arguments, reqId, CallLogRecord.SURFACE_MCP, access)
        .envelope.body
}

/** [ToolExecutionContext.advanceTool] over the rig's production composition. */
internal suspend fun EventLogRig.advanceTool(
    vararg ops: JsonObject,
    atomic: Boolean = false,
    actor: JsonObject? = null,
    reqId: String = "a7r1g0aa"
): JsonObject = ctx.advanceTool(*ops, atomic = atomic, actor = actor, reqId = reqId)

/** One `transition` op for [itemId] (a UUID or an id prefix) with [trigger]; [extra] adds fields (summary, credentialRefs, idempotencyKey). */
internal fun transitionOp(
    itemId: Any,
    trigger: String,
    vararg extra: Pair<String, JsonElement>
): JsonObject =
    buildJsonObject {
        put("op", "transition")
        put("itemId", itemId.toString())
        put("trigger", trigger)
        extra.forEach { (k, v) -> put(k, v) }
    }

/** One `claim` op by id; [extra] adds fields (ttlSeconds, claimRef, idempotencyKey). */
internal fun claimOp(
    itemId: Any,
    vararg extra: Pair<String, JsonElement>
): JsonObject =
    buildJsonObject {
        put("op", "claim")
        put("itemId", itemId.toString())
        extra.forEach { (k, v) -> put(k, v) }
    }

/** One find-and-claim `claim` op with the [select] filters. */
internal fun claimSelectOp(
    select: JsonObject,
    vararg extra: Pair<String, JsonElement>
): JsonObject =
    buildJsonObject {
        put("op", "claim")
        put("select", select)
        extra.forEach { (k, v) -> put(k, v) }
    }

/** One `release` op. */
internal fun releaseOp(itemId: Any): JsonObject =
    buildJsonObject {
        put("op", "release")
        put("itemId", itemId.toString())
    }

/** One `heartbeat` op; [extra] adds fields (ttlSeconds). */
internal fun heartbeatOp(
    itemId: Any,
    vararg extra: Pair<String, JsonElement>
): JsonObject =
    buildJsonObject {
        put("op", "heartbeat")
        put("itemId", itemId.toString())
        extra.forEach { (k, v) -> put(k, v) }
    }

/** An actor object `{id, kind}` (+ [parent], [proof] when given). */
internal fun actorJson(
    id: String,
    kind: String = "subagent",
    parent: String? = null,
    proof: String? = null
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("kind", kind)
        parent?.let { put("parent", it) }
        proof?.let { put("proof", it) }
    }

/** The `error.code` of a failed batch element (or of a failed envelope); null for an `ok:true` one. */
internal fun JsonObject.errorCode(): String? =
    (this["error"] as? JsonObject)
        ?.get("code")
        ?.takeIf { it !is JsonNull }
        ?.jsonPrimitive
        ?.content

/** The `error.detail` object of a failed batch element; null when absent. */
internal fun JsonObject.errorDetail(): JsonObject? = (this["error"] as? JsonObject)?.get("detail") as? JsonObject

/** The `error.message` of a failed batch element; null for an `ok:true` one. */
internal fun JsonObject.errorMessage(): String? = (this["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.content

/** The `error.kind` of a failed batch element; null for an `ok:true` one. */
internal fun JsonObject.errorKind(): String? = (this["error"] as? JsonObject)?.get("kind")?.jsonPrimitive?.content

/** The `error.retryAfterMs` of a failed batch element; null when absent. */
internal fun JsonObject.errorRetryAfterMs(): Long? =
    (this["error"] as? JsonObject)
        ?.get("retryAfterMs")
        ?.jsonPrimitive
        ?.content
        ?.toLong()

/** The keys of a `gate_blocked` element's `error.detail.missing` list (empty when absent). */
internal fun JsonObject.missingKeys(): List<String> =
    (errorDetail()?.get("missing") as? JsonArray).orEmpty().map { it.jsonObject["key"]!!.jsonPrimitive.content }

/** The blocker ids of a `dependency_unmet` element's `error.detail.blockers` list (empty when absent). */
internal fun JsonObject.blockerIds(): List<String> =
    (errorDetail()?.get("blockers") as? JsonArray).orEmpty().map { it.jsonObject["id"]!!.jsonPrimitive.content }

/** The resource names of a `resource_unavailable` element's `error.detail.resources` list (empty when absent). */
internal fun JsonObject.contendedNames(): List<String> =
    (errorDetail()?.get("resources") as? JsonArray).orEmpty().map { it.jsonObject["name"]!!.jsonPrimitive.content }

/** The single element of a one-op `advance` envelope ([results] must hold exactly one). */
internal fun JsonObject.onlyResult(): JsonObject {
    val all = results()
    check(all.size == 1) { "expected one result: $this" }
    return all[0]
}

/** The UUID string member [key] of this object. */
internal fun JsonObject.uuid(key: String): UUID = UUID.fromString(this[key]!!.jsonPrimitive.content)

/** The `data` object of an `ok:true` envelope. */
internal fun JsonObject.dataObject(): JsonObject = this["data"]!!.jsonObject

/**
 * One advance straight through the shared [io.github.jpicklyk.mcptask.current.application.service.AdvanceService] the
 * `advance` tool's `transition` op uses, with the same switches (ownership and leases enforced, the event actor
 * installed), for suites whose oracle is the outcome, the rows or the events rather than the response.
 */
internal suspend fun ToolExecutionContext.advanceDirect(
    item: WorkItem,
    trigger: String,
    actor: ActorClaim? = null,
    verification: VerificationResult? = null,
    summary: String? = null,
    credentialRefs: List<String> = emptyList()
): AdvanceOutcome {
    val fresh = checkNotNull(workItemRepository().getById(item.id)) { "item ${item.id} does not exist" }
    // The dispatcher verifies a lifted actor before any op runs; do the same when the test supplies none.
    val verified = verification ?: actor?.let { actorVerifier().verify(it) }
    return withEventActor(actor) {
        advanceServiceFactory().forItem(fresh).advance(
            item = fresh,
            trigger = trigger,
            summary = summary,
            actorClaim = actor,
            verification = verified,
            degradedModePolicy = degradedModePolicy,
            enforceOwnership = true,
            credentialRefs = credentialRefs,
            enforceResourceLeases = true
        )
    }
}

/** The [AdvanceResult] of a successful [AdvanceOutcome]; fails with the failure otherwise. */
internal fun AdvanceOutcome.success(): AdvanceResult =
    when (this) {
        is AdvanceOutcome.Success -> result
        is AdvanceOutcome.Failure -> error("expected success, got $failure")
    }

/** The [AdvanceFailure] of a failed [AdvanceOutcome]; fails on a success. */
internal fun AdvanceOutcome.failure(): AdvanceFailure =
    when (this) {
        is AdvanceOutcome.Failure -> failure
        is AdvanceOutcome.Success -> error("expected a failure, got $result")
    }
