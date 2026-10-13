package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.advance.TransitionRequest
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceResult
import io.github.jpicklyk.mcptask.current.application.service.CredentialRefValidation
import io.github.jpicklyk.mcptask.current.application.service.buildDispatchProfileJson
import io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.application.support.toDomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.NoteSchemaJson
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AdvanceTransitionOp")

/**
 * `advance` op `transition`: applies a trigger to one item through the shared advance pipeline, with claim ownership
 * and resource leases enforced on every surface (task-scope Q13 D2, D3).
 */
internal val transitionOp =
    OpSpec<TransitionRequest, JsonElement>(
        name = "transition",
        doc =
            "Move one item between phases with a trigger (start, complete, block, hold, resume, cancel, reopen). " +
                "Gates, dependencies, claim ownership and resource leases apply; the result lists cascaded parents, " +
                "unblocked items and what the next phase expects.",
        profiles = orchestration(),
        capability = ApiCapability.ADVANCE,
        request = serializer<TransitionRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.INVALID_TRANSITION,
                ErrorCode.GATE_BLOCKED,
                ErrorCode.DEPENDENCY_UNMET,
                ErrorCode.NOT_CLAIM_HOLDER,
                ErrorCode.RESOURCE_UNAVAILABLE,
                ErrorCode.UNAUTHENTICATED,
                ErrorCode.UNAVAILABLE
            ),
        execute = { env, ctx, req -> runTransition(env, ctx, req) }
    )

private suspend fun WriteScope.runTransition(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: TransitionRequest
): Outcome<JsonElement> {
    val refs = req.credentialRefs.orEmpty()
    (CredentialRefValidation.validate(refs) as? CredentialRefValidation.Result.Invalid)?.let { invalid ->
        val field = if (invalid.index >= 0) "/credentialRefs/${invalid.index}" else "/credentialRefs"
        return Outcome.Err(invalidField(field, invalid.reason))
    }

    val id =
        when (val resolved = AdvanceSupport.resolveInScope(stores, ctx, req.itemId)) {
            is Outcome.Err -> return resolved
            is Outcome.Ok -> resolved.value
        }
    val item =
        when (val loaded = AdvanceSupport.loadItem(stores, id)) {
            is Outcome.Err -> return loaded
            is Outcome.Ok -> loaded.value
        }

    val claim = ctx.principal?.claim
    val outcome =
        try {
            env.advanceServiceFactory().forItem(item).advance(
                item = item,
                trigger = req.trigger.wire,
                summary = req.summary?.takeIf { it.isNotBlank() },
                actorClaim = claim,
                verification = ctx.principal?.verification,
                degradedModePolicy = env.degradedModePolicy,
                enforceOwnership = true,
                credentialRefs = refs,
                enforceResourceLeases = true
            )
        } catch (e: PerRootConfigUnavailableException) {
            return Outcome.Err(AdvanceSupport.configUnavailable(e.message))
        } catch (e: PerRootConfigInvalidException) {
            return Outcome.Err(e.toDomainError())
        }

    return when (outcome) {
        is AdvanceOutcome.Failure -> Outcome.Err(AdvanceSupport.rooted(outcome.failure.error))
        is AdvanceOutcome.Success -> Outcome.Ok(element(env, item, outcome.result))
    }
}

/** The `transition` result element, in the documented key order; empty and null members are omitted. */
private suspend fun WriteScope.element(
    env: ToolExecutionContext,
    item: WorkItem,
    result: AdvanceResult
): JsonObject {
    val schema = result.resolvedSchema
    val notesByKey = if (schema == null) emptyMap() else stores.noteRepository().findByItemId(item.id).associateBy { it.key }
    val phase = schema?.let { computePhaseNoteContext(result.newRole, it, notesByKey) }
    val expectedKeys =
        schema
            ?.notes
            ?.filter { it.role == result.newRole }
            ?.map { it.key }
            .orEmpty()
    // The dispatch hint decorates a transition that already happened: a config read fault omits it (D7).
    val dispatch =
        omitOnConfigUnavailable(logger, "dispatch profile", item.id) {
            env.resolveDispatchProfile(item, result.newRole, schema)
        }

    return buildJsonObject {
        put("itemId", result.itemId.toString())
        put("previousRole", TransitionJson.role(result.previousRole))
        put("newRole", TransitionJson.role(result.newRole))
        put("trigger", result.trigger)
        result.statusLabel?.let { put("statusLabel", it) }
        TransitionJson.cascades(result.cascadeEvents)?.let { put("cascades", it) }
        TransitionJson.unblocked(result.unblockedItems)?.let { put("unblocked", it) }
        if (expectedKeys.isNotEmpty()) put("expectedNotes", JsonArray(expectedKeys.map { JsonPrimitive(it) }))
        phase?.guidanceKey?.let { put("guidanceKey", it) }
        phase?.skillPointer?.let { put("skillPointer", it) }
        dispatch?.let { put("dispatch", buildDispatchProfileJson(it)) }
        NoteSchemaJson.buildViolationsArrayNonEmpty(result.violations)?.let { put("violations", it) }
    }
}
