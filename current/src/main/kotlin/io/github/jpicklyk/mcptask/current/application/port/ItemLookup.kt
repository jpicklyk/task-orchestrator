package io.github.jpicklyk.mcptask.current.application.port

/**
 * The work-item repository a tool op resolves item ids and checks scope against. Every tool-services holder
 * implements it, so the shared id helpers take this rather than a whole holder.
 */
interface ItemLookup {
    fun workItemRepository(): WorkItemRepository
}
