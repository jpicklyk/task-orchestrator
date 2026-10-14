package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** The instant the R19 fencing tests start their [SettableClock] at (the SettableClock default). */
internal val FENCE_T: Instant = Instant.parse("2026-03-01T10:00:00Z")

/** The ttl, in seconds, of every claim the R19 tests take through the store: a claim made at [FENCE_T] expires at T + 900 s. */
internal const val FENCE_TTL: Int = 900

/**
 * Plumbing for the R19 (item 46a83aca) fencing tests over the production composition: items are created through the item
 * service (so depth and root are stamped as in production), claims are taken through the store at the rig's clock, and counts
 * are read with raw SQL. No assertion logic lives here; every oracle stays in the test itself.
 */
internal class FenceRig(
    val rig: EventLogRig,
    val clock: SettableClock
) {
    val d = P11Driver(rig)
    val ctx: ToolExecutionContext get() = rig.ctx
    val raw get() = rig.raw

    /** The expiry of a claim taken while the clock stood at [FENCE_T]. */
    val expiry: Instant get() = FENCE_T.plusSeconds(FENCE_TTL.toLong())

    /** Creates an item of the declared `test-item` type; [role] other than queue is set directly on the stored row. */
    suspend fun make(
        title: String,
        parent: WorkItem? = null,
        role: Role = Role.QUEUE,
        id: UUID = UUID.randomUUID(),
        tags: String? = null
    ): WorkItem {
        val created =
            ctx.itemCommandService
                .create(ItemCreateCommand(type = TEST_ITEM_TYPE, id = id, parentId = parent?.id, title = title, tags = tags))
                .orFail()
        if (role != Role.QUEUE) d.setRole(created, role)
        return d.reload(created)
    }

    /** Takes a [FENCE_TTL] claim on [item] for [agent] at the current clock and checks the store really holds it. */
    suspend fun heldBy(
        item: WorkItem,
        agent: String
    ): WorkItem {
        raw.workItemRepository().claim(item.id, agent, ttlSeconds = FENCE_TTL)
        val held = d.reload(item)
        check(held.claimedBy == agent) { "fixture: ${item.title} must be claimed by $agent, is ${held.claimedBy}" }
        return held
    }

    suspend fun reload(item: WorkItem): WorkItem = d.reload(item)

    fun count(table: String): Int = rawCount(d.jdbcUrl, "SELECT COUNT(*) FROM $table")
}

/** A [FenceRig] over [sqlite] with the P11 schema config and [clock] driving every unit and every claim. */
internal fun newFenceRig(
    sqlite: SqliteTestDatabase,
    dir: Path,
    clock: SettableClock,
    yaml: String = P11_BASE_YAML
): FenceRig = FenceRig(EventLogRig.build(sqlite, dir, yaml, clock), clock)
