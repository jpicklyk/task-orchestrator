package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.github.jpicklyk.mcptask.current.application.api.CallPrincipal
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus

/**
 * The audited actor of a REST call, ALWAYS synthesized server-side from the authenticated [Principal]: a request
 * body's `actor` is never read, so a caller cannot override its attribution.
 *
 * - id = `"api:" + principal.id` (e.g. `"api:dashboard-editor"`)
 * - kind = [ActorKind.EXTERNAL] for every REST principal, whatever its [Principal.kind] (which travels on
 *   [CallPrincipal.principal] instead)
 * - parent = null, proof = null (the credential is in the HTTP header; it is never echoed into the audit)
 */
fun Principal.auditClaim(): ActorClaim =
    ActorClaim(
        id = "$AUDIT_ID_PREFIX$id",
        kind = ActorKind.EXTERNAL,
        parent = null,
        proof = null,
    )

/**
 * The verification recorded for [auditClaim]: [VerificationStatus.VERIFIED] (`api-jwks`) for a verified JWT, fresh
 * or stale; [VerificationStatus.UNCHECKED] for an issued token (`api-bearer`) and for unauthenticated mode
 * (`api-unauthenticated`). The credential itself was already authenticated before any route ran.
 */
fun Principal.auditVerification(): VerificationResult =
    when (proofStatus) {
        ProofStatus.VERIFIED, ProofStatus.VERIFIED_STALE ->
            VerificationResult(status = VerificationStatus.VERIFIED, verifier = "api-jwks", reason = null)
        ProofStatus.ISSUED ->
            VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "api-bearer", reason = null)
        ProofStatus.NONE ->
            VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "api-unauthenticated", reason = null)
    }

/** The [CallPrincipal] of a REST call: the synthesized audit claim and verification, carrying this principal. */
fun Principal.toCallPrincipal(): CallPrincipal = CallPrincipal(auditClaim(), auditVerification(), this)

/** Prefix of every REST principal's audited actor id. */
const val AUDIT_ID_PREFIX = "api:"
