package io.github.jpicklyk.mcptask.current.domain.error

/**
 * The detail properties that a fix-template slot of the same name refers to. A null value means
 * the property is unset, so the matching fix argument is free. Exhaustive over [ErrorDetail] so a
 * new subtype cannot be added without deciding its slots.
 */
internal fun ErrorDetail.slotValues(): Map<String, Any?> =
    when (this) {
        is ErrorDetail.InvalidRequest -> emptyMap()
        is ErrorDetail.UnknownParameter -> emptyMap()
        is ErrorDetail.NotFound -> mapOf("kind" to kind, "id" to id)
        is ErrorDetail.AmbiguousId -> mapOf("prefix" to prefix)
        is ErrorDetail.VersionConflict -> mapOf("kind" to kind, "id" to id, "expected" to expected, "actual" to actual)
        is ErrorDetail.Duplicate -> mapOf("kind" to kind, "id" to id, "existingId" to existingId)
        is ErrorDetail.IdempotencyMismatch -> mapOf("idempotencyKey" to idempotencyKey)
        is ErrorDetail.InvalidTransition -> mapOf("itemId" to itemId, "fromRole" to fromRole, "trigger" to trigger)
        is ErrorDetail.GateBlocked -> mapOf("itemId" to itemId, "role" to role)
        is ErrorDetail.DependencyUnmet -> mapOf("itemId" to itemId)
        is ErrorDetail.CycleDetected -> emptyMap()
        is ErrorDetail.ClaimHeld -> mapOf("itemId" to itemId)
        is ErrorDetail.NotClaimHolder -> mapOf("itemId" to itemId)
        is ErrorDetail.SeatForbidden -> mapOf("itemId" to itemId, "seat" to seat, "action" to action)
        is ErrorDetail.NoteOwnedByOther -> mapOf("itemId" to itemId, "key" to key)
        is ErrorDetail.NoteTooLong -> mapOf("key" to key, "max" to max, "actual" to actual)
        is ErrorDetail.ResourceUnavailable -> mapOf("itemId" to itemId)
        is ErrorDetail.SchemaViolation -> emptyMap()
        is ErrorDetail.SchemaPinnedConflict ->
            mapOf("itemId" to itemId, "pinnedVersion" to pinnedVersion, "currentVersion" to currentVersion)
        is ErrorDetail.ConfigInvalid -> emptyMap()
        is ErrorDetail.PayloadTooLarge -> mapOf("max" to max, "actual" to actual)
        is ErrorDetail.Forbidden -> mapOf("scope" to scope, "required" to required)
        is ErrorDetail.Unavailable -> emptyMap()
    }

/** Canonical wire string of a slot value: enum names lowercase, everything else toString. */
internal fun canonicalSlotValue(value: Any): String = if (value is Enum<*>) value.name.lowercase() else value.toString()
