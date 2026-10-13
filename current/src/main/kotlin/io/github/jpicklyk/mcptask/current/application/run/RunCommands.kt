package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunBudget
import io.github.jpicklyk.mcptask.current.domain.model.RunInFlight
import io.github.jpicklyk.mcptask.current.domain.model.RunReleased
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import java.util.UUID

/**
 * Opens a run on root [rootId] over [items] (duplicates collapse; empty is allowed). [budget] is pinned on the run and
 * must not exceed a configured ceiling; null means unlimited. [stallAfterSeconds] null takes the configured value.
 */
data class RunOpenCommand(
    val rootId: UUID,
    val items: List<UUID> = emptyList(),
    val scopeId: UUID? = null,
    val label: String? = null,
    val title: String? = null,
    val budget: RunBudget? = null,
    val stallAfterSeconds: Int? = null,
    val hints: Map<String, String> = emptyMap(),
    val openedBy: String? = null,
    val openedByKind: String? = null
)

/**
 * Changes an open run: adds [addItems] and removes [removeItems] (an id in both is rejected), lowers the pinned
 * budget to [budget] (a field may only go down; an unset field may be set; nothing may be raised or cleared), and
 * replaces the hints with [hints] when given. [expectedVersion], when given, must equal the run's version.
 */
data class RunUpdateCommand(
    val addItems: List<UUID> = emptyList(),
    val removeItems: List<UUID> = emptyList(),
    val budget: RunBudget? = null,
    val hints: Map<String, String>? = null,
    val expectedVersion: Long? = null
)

/**
 * What a close recorded on `run.ended`: [countsByRole] counts the members at close by lowercase role (`none` for a
 * lifecycle-free one), [inFlight] the members whose seat was still active, [released] the cascade's counts and
 * [openDecisions] the decisions left open (0 until decisions exist).
 */
data class RunEndedSnapshot(
    val reason: RunTerminalReason,
    val countsByRole: Map<String, Int>,
    val inFlight: List<RunInFlight>,
    val released: RunReleased,
    val openDecisions: Int = 0
)

/** A closed [run] (as stored) and the [snapshot] its close recorded. */
data class RunCloseResult(
    val run: Run,
    val snapshot: RunEndedSnapshot
)
