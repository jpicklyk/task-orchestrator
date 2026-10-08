package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.LayerBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.EventSink
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.AdvanceServiceFactory
import io.github.jpicklyk.mcptask.current.application.service.EventRecorder
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.ApiAuthConfigLoader
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.DefaultJwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.GlobalConfigFile
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlActorAuthenticationConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlNoteSchemaService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlStatusLabelService
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.time.SystemClock
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.DeferredEventPublisher
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventPublishingRepositoryProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Paths

/**
 * Resolved REST/SSE API wiring, computed ONCE at startup by [ServerComposition].
 *
 * The same [effectiveProvider] and [eventBus] are shared between the MCP tool context and the REST
 * routes so BOTH MCP-tool writes and REST writes record to the same events table, which the SSE
 * bus projects.
 *
 * @param apiConfig The resolved API auth configuration (Disabled / Bearer / Jwks).
 * @param eventBus The SSE projection of the events table, or null when the API is disabled.
 * @param effectiveProvider The provider to use everywhere: the event-recording decorator, installed
 *   whether or not the API is enabled.
 * @param tokenEntries Pre-loaded bearer token entries with expiry metadata (empty unless bearer mode).
 * @param allowQueryToken Whether `?token=` query-param auth is enabled for the SSE route.
 * @param jwksVerifier REST JWT verifier built from [ApiAuthConfig.Jwks] settings, or null unless the
 *   API is enabled in jwks mode. This is the REST-API verifier ([JwksApiVerifier]) — NOT the
 *   unrelated actor-authentication [JwksActorVerifier].
 * @param eventRecorder The one recorder the decorator and the unit of work's `events` append through
 *   (its commit listener feeds [eventBus] when the API is enabled); null in hand-built wirings.
 */
data class ApiWiring(
    val apiConfig: ApiAuthConfig,
    val eventBus: ApiEventBus?,
    val effectiveProvider: RepositoryProvider,
    val tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry>,
    val allowQueryToken: Boolean,
    val jwksVerifier: JwksApiVerifier?,
    val eventRecorder: EventSink? = null,
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
    val actorAuthEnabled: Boolean,
    val configResolver: EffectiveConfigResolver,
    val advanceServiceFactory: AdvanceServiceFactory,
    val unitOfWork: UnitOfWork,
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
 * @param shutdownCoordinator Coordinator used to register cleanup of JWKS key providers.
 */
class ServerComposition(
    private val appConfig: AppConfig,
    private val databaseManager: DatabaseManager,
    private val shutdownCoordinator: ShutdownCoordinator,
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
        // ONE clock instance for the whole graph: the stores, the unit of work and the tool context all read it.
        val clock = SystemClock
        val repositoryProvider: RepositoryProvider = DefaultRepositoryProvider(databaseManager, clock)

        // Resolve the single, server-wide global config path ONCE from the typed AppConfig snapshot
        // (rather than each loader independently re-reading AGENT_CONFIG_DIR from the environment)
        // and share it across all three global-file readers. Production behavior is unchanged —
        // AppConfig.fromEnv reads the same env var via the same AppConfig.resolveConfigBaseDir
        // fallback — but composition becomes testable without mutating the JVM environment.
        val globalConfigPath =
            Paths
                .get(AppConfig.resolveConfigBaseDir(appConfig.agentConfigDir))
                .resolve(".taskorchestrator/config.yaml")

        // ONE GlobalConfigFile instance reads and parses the file once; the schema service, the
        // status-label service, and the actor-auth service below all read from this SAME parsed
        // document instead of each independently re-reading and re-parsing it (AR-41 step 2/3).
        val globalConfigFile = GlobalConfigFile(globalConfigPath)
        // Force the lazy load NOW, before anything else is wired, so a broken global config file
        // fails startup here (surfaced to CurrentMcpServer.run() -> CurrentMain, before the
        // readiness marker is ever written) rather than on first incidental use deep in a request.
        globalConfigFile.layer()
        val noteSchemaService = YamlNoteSchemaService(globalConfigFile)
        val statusLabelService = YamlStatusLabelService(globalConfigFile)
        val actorAuthConfigService = YamlActorAuthenticationConfigService(globalConfigFile, envResolver = appConfig.envResolver)
        val (actorVerifier, degradedModePolicy) = createActorVerifierAndPolicy(actorAuthConfigService)

        // Resolve the REST/SSE API wiring ONCE, EARLY, before the tool context is built, so the
        // SAME decorated provider (and, with the API on, the SAME event bus) feeds BOTH the MCP tool
        // context AND the REST routes. The event-recording decorator is installed whether or not
        // the API is enabled: every write records its events rows; only the SSE projection of them
        // needs the API.
        val apiWiring = resolveApiWiring(repositoryProvider, clock)
        val effectiveProvider = apiWiring.effectiveProvider

        val nextItemRecommender =
            NextItemRecommender(
                effectiveProvider.workItemRepository(),
                effectiveProvider.dependencyRepository(),
            )
        // Per-root schema layer (T3.2): resolveSchema() consults this root's pushed config before
        // falling back to the global noteSchemaService above. Shares effectiveProvider so MCP tools
        // and REST routes see the same (possibly event-publishing-decorated) project_config access.
        val perRootConfigService = PerRootConfigService(effectiveProvider.projectConfigRepository())
        // Both MCP and REST resolve config through this ONE resolver — see O1 (task-scope f2c50e6d):
        // REST previously built its own PerRootConfigService (a separate last-known-good cache);
        // sharing this instance is the only intended observable behavior change in this item.
        val configResolver = EffectiveConfigResolver(LayerBackedGlobalLookup(globalConfigFile.layer()), perRootConfigService)
        // The unit of work hands scopes the SAME (possibly event-publishing) provider the tools use.
        val unitOfWork: UnitOfWork = SqliteUnitOfWork(databaseManager, effectiveProvider, clock, apiWiring.eventRecorder)
        val toolContext =
            ToolExecutionContext(
                repositoryProvider = effectiveProvider,
                noteSchemaService = noteSchemaService,
                statusLabelService = statusLabelService,
                actorVerifier = actorVerifier,
                degradedModePolicy = degradedModePolicy,
                nextItemRecommender = nextItemRecommender,
                perRootConfigService = perRootConfigService,
                configResolver = configResolver,
                clock = clock,
                unitOfWork = unitOfWork,
            )
        logger.info(
            "Repository provider and tool context initialized (API {})",
            if (apiWiring.eventBus != null) "enabled - events rows stream over SSE" else "disabled - events rows recorded, no SSE",
        )

        // actor_authentication status surfaced via /info (HTTP transport) — derived from the SAME
        // actorAuthConfigService instance createActorVerifierAndPolicy used above (no second parse).
        val actorAuthEnabled =
            actorAuthConfigService
                .getConfig()
                .let { it.verifier !is VerifierConfig.Noop }

        return CompositionResult(
            toolContext = toolContext,
            apiWiring = apiWiring,
            noteSchemaService = noteSchemaService,
            degradedModePolicy = degradedModePolicy,
            actorAuthEnabled = actorAuthEnabled,
            configResolver = configResolver,
            advanceServiceFactory = toolContext.advanceServiceFactory(),
            unitOfWork = unitOfWork,
        )
    }

    /**
     * Resolve the REST/SSE API wiring exactly once at startup.
     *
     * Loads the API auth config (fail-fast on misconfiguration). Always wraps [rawProvider] with the
     * event-recording [EventPublishingRepositoryProvider] over one [EventRecorder]. When the API is
     * enabled, also builds a single [ApiEventBus] projecting the events table (replay window from
     * [AppConfig.apiSseBufferSize]), makes it the recorder's commit listener and starts its
     * cross-process poll (stopped at shutdown); when disabled, the bus is null and the recorder has no
     * listener.
     */
    private fun resolveApiWiring(
        rawProvider: RepositoryProvider,
        clock: Clock,
    ): ApiWiring {
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
            val recorder = EventRecorder(rawProvider.eventStore(), clock)
            return ApiWiring(
                apiConfig = apiConfig,
                eventBus = null,
                effectiveProvider = EventPublishingRepositoryProvider(rawProvider, recorder),
                tokenEntries = emptyMap(),
                allowQueryToken = allowQueryToken,
                jwksVerifier = null,
                eventRecorder = recorder,
            )
        }

        val bus = ApiEventBus(bufferSize = appConfig.apiSseBufferSize, source = rawProvider.eventStore())
        val recorder = EventRecorder(rawProvider.eventStore(), clock, DeferredEventPublisher(bus))
        val decorated = EventPublishingRepositoryProvider(rawProvider, recorder)
        val tailerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bus.startTailer(tailerScope)
        shutdownCoordinator.addCleanupAction("Stop SSE event tail") {
            tailerScope.cancel()
        }
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
                shutdownCoordinator.addCleanupAction("Close REST JWKS key provider") {
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
            eventRecorder = recorder,
        )
    }

    /**
     * Creates the appropriate [ActorVerifier] and reads [DegradedModePolicy] from configuration.
     * Returns a [Pair] of (verifier, policy) so both can be wired into [ToolExecutionContext].
     *
     * When the effective (post-env-override) configuration is a real ([VerifierConfig.Jwks])
     * verifier under [DegradedModePolicy.ACCEPT_CACHED], logs one startup WARN: under that policy,
     * a [io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus.REJECTED] result is
     * still resolved to the self-reported actor id (see
     * [io.github.jpicklyk.mcptask.current.application.tools.ActorAware.resolveTrustedActorId]) — an
     * operator running a real verifier should know that up front, once, rather than only from a
     * per-call WARN buried in request logs. This is intentionally NOT added to
     * [YamlActorAuthenticationConfigService.getWarnings] — that list is asserted empty by a large
     * number of existing jwks-config tests, and is reserved for actual parse warnings.
     */
    private fun createActorVerifierAndPolicy(
        configService: YamlActorAuthenticationConfigService,
    ): Pair<ActorVerifier, DegradedModePolicy> {
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
                    if (config.degradedModePolicy == DegradedModePolicy.ACCEPT_CACHED) {
                        logger.warn(
                            "degraded_mode_policy=accept-cached with a jwks verifier configured: a " +
                                "REJECTED verification result (e.g. bad signature, wrong issuer/audience) " +
                                "still falls back to the self-reported actor.id. Use degraded_mode_policy=" +
                                "reject for cross-org deployments that must not trust an unverified claim.",
                        )
                    }
                    JwksActorVerifier(vc).also { jwksVerifier ->
                        shutdownCoordinator.addCleanupAction("Close JWKS ActorVerifier") {
                            jwksVerifier.close()
                        }
                    }
                }
            }
        return Pair(verifier, config.degradedModePolicy)
    }
}
