package io.github.jpicklyk.mcptask.current.domain.error

/**
 * Per-code "what to do next" templates with `{slot}` placeholders. Required for every PERMANENT
 * code except [ErrorCode.PARTIAL_FAILURE]; optional for the rest. Templates are single-line.
 */
object ErrorFixTemplates {
    private val SLOT = Regex("\\{([A-Za-z][A-Za-z0-9]*)\\}")

    private val templates: Map<ErrorCode, String> =
        mapOf(
            ErrorCode.INVALID_REQUEST to "Correct the invalid fields listed in detail.fields and resend the request.",
            ErrorCode.UNKNOWN_PARAMETER to "Remove the unknown parameters and check describe for the accepted ones.",
            ErrorCode.INVALID_CURSOR to "Restart the listing without a cursor.",
            ErrorCode.NOT_FOUND to "Verify that the {kind} with id {id} exists, or list entities to find a valid id.",
            ErrorCode.AMBIGUOUS_ID to "Use a longer id than {prefix} so it selects exactly one of the candidates.",
            ErrorCode.VERSION_CONFLICT to "Re-read {kind} {id} to get version {actual}, reapply the change, and retry.",
            ErrorCode.DUPLICATE to "Use the existing {kind} {existingId} instead of creating another.",
            ErrorCode.IDEMPOTENCY_MISMATCH to
                "Use a new idempotencyKey for a different payload, or resend the original payload for {idempotencyKey}.",
            ErrorCode.INVALID_TRANSITION to
                "Item {itemId} cannot apply {trigger} from {fromRole}; use one of the allowed triggers.",
            ErrorCode.GATE_BLOCKED to "Fill the missing notes for item {itemId} with notes.write, then retry advance.",
            ErrorCode.DEPENDENCY_UNMET to "Complete the blocking items of {itemId}, then retry.",
            ErrorCode.CYCLE_DETECTED to "Remove one dependency along the reported path to break the cycle.",
            ErrorCode.CLAIM_HELD to "Retry claiming item {itemId} after the claim expires.",
            ErrorCode.NOT_CLAIM_HOLDER to "Claim item {itemId} before advancing it.",
            ErrorCode.SEAT_FORBIDDEN to
                "Action {action} on item {itemId} is restricted to the allowed seats; act from one of them.",
            ErrorCode.NOTE_OWNED_BY_OTHER to
                "Note {key} on item {itemId} is owned by another seat; use a different note key.",
            ErrorCode.NOTE_TOO_LONG to "Shorten note {key} to at most {max} characters.",
            ErrorCode.RESOURCE_UNAVAILABLE to "Retry advancing item {itemId} after the contended resources are released.",
            ErrorCode.SCHEMA_VIOLATION to "Use a type and notes that satisfy the configured schema.",
            ErrorCode.SCHEMA_PINNED_CONFLICT to
                "Item {itemId} is pinned to schema {pinnedVersion} but the current schema is {currentVersion}; refresh and retry.",
            ErrorCode.CONFIG_INVALID to "Fix the configuration errors listed in detail.errors and push the config again.",
            ErrorCode.PAYLOAD_TOO_LARGE to "Reduce the payload to at most {max} bytes.",
            ErrorCode.UNAUTHENTICATED to "Authenticate and retry the request.",
            ErrorCode.FORBIDDEN to "Use a credential that grants {required} for the {scope} scope."
        )

    /** The template for [code], or null when the code has none. */
    fun template(code: ErrorCode): String? = templates[code]

    /** The slot names of the code template (empty when it has no template or no slots). */
    fun slots(code: ErrorCode): Set<String> =
        template(code)
            ?.let { t -> SLOT.findAll(t).map { it.groupValues[1] }.toSet() }
            .orEmpty()

    /**
     * Renders the template of [code] with [args].
     *
     * @throws IllegalArgumentException when [args] misses a slot, carries an extra key, or the
     *   code has no template.
     */
    fun render(
        code: ErrorCode,
        args: Map<String, String>
    ): String {
        val template = requireNotNull(template(code)) { "No fix template for ${code.wire}" }
        val slots = slots(code)
        val missing = slots - args.keys
        require(missing.isEmpty()) { "Missing fix args for ${code.wire}: $missing" }
        val extra = args.keys - slots
        require(extra.isEmpty()) { "Unexpected fix args for ${code.wire}: $extra" }
        return SLOT.replace(template) { args.getValue(it.groupValues[1]) }
    }
}
