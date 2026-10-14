package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.application.service.TreeCompletionResult
import io.github.jpicklyk.mcptask.current.application.service.TreeCompletionService
import io.github.jpicklyk.mcptask.current.application.service.TreeItemOutcome
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance.AdvanceSupport
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance.TransitionJson
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.NoteSchemaJson
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.UUID

/** The wire name of the `complete_tree` op. */
internal object CompleteTreeOp {
    const val NAME = "complete_tree"
}

/**
 * `items_write` `complete_tree` (orchestration profile): one trigger (`complete` or `cancel`) over the descendants of
 * `rootId` (then the root itself, last) or over `itemIds`, in blocking order, through [TreeCompletionService]. Check
 * order: exactly one target form, then scope on every named id, then scope on every collected target (a tree not wholly
 * admitted is `forbidden`, nothing written), then the run. A per-item rejection is data in `items[]`, never an
 * element error; all applied transitions of the element commit together.
 */
internal fun completeTreeOp(): OpSpec<CompleteTreeRequest, ItemWriteResult> =
    OpSpec(
        name = CompleteTreeOp.NAME,
        doc = "Complete or cancel a tree's descendants (rootId) or listed items (itemIds) in dependency order; per-item outcomes.",
        profiles = orchestration(),
        capability = ApiCapability.ADVANCE,
        request = serializer<CompleteTreeRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.UNAVAILABLE
            ),
        execute = { env, ctx, req -> itemWriteBody(ctx) { completeTree(env, ctx, req) } }
    )

private suspend fun WriteScope.completeTree(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: CompleteTreeRequest
): Outcome<ItemWriteResult> {
    val listed = req.itemIds
    when {
        req.rootId != null && listed != null -> return Outcome.Err(invalidField("/rootId", "cannot be given with itemIds"))
        req.rootId == null && listed == null -> return Outcome.Err(invalidField("/rootId", "one of rootId and itemIds is required"))
        listed != null && listed.isEmpty() -> return Outcome.Err(invalidField("/itemIds", "must not be empty"))
    }

    val service = treeCompletionService(env)
    val items = env.workItemRepository()
    val rootRef = req.rootId
    val targets: List<WorkItem>
    val root: WorkItem?
    var treeRootId: UUID? = null
    if (rootRef != null) {
        val rootId =
            when (val r = resolveItemId(env, ctx.scope, rootRef, "/rootId")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
        (checkItemScope(env, ctx, rootId) as? Outcome.Err)?.let { return it }
        treeRootId = rootId
        val collected = service.descendantTargets(rootId, req.includeRoot) ?: return Outcome.Err(ItemCommandErrors.itemNotFound(rootId))
        targets = collected.first
        root = collected.second
    } else {
        val ids = ArrayList<UUID>()
        for ((i, ref) in checkNotNull(listed).withIndex()) {
            val id =
                when (val r = resolveItemId(env, ctx.scope, ref, "/itemIds/$i")) {
                    is Outcome.Err -> return r
                    is Outcome.Ok -> r.value
                }
            (checkItemScope(env, ctx, id) as? Outcome.Err)?.let { return it }
            ids += id
        }
        targets = ids.distinct().map { id -> items.getById(id) ?: return Outcome.Err(ItemCommandErrors.itemNotFound(id)) }
        root = null
    }

    // Fail closed: under a restricted scope every item the call would advance must be admitted, or nothing runs.
    if (!ctx.scope.isUnrestricted) {
        val all = (targets.map { it.id } + listOfNotNull(root?.id)).toSet()
        if (ScopeGuard.admitted(ctx.scope, all, items) != all) return Outcome.Err(scopeDenied(ctx.scope))
    }

    val completion =
        when (val run = service.run(targets, root, req.trigger.wire, ctx.principal?.claim, ctx.principal?.verification)) {
            is Outcome.Err -> return run
            is Outcome.Ok -> run.value
        }
    return Outcome.Ok(ItemWriteResult(id = treeRootId, payload = payload(req.trigger.wire, completion)))
}

/** The completion service over the call's composed services. */
internal fun treeCompletionService(env: ToolExecutionContext): TreeCompletionService =
    TreeCompletionService(env.repositoryProvider, env.advanceServiceFactory(), env.degradedModePolicy)

/** `{trigger, items, summary}`: every item's outcome in processing order, and the counts (total = the sum of the rest). */
private fun payload(
    trigger: String,
    completion: TreeCompletionResult
): JsonObject {
    var applied = 0
    var skipped = 0
    var failed = 0
    val entries =
        completion.outcomes.map { outcome ->
            buildJsonObject {
                put("itemId", outcome.itemId.toString())
                when (outcome) {
                    is TreeItemOutcome.Applied -> {
                        applied++
                        val result = outcome.result
                        put("outcome", "applied")
                        put("previousRole", TransitionJson.role(result.previousRole))
                        put("newRole", TransitionJson.role(result.newRole))
                        result.statusLabel?.let { put("statusLabel", it) }
                        TransitionJson.cascades(result.cascadeEvents)?.let { put("cascades", it) }
                        TransitionJson.unblocked(result.unblockedItems)?.let { put("unblocked", it) }
                        NoteSchemaJson.buildViolationsArrayNonEmpty(result.violations)?.let { put("violations", it) }
                    }
                    is TreeItemOutcome.AlreadyTerminal -> {
                        skipped++
                        put("outcome", "skipped")
                        put("reason", "already_terminal")
                    }
                    is TreeItemOutcome.UpstreamFailed -> {
                        skipped++
                        put("outcome", "skipped")
                        put("reason", "upstream_failed")
                        put("blockedBy", JsonArray(outcome.blockedBy.map { JsonPrimitive(it.toString()) }))
                    }
                    is TreeItemOutcome.Failed -> {
                        failed++
                        put("outcome", "failed")
                        put("error", EnvelopeEncoder.errorMember(AdvanceSupport.rooted(outcome.failure.error)))
                    }
                }
            }
        }
    return buildJsonObject {
        put("trigger", trigger)
        put("items", JsonArray(entries))
        put(
            "summary",
            buildJsonObject {
                put("total", applied + skipped + failed)
                put("applied", applied)
                put("skipped", skipped)
                put("failed", failed)
            }
        )
    }
}
