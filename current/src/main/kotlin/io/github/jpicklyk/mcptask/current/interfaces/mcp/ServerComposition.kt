package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.ApiAuthConfigLoader
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.DefaultJwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlActorAuthenticationConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlNoteSchemaService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlStatusLabelService
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.logging.DefaultMcpLoggingService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventPublishingRepositoryProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Resolved REST/SSE API wiring, computed ONCE at startup by [ServerComposition].
 *
 * The same [effectiveProvider] and [eventBus] are shared between the MCP tool context and the REST
 * routes so BOTH MCP-tool writes and REST writes publish to the same SSE bus.
 *
 * @param apiConfig The resolved API auth configuration (Disabled / Bearer / Jwks).
 * @param eventBus The SSE event bus, or null when the API is disabled.
 * @param effectiveProvider The provider to use everywhere — the event-publishing decorator when the
 *   API is enabled, or the raw provider when disabled.
 * @param tokenEntries Pre-loaded bearer token entries with expiry metadata (empty unless bearer mode).
 * @param allowQueryToken Whether `?token=` query-param auth is enabled for the SSE route.
 * @param jwksVerifier REST JWT verifier built from [ApiAuthConfig.Jwks] settings, or null unless the
 *   API is enabled in jwks mode. This is the REST-API verifier ([JwksApiVerifier]) — NOT the
 *   unrelated actor-authentication [JwksActorVerifier].
 */
data class ApiWiring(
    val apiConfig: ApiAuthConfig,
    val eventBus: ApiEventBus?,
    val effectiveProvider: RepositoryProvider,
    val tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry>,
    val allowQueryToken: Boolean,
    val jwksVerifier: JwksApiVerifier?,
)

/**
 * Fully wired object graph produced by [ServerComposition.build].
 *
 * Holds everything the lifecycle layer ([CurrentMcpServer]) needs to start a transport: the tool
 * context, the API wiring, and the few collaborators the HTTP transport passes into REST routes.
 */
class CompositionResult(
    val toolContext: ToolExecutionContext,
    val apiWiring: ApiWiring,
    val noteSchemaService: WorkItemSchemaService,
    val degradedModePolicy: DegradedModePolicy,
    val idempotencyCache: IdempotencyCache,
    val mcpLoggingService: DefaultMcpLoggingService,
    val actorAuthEnabled: Boolean,
)

/**
 * Manual composition root for the Current (v3) MCP server.
 *
 * Builds the entire object graph (repositories, config services, actor verifier, REST/SSE wiring,
 * and the final [ToolExecutionContext]) from a single typed [AppConfig] snapshot and an already
 * initialized [DatabaseManager]. This keeps [CurrentMcpServer] focused purely on lifecycle
 * (startup/shutdown/transport).
 *
 * There is no DI framework — wiring is explicit and ordered. The environment is read once (in
 * [AppConfig.fromEnv], by the caller) and passed in; this class performs no direct [System.getenv]
 * reads except by delegating to validated config loaders that accept an injectable resolver.
 *
 * @param appConfig The single startup environment snapshot.
 * @param databaseManager An already-initialized [DatabaseManager] (schema applied).
 * @param shutdownCoordinator Optional coordinator used to register cleanup of JWKS key providers.
 */
class ServerComposition(
    private val appConfig: AppConfig,
    private val databaseManager: DatabaseManager,
    private val shutdownCoordinator: ShutdownCoordinator?,
    private val logger: Logger = LoggerFactory.getLogger(ServerComposition::class.java),
) {
    /**
     * Wires the object graph and returns a [CompositionResult].
     *
     * Ordering mirrors the previous inline construction in `CurrentMcpServer.run()`:
     * repositories → config services → actor verifier → API wiring (resolved EARLY so the SAME bus
     * and decorated provider feed both the tool context and the REST routes) → recommender → tool
     * context.
     */
    fun build(): CompositionResult {
        val repositoryProvider: RepositoryProvider = DefaultRepositoryProvider(databaseManager)
        val noteSchemaService = YamlNoteSchemaService()
        val statusLabelService = YamlStatusLabelService()
        val mcpLoggingService = DefaultMcpLoggingService()
        val (actorVerifier, degradedModePolicy) = createActorVerifierAndPolicy()
        val idempotencyCache = IdempotencyCache()

        // Resolve the REST/SSE API wiring ONCE, EARLY — before the tool context is built — so the
        // SAME event bus and SAME decorated provider feed BOTH the MCP tool context AND the REST
        // routes. MCP-tool writes (the primary workflow) must publish to the same bus SSE
        // subscribers read from. When the API is disabled the raw provider is used unchanged —
        // byte-for-byte identical MCP behavior with zero overhead and no decorator on the hot path.
        val apiWiring = resolveApiWiring(repositoryProvider)
        val effectiveProvider = apiWiring.effectiveProvider

        val nextItemRecommender =
            NextItemRecommender(
                effectiveProvider.workItemRepository(),
                effectiveProvider.dependencyRepository(),
            )
        val toolContext =
            ToolExecutionContext(
                effectiveProvider,
                noteSchemaService,
                statusLabelService,
                mcpLoggingService,
                actorVerifier,
                degradedModePolicy,
                idempotencyCache,
                nextItemRecommender,
            )
        logger.info(
            "Repository provider and tool context initialized (API {})",
            if (apiWiring.eventBus != null) "enabled — writes publish to SSE bus" else "disabled — raw provider",
        )

        // actor_authentication status surfaced via /info (HTTP transport) — derived from the same
        // config the verifier was built from.
        val actorAuthEnabled =
            YamlActorAuthenticationConfigService(envResolver = appConfig.envResolver)
                .getConfig()
                .let { it.verifier !is VerifierConfig.Noop }

        return CompositionResult(
            toolContext = toolContext,
            apiWiring = apiWiring,
            noteSchemaService = noteSchemaService,
            degradedModePolicy = degradedModePolicy,
            idempotencyCache = idempotencyCache,
            mcpLoggingService = mcpLoggingService,
            actorAuthEnabled = actorAuthEnabled,
        )
    }

    /**
     * Resolve the REST/SSE API wiring exactly once at startup.
     *
     * Loads the API auth config (fail-fast on misconfiguration). When the API is enabled, builds a
     * single [ApiEventBus] (sized from [AppConfig.apiSseBufferSize]) and wraps [rawProvider] with
     * [EventPublishingRepositoryProvider]; both are returned so the tool context AND the REST routes
     * share them. When disabled, returns the raw provider unchanged with a null bus.
     */
    private fun resolveApiWiring(rawProvider: RepositoryProvider): ApiWiring {
        val apiConfig =
            try {
                ApiAuthConfigLoader(envResolver = appConfig.envResolver).load()
            } catch (e: IllegalArgumentException) {
                logger.error("REST API configuration error: {}", e.message)
                throw e
            }

        val allowQueryToken = appConfig.apiAllowQueryTokenForSse
        if (allowQueryToken) {
            logger.warn(
                "API_ALLOW_QUERY_TOKEN_FOR_SSE=true: bearer tokens accepted as ?token= query " +
                    "parameter on GET /api/v1/events. This leaks tokens into server logs and " +
                    "browser history. Do NOT use in production.",
            )
        }

        if (apiConfig is ApiAuthConfig.Disabled) {
            return ApiWiring(
                apiConfig = apiConfig,
                eventBus = null,
                effectiveProvider = rawProvider,
                tokenEntries = emptyMap(),
                allowQueryToken = allowQueryToken,
                jwksVerifier = null,
            )
        }

        val bus = ApiEventBus(bufferSize = appConfig.apiSseBufferSize)
        val decorated = EventPublishingRepositoryProvider(rawProvider, bus)
        val tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry> =
            if (apiConfig is ApiAuthConfig.Bearer) {
                BearerTokenStore(appConfig.apiTokensPath).loadWithEntries()
            } else {
                emptyMap()
            }

        // In jwks mode, build the REST JWT verifier from the resolved ApiAuthConfig.Jwks settings.
        // Without this, the ApiBearerAuth plugin's Jwks branch finds a null verifier and 401s every
        // request. NOTE: this is the REST verifier (JwksApiVerifier), NOT the actor-auth
        // JwksActorVerifier.
        val jwksVerifier: JwksApiVerifier? =
            if (apiConfig is ApiAuthConfig.Jwks) {
                val keyProvider =
                    DefaultJwksKeySetProvider(
                        VerifierConfig.Jwks(
                            jwksUri = apiConfig.url,
                            issuer = apiConfig.issuer,
                            audience = apiConfig.audience,
                            algorithms = apiConfig.algorithms,
                            cacheTtlSeconds = apiConfig.cacheTtlSeconds,
                        ),
                    )
                shutdownCoordinator?.addCleanupAction("Close REST JWKS key provider") {
                    keyProvider.close()
                }
                JwksApiVerifier(apiConfig, keyProvider)
            } else {
                null
            }

        return ApiWiring(
            apiConfig = apiConfig,
            eventBus = bus,
            effectiveProvider = decorated,
            tokenEntries = tokenEntries,
            allowQueryToken = allowQueryToken,
            jwksVerifier = jwksVerifier,
        )
    }

    /**
     * Creates the appropriate [ActorVerifier] and reads [DegradedModePolicy] from configuration.
     * Returns a [Pair] of (verifier, policy) so both can be wired into [ToolExecutionContext].
     */
    private fun createActorVerifierAndPolicy(): Pair<ActorVerifier, DegradedModePolicy> {
        val configService = YamlActorAuthenticationConfigService(envResolver = appConfig.envResolver)
        configService.getWarnings().forEach { logger.warn("Actor authentication config: {}", it) }
        val config = configService.getConfig()
        logger.info("Degraded mode policy: {}", config.degradedModePolicy.toConfigString())
        val verifier =
            when (val vc = config.verifier) {
                is VerifierConfig.Noop -> {
                    logger.info("Actor verifier: noop (all claims unverified)")
                    NoOpActorVerifier
                }
                is VerifierConfig.Jwks -> {
                    logger.info(
                        "Actor verifier: jwks (uri={}, path={}, discovery={})",
                        vc.jwksUri ?: "none",
                        vc.jwksPath ?: "none",
                        vc.oidcDiscovery ?: "none",
                    )
                    JwksActorVerifier(vc).also { jwksVerifier ->
                        shutdownCoordinator?.addCleanupAction("Close JWKS ActorVerifier") {
                            jwksVerifier.close()
                        }
                    }
                }
            }
        return Pair(verifier, config.degradedModePolicy)
    }
}
