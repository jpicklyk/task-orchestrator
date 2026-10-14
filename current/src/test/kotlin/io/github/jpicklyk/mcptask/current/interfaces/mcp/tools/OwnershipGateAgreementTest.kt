package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools

import io.github.jpicklyk.mcptask.current.application.service.ClaimHolder
import io.github.jpicklyk.mcptask.current.application.service.FenceGuard
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.FENCE_T
import io.github.jpicklyk.mcptask.current.test.FenceRig
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.errorCode
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.newFenceRig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Independent test (test-author seat, item 46a83aca) for scenario S5 of the frozen test-plan: the transition OWNERSHIP gate and
 * the write fence decide every claim cell identically.
 *
 * Oracle (frozen, planning phase): task-scope item 2 ("the predicate is the OWNERSHIP gate's restated; the gate itself is
 * unchanged") and the pre-existing ClaimState KDoc (a claim is active while `expiresAt` is strictly after the instant asked
 * about). The expectation of every cell is written out literally below (refused / allowed) and ALSO compared with
 * [FenceGuard.check], so the gate, the guard and the independent oracle must all agree.
 *
 * EXISTING-SURFACE for the gate half (a plain revert of the fence leaves the gate cells red/green unchanged), NEW-SURFACE for the
 * guard half. Fixtures: a queue item of the declared `p11-optional` type, with the claim columns stamped relative to the settable
 * clock so claimedAt <= expiresAt and originalClaimedAt == claimedAt hold by construction; the caller is a distinct principal.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class OwnershipGateAgreementTest {
    private val clock = SettableClock(FENCE_T)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private fun rig(dir: Path): FenceRig = newFenceRig(sqlite.db, dir, clock)

    private class Cell(
        val label: String,
        val claimant: String?,
        val expiresInMillis: Long,
        val fenced: Boolean
    )

    private val cells =
        listOf(
            Cell("active by another principal", "agent-b", 60_000, fenced = true),
            Cell("active by the caller", "agent-a", 60_000, fenced = false),
            Cell("one millisecond before expiry, another principal", "agent-b", 1, fenced = true),
            Cell("exactly at expiry, another principal", "agent-b", 0, fenced = false),
            Cell("one millisecond after expiry, another principal", "agent-b", -1, fenced = false),
            Cell("long expired, another principal", "agent-b", -3_600_000, fenced = false),
            Cell("unclaimed", null, 0, fenced = false)
        )

    private suspend fun FenceRig.stamped(cell: Cell): WorkItem {
        val id = UUID.randomUUID()
        val now = clock.now()
        val expiresAt = now.plusMillis(cell.expiresInMillis)
        val claimedAt = expiresAt.minusSeconds(60)
        val item =
            WorkItem(
                id = id,
                title = cell.label,
                role = Role.QUEUE,
                type = "p11-optional",
                depth = 0,
                rootId = id,
                claimedBy = cell.claimant,
                claimedAt = if (cell.claimant != null) claimedAt else null,
                claimExpiresAt = if (cell.claimant != null) expiresAt else null,
                originalClaimedAt = if (cell.claimant != null) claimedAt else null
            )
        raw.workItemRepository().create(item)
        return d.reload(item)
    }

    @Test
    fun `S5 a start by agent-a is refused by the ownership gate exactly in the cells the write fence refuses`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val now: Instant = clock.now()
            for (cell in cells) {
                val item = fx.stamped(cell)

                val guard = FenceGuard.check(item, ClaimHolder("agent-a"), now)
                assertEquals(cell.fenced, guard is Outcome.Err, "${cell.label}: the write fence")

                val element = fx.d.advance(item, "start", actor = "agent-a")
                if (cell.fenced) {
                    assertEquals("not_claim_holder", element.errorCode(), "${cell.label}: the ownership gate refuses: $element")
                    assertEquals(Role.QUEUE, fx.d.role(item), "${cell.label}: a refused start leaves the role")
                } else {
                    assertEquals(true, element.flag("ok"), "${cell.label}: the ownership gate admits: $element")
                    assertEquals(Role.WORK, fx.d.role(item), "${cell.label}: the start applied")
                }
            }
        }
}
