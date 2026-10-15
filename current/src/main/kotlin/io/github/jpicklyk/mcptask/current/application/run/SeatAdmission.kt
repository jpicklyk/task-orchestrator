package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.model.Run
import java.util.UUID

/**
 * The run limits an assign must pass (R27), asked by [SeatService.assign] inside its unit once the assign is otherwise
 * valid: [attempt] is the attempt the assign would create for [seat] on [itemId], and [supersedes] is true when it would
 * end an active assignment of the same (item, seat). A returned error refuses the assign (its unit rolls back).
 * [OPEN] admits every assign; `RunLimits` enforces the attempt cap and `maxActiveSeats`.
 */
fun interface SeatAdmission {
    suspend fun admit(
        scope: WriteScope,
        run: Run,
        itemId: UUID,
        seat: String,
        attempt: Int,
        supersedes: Boolean
    ): DomainError?

    companion object {
        /** Admits every assign. */
        val OPEN: SeatAdmission = SeatAdmission { _, _, _, _, _, _ -> null }
    }
}
