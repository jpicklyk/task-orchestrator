package io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ActorClaimDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.NoteDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.VerificationDto
import io.ktor.server.application.ApplicationCall

/**
 * Applies environment-driven attribution redaction to [NoteDto] instances.
 *
 * Two env variables control the behaviour:
 *
 * | Variable | Default | Effect |
 * |----------|---------|--------|
 * | `API_REDACT_NOTE_ATTRIBUTION` | `true` | When `true` AND the caller lacks [ApiCapability.ADMIN], sets `actor` and `verification` to `null` on every [NoteDto]. |
 * | `API_REDACT_ACTOR_PROOF`      | `true` | When `true`, `actor.proof` is redacted from ALL responses unless the caller has [ApiCapability.ADMIN] AND the request includes `?include=proof`. |
 *
 * Both variables default to `true` (redact). Set to `"false"` to disable.
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
    private val redactActorProof: Boolean,
) {
    /**
     * Applies redaction rules to a single [NoteDto].
     *
     * @param note The DTO to potentially redact.
     * @param call The current HTTP request (used to read the authenticated principal and query params).
     * @return A (possibly modified) copy of [note] with sensitive fields nulled out.
     */
    fun redact(
        note: NoteDto,
        call: ApplicationCall,
    ): NoteDto {
        val principal = call.attributes.getOrNull(ApiPrincipalKey)
        val isAdmin = principal?.capabilities?.let {
            it.contains(ApiCapability.ADMIN)
        } ?: false

        // Step 1: redact full actor + verification attribution unless caller is admin
        if (redactNoteAttribution && !isAdmin) {
            return note.copy(actor = null, verification = null)
        }

        // Step 2: caller is admin (or redaction is disabled); possibly redact proof only
        val actor = note.actor ?: return note
        if (!redactActorProof || !isAdmin) {
            // No proof redaction needed
            return note
        }

        // Proof redaction: require admin AND ?include=proof
        val includeProof = call.request.queryParameters["include"]
            ?.split(",")
            ?.map { it.trim() }
            ?.contains("proof") ?: false

        return if (includeProof) {
            note
        } else {
            note.copy(actor = actor.copy(proof = null))
        }
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
         * Reads `API_REDACT_NOTE_ATTRIBUTION` and `API_REDACT_ACTOR_PROOF`.
         * Both default to `true` when absent or blank.
         */
        fun fromEnv(): AttributionRedactor =
            AttributionRedactor(
                redactNoteAttribution = System.getenv("API_REDACT_NOTE_ATTRIBUTION")
                    ?.trim()?.lowercase()?.let { it != "false" } ?: true,
                redactActorProof = System.getenv("API_REDACT_ACTOR_PROOF")
                    ?.trim()?.lowercase()?.let { it != "false" } ?: true,
            )

        /** Constructs an [AttributionRedactor] with explicit values (useful for testing). */
        fun of(
            redactNoteAttribution: Boolean,
            redactActorProof: Boolean,
        ): AttributionRedactor = AttributionRedactor(redactNoteAttribution, redactActorProof)
    }
}

/**
 * Redacts sensitive fields from an [ActorClaimDto] when attribution is shown to admin callers
 * but proof must still be hidden.
 *
 * This is a standalone helper used by the role-transition mapper where [NoteDto] is not the
 * container.
 *
 * @param actor The actor claim to potentially strip proof from.
 * @param call The current HTTP request.
 * @param redactActorProof Whether proof redaction is enabled globally.
 * @return A (possibly modified) copy of [actor].
 */
fun redactActorProofIfNeeded(
    actor: ActorClaimDto?,
    call: ApplicationCall,
    redactActorProof: Boolean,
): ActorClaimDto? {
    if (actor == null) return null
    if (!redactActorProof) return actor

    val principal = call.attributes.getOrNull(ApiPrincipalKey)
    val isAdmin = principal?.capabilities?.contains(ApiCapability.ADMIN) ?: false
    if (!isAdmin) return actor.copy(proof = null)

    val includeProof = call.request.queryParameters["include"]
        ?.split(",")
        ?.map { it.trim() }
        ?.contains("proof") ?: false
    return if (includeProof) actor else actor.copy(proof = null)
}

/**
 * Redacts actor attribution from [VerificationDto] context based on admin status.
 * (Standalone helper for routes that expose [io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.RoleTransitionDto].)
 */
fun redactVerification(
    verification: VerificationDto?,
    call: ApplicationCall,
    redactAttribution: Boolean,
): VerificationDto? {
    if (!redactAttribution) return verification
    val principal = call.attributes.getOrNull(ApiPrincipalKey)
    val isAdmin = principal?.capabilities?.contains(ApiCapability.ADMIN) ?: false
    return if (isAdmin) verification else null
}
