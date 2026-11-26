package io.github.jpicklyk.mcptask.current.itemwrite

import io.github.jpicklyk.mcptask.current.lifecycle.LifecycleStoreProvider
import java.util.UUID

/**
 * The stores the workflow slice reads: the lifecycle set (which carries the knowledge set the tree service writes
 * links through) and the count of open runs an item delete checks. [RepositoryProvider] implements it.
 */
interface ItemWriteStoreProvider : LifecycleStoreProvider {
    fun openRunCount(): OpenRunCount
}

/** The count of open runs on a root; the run slice's [RunStore] implements it. */
interface OpenRunCount {
    /** The number of runs on root [rootId] that are not closed (open or paused). */
    suspend fun countNotClosedByRoot(rootId: UUID): Int
}
