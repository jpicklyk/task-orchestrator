package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.infrastructure.identity.CredentialResolver
import io.github.jpicklyk.mcptask.current.infrastructure.identity.Resolved
import io.github.jpicklyk.mcptask.current.infrastructure.identity.TrustDecision
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.ApiTokenVerdict
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.VerifiedApiToken

/** The credential [raw] establishes, or null for anything but [TrustDecision.Trusted] (rejected or unavailable). */
suspend fun CredentialResolver.resolveOrNull(raw: String): Resolved? = (resolve(raw) as? TrustDecision.Trusted)?.resolved

/** The verified token [jwtString] yields, or null for anything but [ApiTokenVerdict.Verified]. */
suspend fun JwksApiVerifier.verifyWithExpiryOrNull(jwtString: String): VerifiedApiToken? =
    (verifyWithExpiry(jwtString) as? ApiTokenVerdict.Verified)?.token
