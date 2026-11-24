package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.schema.PinView

/** The item reads' view of a pin, so they never name the config slice's pin writer. `SchemaPinService` implements it. */
fun interface PinViews {
    /** The read-side view of [item]'s pin, or null when it is unpinned. */
    suspend fun view(item: WorkItem): PinView?
}
