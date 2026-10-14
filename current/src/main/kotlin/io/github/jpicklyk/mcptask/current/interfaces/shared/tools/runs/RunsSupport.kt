package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunBudgetRequest
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunBudget
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Helpers shared by the `runs_read` and `runs_write` handlers. */
internal object RunsSupport {
    /** A mark key: lowercase letters, digits, `-` and `_`, starting with a letter, at most 64 characters. */
    val MARK_KEY = Regex("^[a-z][a-z0-9_-]{0,63}$")

    private const val UUID_LENGTH = 36
    private const val PREFIX_PROBE = 2
    private const val RESTRICTED_PREFIX_PROBE = 200

    /**
     * Resolves a run id as the caller wrote it, under the call scope (the ScopedIds rule, for runs): a full UUID
     * resolves with no read, a hex prefix through
     * [io.github.jpicklyk.mcptask.current.application.port.RunStore.findIdsByPrefix]. Unrestricted scope: no match is
     * `not_found`, two or more `ambiguous_id`. Restricted scope: no match is `forbidden` (never `not_found`), and an
     * ambiguous prefix keeps only the candidates [ScopeGuard] admits (none is `forbidden`, one resolves, two or more
     * is `ambiguous_id` listing only those). Whether a full UUID names a run is the loader question.
     */
    suspend fun resolveRunId(
        stores: RepositoryProvider,
        scope: AccessScope,
        ref: IdRef
    ): Outcome<UUID> {
        if (ref.raw.length == UUID_LENGTH) return Outcome.Ok(UUID.fromString(ref.raw))
        val restricted = !scope.isUnrestricted
        val probe = if (restricted) RESTRICTED_PREFIX_PROBE else PREFIX_PROBE
        var hits = stores.runStore().findIdsByPrefix(ref.raw, probe)
        if (restricted) {
            hits =
                hits.filter { id ->
                    val run = stores.runStore().getById(id)
                    run != null && ScopeGuard.check(scope, run.rootId, stores.workItemRepository()) is Outcome.Ok
                }
        }
        return when (hits.size) {
            0 -> Outcome.Err(if (restricted) ScopedIds.denied(scope) else runNotFound(ref.raw))
            1 -> Outcome.Ok(hits.single())
            else ->
                Outcome.Err(
                    DomainError(
                        code = ErrorCode.AMBIGUOUS_ID,
                        message = "The id ${ref.raw} matches more than one run.",
                        detail = ErrorDetail.AmbiguousId(ref.raw, hits),
                        fixArgs = mapOf("prefix" to ref.raw)
                    )
                )
        }
    }

    /**
     * Resolves and loads the run [ref], then checks the call scope against the run root BEFORE the caller can reach
     * any kind or coordinator predicate: a run out of scope is `forbidden`, never an existence oracle, and under a
     * restricted scope an unknown run is `forbidden` too.
     */
    suspend fun loadRun(
        stores: RepositoryProvider,
        ctx: CallContext,
        ref: IdRef
    ): Outcome<Run> {
        val id =
            when (val r = resolveRunId(stores, ctx.scope, ref)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        val run =
            stores.runStore().getById(id)
                ?: return Outcome.Err(if (ctx.scope.isUnrestricted) runNotFound(id.toString()) else ScopedIds.denied(ctx.scope))
        return when (val guard = ScopeGuard.check(ctx.scope, run.rootId, stores.workItemRepository())) {
            is Outcome.Err -> guard
            is Outcome.Ok -> Outcome.Ok(run)
        }
    }

    /** [run] as its wire view: the member count and active seats are read now. */
    suspend fun view(
        env: ToolExecutionContext,
        stores: RepositoryProvider,
        run: Run
    ): JsonObject =
        RunView.of(
            run = run,
            members = stores.runStore().members(run.id).size,
            activeSeats = env.runService.activeSeats(run.id),
            now = env.clock.now()
        )

    /** The request budget as a domain budget (dollars to micro-USD); `invalid_request` on [field] for a cost that is not a number of dollars. */
    fun budget(
        request: RunBudgetRequest?,
        field: String
    ): Outcome<RunBudget?> {
        if (request == null) return Outcome.Ok(null)
        var micro: Long? = null
        if (request.costUsd != null) {
            micro = ConfigValidator.costMicroUsd(request.costUsd)
            if (micro == null) {
                return Outcome.Err(invalid("$field/costUsd", "must be a finite number of US dollars", request.costUsd.toString()))
            }
        }
        return Outcome.Ok(RunBudget(request.tokens, micro, request.attemptsPerSeat, request.maxActiveSeats))
    }

    /** An `invalid_request` on one JSON-pointer [field]. */
    fun invalid(
        field: String,
        reason: String,
        received: String? = null
    ): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = "Invalid $field: $reason",
            detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(field, reason, received)))
        )

    fun notFoundItem(id: UUID): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = "No item matches $id.",
            detail = ErrorDetail.NotFound(EntityKind.ITEM, id.toString()),
            fixArgs = mapOf("kind" to "item", "id" to id.toString())
        )

    fun runNotFound(id: String): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = "No run matches $id.",
            detail = ErrorDetail.NotFound(EntityKind.RUN, id),
            fixArgs = mapOf("kind" to "run", "id" to id)
        )

    /**
     * Rewrites the field names of a service `invalid_request` as JSON pointers (`items[0]` becomes `/items/0`,
     * `budget.costMicroUsd` becomes `/budget/costUsd`): the wire never shows micro-USD or index brackets. Other
     * errors pass through.
     */
    fun <T> wire(outcome: Outcome<T>): Outcome<T> {
        if (outcome !is Outcome.Err) return outcome
        val detail = outcome.error.detail as? ErrorDetail.InvalidRequest ?: return outcome
        val fields = detail.fields.map { it.copy(field = pointer(it.field)) }
        return Outcome.Err(outcome.error.copy(detail = ErrorDetail.InvalidRequest(fields)))
    }

    private fun pointer(field: String): String {
        if (field.startsWith("/")) return field
        val plain = field.replace("costMicroUsd", "costUsd").replace('[', '.').replace("]", "")
        return "/" + plain.replace('.', '/')
    }
}
