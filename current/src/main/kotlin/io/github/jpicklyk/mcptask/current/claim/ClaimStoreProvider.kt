package io.github.jpicklyk.mcptask.current.claim

import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.platform.port.AncestorChains
import io.github.jpicklyk.mcptask.current.platform.port.PlatformStoreProvider
import java.util.UUID

/**
 * The item reads claim writes need: the claimed item by id, and the ancestor chains an event's root is found through.
 * The work-item repository implements it, so the claim slice names no item store.
 */
interface ClaimItems : AncestorChains {
    /** The item with [id], or null when there is none. */
    suspend fun getById(id: UUID): WorkItem?
}

/** The stores the claim slice reads and writes, on top of the platform stores: claims, the items they sit on, resource leases. */
interface ClaimStoreProvider : PlatformStoreProvider {
    fun claimStore(): ClaimStore

    fun claimItems(): ClaimItems

    fun resourceLeaseRepository(): LeaseStore
}
