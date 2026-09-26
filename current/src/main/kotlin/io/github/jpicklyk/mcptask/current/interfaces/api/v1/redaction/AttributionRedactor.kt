package io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.NoteDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.VerificationDto
import io.ktor.server.application.ApplicationCall

/**
 * Applies environment-driven attribution redaction to [NoteDto] instances.
 *
 * | Variable | Default | Effect |
 * |----------|---------|--------|
 * | `API_REDACT_NOTE_ATTRIBUTION` | `true` | When `true` AND the caller lacks [ApiCapability.ADMIN], sets `actor` and `verification` to `null` on every [NoteDto]. |
 *
 * `API_REDACT_NOTE_ATTRIBUTION` defaults to `true` (redact). Set to `"false"` to disable.
 *
 * `verification.proof` (the forensic hash + verified-claims evidence introduced by V17, see
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ProofEvidenceDto]) has its OWN
 * admin-only gate, independent of the variable above: any [ApiCapability.ADMIN] caller sees it
 * whenever `API_REDACT_NOTE_ATTRIBUTION=false` or when attribution is otherwise shown; a
 * non-admin caller never sees it.
 *
 * Usage:
 * ```kotlin
 * val redactor = AttributionRedactor.fromEnv()
 * val redactedNote = redactor.redact(noteDto, call)
 * val redactedNotes = redactor.redactAll(noteDtos, call)
 * ```
 */
class AttributionRedactor(
    private val redactNoteAttribution: Boolean,
) {
    /**
     * Applies redaction rules to a single [NoteDto].
     *
     * @param note The DTO to potentially redact.
     * @param call The current HTTP request (used to read the authenticated principal).
     * @return A (possibly modified) copy of [note] with sensitive fields nulled out.
     */
    fun redact(
        note: NoteDto,
        call: ApplicationCall,
    ): NoteDto {
        val principal = call.attributes.getOrNull(ApiPrincipalKey)
        val isAdmin =
            principal?.capabilities?.let {
                it.contains(ApiCapability.ADMIN)
            } ?: false

        // Step 1: redact full actor + verification attribution unless caller is admin
        if (redactNoteAttribution && !isAdmin) {
            return note.copy(actor = null, verification = null)
        }

        // Step 2: attribution is shown (admin caller, or attribution redaction disabled).
        return note.copy(verification = stripProofUnlessAdmin(note.verification, isAdmin))
    }

    /**
     * Applies [redact] to a list of [NoteDto]s.
     */
    fun redactAll(
        notes: List<NoteDto>,
        call: ApplicationCall,
    ): List<NoteDto> = notes.map { redact(it, call) }

    companion object {
        /**
         * Constructs an [AttributionRedactor] from environment variables.
         *
         * Reads `API_REDACT_NOTE_ATTRIBUTION`, which defaults to `true` when absent or blank.
         */
        fun fromEnv(): AttributionRedactor = fromConfig(AppConfig.fromEnv())

        /**
         * Constructs an [AttributionRedactor] from a typed [AppConfig] snapshot. Preferred over
         * [fromEnv] on the production path so the environment is read once at startup.
         */
        fun fromConfig(appConfig: AppConfig): AttributionRedactor =
            AttributionRedactor(
                redactNoteAttribution = appConfig.apiRedactNoteAttribution,
            )

        /** Constructs an [AttributionRedactor] with an explicit value (useful for testing). */
        fun of(redactNoteAttribution: Boolean): AttributionRedactor = AttributionRedactor(redactNoteAttribution)
    }
}

/**
 * Redacts actor attribution from [VerificationDto] context based on admin status.
 * (Standalone helper for routes that expose [io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.RoleTransitionDto].)
 *
 * When [redactAttribution] is true and the caller lacks admin, the whole [VerificationDto] is
 * nulled. Otherwise it is shown, but [VerificationDto.proof] is stripped for a non-admin caller
 * regardless of [redactAttribution] — `verification.proof` is admin-only, independent of the
 * attribution-redaction toggle (mirrors the `AttributionRedactor.redact` NoteDto path).
 */
fun redactVerification(
    verification: VerificationDto?,
    call: ApplicationCall,
    redactAttribution: Boolean,
): VerificationDto? {
    val principal = call.attributes.getOrNull(ApiPrincipalKey)
    val isAdmin = principal?.capabilities?.contains(ApiCapability.ADMIN) ?: false
    if (redactAttribution && !isAdmin) return null
    return stripProofUnlessAdmin(verification, isAdmin)
}

/**
 * `verification.proof` (hash + verified claims) is admin-only — independent of
 * `API_REDACT_NOTE_ATTRIBUTION`. Single home for that rule, shared by the note and transition
 * paths.
 */
private fun stripProofUnlessAdmin(
    verification: VerificationDto?,
    isAdmin: Boolean,
): VerificationDto? = if (isAdmin) verification else verification?.copy(proof = null)
