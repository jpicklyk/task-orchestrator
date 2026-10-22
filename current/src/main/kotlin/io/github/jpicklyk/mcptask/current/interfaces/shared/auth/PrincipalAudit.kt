package io.github.jpicklyk.mcptask.current.interfaces.shared.auth

import io.github.jpicklyk.mcptask.current.application.api.CallPrincipal
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus

/**
 * The audited actor of a call, ALWAYS synthesized server-side from the [Principal] the transport established (the
 * REST bearer, the `/mcp` bearer, or the local principal): no request body names its own actor, so a caller cannot
 * override its attribution. One mapping for every transport.
 *
 * - id = `"api:" + principal.id` for a credential principal ([ProofStatus.ISSUED], [ProofStatus.VERIFIED],
 *   [ProofStatus.VERIFIED_STALE]; e.g. `"api:dashboard-editor"`), and the bare [Principal.id] for a principal no
 *   credential established ([ProofStatus.NONE]: the local principal, audited as `local`)
 * - kind = [ActorKind.EXTERNAL] for every principal, whatever its [Principal.kind] (which travels on
 *   [CallPrincipal.principal] instead)
 * - parent = null, proof = null (a credential is in the HTTP header; it is never echoed into the audit)
 */
fun Principal.auditClaim(): ActorClaim =
    ActorClaim(
        id = auditId(),
        kind = ActorKind.EXTERNAL,
        parent = null,
        proof = null,
    )

/** The audited actor id of this principal: `api:<id>` for a credential, the bare id for [ProofStatus.NONE]. */
fun Principal.auditId(): String = if (proofStatus == ProofStatus.NONE) id else "$AUDIT_ID_PREFIX$id"

/**
 * The verification recorded for [auditClaim]: [VerificationStatus.VERIFIED] (`api-jwks`) for a verified JWT, fresh
 * or stale; [VerificationStatus.UNCHECKED] for an issued token (`api-bearer`) and for the local principal (`local`).
 * The credential itself was already authenticated before any handler ran.
 */
fun Principal.auditVerification(): VerificationResult =
    when (proofStatus) {
        ProofStatus.VERIFIED, ProofStatus.VERIFIED_STALE ->
            VerificationResult(status = VerificationStatus.VERIFIED, verifier = "api-jwks", reason = null)
        ProofStatus.ISSUED ->
            VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "api-bearer", reason = null)
        ProofStatus.NONE ->
            VerificationResult(status = VerificationStatus.UNCHECKED, verifier = LOCAL_VERIFIER, reason = null)
        // History-only: the pipeline never builds a principal with it (see ProofStatus.REJECTED); if one ever did, it
        // is audited as a rejected proof, never as a verified or an unchecked one.
        ProofStatus.REJECTED ->
            VerificationResult(status = VerificationStatus.REJECTED, verifier = "api-jwks", reason = null)
    }

/** The [CallPrincipal] of a call: the synthesized audit claim and verification, carrying this principal. */
fun Principal.toCallPrincipal(): CallPrincipal = CallPrincipal(auditClaim(), auditVerification(), this)

/** Prefix of every credential principal's audited actor id. */
const val AUDIT_ID_PREFIX = "api:"

/** The verifier recorded for a principal no credential established ([ProofStatus.NONE]). */
const val LOCAL_VERIFIER = "local"
