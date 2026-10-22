package io.github.jpicklyk.mcptask.current.domain.graph

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import java.util.UUID

/** Fixed, ordered ids so UUID tie-break expectations are written down, not computed. a < b < c < d < x1 < x2. */
internal object Ids {
    val a: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    val b: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    val c: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    val d: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000d")
    val x1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")
    val x2: UUID = UUID.fromString("00000000-0000-0000-0000-000000000022")
}

internal fun blocks(
    from: UUID,
    to: UUID,
    unblockAt: String? = null
): Dependency = Dependency(fromItemId = from, toItemId = to, type = DependencyType.BLOCKS, unblockAt = unblockAt)

internal fun isBlockedBy(
    from: UUID,
    to: UUID,
    unblockAt: String? = null
): Dependency = Dependency(fromItemId = from, toItemId = to, type = DependencyType.IS_BLOCKED_BY, unblockAt = unblockAt)

internal fun relates(
    from: UUID,
    to: UUID
): Dependency = Dependency(fromItemId = from, toItemId = to, type = DependencyType.RELATES_TO)
