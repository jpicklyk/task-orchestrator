package io.github.jpicklyk.mcptask.current.bootstrap

import io.github.jpicklyk.mcptask.current.application.BuildInfo
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.ServerConfigSource
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.EventSink
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.read.WorkViewQueries
import io.github.jpicklyk.mcptask.current.application.report.ReportRunRollupSnapshot
import io.github.jpicklyk.mcptask.current.application.service.AdvanceServiceFactory
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.application.service.EventRecorder
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.support.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStep
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepRunner
import io.github.jpicklyk.mcptask.current.application.upgrade.steps.ConfigCanonicalizeStep
import io.github.jpicklyk.mcptask.current.application.upgrade.steps.ServerConfigImportStep
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.GlobalConfigFile
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.config.checkRemovedServerConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.productionConfigValidator
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfigLoader
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.DefaultJwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.toKeySource
import io.github.jpicklyk.mcptask.current.infrastructure.security.FileBodySource
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteDataStepStore
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.PinBackfillStep
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.TraitsFromPropertiesStep
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.TypeBackfillStep
import io.github.jpicklyk.mcptask.current.infrastructure.time.SystemClock
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolved REST/SSE API wiring, computed ONCE at startup by [ServerComposition].
 *
 * The same [eventRecorder] and [eventBus] are shared between the MCP tool context and the REST
 * routes so BOTH MCP-tool writes and REST writes record to the same events table, which the SSE
 * bus projects. Every write service records its own rows through the unit of work's sink.
 *
 * @param apiConfig The resolved API auth configuration (Disabled / Bearer / Jwks).
 * @param eventBus The SSE projection of the events table, or null when the API is disabled.
 * @param effectiveProvider The provider to use everywhere (the raw store provider: no store is decorated).
 * @param tokenEntries Pre-loaded bearer token entries with expiry metadata (empty unless bearer mode).
 * @param allowQueryToken Whether `?token=` query-param auth is enabled for the SSE route.
 * @param jwksVerifier REST JWT verifier built from [ApiAuthConfig.Jwks] settings, or null unless the
 *   API is enabled in jwks mode.
 * @param eventRecorder The one recorder the unit of work's `events` append through (its commit listener is
 *   [eventBus] when the API is enabled); null in hand-built wirings.
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
    /** The server-scope config layer: the DB head, read per call (the REST `/config*` routes view it per request). */
    val serverConfig: ServerConfigSource,
    val configResolver: EffectiveConfigResolver,
    val advanceServiceFactory: AdvanceServiceFactory,
    val unitOfWork: UnitOfWork,
    val dataStepRunner: DataStepRunner,
    val configService: ConfigService,
)

/**
 * Manual composition root for the Current (4.0) MCP server.
 *
 * Builds the entire object graph (repositories, config services, REST/SSE wiring,
 * and the final [ToolExecutionContext]) from a single typed [AppConfig] snapshot and an already
 * initialized [DatabaseManager]. This keeps [CurrentMcpServer] focused purely on lifecycle
 * (startup/shutdown/transport).
 *
 * There is no DI framework — wiring is explicit and ordered. The environment is read once (in
 * [AppConfig.fromEnv], by the caller) and passed in; this class performs no direct environment
 * reads except by delegating to validated config loaders that accept an injectable resolver.
 *
 * @param appConfig The single startup environment snapshot.
 * @param databaseManager An already-initialized [DatabaseManager] (schema applied).
 * @param shutdownCoordinator Coordinator used to register cleanup of JWKS key providers.
 * @param clock The one clock bound into the whole graph; production uses [SystemClock], tests inject a settable one.
 * @param extraDataSteps Test seam: data steps registered beside the production steps (none in production).
 */
class ServerComposition(
    private val appConfig: AppConfig,
    private val databaseManager: DatabaseManager,
    private val shutdownCoordinator: ShutdownCoordinator,
    private val logger: Logger = LoggerFactory.getLogger(ServerComposition::class.java),
    private val clock: Clock = SystemClock,
    private val extraDataSteps: List<DataStep> = emptyList(),
) {
    /**
     * Wires the object graph and returns a [CompositionResult].
     *
     * Ordering mirrors the previous inline construction in `CurrentMcpServer.run()`:
     * repositories → config services → API wiring (resolved EARLY so the SAME bus
     * and recorder feed both the tool context and the REST routes) → recommender → tool context.
     */
    fun build(): CompositionResult {
        // ONE clock instance for the whole graph: the stores, the unit of work and the tool context all read it.
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

        // The root every `bodyFromFile` path is resolved inside (AR-98): the same base directory, built once here from the
        // typed AppConfig. FileBodySource reads no environment.
        val bodySource = FileBodySource(Paths.get(AppConfig.resolveConfigBaseDir(appConfig.agentConfigDir)))

        // ONE GlobalConfigFile instance reads and parses the file once. It feeds the `server-config-import` boot step
        // (the file's text), the removed-key startup guard and the type-backfill
        // step. The runtime never reads the file for schemas, traits or labels: it reads the stored server head.
        val globalConfigFile = GlobalConfigFile(globalConfigPath)
        // Force the lazy load NOW, before anything else is wired, so a broken global config file
        // fails startup here (surfaced to CurrentMcpServer.run() -> CurrentMain, before the
        // readiness marker is ever written) rather than on first incidental use deep in a request.
        globalConfigFile.layer()
        checkRemovedServerConfig(globalConfigFile, appConfig.envResolver)

        // Resolve the REST/SSE API wiring ONCE, EARLY, before the tool context is built, so the
        // SAME recorder (and, with the API on, the SAME event bus) feeds BOTH the MCP tool context
        // AND the REST routes. Every write service records its events rows whether or not the API is
        // enabled; only the SSE projection of them needs the API.
        val apiWiring = resolveApiWiring(repositoryProvider, clock)
        val effectiveProvider = apiWiring.effectiveProvider

        val nextItemRecommender = NextItemRecommender(effectiveProvider.workItemRepository())
        // Per-root schema layer (T3.2): resolveSchema() consults this root's pushed config before
        // falling back to the global config layer. Shares effectiveProvider so MCP tools
        // and REST routes see the same versioned config store.
        val perRootConfigService = PerRootConfigService(effectiveProvider.configStore())
        // Both MCP and REST resolve config through this ONE resolver — see O1 (task-scope f2c50e6d):
        // REST previously built its own PerRootConfigService (a separate last-known-good cache);
        // sharing this instance is the only intended observable behavior change in this item.
        // The server layer is the DB head too (`PerRootConfigService` is both sources): the boot import landed the file
        // there, and a version another process appends is served on the next read, with no restart.
        val configResolver = EffectiveConfigResolver(perRootConfigService, perRootConfigService)
        // The unit of work hands scopes the SAME provider the tools use.
        val unitOfWork: UnitOfWork = SqliteUnitOfWork(databaseManager, effectiveProvider, clock, apiWiring.eventRecorder)
        // ONE read model for the whole server, over the same unit of work (served by the read tools as they move onto it).
        val workViewQueries = WorkViewQueries(unitOfWork)
        // The ONE config service: the `config` tool (MCP and REST ops) and the boot import all drive it.
        // Admission check for pushed config: the JSON Schema pass, the strict parser's findings, and the semantic rules
        // against the server layer, which ConfigService reads from the DB head on every push / validate.
        val configValidator = productionConfigValidator()
        val configService =
            ConfigService(effectiveProvider, YamlConfigDocumentParser, unitOfWork, configValidator, serverConfig = perRootConfigService)
        val toolContext =
            ToolExecutionContext(
                repositoryProvider = effectiveProvider,
                nextItemRecommender = nextItemRecommender,
                perRootConfigService = perRootConfigService,
                configResolver = configResolver,
                clock = clock,
                unitOfWork = unitOfWork,
                workViewQueries = workViewQueries,
                configService = configService,
                bodySource = bodySource,
                resourceLeasesEnforced = appConfig.resourceLeasesEnforced,
                rollupSnapshot = ReportRunRollupSnapshot.over(effectiveProvider, unitOfWork, clock),
            )
        logger.info(
            "Repository provider and tool context initialized (API {})",
            if (apiWiring.eventBus != null) "enabled - events rows stream over SSE" else "disabled - events rows recorded, no SSE",
        )

        return CompositionResult(
            toolContext = toolContext,
            apiWiring = apiWiring,
            serverConfig = perRootConfigService,
            configResolver = configResolver,
            advanceServiceFactory = toolContext.advanceServiceFactory(),
            unitOfWork = unitOfWork,
            configService = configService,
            dataStepRunner =
                DataStepRunner(
                    steps = productionDataSteps(configService, globalConfigFile, globalConfigPath) + extraDataSteps,
                    unitOfWork = unitOfWork,
                    store = SqliteDataStepStore(databaseManager),
                    clock = clock,
                    binaryVersion = BuildInfo.version,
                ),
        )
    }

    /**
     * Resolve the REST/SSE API wiring exactly once at startup.
     *
     * Loads the API auth config (fail-fast on misconfiguration) and builds the one [EventRecorder] over
     * [rawProvider]'s event store. When the API is enabled, also builds a single [ApiEventBus] projecting the
     * events table (replay window from [AppConfig.apiSseBufferSize]), makes it the recorder's commit listener
     * (its [ApiEventBus.committed] never throws, so a fan-out failure cannot reach a committed write) and starts
     * its cross-process poll (stopped at shutdown); when disabled, the bus is null and the recorder has no
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

        // Every bearer entry's scope.root_ids anchor that EXISTS must be a depth-0 root. A missing anchor is accepted (it
        // admits nothing until the project root is created through the running server). The database is already
        // migrated here. JWT anchors are checked per credential by the CredentialResolver.
        if (apiConfig is ApiAuthConfig.Bearer) validateBearerScopeAnchors(apiConfig, rawProvider)

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
                effectiveProvider = rawProvider,
                tokenEntries = emptyMap(),
                allowQueryToken = allowQueryToken,
                jwksVerifier = null,
                eventRecorder = recorder,
            )
        }

        val bus = ApiEventBus(bufferSize = appConfig.apiSseBufferSize, source = rawProvider.eventStore())
        val recorder = EventRecorder(rawProvider.eventStore(), clock, bus)
        val tailerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bus.startTailer(tailerScope)
        shutdownCoordinator.addCleanupAction("Stop SSE event tail") {
            tailerScope.cancel()
        }
        // Loaded once, by ApiAuthConfigLoader: the CredentialResolver resolves against this one map.
        val tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry> =
            if (apiConfig is ApiAuthConfig.Bearer) apiConfig.tokens else emptyMap()

        // In jwks mode, build the REST JWT verifier from the resolved ApiAuthConfig.Jwks settings.
        // Without this, the ApiBearerAuth plugin's Jwks branch finds a null verifier and 401s every
        // request.
        val jwksVerifier: JwksApiVerifier? =
            if (apiConfig is ApiAuthConfig.Jwks) {
                val keyProvider =
                    DefaultJwksKeySetProvider(
                        apiConfig.toKeySource(),
                        Paths.get(AppConfig.resolveConfigBaseDir(appConfig.agentConfigDir)),
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
            effectiveProvider = rawProvider,
            tokenEntries = tokenEntries,
            allowQueryToken = allowQueryToken,
            jwksVerifier = jwksVerifier,
            eventRecorder = recorder,
        )
    }

    /** Fails startup naming the token id and the anchor when a bearer entry's `scope.root_ids` names an existing non-root. */
    private fun validateBearerScopeAnchors(
        apiConfig: ApiAuthConfig.Bearer,
        rawProvider: RepositoryProvider,
    ) {
        val anchors =
            apiConfig.tokens.values.flatMap { entry ->
                entry.principal.scope.rootIds
                    .orEmpty()
                    .map { entry.principal.id to it }
            }
        if (anchors.isEmpty()) return
        val nonRoots = runBlocking { ScopeGuard.nonRoots(anchors.mapTo(HashSet()) { it.second }, rawProvider.workItemRepository()) }
        val (tokenId, anchor) = anchors.firstOrNull { it.second in nonRoots } ?: return
        val message = "API token file: token '$tokenId' scope.root_ids anchor $anchor is not a depth-0 root"
        logger.error("REST API configuration error: {}", message)
        throw IllegalArgumentException(message)
    }

    private companion object {
        /**
         * The data steps the server ships; later work items register theirs here. Phase order runs config-canonicalize
         * (CONFIG_CANONICALIZE), then server-config-import (CONFIG_IMPORT, every boot), then type-backfill and
         * traits-from-properties (ITEM_BACKFILL), then pin-backfill (PIN).
         */
        fun productionDataSteps(
            configService: ConfigService,
            globalConfigFile: GlobalConfigFile,
            globalConfigPath: Path,
        ): List<DataStep> =
            listOf(
                ConfigCanonicalizeStep(configService),
                ServerConfigImportStep(configService, globalConfigFile),
                TypeBackfillStep(globalConfigPath),
                TraitsFromPropertiesStep(),
                PinBackfillStep(globalConfigPath),
            )
    }
}
