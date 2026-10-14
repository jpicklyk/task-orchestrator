package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.application.config.RunsConfig
import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.RunSeatPort
import io.github.jpicklyk.mcptask.current.application.port.RunStore
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.application.service.eventRootOf
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.ClaimReleaseReason
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunBudget
import io.github.jpicklyk.mcptask.current.domain.model.RunInFlight
import io.github.jpicklyk.mcptask.current.domain.model.RunPauseReason
import io.github.jpicklyk.mcptask.current.domain.model.RunReleased
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/**
 * The single owner of run writes and run events (plan section 4.1). Every operation is ONE [UnitOfWork.write] unit
 * (it joins an ambient one) that records its `run.*` event; an [Outcome.Err] rolls the whole unit back.
 *
 * ## Lifecycle
 *
 * `open` -> OPEN; [pause] (manual) OPEN -> PAUSED; [resume] PAUSED(manual) -> OPEN; [close] OPEN|PAUSED -> CLOSED.
 * Any operation on a CLOSED run is `run_closed`; [update] on a PAUSED run is `run_paused`; [pause] when not OPEN and
 * [resume] when not PAUSED are `invalid_run_state` with the operations the run accepts now; a budget or attempts pause
 * can only be closed (`invalid_run_state`, allowed `[close]`).
 *
 * ## Membership
 *
 * A member is an item of the run's root that has a lifecycle; an item belongs to at most one run that is not closed
 * (`duplicate`); a run holds at most [Run.MAX_MEMBERS] members.
 *
 * ## Close cascade
 *
 * `completed` is refused while a seat assignment is active and runs no cascade. Every other reason, in order: ends the
 * active seats ([RunSeatPort.endActive]); clears the member claims held by the run's seat assignees (reason
 * `run_closed`); releases the leases of every member in WORK; stores the closed run; calls [onClose]; records
 * `run.ended`. The holders are read before the seats end, so the claims of the seats this close ends are covered.
 */
class RunService(
    private val repositoryProvider: RepositoryProvider,
    private val unitOfWork: UnitOfWork,
    private val claimService: ClaimService,
    private val runsConfig: suspend (UUID) -> RunsConfig,
    private val seats: RunSeatPort = RunSeatPort.NONE,
    private val onClose: RunCloseHook = RunCloseHook.NONE
) {
    private val runs: RunStore get() = repositoryProvider.runStore()
    private val items get() = repositoryProvider.workItemRepository()

    /** The number of seat assignments of [runId] that are still active (0 until seats exist). */
    suspend fun activeSeats(runId: UUID): Int = seats.activeCount(runId)

    // -------------------------------------------------------------------------
    // open
    // -------------------------------------------------------------------------

    suspend fun open(command: RunOpenCommand): Outcome<Run> =
        unitOfWork.write(OP_OPEN) {
            val root = items.getById(command.rootId) ?: return@write err(itemNotFound(command.rootId))
            if (root.parentId != null) return@write err(invalid(FIELD_ROOT_ID, "must be a root item (it has a parent)", command.rootId))
            textViolations(command.label, command.title)?.let { return@write err(it) }

            val config = runsConfig(command.rootId)
            val stallAfter = command.stallAfterSeconds ?: config.stallAfterSeconds ?: Run.DEFAULT_STALL_AFTER_SECONDS
            if (stallAfter <= 0) return@write err(invalid("stallAfterSeconds", "must be greater than 0", stallAfter))
            val budget = command.budget?.takeUnless { it.isUnlimited }
            if (budget != null) {
                val problems = budgetViolations(budget, config.budgets)
                if (problems.isNotEmpty()) {
                    return@write err(
                        invalidFields(problems, "Run budget exceeds a configured ceiling or is invalid")
                    )
                }
            }

            val ids = command.items.distinct()
            if (ids.size > Run.MAX_MEMBERS) {
                return@write err(invalid(FIELD_ITEMS, "a run holds at most ${Run.MAX_MEMBERS} items", ids.size))
            }
            checkMembers(command.items, FIELD_ITEMS, command.rootId, excludeRunId = null)?.let { return@write err(it) }

            val run =
                Run(
                    rootId = command.rootId,
                    scopeId = command.scopeId,
                    label = command.label,
                    title = command.title,
                    status = RunStatus.OPEN,
                    openedBy = command.openedBy,
                    openedByKind = command.openedByKind,
                    openedAt = now,
                    coordinatorSeenAt = now,
                    stallAfterSeconds = stallAfter,
                    budget = budget,
                    hints = command.hints
                )
            val stored = runs.create(run)
            runs.addMembers(stored.id, ids, now)
            events.record(DomainEvent.RunOpened(stored.id, stored.rootId, ids, stored.budget))
            Outcome.Ok(stored)
        }

    // -------------------------------------------------------------------------
    // update
    // -------------------------------------------------------------------------

    suspend fun update(
        runId: UUID,
        command: RunUpdateCommand
    ): Outcome<Run> =
        unitOfWork.write(OP_UPDATE) {
            val run = runs.getById(runId) ?: return@write err(runNotFound(runId))
            stateGuard(run)?.let { return@write err(it) }
            if (run.status == RunStatus.PAUSED) return@write err(runPaused(run))
            if (command.expectedVersion != null && command.expectedVersion != run.version) {
                return@write err(versionConflict(runId, command.expectedVersion, run.version))
            }

            val addSet = command.addItems.toSet()
            command.removeItems.forEachIndexed { index, id ->
                if (id in addSet) return@write err(invalid("$FIELD_REMOVE_ITEMS[$index]", "is also listed in addItems", id))
            }

            val config = runsConfig(run.rootId)
            var budget = run.budget
            if (command.budget != null) {
                val problems = loweredBudgetViolations(run.budget, command.budget) + budgetViolations(command.budget, config.budgets)
                if (problems.isNotEmpty()) {
                    return@write err(
                        invalidFields(problems, "A run budget can only be lowered within the configured ceilings")
                    )
                }
                budget = mergeBudget(run.budget, command.budget)
            }

            val current = runs.members(runId).toSet()
            val toAdd = command.addItems.distinct().filter { it !in current }
            val toRemove = command.removeItems.distinct().filter { it in current }
            if (current.size + toAdd.size - toRemove.size > Run.MAX_MEMBERS) {
                return@write err(
                    invalid(
                        FIELD_ADD_ITEMS,
                        "a run holds at most ${Run.MAX_MEMBERS} items",
                        current.size + toAdd.size - toRemove.size
                    )
                )
            }
            checkMembers(command.addItems, FIELD_ADD_ITEMS, run.rootId, excludeRunId = runId)?.let { return@write err(it) }

            if (toAdd.isNotEmpty()) runs.addMembers(runId, toAdd, now)
            if (toRemove.isNotEmpty()) runs.removeMembers(runId, toRemove)
            val changed = run.copy(budget = budget, hints = command.hints ?: run.hints)
            val stored = runs.update(changed, run.version) ?: return@write err(conflictAfterRead(runId, run.version))
            events.record(DomainEvent.RunUpdated(runId, run.rootId, toAdd, toRemove, budgetChanged = budget != run.budget))
            Outcome.Ok(stored)
        }

    // -------------------------------------------------------------------------
    // pause / resume
    // -------------------------------------------------------------------------

    /** Pauses an OPEN run manually. */
    suspend fun pause(runId: UUID): Outcome<Run> =
        unitOfWork.write(OP_PAUSE) {
            val run = runs.getById(runId) ?: return@write err(runNotFound(runId))
            stateGuard(run)?.let { return@write err(it) }
            if (run.status != RunStatus.OPEN) return@write err(invalidState(run, allowedNow(run)))
            val stored =
                runs.update(run.copy(status = RunStatus.PAUSED, pauseReason = RunPauseReason.MANUAL, pauseDecisionId = null), run.version)
                    ?: return@write err(conflictAfterRead(runId, run.version))
            events.record(DomainEvent.RunPaused(runId, run.rootId, RunPauseReason.MANUAL, null))
            Outcome.Ok(stored)
        }

    /** Resumes a manually PAUSED run. A budget or attempts pause cannot be resumed, only closed. */
    suspend fun resume(runId: UUID): Outcome<Run> =
        unitOfWork.write(OP_RESUME) {
            val run = runs.getById(runId) ?: return@write err(runNotFound(runId))
            stateGuard(run)?.let { return@write err(it) }
            if (run.status != RunStatus.PAUSED) return@write err(invalidState(run, allowedNow(run)))
            if (run.pauseReason != RunPauseReason.MANUAL) return@write err(invalidState(run, listOf(ALLOWED_CLOSE)))
            val stored =
                runs.update(run.copy(status = RunStatus.OPEN, pauseReason = null, pauseDecisionId = null), run.version)
                    ?: return@write err(conflictAfterRead(runId, run.version))
            events.record(DomainEvent.RunResumed(runId, run.rootId))
            Outcome.Ok(stored)
        }

    // -------------------------------------------------------------------------
    // close
    // -------------------------------------------------------------------------

    suspend fun close(
        runId: UUID,
        reason: RunTerminalReason,
        closedBy: String? = null
    ): Outcome<RunCloseResult> =
        unitOfWork.write(OP_CLOSE) {
            val run = runs.getById(runId) ?: return@write err(runNotFound(runId))
            stateGuard(run)?.let { return@write err(it) }
            if (reason == RunTerminalReason.COMPLETED && seats.activeCount(runId) > 0) {
                return@write err(invalidState(run, closeOptions(withCompleted = false)))
            }

            val members = items.findByIds(runs.members(runId).toSet())
            val roleOf = members.associate { it.id to roleName(it) }
            val countsByRole =
                members
                    .groupingBy { roleName(it) }
                    .eachCount()
                    .toSortedMap()
                    .toMap()

            var inFlight: List<RunInFlight> = emptyList()
            var released = RunReleased.NONE
            if (reason != RunTerminalReason.COMPLETED) {
                // Read the assignees before their seats end: ending them must not hide the claims they hold.
                val holders = seats.assigneeHolders(runId)
                val ended = seats.endActive(runId, reason.wire)
                inFlight = ended.map { RunInFlight(it.itemId, roleOf[it.itemId] ?: ROLE_NONE, it.seat) }

                var claimsCleared = 0
                for (member in members) {
                    if (member.claimedBy == null || member.claimedBy !in holders) continue
                    when (val cleared = claimService.clearClaim(member.id, ClaimReleaseReason.RUN_CLOSED)) {
                        is Outcome.Err -> return@write cleared
                        is Outcome.Ok -> if (cleared.value) claimsCleared++
                    }
                }

                val workIds = members.filter { it.role == Role.WORK }.mapTo(LinkedHashSet()) { it.id }
                val leasesReleased =
                    when (val result = claimService.releaseLeases(workIds)) {
                        is Outcome.Err -> return@write result
                        is Outcome.Ok -> (result.value as? LeaseReleaseResult.Success)?.releasedCount ?: 0
                    }
                released = RunReleased(claims = claimsCleared, leases = leasesReleased, seats = ended.size)
            }

            val closed =
                run.copy(
                    status = RunStatus.CLOSED,
                    pauseReason = null,
                    terminalReason = reason,
                    closedAt = now,
                    closedBy = closedBy
                )
            val stored = runs.update(closed, run.version) ?: return@write err(conflictAfterRead(runId, run.version))
            val snapshot = RunEndedSnapshot(reason, countsByRole, inFlight, released, openDecisions = OPEN_DECISIONS)
            onClose.onClose(stored, snapshot)
            events.record(
                DomainEvent.RunEnded(runId, run.rootId, reason, countsByRole, inFlight, released, snapshot.openDecisions)
            )
            Outcome.Ok(RunCloseResult(stored, snapshot))
        }

    // -------------------------------------------------------------------------
    // mark
    // -------------------------------------------------------------------------

    /**
     * Sets the named mark [key] on a run to now. Marks are bookkeeping, not lifecycle: allowed in every status (marking
     * a closed run is the purpose) and first-write-wins, so a key already set returns the run unchanged with
     * `marked = false` and records nothing. A run holds at most [MAX_MARKS] marks.
     */
    suspend fun mark(
        runId: UUID,
        key: String
    ): Outcome<RunMarkResult> =
        unitOfWork.write(OP_MARK) {
            val run = runs.getById(runId) ?: return@write err(runNotFound(runId))
            if (key in run.marks) return@write Outcome.Ok(RunMarkResult(run, marked = false))
            if (run.marks.size >= MAX_MARKS) {
                return@write err(invalid("key", "a run holds at most $MAX_MARKS marks", key))
            }
            val stored =
                runs.update(run.copy(marks = run.marks + (key to now)), run.version)
                    ?: return@write err(conflictAfterRead(runId, run.version))
            events.record(DomainEvent.RunMarked(runId, run.rootId, listOf(key)))
            Outcome.Ok(RunMarkResult(stored, marked = true))
        }

    // -------------------------------------------------------------------------
    // Checks
    // -------------------------------------------------------------------------

    /** `run_closed` for a closed run, else null. */
    private fun stateGuard(run: Run): DomainError? = if (run.status == RunStatus.CLOSED) runClosed(run) else null

    /**
     * The member checks for [requested] (the caller's list, so field indexes match it): every item exists (`not_found`),
     * has a lifecycle and belongs to [rootId] (`invalid_request` naming each offending `field[i]`), and none is a
     * member of a not-closed run other than [excludeRunId] (`duplicate`). Null when all pass.
     */
    private suspend fun checkMembers(
        requested: List<UUID>,
        field: String,
        rootId: UUID,
        excludeRunId: UUID?
    ): DomainError? {
        if (requested.isEmpty()) return null
        val ids = requested.distinct()
        val found = items.findByIds(ids.toSet()).associateBy { it.id }
        ids.firstOrNull { it !in found }?.let { return itemNotFound(it) }

        val violations = mutableListOf<FieldViolation>()
        val seen = HashSet<UUID>()
        requested.forEachIndexed { index, id ->
            if (!seen.add(id)) return@forEachIndexed
            val item = found.getValue(id)
            if (item.role == null) {
                violations += FieldViolation("$field[$index]", "item has no lifecycle and cannot join a run", id.toString())
            } else {
                val itemRoot = eventRootOf(item, items)
                if (itemRoot != rootId) {
                    violations +=
                        FieldViolation("$field[$index]", "item belongs to root $itemRoot, not the run's root $rootId", id.toString())
                }
            }
        }
        if (violations.isNotEmpty()) return invalidFields(violations, "Some items cannot join this run")

        val open = runs.openRunByItem(ids.toSet())
        for (id in ids) {
            val existing = open[id] ?: continue
            if (existing != excludeRunId) return duplicate(id, existing)
        }
        return null
    }

    private fun textViolations(
        label: String?,
        title: String?
    ): DomainError? {
        val violations = mutableListOf<FieldViolation>()
        if (label != null && label.length > Run.MAX_LABEL_LENGTH) {
            violations += FieldViolation("label", "must be at most ${Run.MAX_LABEL_LENGTH} characters", label.length.toString())
        }
        if (title != null && title.length > Run.MAX_TITLE_LENGTH) {
            violations += FieldViolation("title", "must be at most ${Run.MAX_TITLE_LENGTH} characters", title.length.toString())
        }
        return if (violations.isEmpty()) null else invalidFields(violations, "Run label or title is too long")
    }

    /** [micro] micro-USD as decimal US dollars, the way the cost field is expressed on the wire. */
    private fun usd(micro: Long): String =
        java.math.BigDecimal
            .valueOf(micro, 6)
            .stripTrailingZeros()
            .toPlainString()

    /** Fields of [budget] that are not positive, or exceed the matching configured ceiling of [ceilings]. */
    private fun budgetViolations(
        budget: RunBudget,
        ceilings: RunBudget?
    ): List<FieldViolation> {
        val out = mutableListOf<FieldViolation>()

        fun check(
            name: String,
            value: Long?,
            ceiling: Long?
        ) {
            if (value == null) return
            if (value <= 0) {
                out += FieldViolation("budget.$name", "must be greater than 0", value.toString())
            } else if (ceiling != null && value > ceiling) {
                val shown = if (name == "costMicroUsd") usd(ceiling) else ceiling.toString()
                out += FieldViolation("budget.$name", "exceeds the configured ceiling $shown", value.toString())
            }
        }
        check("tokens", budget.tokens, ceilings?.tokens)
        check("costMicroUsd", budget.costMicroUsd, ceilings?.costMicroUsd)
        check("attemptsPerSeat", budget.attemptsPerSeat?.toLong(), ceilings?.attemptsPerSeat?.toLong())
        check("maxActiveSeats", budget.maxActiveSeats?.toLong(), ceilings?.maxActiveSeats?.toLong())
        return out
    }

    /** Fields of [requested] that would raise the pinned [current] value (a null requested field leaves it unchanged). */
    private fun loweredBudgetViolations(
        current: RunBudget?,
        requested: RunBudget
    ): List<FieldViolation> {
        val out = mutableListOf<FieldViolation>()

        fun check(
            name: String,
            pinned: Long?,
            value: Long?
        ) {
            if (pinned != null && value != null && value > pinned) {
                out += FieldViolation("budget.$name", "can only be lowered (pinned $pinned)", value.toString())
            }
        }
        check("tokens", current?.tokens, requested.tokens)
        check("costMicroUsd", current?.costMicroUsd, requested.costMicroUsd)
        check("attemptsPerSeat", current?.attemptsPerSeat?.toLong(), requested.attemptsPerSeat?.toLong())
        check("maxActiveSeats", current?.maxActiveSeats?.toLong(), requested.maxActiveSeats?.toLong())
        return out
    }

    private fun mergeBudget(
        current: RunBudget?,
        requested: RunBudget
    ): RunBudget? =
        RunBudget(
            tokens = requested.tokens ?: current?.tokens,
            costMicroUsd = requested.costMicroUsd ?: current?.costMicroUsd,
            attemptsPerSeat = requested.attemptsPerSeat ?: current?.attemptsPerSeat,
            maxActiveSeats = requested.maxActiveSeats ?: current?.maxActiveSeats
        ).takeUnless { it.isUnlimited }

    /** The operations [run] accepts now, for `invalid_run_state`; closes are `close:<reason>`. */
    private suspend fun allowedNow(run: Run): List<String> =
        when (run.status) {
            RunStatus.OPEN -> listOf("update", "pause") + closeOptions(withCompleted = seats.activeCount(run.id) == 0)
            RunStatus.PAUSED ->
                if (run.pauseReason == RunPauseReason.MANUAL) {
                    listOf("resume") + closeOptions(withCompleted = seats.activeCount(run.id) == 0)
                } else {
                    listOf(ALLOWED_CLOSE)
                }
            RunStatus.CLOSED -> emptyList()
        }

    private fun closeOptions(withCompleted: Boolean): List<String> =
        RunTerminalReason.entries
            .filter { withCompleted || it != RunTerminalReason.COMPLETED }
            .map { "close:${it.wire}" }

    private fun roleName(item: WorkItem): String = item.role?.name?.lowercase() ?: ROLE_NONE

    /** The update after a read found [expected]: the run moved under us (`version_conflict`) or vanished (`not_found`). */
    private suspend fun conflictAfterRead(
        runId: UUID,
        expected: Long
    ): DomainError {
        val current = runs.getById(runId) ?: return runNotFound(runId)
        return if (current.version != expected) versionConflict(runId, expected, current.version) else runNotFound(runId)
    }

    private fun <T> err(error: DomainError): Outcome<T> = Outcome.Err(error)

    companion object {
        private const val OP_OPEN = "RunService.open"
        private const val OP_UPDATE = "RunService.update"
        private const val OP_PAUSE = "RunService.pause"
        private const val OP_RESUME = "RunService.resume"
        private const val OP_CLOSE = "RunService.close"
        private const val OP_MARK = "RunService.mark"

        /** The most marks one run may hold. */
        const val MAX_MARKS = 32

        private const val FIELD_ROOT_ID = "rootId"
        private const val FIELD_ITEMS = "items"
        private const val FIELD_ADD_ITEMS = "addItems"
        private const val FIELD_REMOVE_ITEMS = "removeItems"

        /** The role bucket of a lifecycle-free member in `countsByRole`. */
        const val ROLE_NONE = "none"

        /** The single allowed operation of a budget or attempts pause. */
        const val ALLOWED_CLOSE = "close"

        /** Decisions do not exist yet, so a close never leaves one open. */
        private const val OPEN_DECISIONS = 0

        fun runClosed(run: Run): DomainError {
            val reason = run.terminalReason?.wire ?: "closed"
            return DomainError(
                code = ErrorCode.RUN_CLOSED,
                message = "Run ${run.id} is closed ($reason)",
                detail = ErrorDetail.RunClosed(run.id, reason),
                fixArgs = mapOf("runId" to run.id.toString(), "terminalReason" to reason)
            )
        }

        fun runPaused(run: Run): DomainError {
            val reason = run.pauseReason?.wire ?: RunPauseReason.MANUAL.wire
            return DomainError(
                code = ErrorCode.RUN_PAUSED,
                message = "Run ${run.id} is paused ($reason)",
                detail = ErrorDetail.RunPaused(run.id, reason, run.pauseDecisionId),
                fixArgs = mapOf("runId" to run.id.toString(), "pauseReason" to reason)
            )
        }

        fun invalidState(
            run: Run,
            allowed: List<String>
        ): DomainError =
            DomainError(
                code = ErrorCode.INVALID_RUN_STATE,
                message = "Run ${run.id} is ${run.status.wire}; allowed now: ${allowed.joinToString(", ").ifEmpty { "nothing" }}",
                detail = ErrorDetail.InvalidRunState(run.id, run.status.wire, allowed),
                fixArgs = mapOf("runId" to run.id.toString(), "status" to run.status.wire)
            )

        private fun runNotFound(id: UUID): DomainError =
            DomainError(
                code = ErrorCode.NOT_FOUND,
                message = "Run $id not found",
                detail = ErrorDetail.NotFound(EntityKind.RUN, id.toString()),
                fixArgs = mapOf("kind" to "run", "id" to id.toString())
            )

        private fun itemNotFound(id: UUID): DomainError =
            DomainError(
                code = ErrorCode.NOT_FOUND,
                message = "WorkItem not found with id: $id",
                detail = ErrorDetail.NotFound(EntityKind.ITEM, id.toString()),
                fixArgs = mapOf("kind" to "item", "id" to id.toString())
            )

        private fun duplicate(
            itemId: UUID,
            existingRunId: UUID
        ): DomainError =
            DomainError(
                code = ErrorCode.DUPLICATE,
                message = "Item $itemId is already a member of open run $existingRunId",
                detail = ErrorDetail.Duplicate(EntityKind.RUN, itemId.toString(), existingRunId.toString()),
                fixArgs = mapOf("kind" to "run", "existingId" to existingRunId.toString())
            )

        private fun versionConflict(
            runId: UUID,
            expected: Long,
            actual: Long
        ): DomainError =
            DomainError(
                code = ErrorCode.VERSION_CONFLICT,
                message = "Run $runId is at version $actual, not $expected",
                detail = ErrorDetail.VersionConflict(EntityKind.RUN, runId.toString(), expected, actual),
                fixArgs = mapOf("kind" to "run", "id" to runId.toString(), "actual" to actual.toString())
            )

        private fun invalid(
            field: String,
            reason: String,
            received: Any?
        ): DomainError = invalidFields(listOf(FieldViolation(field, reason, received?.toString())), "Invalid $field: $reason")

        private fun invalidFields(
            violations: List<FieldViolation>,
            message: String
        ): DomainError =
            DomainError(
                code = ErrorCode.INVALID_REQUEST,
                message = message,
                detail = ErrorDetail.InvalidRequest(violations)
            )
    }
}
