package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.advance.TransitionRequest
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.AdvanceCascadeEvent
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceResult
import io.github.jpicklyk.mcptask.current.application.service.BlockerInfo
import io.github.jpicklyk.mcptask.current.application.service.CredentialRefValidation
import io.github.jpicklyk.mcptask.current.application.service.buildDispatchProfileJson
import io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.omitOnConfigUnavailable
import io.github.jpicklyk.mcptask.current.application.tools.workflow.NoteSchemaJsonHelpers
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
        profile = ToolProfile.ORCHESTRATION,
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
            withEventActor(claim) {
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
            }
        } catch (e: PerRootConfigUnavailableException) {
            return Outcome.Err(AdvanceSupport.configUnavailable(e.message))
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
        put("previousRole", wire(result.previousRole))
        put("newRole", wire(result.newRole))
        put("trigger", result.trigger)
        result.statusLabel?.let { put("statusLabel", it) }
        if (result.cascadeEvents.isNotEmpty()) put("cascades", JsonArray(result.cascadeEvents.map(::cascade)))
        if (result.unblockedItems.isNotEmpty()) {
            put(
                "unblocked",
                buildJsonArray {
                    result.unblockedItems.forEach {
                        add(
                            buildJsonObject {
                                put("itemId", it.itemId.toString())
                                put("title", it.title)
                            }
                        )
                    }
                }
            )
        }
        if (expectedKeys.isNotEmpty()) put("expectedNotes", JsonArray(expectedKeys.map { JsonPrimitive(it) }))
        phase?.guidanceKey?.let { put("guidanceKey", it) }
        phase?.skillPointer?.let { put("skillPointer", it) }
        dispatch?.let { put("dispatch", buildDispatchProfileJson(it)) }
        NoteSchemaJsonHelpers.buildViolationsArrayNonEmpty(result.violations)?.let { put("violations", it) }
    }
}

private fun cascade(event: AdvanceCascadeEvent): JsonObject =
    buildJsonObject {
        put("itemId", event.itemId.toString())
        put("previousRole", wire(event.previousRole))
        put("targetRole", wire(event.targetRole))
        put("applied", event.applied)
        val blocked =
            when {
                event.gateBlocked -> "gate"
                event.resourceBlocked -> "resource"
                event.roleBlocked -> "role"
                event.dependencyBlocked -> "dependency"
                else -> null
            }
        blocked?.let { put("blocked", it) }
        if (event.gateBlocked && event.gateMissingNotes.isNotEmpty()) {
            put(
                "missing",
                buildJsonArray {
                    event.gateMissingNotes.forEach { note ->
                        add(
                            buildJsonObject {
                                put("key", note.key)
                                put("role", wire(note.role))
                                note.seat?.let { put("seat", it) }
                            }
                        )
                    }
                }
            )
        }
        if (event.resourceBlocked && event.contendedResources.isNotEmpty()) {
            put("contended", JsonArray(event.contendedResources.map { JsonPrimitive(it) }))
        }
        if (event.dependencyBlocked && event.blockers.isNotEmpty()) {
            put(
                "blockers",
                buildJsonArray {
                    event.blockers.forEach { blocker ->
                        add(
                            buildJsonObject {
                                put("id", blocker.fromItemId.toString())
                                put("role", blocker.currentRole?.let(::wire) ?: BlockerInfo.UNKNOWN_ROLE)
                            }
                        )
                    }
                }
            )
        }
    }

private fun wire(role: Role): String = role.name.lowercase()
