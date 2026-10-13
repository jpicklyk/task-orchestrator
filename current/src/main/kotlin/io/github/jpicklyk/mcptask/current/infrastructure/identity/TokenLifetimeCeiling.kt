package io.github.jpicklyk.mcptask.current.infrastructure.identity

/**
 * Sane upper bound (~100 years) for `actor_authentication.verifier.max_token_lifetime_seconds` /
 * `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS`. Configuring a cap near [Long.MAX_VALUE] would overflow
 * when the clock-skew leeway is added in `TokenLifetimeCap.violation`; both config loaders
 * reject a value above this ceiling at parse time (same startup-failure path as the existing
 * `<= 0` check), so the addition inside `TokenLifetimeCap.violation` can never overflow.
 *
 * Lives in the identity root package so the `jwt`, `bearer` and `config` packages can share it
 * without importing each other.
 */
const val MAX_TOKEN_LIFETIME_SECONDS_CEILING = 3_153_600_000L // 100 years, in seconds
