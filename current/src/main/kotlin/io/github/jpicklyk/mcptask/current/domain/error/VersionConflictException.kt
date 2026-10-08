package io.github.jpicklyk.mcptask.current.domain.error

import java.util.UUID

/**
 * Thrown by a store when an optimistic-locking write lost the version race: the caller wrote with
 * version [expected], but the row (re-read INSIDE the same unit of work) is at [actual]. The unit
 * boundary translates it into [ErrorCode.VERSION_CONFLICT] with an [ErrorDetail.VersionConflict]
 * carrying both versions, so [expected] and [actual] always differ.
 *
 * The message is the 3.x store text, so callers that still render a store message reproduce the
 * same bytes ([MESSAGE]).
 */
class VersionConflictException(
    val id: UUID,
    val expected: Long,
    val actual: Long
) : RuntimeException(MESSAGE) {
    init {
        require(expected != actual) { "expected and actual versions must differ" }
    }

    companion object {
        /** The 3.x store text for a lost optimistic-locking race (MCP error text keeps it). */
        const val MESSAGE: String = "WorkItem was modified by another transaction (version mismatch)"
    }
}
