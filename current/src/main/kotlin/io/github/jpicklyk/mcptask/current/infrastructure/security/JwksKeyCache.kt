package io.github.jpicklyk.mcptask.current.infrastructure.security

import io.github.jpicklyk.mcptask.current.infrastructure.config.DefaultJwksKeySetProvider

/**
 * Type alias for [DefaultJwksKeySetProvider] exposing the key-caching layer in the
 * `infrastructure.security` package.
 *
 * This alias provides a stable, security-scoped name for external consumers (e.g., the
 * upcoming REST API authentication pipeline in Phase 1) without duplicating implementation.
 *
 * All caching concerns live in [DefaultJwksKeySetProvider]:
 * - JWKS endpoint fetch (OIDC discovery / jwks_uri / jwks_path resolution)
 * - Key-set cache with TTL ([io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig.Jwks.cacheTtlSeconds])
 * - Stale-on-error fallback ([io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig.Jwks.staleOnError])
 * - `did:web` document resolution + per-issuer LRU cache
 *
 * [JwksActorVerifier][io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifier]
 * retains JWT signature verification, claim validation, and DID allowlist enforcement — it
 * delegates all key-material concerns to [JwksKeySetProvider][io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider].
 */
typealias JwksKeyCache = DefaultJwksKeySetProvider
