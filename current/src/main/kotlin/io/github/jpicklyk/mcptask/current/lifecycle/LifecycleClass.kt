package io.github.jpicklyk.mcptask.current.lifecycle

import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.model.event.PinReason
import io.github.jpicklyk.mcptask.current.model.item.LifecycleMode
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.schema.SchemaPin
import java.time.Instant
import java.util.UUID

/**
 * The lifecycle class of an item: role-bearing, or lifecycle-free (null role). The one place a pin point asks
 * whether a pin crosses the class boundary, builds the error when it may not, and writes the role a class
 * change sets. Only a type change and `repin` may flip an item's class (the U13b flip rule); every other pin
 * point rejects a crossing pin.
 */
object LifecycleClass {
    /**
     * True when storing [pin] on an item with [role] would move it across the class boundary: a lifecycle-free item
     * (null role) takes a pin whose lifecycle is not [LifecycleMode.NONE], or a role-bearing item takes a NONE pin.
     */
    fun crosses(
        role: Role?,
        pin: SchemaPin
    ): Boolean = (role == null) != (pin.lifecycle == LifecycleMode.NONE)

    /**
     * A pin point that would move [itemId] across the class boundary where it may not: any pin point other than a type
     * change or `repin`, or a flip those two do not allow. `fromRole` is [role]'s wire value (`none` when
     * lifecycle-free), trigger the [reason]'s wire value, nothing allowed.
     */
    fun crossing(
        itemId: UUID,
        role: Role?,
        reason: PinReason
    ): DomainError {
        val from = role.wireOrNone()
        return DomainError(
            code = ErrorCode.INVALID_TRANSITION,
            message = "Item $itemId cannot change lifecycle class at '${reason.wire}' from role $from.",
            detail = ErrorDetail.InvalidTransition(itemId, from, reason.wire, emptyList()),
            fixArgs = mapOf("itemId" to itemId.toString(), "trigger" to reason.wire, "fromRole" to from)
        )
    }

    /** A newly created [item] carrying [pin] (when pinning is on): lifecycle-free when the pin's lifecycle is NONE. */
    fun pinned(
        item: WorkItem,
        pin: SchemaPin?
    ): WorkItem =
        when {
            pin == null -> item
            pin.lifecycle == LifecycleMode.NONE -> item.copy(role = null, pin = pin)
            else -> item.copy(pin = pin)
        }

    /** [candidate] moved into lifecycle-free under [pin]: the role, previous role and status label are cleared. */
    fun leave(
        candidate: WorkItem,
        pin: SchemaPin,
        now: Instant
    ): WorkItem = candidate.copy(pin = pin, role = null, previousRole = null, statusLabel = null, roleChangedAt = now)

    /** [candidate] moved out of lifecycle-free under [pin]: it re-enters QUEUE. */
    fun enter(
        candidate: WorkItem,
        pin: SchemaPin,
        now: Instant
    ): WorkItem = candidate.copy(pin = pin, role = Role.QUEUE, previousRole = null, roleChangedAt = now)
}
