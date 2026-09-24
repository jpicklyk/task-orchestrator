package io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ActorClaimDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.NoteDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.VerificationDto
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header

/**
 * Applies environment-driven attribution redaction to [NoteDto] instances.
 *
 * | Variable | Default | Effect |
 * |----------|---------|--------|
 * | `API_REDACT_NOTE_ATTRIBUTION` | `true` | When `true` AND the caller lacks [ApiCapability.ADMIN], sets `actor` and `verification` to `null` on every [NoteDto]. |
 * | `API_REDACT_ACTOR_PROOF`      | `true` | **Deprecated, no-op since migration V17** — `actor.proof` is now unconditionally `null` on every response, regardless of admin status, `?include=proof`, or this flag's value (raw proofs are never persisted); see [io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig.deprecatedEnvWarnings]. Retained only so existing call sites/tests compile unchanged. |
 *
 * `API_REDACT_NOTE_ATTRIBUTION` defaults to `true` (redact). Set to `"false"` to disable.
 *
 * `verification.proof` (the forensic hash + verified-claims evidence introduced by V17, see
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ProofEvidenceDto]) has its OWN
 * admin-only gate, independent of both variables above and NOT gated behind `?include=proof`:
 * any [ApiCapability.ADMIN] caller sees it whenever `API_REDACT_NOTE_ATTRIBUTION=false` or when
 * attribution is otherwise shown; a non-admin caller never sees it.
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
        val isAdmin =
            principal?.capabilities?.let {
                it.contains(ApiCapability.ADMIN)
            } ?: false

        // Step 1: redact full actor + verification attribution unless caller is admin
        if (redactNoteAttribution && !isAdmin) {
            return note.copy(actor = null, verification = null)
        }

        // Step 2: attribution is shown (admin caller, or attribution redaction disabled); the
        // proof itself is still gated separately — actor.proof must be null on EVERY branch
        // (admin or not, include=proof or not), so this always runs through the same helper the
        // transition mapper uses rather than short-circuiting on admin status.
        val redactedActor = redactActorProofIfNeeded(note.actor, call, redactActorProof)
        // verification.proof (hash + verified claims) is admin-only, independent of
        // API_REDACT_NOTE_ATTRIBUTION and NOT gated behind ?include=proof — see VerificationDto.
        val redactedVerification = if (isAdmin) note.verification else note.verification?.copy(proof = null)
        return note.copy(actor = redactedActor, verification = redactedVerification)
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
        fun fromEnv(): AttributionRedactor = fromConfig(AppConfig.fromEnv())

        /**
         * Constructs an [AttributionRedactor] from a typed [AppConfig] snapshot. Preferred over
         * [fromEnv] on the production path so the environment is read once at startup.
         */
        fun fromConfig(appConfig: AppConfig): AttributionRedactor =
            AttributionRedactor(
                redactNoteAttribution = appConfig.apiRedactNoteAttribution,
                redactActorProof = appConfig.apiRedactActorProof,
            )

        /** Constructs an [AttributionRedactor] with explicit values (useful for testing). */
        fun of(
            redactNoteAttribution: Boolean,
            redactActorProof: Boolean,
        ): AttributionRedactor = AttributionRedactor(redactNoteAttribution, redactActorProof)
    }
}

/**
 * Unconditionally strips [ActorClaimDto.proof]. Used for every REST path that shows actor
 * attribution.
 *
 * Since migration V17, raw actor proofs are never persisted — [ActorClaimDto.proof] arrives here
 * already `null` in every case, so stripping it again is defense in depth: no future DTO
 * construction path may leak a proof through this function, regardless of admin status,
 * `?include=proof`, or the deprecated `API_REDACT_ACTOR_PROOF` flag. `?include=proof` is still
 * accepted (see [flagDeprecatedIncludeProof]) but returns nothing to include. Forensic evidence
 * about the proof (hash + verified claims) lives on `verification.proof` instead — see
 * [redactVerification] and [io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ProofEvidenceDto].
 *
 * This is a standalone helper used by the role-transition mapper where [NoteDto] is not the
 * container.
 *
 * @param actor The actor claim to strip proof from.
 * @param call The current HTTP request. Unused now that proof stripping is unconditional; kept
 *   for call-site compatibility.
 * @param redactActorProof Deprecated, ignored — `actor.proof` is always nulled regardless of this
 *   value. Kept in the signature for call-site compatibility.
 * @return A copy of [actor] with `proof` set to `null`, or `null` if [actor] is `null`.
 */
@Suppress("UNUSED_PARAMETER")
fun redactActorProofIfNeeded(
    actor: ActorClaimDto?,
    call: ApplicationCall,
    redactActorProof: Boolean,
): ActorClaimDto? {
    if (actor == null) return null
    return actor.copy(proof = null)
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
    return if (isAdmin) verification else verification?.copy(proof = null)
}

/**
 * The exact deprecation notice text for a request that passed `?include=proof`. A single
 * canonical string keeps every call site (and the tests asserting it) in sync.
 */
private const val DEPRECATED_INCLUDE_PROOF_WARNING =
    "299 - \"include=proof is deprecated and ignored; actor proofs are no longer stored\""

/**
 * Adds exactly one `Warning: 299 - "..."` response header when the request's `include` query
 * parameter names `proof` — whether as a lone value, one of several comma-separated values
 * within a single `include=` occurrence, or within any repeated `include=` occurrence
 * (`call.request.queryParameters` only exposes the first value per name via `get`, so this reads
 * every occurrence via `getAll`).
 *
 * `?include=proof` is accepted as a no-op for backward compatibility: actor proofs (raw JWTs)
 * are no longer persisted since migration V17, so there is nothing left to include — this header
 * is the caller-visible signal that the parameter no longer does anything. `ActorClaimDto.proof`
 * stays in the response shape but is always null; see [redactActorProofIfNeeded].
 *
 * RFC 9745's `Deprecation` header was considered and rejected — it dates deprecation of a
 * *resource*, not a request *parameter*, which does not fit this case. `Warning: 299` (RFC 7234
 * §5.5, still the closest fit for "this exact request did something now-ignored") is used
 * instead. Idempotent per response: multiple `include=proof` occurrences still produce exactly
 * one header.
 */
fun ApplicationCall.flagDeprecatedIncludeProof() {
    val includesProof =
        request.queryParameters
            .getAll("include")
            ?.asSequence()
            ?.flatMap { it.split(",") }
            ?.map { it.trim() }
            ?.any { it == "proof" } ?: false
    if (includesProof) {
        response.header("Warning", DEPRECATED_INCLUDE_PROOF_WARNING)
    }
}
