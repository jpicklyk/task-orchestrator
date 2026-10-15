package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.RunRefs
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
import java.time.Instant
import java.util.UUID

/** Helpers shared by the `runs_read` and `runs_write` handlers. */
internal object RunsSupport {
    /** A mark key: lowercase letters, digits, `-` and `_`, starting with a letter, at most 64 characters. */
    val MARK_KEY = Regex("^[a-z][a-z0-9_-]{0,63}$")

    /** Resolves a run id as the caller wrote it, under [scope]: [RunRefs.resolveRunId] (the rule lives in the application layer). */
    suspend fun resolveRunId(
        stores: RepositoryProvider,
        scope: AccessScope,
        ref: IdRef
    ): Outcome<UUID> = RunRefs.resolveRunId(stores, scope, ref)

    /** Resolves and loads the run [ref] under the call scope: [RunRefs.loadRun]. */
    suspend fun loadRun(
        stores: RepositoryProvider,
        ctx: CallContext,
        ref: IdRef
    ): Outcome<Run> = RunRefs.loadRun(stores, ctx.scope, ref)

    /**
     * [run] as its wire view: the member count and the active seats (one seat read) are read now. Under a restricted
     * [scope] only members and seats on items the scope can see are counted (one `filterVisible` statement); an
     * unrestricted scope reads nothing extra.
     */
    suspend fun view(
        env: ToolExecutionContext,
        stores: RepositoryProvider,
        run: Run,
        scope: AccessScope
    ): JsonObject = view(stores, run, scope, env.clock.now())

    /** [view] at the instant [now]. */
    suspend fun view(
        stores: RepositoryProvider,
        run: Run,
        scope: AccessScope,
        now: Instant
    ): JsonObject {
        val members = stores.runStore().members(run.id)
        val seats = stores.runSeatStore().list(run.id)
        if (scope.isUnrestricted) return RunView.of(run, members.size, seats, now)
        val visible = stores.itemStore().filterVisible(members.toHashSet() + seats.mapNotNull { it.itemId }, scope)
        return RunView.of(run, members.count { it in visible }, seats.filter { it.itemId == null || it.itemId in visible }, now)
    }

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

    fun runNotFound(id: String): DomainError = RunRefs.runNotFound(id)

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
