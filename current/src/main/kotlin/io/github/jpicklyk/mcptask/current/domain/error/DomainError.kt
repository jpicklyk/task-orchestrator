package io.github.jpicklyk.mcptask.current.domain.error

/**
 * A catalog error: a stable [code], a one-sentence [message], the code detail (null iff the
 * code defines none), and the [fixArgs] that fill the fix template of the code.
 */
data class DomainError(
    val code: ErrorCode,
    val message: String,
    val detail: ErrorDetail? = null,
    val fixArgs: Map<String, String> = emptyMap()
) {
    init {
        require(message.isNotBlank()) { "message must not be blank" }
        val expected = code.detailClass
        if (expected == null) {
            require(detail == null) { "${code.wire} defines no detail" }
        } else {
            require(detail != null && expected.isInstance(detail)) {
                "${code.wire} requires detail of type ${expected.simpleName}"
            }
        }
        require(fixArgs.keys == ErrorFixTemplates.slots(code)) {
            "fixArgs keys ${fixArgs.keys} must equal template slots ${ErrorFixTemplates.slots(code)} for ${code.wire}"
        }
        detail?.slotValues()?.forEach { (slot, value) ->
            val arg = fixArgs[slot]
            if (value != null && arg != null) {
                require(arg == canonicalSlotValue(value)) {
                    "fixArgs[$slot] must equal detail value '${canonicalSlotValue(value)}' for ${code.wire}"
                }
            }
        }
    }

    /** Retry semantics, decided by the code. */
    val kind: ErrorKind get() = code.kind

    /** The rendered fix sentence, or null when the code has no template. */
    val fix: String? get() = ErrorFixTemplates.template(code)?.let { ErrorFixTemplates.render(code, fixArgs) }
}
